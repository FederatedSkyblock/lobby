package fr.overrride.minecraft.skyblockplugin.lobby

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * Transfers a player to their paired island, carrying a freshly minted session token in a transfer
 * cookie. The full flow on a `/joinisland` (or transfer-prompt click):
 *   1. ask central's private API to mint a session token + resolve the island address (async HTTP);
 *   2. back on the main thread, stash the token in the [SessionCookie] cookie; then
 *   3. fire the native transfer packet — the cookie rides along to the island server, where the
 *      watchdog reads it on join.
 *
 * Steps 2–3 must run on the main thread (Bukkit API); only step 1 is off-thread.
 */
class IslandTransfer(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val sessions: IslandSessionClient,
) {

    /** Mints a session, attaches it as a cookie, and transfers [player] to their island. */
    fun transferToIsland(player: Player) {
        val uuid = player.uniqueId
        player.sendMessage(Component.text("Preparing your island…", NamedTextColor.YELLOW))

        scope.launch {
            val session = sessions.mintSession(uuid)
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                if (session == null) {
                    player.sendMessage(
                        Component.text(
                            "Couldn't reach your island right now. Make sure it's paired and try again.",
                            NamedTextColor.RED,
                        )
                    )
                    return@Runnable
                }
                deliver(player, session)
            })
        }
    }

    /** Main-thread: store the cookie then fire the transfer to the resolved `host:port`. */
    private fun deliver(player: Player, session: IslandSessionClient.Session) {
        val address = session.serverAddress
        val colon = address.lastIndexOf(':')
        val host = if (colon == -1) address else address.substring(0, colon)
        val port = if (colon == -1) DEFAULT_PORT else address.substring(colon + 1).toIntOrNull()

        if (host.isBlank() || port == null || port !in 1..65535) {
            plugin.logger.warning("Central returned an unusable island address '$address' for ${player.uniqueId}.")
            player.sendMessage(Component.text("Your island address is misconfigured. Please contact an admin.", NamedTextColor.RED))
            return
        }

        // Store the session token in the client's cookie store *before* transferring, so it travels
        // with the player to the island server.
        player.storeCookie(SessionCookie.KEY, session.token.toByteArray(Charsets.UTF_8))

        player.sendMessage(Component.text("Transferring you to your island…", NamedTextColor.GREEN))
        player.transfer(host, port)
    }

    private companion object {
        const val DEFAULT_PORT = 25565
    }
}
