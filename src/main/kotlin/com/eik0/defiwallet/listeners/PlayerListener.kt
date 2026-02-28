package com.eik0.defiwallet.listeners

import com.eik0.defiwallet.DefiWallet
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class PlayerListener: Listener {
    @EventHandler
    suspend fun onJoin(event: PlayerJoinEvent) {
        DefiWallet.instance.walletManager.getOrCreateWallet(event.player.uniqueId)
    }
}