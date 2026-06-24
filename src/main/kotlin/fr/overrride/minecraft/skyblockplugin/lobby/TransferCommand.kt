package fr.overrride.minecraft.skyblockplugin.lobby

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player

/**
 * `/transfer <ip>[:<port>]` — hands the player off to an *external* server using Minecraft's
 * native transfer packet (`Player#transfer`, 1.20.5+). Unlike the old `/skyblock` proxy switch,
 * this leaves this network entirely: the client reconnects directly to the given address. The
 * port defaults to 25565 (the vanilla default) when omitted.
 */
class TransferCommand {

    fun execute(player: Player, address: String): Int {
        val host: String
        val port: Int

        val colon = address.lastIndexOf(':')
        if (colon == -1) {
            host = address
            port = DEFAULT_PORT
        } else {
            host = address.substring(0, colon)
            val portStr = address.substring(colon + 1)
            val parsed = portStr.toIntOrNull()
            if (parsed == null || parsed !in 1..65535) {
                player.sendMessage(Component.text("Invalid port '$portStr'. Must be a number between 1 and 65535.", NamedTextColor.RED))
                return 0
            }
            port = parsed
        }

        if (host.isBlank()) {
            player.sendMessage(Component.text("Usage: /transfer <ip>[:<port>]", NamedTextColor.RED))
            return 0
        }

        player.sendMessage(Component.text("Transferring you to $host:$port…", NamedTextColor.YELLOW))
        player.transfer(host, port)
        return com.mojang.brigadier.Command.SINGLE_SUCCESS
    }

    private companion object {
        const val DEFAULT_PORT = 25565
    }
}
