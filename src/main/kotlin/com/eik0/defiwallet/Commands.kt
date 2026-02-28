package com.eik0.defiwallet

import com.github.shynixn.mccoroutine.bukkit.launch
import dev.jorel.commandapi.kotlindsl.commandAPICommand
import dev.jorel.commandapi.kotlindsl.entitySelectorArgumentOnePlayer
import dev.jorel.commandapi.kotlindsl.longArgument
import dev.jorel.commandapi.kotlindsl.playerExecutor
import kotlinx.coroutines.Dispatchers
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.entity.Player

class Commands {
    init {
        commandAPICommand("balance") {
            playerExecutor { player, _ ->
                DefiWallet.instance.launch(Dispatchers.IO) {
                    val balance = DefiWallet.instance.walletManager.getBalance(player.uniqueId)
                    player.sendMessage(
                        MiniMessage.miniMessage().deserialize(
                            "<gold><b>(!)</b> Your balance is $balance"
                        )
                    )
                }
            }
        }

        commandAPICommand("pay") {
            entitySelectorArgumentOnePlayer("player")
            longArgument("amount")
            playerExecutor { player, arguments ->
                val recipient = arguments.get("player") as Player
                val amount = arguments.get("amount") as Long
                DefiWallet.instance.launch(Dispatchers.IO) {
                    DefiWallet.instance.walletManager.sendMoney(player.uniqueId, recipient.uniqueId, amount)
                }
            }
        }
    }
}