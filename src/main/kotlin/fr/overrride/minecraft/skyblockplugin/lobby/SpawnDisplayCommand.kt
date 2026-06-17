package fr.overrride.minecraft.skyblockplugin.lobby

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f

class SpawnDisplayCommand {

    fun execute(player: Player, itemScale: Float, glassScale: Float): Int {
        if (!player.hasPermission("skyblock.admin")) {
            player.sendMessage(Component.text("You don't have permission to use this command.", NamedTextColor.RED))
            return 0
        }
        val handItem = player.inventory.itemInMainHand
        if (handItem.type == Material.AIR) {
            player.sendMessage(Component.text("Hold an item in your main hand first.", NamedTextColor.RED))
            return 0
        }

        val cardinalYaw = snapToCardinalYaw(player.location.yaw)

        // Snap X/Z to block center, raise Y so glass bottom sits on the block surface
        val centerY = player.location.y + (glassScale / 2f).toDouble()
        val spawnLoc = player.location.clone().apply {
            x     = Math.floor(player.location.x) + 0.5
            y     = centerY
            z     = Math.floor(player.location.z) + 0.5
            yaw   = cardinalYaw
            pitch = 0f
        }

        spawnItemDisplay(spawnLoc,        ItemStack(Material.GLASS), glassScale)
        spawnItemDisplay(spawnLoc.clone(), handItem.clone(),          itemScale)

        player.sendMessage(
            Component.text("Spawned 2 item displays (item ${itemScale}x, glass ${glassScale}x, facing ${cardinalName(cardinalYaw)}).", NamedTextColor.GREEN)
        )
        return com.mojang.brigadier.Command.SINGLE_SUCCESS
    }

    private fun snapToCardinalYaw(yaw: Float): Float {
        val n = ((yaw % 360f) + 360f) % 360f
        return when {
            n <= 45f || n > 315f -> 0f    // South
            n <= 135f            -> 90f   // West
            n <= 225f            -> 180f  // North
            else                 -> 270f  // East
        }
    }

    private fun cardinalName(yaw: Float) = when (yaw) {
        0f   -> "South"
        90f  -> "West"
        180f -> "North"
        else -> "East"
    }

    private fun spawnItemDisplay(loc: Location, item: ItemStack, scale: Float) {
        val entity = loc.world!!.spawn(loc, ItemDisplay::class.java)
        entity.setItemStack(item)
        entity.billboard = Display.Billboard.FIXED
        entity.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
        entity.isPersistent = true
        entity.transformation = Transformation(
            Vector3f(0f, 0f, 0f),
            Quaternionf(),
            Vector3f(scale, scale, scale),
            Quaternionf()
        )
    }
}
