package com.eik0.defiwallet

import com.eik0.defiwallet.listeners.PlayerListener
import com.eik0.defiwallet.managers.DatabaseManager
import com.eik0.defiwallet.managers.WalletManager
import com.github.shynixn.mccoroutine.bukkit.SuspendingJavaPlugin
import com.github.shynixn.mccoroutine.bukkit.registerSuspendingEvents
import dev.jorel.commandapi.CommandAPI
import dev.jorel.commandapi.CommandAPIPaperConfig
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bukkit.Bukkit
import java.security.Security

class DefiWallet : SuspendingJavaPlugin() {

    companion object {
        lateinit var instance: DefiWallet
    }

    val databaseManager by lazy { DatabaseManager() }
    val walletManager by lazy { WalletManager() }

    override suspend fun onLoadAsync() {
        super.onLoadAsync()
        val config = CommandAPIPaperConfig(this)
        config.fallbackToLatestNMS(true)
        CommandAPI.onLoad(config)
    }

    override suspend fun onEnableAsync() {
        super.onEnableAsync()
        instance = this
        saveDefaultConfig()

        CommandAPI.onEnable()

        Commands()

        Bukkit.getPluginManager().registerSuspendingEvents(PlayerListener(), this)

        Security.addProvider(BouncyCastleProvider())
        databaseManager.load()
    }

    override suspend fun onDisableAsync() {
        databaseManager.close()
        super.onDisableAsync()
        CommandAPI.onDisable()
    }
}
