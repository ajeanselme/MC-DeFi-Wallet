package com.eik0.defiwallet.managers

import com.eik0.defiwallet.DefiWallet
import com.eik0.defiwallet.extensions.playerName
import com.eik0.defiwallet.extensions.sendMessage
import com.github.shynixn.mccoroutine.bukkit.launch
import io.privy.api.models.components.StatusEnum
import io.privy.api.utils.JSON
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

class TransactionManager {
    data class PendingTransaction(
        val sender: UUID,
        val recipient: UUID,
        val transactionId: String,
        val amount: Long,
        var lastStatus: StatusEnum? = null
    )
    
    var transactionJob: Job? = null

    private val pendingMutex = Mutex()
    private val pendingTransaction = mutableListOf<PendingTransaction>()
    
    fun load() {
        transactionJob = DefiWallet.instance.launch(Dispatchers.IO) { 
            while(DefiWallet.instance.isEnabled) {
                validatePendingTransactions()
                delay(1000)
            }
        }
    }
    
    fun addTransaction(sender: UUID, recipient: UUID, transactionId: String, amount: Long) {
        DefiWallet.instance.launch(Dispatchers.IO) {
            pendingMutex.withLock {
                pendingTransaction.add(PendingTransaction(sender, recipient, transactionId, amount))
            }
        }
    }
    
    suspend fun validatePendingTransactions() {
        val snapshot = pendingMutex.withLock { pendingTransaction.toList() }

        for (pt in snapshot) {
            // Prevent API rate limiting
            delay(100)

            val transaction =
                DefiWallet.instance.walletManager.client.transactions().retrieve(pt.transactionId)
                    ?.transaction()?.getOrNull()

            if (transaction == null) {
                val amountBase = DefiWallet.instance.walletManager.tokensToBaseUnits(pt.amount)
                DefiWallet.instance.walletManager.getOrCreateUserData(pt.sender)?.restoreMoneyBase(amountBase)
                DefiWallet.instance.walletManager.sendError(pt.sender)

                DefiWallet.instance.walletManager.walletRefresher.requestBalanceRefresh(pt.sender)

                DefiWallet.instance.logger.severe(
                    "Failed to retrieve Privy transaction from transaction id: ${pt.transactionId} (sender=${pt.sender}, recipient=${pt.recipient})"
                )
                pendingMutex.withLock { pendingTransaction.removeIf { it.transactionId == pt.transactionId } }
                continue
            }

            val newStatus = transaction.status()

            val shouldHandle = pendingMutex.withLock {
                val current =
                    pendingTransaction.firstOrNull { it.transactionId == pt.transactionId } ?: return@withLock false
                if (current.lastStatus == newStatus) return@withLock false
                current.lastStatus = newStatus
                true
            }
            if (!shouldHandle) continue

            when (newStatus) {
                StatusEnum.CONFIRMED -> {
                    val amountBase = DefiWallet.instance.walletManager.tokensToBaseUnits(pt.amount)

                    DefiWallet.instance.walletManager.getOrCreateUserData(pt.sender)?.confirmSpendBase(amountBase)
                    DefiWallet.instance.walletManager.getOrCreateUserData(pt.recipient)?.creditBase(amountBase)

                    DefiWallet.instance.walletManager.walletRefresher.requestBalanceRefresh(pt.sender, pt.recipient)

                    pt.recipient.sendMessage("<green><b>(!)</b> You received ${pt.amount}$ from ${pt.sender.playerName()}!")
                    pt.sender.sendMessage("<green><b>(!)</b> You sent ${pt.amount}$ to ${pt.recipient.playerName()}!")

                    pendingMutex.withLock { pendingTransaction.removeIf { it.transactionId == pt.transactionId } }
                    DefiWallet.instance.logger.info(
                        "Finalized transaction ${pt.transactionId} (sender=${pt.sender}, recipient=${pt.recipient}, amount=${pt.amount})"
                    )
                }

                StatusEnum.EXECUTION_REVERTED, StatusEnum.FAILED, StatusEnum.PROVIDER_ERROR, StatusEnum.REPLACED -> {
                    val amountBase = DefiWallet.instance.walletManager.tokensToBaseUnits(pt.amount)

                    DefiWallet.instance.walletManager.getOrCreateUserData(pt.sender)?.restoreMoneyBase(amountBase)
                    DefiWallet.instance.walletManager.sendError(pt.sender)

                    DefiWallet.instance.walletManager.walletRefresher.requestBalanceRefresh(pt.sender)

                    pendingMutex.withLock { pendingTransaction.removeIf { it.transactionId == pt.transactionId } }
                    DefiWallet.instance.logger.severe(
                        "An error occurred during validation of transaction ${pt.transactionId} (status=$newStatus, sender=${pt.sender}, recipient=${pt.recipient}, amount=${pt.amount})"
                    )
                }

                else -> {
                    // Optional: emit progress updates}
                }
            }
        }
    }

    fun getTransactionIdFromResponseBody(response: String): String? {
        try {
            val jsonResponse = JSON.getMapper().readTree(response)
            val transactionId = jsonResponse.get("data")?.get("transaction_id")?.asText()
            if (transactionId == null) {
                DefiWallet.instance.logger.severe("Failed to parse transactionId from transaction request: $jsonResponse")
                return null
            }
            
            return transactionId
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return null
    }
}