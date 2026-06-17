package fr.overrride.minecraft.skyblockplugin.lobby

import fr.overrride.minecraft.skyblockplugin.sync.PlayerSyncManager
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * Sends a player to the skyblock server via a custom `skyblock:redirect` plugin message,
 * handled by SkyblockProxyPlugin (Velocity). The legacy "BungeeCord" plugin channel is
 * unreliable on this Velocity setup, so a dedicated channel is used instead (see
 * `velocity-plugin/SkyblockProxyPlugin.kt`). Island worlds only exist on the skyblock
 * server, so going to your island from the lobby always means switching servers first;
 * [IslandJoinListener] then teleports the player to their island once they arrive.
 */
class IslandRedirectCommand(
    private val plugin: JavaPlugin,
    private val syncManager: PlayerSyncManager
) {

    fun execute(player: Player) {
        // Flush the player's current lobby state (e.g. a shop purchase made a moment ago) to the
        // shared DB *before* switching, so skyblock reads it on join. The lobby→skyblock redirect
        // is the one switch where a player can act and leave in the same instant, and Velocity
        // brings up the skyblock connection before the lobby's quit-save fires - so without this
        // blocking save that last action could be lost. Synchronous on purpose.
        syncManager.save(player)
        player.sendPluginMessage(plugin, "skyblock:redirect", "skyblock".toByteArray(Charsets.UTF_8))
    }
}
