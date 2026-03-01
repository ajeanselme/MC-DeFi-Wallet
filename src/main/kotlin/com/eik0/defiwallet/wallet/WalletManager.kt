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

    val tokenChainId = DefiWallet.instance.config.getInt("token_chain_id").toLong()
    val tokenContractAddress = DefiWallet.instance.config.getString("token_contract_address")
    val privyAppId = DefiWallet.instance.config.getString("privy_app_id")
    val privyAppSecret = DefiWallet.instance.config.getString("privy_app_private")

    val masterKeyId = DefiWallet.instance.config.getString("master_key_id")
    val masterKeySecret = DefiWallet.instance.config.getString("master_key_secret")

    val userEmailDomain = DefiWallet.instance.config.getString("user_email_domain")

    val rpcUrl = DefiWallet.instance.config.getString("chain_rpc")
    val web3j: Web3j = Web3j.build(HttpService(rpcUrl))

    private val walletsMutex = Mutex()
    val wallets = mutableMapOf<UUID, UserData>()

    val client: PrivyClient = PrivyClient.builder()
        .appId(privyAppId)
        .appSecret(privyAppSecret)
        .build()

    private val tokenDecimalsMutex = Mutex()
    private var cachedTokenDecimals: Int? = null

    private suspend fun getTokenDecimals(): Int {
        cachedTokenDecimals?.let { return it }
        return tokenDecimalsMutex.withLock {
            cachedTokenDecimals?.let { return@withLock it }

            val decimals = withContext(Dispatchers.IO) {
                val transactionManager =
                    ReadonlyTransactionManager(web3j, tokenContractAddress)
                val contract = ERC20.load(
                    tokenContractAddress,
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

    suspend fun sendMoney(sender: UUID, recipient: UUID, amount: Long) {
        withContext(Dispatchers.IO) {
            val senderUser = getOrCreateUserData(sender) ?: return@withContext
            val recipientUser = getOrCreateUserData(recipient) ?: return@withContext

            val amountBase = tokensToBaseUnits(amount)

            var held = false
            try {
                val available = senderUser.availableBase()
                if (available < amountBase) {
                    val displayAvail = baseUnitsToWholeTokensFloor(available)
                    sender.sendMessage("<red><b>(!)</b> You don't have enough money! Available: ${displayAvail}$")
                    return@withContext
                }

                senderUser.holdMoneyBase(amountBase)
                held = true

                sender.sendMessage("<gold><b>(!)</b> Sending $amount$ to ${recipient.playerName()}, please wait...")

                val basicAuth = Base64.getEncoder()
                    .encodeToString("$privyAppId:$privyAppSecret".toByteArray(Charsets.UTF_8))

                val transferFunction = Function(
                    "transfer",
                    listOf(Address(recipientUser.walletAddress), Uint256(amountBase)),
                    emptyList<TypeReference<*>>()
                )
                val encodedFunction = FunctionEncoder.encode(transferFunction)

                val body: ObjectNode = ObjectMapper().createObjectNode().apply {
                    put("method", "eth_sendTransaction")
                    put("caip2", "eip155:$tokenChainId")
                    putObject("params").putObject("transaction").apply {
                        put("to", tokenContractAddress)
                        put("data", encodedFunction)
                    }
                    put("sponsor", true)
                }

                val url = "https://api.privy.io/v1/wallets/${senderUser.walletId}/rpc"

                val signature = client
                    .utils()
                    .requestSigner()
                    .generateAuthorizationSignature(
                        masterKeySecret,
                        WalletApiRequestSignatureInput(
                            1,
                            body,
                            HttpMethod.POST,
                            url,
                            null
                        )
                    )

                val httpRequest = HttpRequest
                    .newBuilder(URI("https://api.privy.io/v1/wallets/${senderUser.walletId}/rpc"))
                    .header("Authorization", "Basic $basicAuth")
                    .header("privy-app-id", privyAppId)
                    .header("privy-authorization-signature", signature)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build()

                val httpResponse = HttpClient.newHttpClient().send(httpRequest, HttpResponse.BodyHandlers.ofString())
                val transactionId =
                    DefiWallet.instance.transactionManager.getTransactionIdFromResponseBody(httpResponse.body())

                if (transactionId == null) {
                    senderUser.restoreMoneyBase(amountBase)
                    held = false
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
                if (held) senderUser.restoreMoneyBase(amountBase)
                sendError(sender)
            } catch (e: Exception) {
                e.printStackTrace()
                if (held) senderUser.restoreMoneyBase(amountBase)
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
                        listOf(UserWalletAdditionalSigner(masterKeyId!!))
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
                                .address("$uuid@$userEmailDomain")
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