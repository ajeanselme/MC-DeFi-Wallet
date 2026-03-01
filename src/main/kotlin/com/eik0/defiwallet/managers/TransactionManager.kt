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
    val pendingTransaction = mutableListOf<PendingTransaction>()
    
    fun load() {
        transactionJob = DefiWallet.instance.launch(Dispatchers.IO) { 
            while(DefiWallet.instance.isEnabled) {
                validatePendingTransactions()
                delay(1000)
            }
        }
    }
    
    fun addTransaction(sender: UUID, recipient: UUID, transactionId: String, amount: Long) {
        pendingTransaction.add(PendingTransaction(sender, recipient, transactionId, amount))
    }
    
    suspend fun validatePendingTransactions() {
        val iterator = pendingTransaction.iterator()
        while (iterator.hasNext()) {
            // Prevent API rate limiting
            delay(100)

            val pendingTransaction = iterator.next()

            val transaction =
                DefiWallet.instance.walletManager.client.transactions().retrieve(pendingTransaction.transactionId)
                    ?.transaction()?.getOrNull()

            if (transaction == null) {
                val amountBase = DefiWallet.instance.walletManager.tokensToBaseUnits(pt.amount)
                DefiWallet.instance.walletManager.getOrCreateUserData(pt.sender)?.restoreMoneyBase(amountBase)
                DefiWallet.instance.walletManager.sendError(pt.sender)
                DefiWallet.instance.logger.severe(
                    "Failed to retrieve Privy transaction from transaction id: ${pt.transactionId} (sender=${pt.sender}, recipient=${pt.recipient})"
                )
                pendingMutex.withLock { pendingTransaction.removeIf { it.transactionId == pt.transactionId } }
                continue
            }

            val newStatus = transaction.status()

            if(pendingTransaction.lastStatus == newStatus) {
                continue
            }

            pendingTransaction.lastStatus = newStatus

            when(newStatus) {
                StatusEnum.CONFIRMED -> {
                    val amountBase = DefiWallet.instance.walletManager.tokensToBaseUnits(pt.amount)

                    DefiWallet.instance.walletManager.getOrCreateUserData(pt.sender)?.confirmSpendBase(amountBase)
                    DefiWallet.instance.walletManager.getOrCreateUserData(pt.recipient)?.creditBase(amountBase)

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

                    pendingMutex.withLock { pendingTransaction.removeIf { it.transactionId == pt.transactionId } }
                    DefiWallet.instance.logger.severe(
                        "An error occurred during validation of transaction ${pt.transactionId} (status=$newStatus, sender=${pt.sender}, recipient=${pt.recipient}, amount=${pt.amount})"
                    )
                }
                // Possibility to add status updates to keep user informed of the progress
                else -> {}
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