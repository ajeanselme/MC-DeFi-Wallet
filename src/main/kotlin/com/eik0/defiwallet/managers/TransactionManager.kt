package com.eik0.defiwallet.managers

import com.eik0.defiwallet.DefiWallet
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
    
    fun validatePendingTransactions() {
        val iterator = pendingTransaction.iterator()
        while (iterator.hasNext()) {
            val pendingTransaction = iterator.next()

            val transaction =
                DefiWallet.instance.walletManager.client.transactions().retrieve(pendingTransaction.transactionId)
                    ?.transaction()?.getOrNull()

            if (transaction == null) {
                DefiWallet.instance.walletManager.sendError(pendingTransaction.sender)
                DefiWallet.instance.logger.severe("Failed to retrieve Privy transaction from transaction id: ${pendingTransaction.transactionId} (sender=${pendingTransaction.sender}, recipient=${pendingTransaction.recipient})")
                iterator.remove()
                continue
            }

            val newStatus = transaction.status()

            if(pendingTransaction.lastStatus == newStatus) {
                continue
            }

            pendingTransaction.lastStatus = newStatus

            when(newStatus) {
                StatusEnum.CONFIRMED -> {
                    pendingTransaction.sender.sendMessage("<green><b>(!)</b> Vous avez envoyé ${pendingTransaction.amount}$ à ${pendingTransaction.recipient} !")
                    iterator.remove()
                    DefiWallet.instance.logger.info("Finalized transaction ${pendingTransaction.transactionId} (sender=${pendingTransaction.sender}, recipient=${pendingTransaction.recipient}, amount=${pendingTransaction.amount})")
                    continue
                }

                StatusEnum.EXECUTION_REVERTED, StatusEnum.FAILED, StatusEnum.PROVIDER_ERROR, StatusEnum.REPLACED -> {
                    DefiWallet.instance.walletManager.sendError(pendingTransaction.sender)
                    iterator.remove()
                    DefiWallet.instance.logger.severe("An error occurred during validation of transaction ${pendingTransaction.transactionId} (status=$newStatus, sender=${pendingTransaction.sender}, recipient=${pendingTransaction.recipient}, amount=${pendingTransaction.amount})")
                    continue
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