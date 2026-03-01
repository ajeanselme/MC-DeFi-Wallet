package com.eik0.defiwallet.wallet

import com.eik0.defiwallet.DefiWallet
import com.github.shynixn.mccoroutine.bukkit.launch
import io.privy.api.models.components.FundsDepositedWebhookPayloadTypeErc20
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.web3j.contracts.eip20.generated.ERC20
import org.web3j.tx.ReadonlyTransactionManager
import org.web3j.tx.gas.DefaultGasProvider
import java.math.BigInteger
import java.util.UUID

class UserData(
    val uuid: UUID,
    val userId: String,
    val walletId: String,
    val walletAddress: String,
) {
    private val balanceMutex = Mutex()

    var cachedBalanceBase: BigInteger = BigInteger.ZERO
        private set

    var moneyOnHoldBase: BigInteger = BigInteger.ZERO
        private set

    init {
        DefiWallet.instance.launch(Dispatchers.IO) {
            runCatching {
                updateCachedBalance()
            }.onFailure {
                DefiWallet.instance.logger.warning("Failed to update balance for $uuid: ${it.message}")
            }
        }
    }

    suspend fun updateCachedBalance() {
        val newBalance = withContext(Dispatchers.IO) {
            val transactionManager =
                ReadonlyTransactionManager(DefiWallet.instance.walletManager.web3j, walletAddress)

            val contract = ERC20.load(
                DefiWallet.instance.cfg.wallet.tokenContractAddress,
                DefiWallet.instance.walletManager.web3j,
                transactionManager,
                DefaultGasProvider()
            )

            contract.balanceOf(walletAddress).send()
        }

        balanceMutex.withLock {
            cachedBalanceBase = newBalance
        }
    }

    suspend fun availableBase(): BigInteger = balanceMutex.withLock {
        cachedBalanceBase - moneyOnHoldBase
    }

    suspend fun holdMoneyBase(amountBase: BigInteger) {
        require(amountBase.signum() >= 0) { "amountBase must be >= 0" }
        balanceMutex.withLock {
            moneyOnHoldBase += amountBase
        }
    }

    suspend fun restoreMoneyBase(amountBase: BigInteger) {
        require(amountBase.signum() >= 0) { "amountBase must be >= 0" }
        balanceMutex.withLock {
            moneyOnHoldBase = (moneyOnHoldBase - amountBase).coerceAtLeast(BigInteger.ZERO)
        }
    }

    suspend fun confirmSpendBase(amountBase: BigInteger) {
        require(amountBase.signum() >= 0) { "amountBase must be >= 0" }
        balanceMutex.withLock {
            moneyOnHoldBase = (moneyOnHoldBase - amountBase).coerceAtLeast(BigInteger.ZERO)
            cachedBalanceBase = (cachedBalanceBase - amountBase).coerceAtLeast(BigInteger.ZERO)
        }
    }

    suspend fun creditBase(amountBase: BigInteger) {
        require(amountBase.signum() >= 0) { "amountBase must be >= 0" }
        balanceMutex.withLock {
            cachedBalanceBase += amountBase
        }
    }

    private fun BigInteger.coerceAtLeast(min: BigInteger): BigInteger = if (this < min) min else this
}