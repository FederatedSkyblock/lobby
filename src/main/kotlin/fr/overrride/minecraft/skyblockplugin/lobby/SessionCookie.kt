package fr.overrride.minecraft.skyblockplugin.lobby

import org.bukkit.NamespacedKey

/**
 * The transfer cookie under which the lobby stashes a player's island-session token before
 * transferring them to their island. Minecraft keeps cookies in the client across a server transfer
 * (1.20.5+), so the island server's watchdog reads this same key back on join.
 *
 * The namespace/key pair MUST stay identical to the watchdog's copy or the cookie won't be found.
 */
object SessionCookie {
    val KEY: NamespacedKey = NamespacedKey("federatedskyblock", "session")
}
