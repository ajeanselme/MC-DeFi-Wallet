package com.eik0.defiwallet.extensions

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import java.util.UUID

fun UUID.sendMessage(message: String) {
    Bukkit.getPlayer(this)?.sendMessage(
        MiniMessage.miniMessage().deserialize(message)
    )
}

suspend fun UUID.playerName(): String? {
    return withContext(Dispatchers.IO) {
        return@withContext Bukkit.getOfflinePlayer(this@playerName).name
    }
}