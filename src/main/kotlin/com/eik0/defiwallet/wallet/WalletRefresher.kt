package com.eik0.defiwallet.wallet

import com.eik0.defiwallet.DefiWallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

class WalletRefresher(val walletManager: WalletManager) {
    private val balanceRefreshCooldownMs: Long = 5_000L

    private val balanceRefreshMutex = Mutex()
    private val pendingBalanceRefreshUuids = mutableSetOf<UUID>()
    private var lastBalanceRefreshAtMs: Long = 0L
    private var refreshJobRunning: Boolean = false

    suspend fun requestBalanceRefresh(vararg uuids: UUID) {
        if (uuids.isEmpty()) return

        val shouldSchedule = balanceRefreshMutex.withLock {
            pendingBalanceRefreshUuids.addAll(uuids)
            if (refreshJobRunning) return@withLock false

            val now = System.currentTimeMillis()
            val elapsed = now - lastBalanceRefreshAtMs
            elapsed >= balanceRefreshCooldownMs
        }

        if (shouldSchedule) {
            runGlobalBalanceRefreshIfAllowed()
        }
    }

    private suspend fun runGlobalBalanceRefreshIfAllowed() {
        balanceRefreshMutex.withLock {
            if (refreshJobRunning) return
            val now = System.currentTimeMillis()
            val elapsed = now - lastBalanceRefreshAtMs
            if (elapsed < balanceRefreshCooldownMs) return

            refreshJobRunning = true
            lastBalanceRefreshAtMs = now
        }

        try {
            val batch: List<UUID> = balanceRefreshMutex.withLock {
                val drained = pendingBalanceRefreshUuids.toList()
                pendingBalanceRefreshUuids.clear()
                drained
            }

            withContext(Dispatchers.IO) {
                for (uuid in batch) {
                    runCatching {
                         walletManager.getOrCreateUserData(uuid)?.updateCachedBalance()
                    }.onFailure {
                        DefiWallet.instance.logger.warning("Balance refresh failed for $uuid: ${it.message}")
                    }
                }
            }
        } finally {
            balanceRefreshMutex.withLock {
                refreshJobRunning = false
            }
        }
    }
}