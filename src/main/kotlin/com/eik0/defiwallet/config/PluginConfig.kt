package com.eik0.defiwallet.config

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.plugin.java.JavaPlugin

class PluginConfig(private val plugin: JavaPlugin) {
    fun reloadAndValidate() {
        plugin.reloadConfig()

        mysql.host
        mysql.port
        mysql.database
        mysql.username
        mysql.password

        wallet.tokenChainId
        wallet.tokenContractAddress
        wallet.chainRpc
        wallet.tokenOwnerId

        privy.appId
        privy.appSecret

        signing.masterKeyId
        signing.masterKeySecret

        users.emailDomain
    }

    val mysql = Mysql(plugin)
    val wallet = Wallet(plugin)
    val privy = Privy(plugin)
    val signing = Signing(plugin)
    val users = Users(plugin)

    class Mysql(private val plugin: JavaPlugin) {
        val host: String get() = plugin.config.requiredString("mysql.host")
        val port: Int get() = plugin.config.int("mysql.port", 3306)
        val database: String get() = plugin.config.requiredString("mysql.database")
        val username: String get() = plugin.config.requiredString("mysql.username")
        val password: String get() = plugin.config.requiredString("mysql.password")
    }

    class Wallet(private val plugin: JavaPlugin) {
        val tokenChainId: Long get() = plugin.config.requiredLong("token_chain_id")
        val tokenContractAddress: String get() = plugin.config.requiredString("token_contract_address")
        val tokenOwnerId: String get() = plugin.config.requiredString("token_owner_id")
        val chainRpc: String get() = plugin.config.requiredString("chain_rpc")
    }

    class Privy(private val plugin: JavaPlugin) {
        val appId: String get() = plugin.config.requiredString("privy_app_id")
        val appSecret: String get() = plugin.config.requiredString("privy_app_secret")
    }

    class Signing(private val plugin: JavaPlugin) {
        val masterKeyId: String get() = plugin.config.requiredString("master_key_id")
        val masterKeySecret: String get() = plugin.config.requiredString("master_key_secret")
    }

    class Users(private val plugin: JavaPlugin) {
        val emailDomain: String get() = plugin.config.requiredString("user_email_domain")
    }
}


private fun ConfigurationSection.requiredString(path: String): String {
    val value = getString(path)?.trim()
    if (value.isNullOrEmpty()) {
        throw IllegalStateException("Missing/blank config value at '$path' in config.yml")
    }
    return value
}

private fun ConfigurationSection.int(path: String, default: Int): Int =
    if (isInt(path)) getInt(path) else default

private fun ConfigurationSection.requiredLong(path: String): Long {
    return when {
        isLong(path) -> getLong(path)
        isInt(path) -> getInt(path).toLong()
        else -> throw IllegalStateException("Missing/invalid long at '$path' in config.yml")
    }
}