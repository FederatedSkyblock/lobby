package fr.overrride.minecraft.skyblockplugin.lobby

import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Looks up the island server a player is paired with (via central's `/island/server`) and offers a
 * one-click transfer to it.
 *
 * Used when a player joins the lobby and right after they successfully run `/pair`, so they never
 * have to type the address by hand — the prompt runs the existing `/transfer <address>` command.
 * Central records the server's address at pairing time; we just surface it.
 */
class IslandServerLocator(
    private val plugin: JavaPlugin,
    private val scope: CoroutineScope,
    private val http: HttpClient,
) {

    // `/island/server` lives on central's PRIVATE API surface (internal network only, not exposed to
    // the internet), so it is reached via CENTRAL_PRIVATE_BASE_URL. Inside the compose network that
    // is http://central:3001; overridable via env.
    private val baseUrl: String =
        (System.getenv("CENTRAL_PRIVATE_BASE_URL") ?: "http://central:3001").trimEnd('/')

    /**
     * Asynchronously asks central whether [player] has a paired island server and, if so, sends them
     * a clickable transfer prompt (on the main thread). Does nothing if they aren't paired or central
     * is unreachable.
     */
    fun promptIfPaired(player: Player) {
        val uuid = player.uniqueId
        scope.launch {
            val address = lookup(uuid) ?: return@launch
            plugin.server.scheduler.runTask(plugin, Runnable {
                if (player.isOnline) sendPrompt(player, address)
            })
        }
    }

    /** GETs /island/server for [uuid]; returns the `host:port` on 200, null on 404/error. */
    private suspend fun lookup(uuid: UUID): String? = try {
        val response = http.get("$baseUrl/island/server?player_uuid=$uuid")
        when (response.status.value) {
            200 -> JsonParser.parseString(response.bodyAsText()).asJsonObject.get("server_address").asString
            404 -> null
            else -> {
                plugin.logger.warning("Central returned HTTP ${response.status.value} looking up island server for $uuid.")
                null
            }
        }
    } catch (e: Exception) {
        plugin.logger.warning("Could not look up island server for $uuid: ${e.message}")
        null
    }

    private fun sendPrompt(player: Player, address: String) {
        // Click runs /joinisland, which mints a session token, attaches it as a transfer cookie, and
        // then transfers the player — rather than a bare /transfer that carries no identity.
        val link = Component.text("[Click here to join your island]", NamedTextColor.AQUA)
            .decorate(TextDecoration.BOLD)
            .clickEvent(ClickEvent.runCommand("/joinisland"))
            .hoverEvent(HoverEvent.showText(Component.text("Transfer to your island")))

        player.sendMessage(
            Component.text("Your island server is ready. ", NamedTextColor.GREEN).append(link)
        )
    }
}
