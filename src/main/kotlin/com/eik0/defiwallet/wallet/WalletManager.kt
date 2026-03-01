package com.eik0.defiwallet.wallet

import com.eik0.defiwallet.DefiWallet
import com.eik0.defiwallet.extensions.playerName
import com.eik0.defiwallet.extensions.sendMessage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.github.shynixn.mccoroutine.bukkit.launch
import io.privy.api.PrivyClient
import io.privy.api.models.components.LinkedAccountEmailInput
import io.privy.api.models.components.LinkedAccountEmailInputType
import io.privy.api.models.components.LinkedAccountEthereumEmbeddedWallet
import io.privy.api.models.components.UserWalletAdditionalSigner
import io.privy.api.models.components.UserWalletRequest
import io.privy.api.models.components.WalletChainType
import io.privy.api.models.errors.APIException
import io.privy.api.models.operations.UserCreateRequestBody
import io.privy.api.signing.HttpMethod
import io.privy.api.signing.WalletApiRequestSignatureInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.contracts.eip20.generated.ERC20
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.ReadonlyTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

class WalletManager {

    val web3j: Web3j = Web3j.build(HttpService(DefiWallet.instance.cfg.wallet.chainRpc))

    private val walletsMutex = Mutex()
    val wallets = mutableMapOf<UUID, UserData>()

    val client: PrivyClient = PrivyClient.builder()
        .appId(DefiWallet.instance.cfg.privy.appId)
        .appSecret(DefiWallet.instance.cfg.privy.appSecret)
        .build()

    val walletRefresher = WalletRefresher(this)

    private val tokenDecimalsMutex = Mutex()
    private var cachedTokenDecimals: Int? = null

    private suspend fun getTokenDecimals(): Int {
        cachedTokenDecimals?.let { return it }
        return tokenDecimalsMutex.withLock {
            cachedTokenDecimals?.let { return@withLock it }

            val decimals = withContext(Dispatchers.IO) {
                val transactionManager =
                    ReadonlyTransactionManager(web3j, DefiWallet.instance.cfg.wallet.tokenContractAddress)
                val contract = ERC20.load(
                    DefiWallet.instance.cfg.wallet.tokenContractAddress,
                    web3j,
                    transactionManager,
                    DefaultGasProvider()
                )
                contract.decimals().send().toInt()
            }

            cachedTokenDecimals = decimals
            decimals
        }
    }

    suspend fun tokensToBaseUnits(amountTokens: Long): BigInteger {
        require(amountTokens >= 0) { "amountTokens must be >= 0" }
        val decimals = getTokenDecimals()
        return BigInteger.valueOf(amountTokens) * BigInteger.TEN.pow(decimals)
    }

    suspend fun baseUnitsToWholeTokensFloor(amountBase: BigInteger): BigInteger {
        val decimals = getTokenDecimals()
        return amountBase / BigInteger.TEN.pow(decimals)
    }


    private fun buildEthSendTransactionBody(caip2: String, contractAddress: String, encodedCallData: String): ObjectNode {
        return ObjectMapper().createObjectNode().apply {
            put("method", "eth_sendTransaction")
            put("caip2", caip2)
            putObject("params").putObject("transaction").apply {
                put("to", contractAddress)
                put("data", encodedCallData)
            }
            put("sponsor", true)
        }
    }

    private fun sendPrivyContractCallAndGetTransactionId(
        rpcUrl: String,
        body: ObjectNode,
        appId: String,
        basicAuthHeaderValue: String
    ): String? {
        val signature = client
            .utils()
            .requestSigner()
            .generateAuthorizationSignature(
                DefiWallet.instance.cfg.signing.masterKeySecret,
                WalletApiRequestSignatureInput(
                    1,
                    body,
                    HttpMethod.POST,
                    rpcUrl,
                    null
                )
            )

        val httpRequest = HttpRequest
            .newBuilder(URI(rpcUrl))
            .header("Authorization", basicAuthHeaderValue)
            .header("privy-app-id", appId)
            .header("privy-authorization-signature", signature)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()

        val httpResponse = HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString())
        return DefiWallet.instance.transactionManager.getTransactionIdFromResponseBody(httpResponse.body())
    }

    suspend fun sendMoney(sender: UUID?, recipient: UUID, amount: Long) {
        withContext(Dispatchers.IO) {
            val cfg = DefiWallet.instance.cfg

            val recipientUser = getOrCreateUserData(recipient) ?: return@withContext
            val amountBaseUnits = tokensToBaseUnits(amount)

            val caip2 = "eip155:${cfg.wallet.tokenChainId}"
            val basicAuthHeaderValue = "Basic ${Base64.getEncoder().encodeToString("${cfg.privy.appId}:${cfg.privy.appSecret}".toByteArray(Charsets.UTF_8))}"

            if (sender == null) {
                try {
                    val mintFunction = Function(
                        "mint",
                        listOf(Address(recipientUser.walletAddress), Uint256(amountBaseUnits)),
                        emptyList<TypeReference<*>>()
                    )
                    val encodedCallData = FunctionEncoder.encode(mintFunction)

                    val body = buildEthSendTransactionBody(
                        caip2 = caip2,
                        contractAddress = cfg.wallet.tokenContractAddress,
                        encodedCallData = encodedCallData
                    )

                    val rpcUrl = "https://api.privy.io/v1/wallets/${cfg.wallet.tokenOwnerId}/rpc"
                    val transactionId = sendPrivyContractCallAndGetTransactionId(
                        rpcUrl = rpcUrl,
                        body = body,
                        appId = cfg.privy.appId,
                        basicAuthHeaderValue = basicAuthHeaderValue
                    ) ?: return@withContext

                    DefiWallet.instance.logger.info("Minting $amount$ for player $recipient")

                    DefiWallet.instance.transactionManager.addTransaction(
                        null,
                        recipient,
                        transactionId,
                        amount
                    )
                } catch (e: APIException) {
                    DefiWallet.instance.logger.severe(e.bodyAsString())
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                return@withContext
            }

            val senderUser = getOrCreateUserData(sender) ?: return@withContext

            var moneyHeld = false
            try {
                val availableBaseUnits = senderUser.availableBase()
                if (availableBaseUnits < amountBaseUnits) {
                    val displayAvail = baseUnitsToWholeTokensFloor(availableBaseUnits)
                    sender.sendMessage("<red><b>(!)</b> You don't have enough money! Available: ${displayAvail}$")
                    return@withContext
                }

                senderUser.holdMoneyBase(amountBaseUnits)
                moneyHeld = true

                sender.sendMessage("<gold><b>(!)</b> Sending $amount$ to ${recipient.playerName()}, please wait...")

                val transferFunction = Function(
                    "transfer",
                    listOf(Address(recipientUser.walletAddress), Uint256(amountBaseUnits)),
                    emptyList<TypeReference<*>>()
                )
                val encodedCallData = FunctionEncoder.encode(transferFunction)

                val body = buildEthSendTransactionBody(
                    caip2 = caip2,
                    contractAddress = cfg.wallet.tokenContractAddress,
                    encodedCallData = encodedCallData
                )

                val rpcUrl = "https://api.privy.io/v1/wallets/${senderUser.walletId}/rpc"
                val transactionId = sendPrivyContractCallAndGetTransactionId(
                    rpcUrl = rpcUrl,
                    body = body,
                    appId = cfg.privy.appId,
                    basicAuthHeaderValue = basicAuthHeaderValue
                )

                if (transactionId == null) {
                    senderUser.restoreMoneyBase(amountBaseUnits)
                    moneyHeld = false
                    sendError(sender)
                    return@withContext
                }

                DefiWallet.instance.transactionManager.addTransaction(
                    sender,
                    recipient,
                    transactionId,
                    amount
                )
            } catch (e: APIException) {
                DefiWallet.instance.logger.severe(e.bodyAsString())
                if (moneyHeld) senderUser.restoreMoneyBase(amountBaseUnits)
                sendError(sender)
            } catch (e: Exception) {
                e.printStackTrace()
                if (moneyHeld) senderUser.restoreMoneyBase(amountBaseUnits)
                sendError(sender)
            }
        }
    }

    suspend fun getOrCreateUserData(uuid: UUID): UserData? {
        return walletsMutex.withLock {
            wallets[uuid]?.let { return it }

            val existingUser = DefiWallet.instance.databaseManager.loadUser(uuid)
            if (existingUser != null) {
                wallets[uuid] = existingUser
                return existingUser
            }

            val wallet = createUser(uuid) ?: return null
            DefiWallet.instance.launch(Dispatchers.IO) {
                sendMoney(null, uuid, 100)
            }

            wallets[uuid] = wallet
            wallet
        }
    }

    private suspend fun createUser(uuid: UUID): UserData? {
        return withContext(Dispatchers.IO) {
            try {
                val walletRequest = UserWalletRequest
                    .builder()
                    .chainType(WalletChainType.ETHEREUM)
                    .additionalSigners(
                        listOf(UserWalletAdditionalSigner(DefiWallet.instance.cfg.signing.masterKeyId))
                    )
                    .build()

                val requestBody = UserCreateRequestBody
                    .builder()
                    .wallets(listOf(walletRequest))
                    .linkedAccounts(
                        listOf(
                            LinkedAccountEmailInput
                                .builder()
                                .type(LinkedAccountEmailInputType.EMAIL)
                                .address("$uuid@${DefiWallet.instance.cfg.users.emailDomain}")
                                .build()
                        )
                    )
                    .build()

                DefiWallet.instance.logger.info("Creating new Privy user ($uuid)")
                val user = client.users().create(requestBody).user()?.getOrNull()
                if (user == null) {
                    DefiWallet.instance.logger.severe("Failed to create Privy user ($uuid)")
                    return@withContext null
                }

                val wallet = user.linkedAccounts()
                    .find { it.value() is LinkedAccountEthereumEmbeddedWallet }
                    ?.value() as? LinkedAccountEthereumEmbeddedWallet

                if (wallet == null) {
                    DefiWallet.instance.logger.severe("Failed to retrieve pre generated wallet of ${user.id()} ($uuid)")
                    return@withContext null
                }
                val userId = user.id()
                val walletId = wallet.id().get()
                val walletAddress = wallet.address()

                DefiWallet.instance.databaseManager.saveUser(uuid, userId, walletId, walletAddress)

                return@withContext UserData(
                    uuid = uuid,
                    userId = userId,
                    walletAddress = walletAddress,
                    walletId = walletId
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }

            return@withContext null
        }
    }

    fun sendError(uuid: UUID) {
        uuid.sendMessage("<red><b>(!)</b> An error happened during transfer of your funds. Don't worry, your balance hasn't changed.")
    }
}