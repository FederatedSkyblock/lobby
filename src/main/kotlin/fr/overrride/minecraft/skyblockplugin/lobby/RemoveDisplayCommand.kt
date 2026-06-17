package fr.overrride.minecraft.skyblockplugin.lobby

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Display
import org.bukkit.entity.Player

class RemoveDisplayCommand {

    companion object {
        private const val SEARCH_RADIUS = 10.0
    }

    fun execute(player: Player): Int {
        val nearest = player.getNearbyEntities(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS)
            .filterIsInstance<Display>()
            .minByOrNull { it.location.distanceSquared(player.location) }

        if (nearest == null) {
            player.sendMessage(Component.text("No display entity found nearby.", NamedTextColor.RED))
            return 0
        }

        nearest.remove()
        player.sendMessage(Component.text("Removed nearest display entity.", NamedTextColor.GREEN))
        return com.mojang.brigadier.Command.SINGLE_SUCCESS
    }
}
