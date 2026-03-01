package com.eik0.defiwallet.extensions

import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import java.util.UUID

fun UUID.sendMessage(message: String) {
    Bukkit.getPlayer(this)?.sendMessage(
        MiniMessage.miniMessage().deserialize(message)
    )
}