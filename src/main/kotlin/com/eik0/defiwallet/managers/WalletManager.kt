package com.eik0.defiwallet.managers

import com.eik0.defiwallet.DefiWallet
import com.eik0.defiwallet.extensions.sendMessage
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.shynixn.mccoroutine.bukkit.launch
import io.privy.api.PrivyClient
import io.privy.api.models.components.LinkedAccountEmailInput
import io.privy.api.models.components.LinkedAccountEmailInputType
import io.privy.api.models.components.LinkedAccountEthereumEmbeddedWallet
import io.privy.api.models.components.StatusEnum
import io.privy.api.models.components.UserWalletAdditionalSigner
import io.privy.api.models.components.UserWalletRequest
import io.privy.api.models.components.WalletChainType
import io.privy.api.models.errors.APIException
import io.privy.api.models.operations.UserCreateRequestBody
import io.privy.api.signing.HttpMethod
import io.privy.api.signing.WalletApiRequestSignatureInput
import io.privy.api.utils.JSON
import org.web3j.contracts.eip20.generated.ERC20
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.web3j.protocol.Web3j
import org.web3j.protocol.http.HttpService
import org.web3j.tx.ReadonlyTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import org.web3j.abi.FunctionEncoder
import org.web3j.abi.TypeReference
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.Function
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

class WalletManager {
    class UserData(
        val uuid: UUID,
        val userId: String,
        val walletId: String,
        val walletAddress: String,
    )
    
    val tokenChainId = DefiWallet.instance.config.getInt("token_chain_id").toLong()
    val tokenContractAddress = DefiWallet.instance.config.getString("token_contract_address")
    val privyAppId = DefiWallet.instance.config.getString("privy_app_id")
    val privyAppSecret = DefiWallet.instance.config.getString("privy_app_private")
    
    val masterKeyId = DefiWallet.instance.config.getString("master_key_id")
    val masterKeySecret = DefiWallet.instance.config.getString("master_key_secret")

    val userEmailDomain = DefiWallet.instance.config.getString("user_email_domain")
    
    val rpcUrl = DefiWallet.instance.config.getString("chain_rpc")
    val web3j = Web3j.build(HttpService(rpcUrl))
    
    val wallets = mutableMapOf<UUID, UserData>()
    
    val client: PrivyClient = PrivyClient.builder()
        .appId(privyAppId)
        .appSecret(privyAppSecret)
        .build()
    
    suspend fun getBalance(uuid: UUID): BigInteger {
        return withContext(Dispatchers.IO) {
            try {
                val userData = getOrCreateWallet(uuid) ?: return@withContext BigInteger.ZERO
                
                val transactionManager = ReadonlyTransactionManager(web3j, userData.walletAddress)
                val contract = ERC20.load(tokenContractAddress, web3j, transactionManager, DefaultGasProvider())
                
                val balance = contract.balanceOf(userData.walletAddress).send()
                val decimals = contract.decimals().send()
                
                val balanceDouble = balance / BigInteger.TEN.pow(decimals.toInt())
                
                return@withContext balanceDouble
                
            } catch (e: Exception) {
                DefiWallet.instance.logger.severe("Failed to retrieve wallet balance for $uuid: ${e.message}")
                e.printStackTrace()
            }
            
            return@withContext BigInteger.ZERO
        }
    }
    
    suspend fun sendMoney(sender: UUID, recipient: UUID, amount: Long) {
        withContext(Dispatchers.IO) {
            try {
                sender.sendMessage("<gold><b>(!)</b> Transferring $amount$, please wait...")

                val senderUser = getOrCreateWallet(sender) ?: return@withContext
                val recipientUser = getOrCreateWallet(recipient) ?: return@withContext
                
                val basicAuth = Base64.getEncoder().encodeToString("$privyAppId:$privyAppSecret".toByteArray(Charsets.UTF_8))

                val transferFunction = Function(
                    "transfer",
                    listOf(Address(recipientUser.walletAddress), Uint256(BigInteger.valueOf(amount) * BigInteger.TEN.pow(18))),
                    emptyList<TypeReference<*>>()
                )
                val encodedFunction = FunctionEncoder.encode(transferFunction)

                val bodyString = """
                        {
                            "method": "eth_sendTransaction",
                            "caip2": "eip155:$tokenChainId",
                            "params": {
                                "transaction": {
                                    "to": "$tokenContractAddress",
                                    "data": "$encodedFunction"
                                }
                            },
                            "sponsor": true
                        }
                    """.trimIndent()

                val objectMapper = ObjectMapper()
                val body: JsonNode = objectMapper.readTree(bodyString)

                val signature = client
                    .utils()
                    .requestSigner()
                    .generateAuthorizationSignature(
                        masterKeySecret,
                        WalletApiRequestSignatureInput(
                            1,
                            body,
                            HttpMethod.POST,
                            "https://api.privy.io/v1/wallets/${senderUser.walletId}/rpc",
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
                val transactionId = DefiWallet.instance.transactionManager.getTransactionIdFromResponseBody(httpResponse.body())
                if(transactionId == null) {
                    sendError(sender)
                    return@withContext
                }
                DefiWallet.instance.transactionManager.addTransaction(sender, recipient, transactionId, amount)

            } catch (e: APIException) {
                val errorBody = e.bodyAsString()
                DefiWallet.instance.logger.severe(errorBody)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    suspend fun getOrCreateWallet(uuid: UUID): UserData? {
        return withContext(Dispatchers.IO) {
            wallets[uuid]?.let { return@withContext it }
            
            val existingUser = DefiWallet.instance.databaseManager.loadUser(uuid)
            if (existingUser != null) {
                wallets[uuid] = existingUser
                return@withContext existingUser
            }
            
            val wallet = createUser(uuid) ?: return@withContext null
            
            wallets[uuid] = wallet
            return@withContext wallet
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