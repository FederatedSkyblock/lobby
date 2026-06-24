package fr.overrride.minecraft.skyblockplugin.lobby

import com.google.gson.JsonObject
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

/**
 * `/pair <code>` — claims a pairing code shown in a player's own island-server console, linking that
 * server to the player's account.
 *
 * The heavy lifting is on the island server (which opened the request and polls for the result); the
 * lobby only forwards the code + player UUID to central. The request is sent with the shared Ktor
 * coroutine client (non-blocking); the player is messaged back on the main thread once it returns.
 */
class PairCommand(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val islandServerLocator: IslandServerLocator,
) {

    // `/island/pair` lives on central's PRIVATE API surface (internal network only, not exposed to
    // the internet), so it is reached via CENTRAL_PRIVATE_BASE_URL rather than the public base URL.
    // Inside the compose network that is http://central:3001; overridable via env.
    private val baseUrl: String =
        (System.getenv("CENTRAL_PRIVATE_BASE_URL") ?: "http://central:3001").trimEnd('/')

    fun execute(player: Player, code: String): Int {
        val normalized = code.trim().uppercase()
        if (normalized.isEmpty()) {
            player.sendMessage(Component.text("Usage: /pair <code>", NamedTextColor.RED))
            return 0
        }

        player.sendMessage(Component.text("Pairing your island server with code $normalized…", NamedTextColor.YELLOW))
        val playerUuid = player.uniqueId
        val body = JsonObject().apply {
            addProperty("code", normalized)
            addProperty("player_uuid", playerUuid.toString())
        }.toString()

        scope.launch {
            val status = try {
                http.post("$baseUrl/island/pair") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status.value
            } catch (e: Exception) {
                plugin.logger.warning("Pair request to central failed: ${e.message}")
                -1
            }
            // Hop back to the main thread to message the player.
            plugin.server.scheduler.runTask(plugin, Runnable { reply(player, status) })
        }
        return com.mojang.brigadier.Command.SINGLE_SUCCESS
    }

    private fun reply(player: Player, status: Int) {
        if (status == 200) {
            // Pairing recorded — central now knows this player's server, so offer a transfer to it.
            islandServerLocator.promptIfPaired(player)
        }
        val message = when (status) {
            200 -> Component.text(
                "Your island server is now paired with your account! It will load your island shortly.",
                NamedTextColor.GREEN,
            )
            404 -> Component.text(
                "Invalid or expired pairing code. Check the code shown in your island server console.",
                NamedTextColor.RED,
            )
            else -> Component.text(
                "Couldn't reach the pairing service right now. Please try again in a moment.",
                NamedTextColor.RED,
            )
        }
        player.sendMessage(message)
    }
}
