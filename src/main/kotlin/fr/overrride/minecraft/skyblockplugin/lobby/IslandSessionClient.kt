package fr.overrride.minecraft.skyblockplugin.lobby

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/**
 * Talks to central's PRIVATE API for player island sessions. Both endpoints live on the private
 * surface (internal network only), reached via CENTRAL_PRIVATE_BASE_URL (default http://central:3001):
 *  - `POST /island/session`            — mint a session token + the island address to transfer to.
 *  - `POST /island/session/invalidate` — revoke a player's existing session tokens.
 *
 * All calls are suspending (run on the caller's coroutine scope); the HTTP client is shared.
 */
class IslandSessionClient(
    private val plugin: JavaPlugin,
    private val http: HttpClient,
) {

    private val baseUrl: String =
        (System.getenv("CENTRAL_PRIVATE_BASE_URL") ?: "http://central:3001").trimEnd('/')

    /** A freshly minted session: the token to put in the cookie and where to transfer the player. */
    data class Session(val token: String, val serverAddress: String)

    /**
     * Mints a session token for [uuid]'s paired island. Returns the token + address on 200, or null
     * if the player has no paired island (404) or central is unreachable.
     */
    suspend fun mintSession(uuid: UUID): Session? = try {
        val response = http.post("$baseUrl/island/session") {
            contentType(ContentType.Application.Json)
            setBody(JsonObject().apply { addProperty("player_uuid", uuid.toString()) }.toString())
        }
        when (response.status.value) {
            200 -> {
                val json = JsonParser.parseString(response.bodyAsText()).asJsonObject
                Session(json.get("token").asString, json.get("server_address").asString)
            }
            404 -> null
            else -> {
                plugin.logger.warning("Central returned HTTP ${response.status.value} minting a session for $uuid.")
                null
            }
        }
    } catch (e: Exception) {
        plugin.logger.warning("Could not mint island session for $uuid: ${e.message}")
        null
    }

    /**
     * Asks central to invalidate every session token previously minted for [uuid]. Best-effort: a
     * failure is logged but not surfaced (the caller — a join handler — can't do anything about it).
     */
    suspend fun invalidateSessions(uuid: UUID) {
        try {
            val response = http.post("$baseUrl/island/session/invalidate") {
                contentType(ContentType.Application.Json)
                setBody(JsonObject().apply { addProperty("player_uuid", uuid.toString()) }.toString())
            }
            if (response.status.value != 200) {
                plugin.logger.warning("Central returned HTTP ${response.status.value} invalidating sessions for $uuid.")
            }
        } catch (e: Exception) {
            plugin.logger.warning("Could not invalidate island sessions for $uuid: ${e.message}")
        }
    }
}
