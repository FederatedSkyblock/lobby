package fr.overrride.minecraft.skyblockplugin.lobby

import fr.overrride.minecraft.skyblockplugin.sync.IslandStatsSyncManager
import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextDecoration
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.DisplaySlot
import java.util.UUID

/**
 * Sidebar scoreboard for the lobby server, showing the player's Vault balance and their
 * island stats (level, rank, bank balance) read from the shared `island_stats` table that
 * [IslandStatsSyncManager] is populated from on the skyblock server.
 */
class LobbyScoreboardManager(
    private val plugin: JavaPlugin,
    private val islandStatsSync: IslandStatsSyncManager
) : Listener {

    companion object {
        private const val OBJECTIVE_NAME = "lobby_sidebar"
        private const val UPDATE_PERIOD_TICKS = 40L // 2 seconds

        private val LINE_ENTRIES = listOf("§0", "§1", "§2", "§3", "§4")
    }

    private val activePlayers = mutableSetOf<UUID>()
    private var task: org.bukkit.scheduler.BukkitTask? = null

    /** Sets up the sidebar scoreboard for a player and starts the refresh task if needed. */
    fun setup(player: Player) {
        val scoreboard = Bukkit.getScoreboardManager()?.newScoreboard ?: return

        val objective = scoreboard.registerNewObjective(
            OBJECTIVE_NAME,
            "dummy",
            Component.text("SKYBLOCK", NamedTextColor.AQUA, TextDecoration.BOLD)
        )
        objective.displaySlot = DisplaySlot.SIDEBAR

        LINE_ENTRIES.forEachIndexed { index, entry ->
            val score = objective.getScore(entry)
            score.score = LINE_ENTRIES.size - index
            score.numberFormat(NumberFormat.blank())
        }

        player.scoreboard = scoreboard
        activePlayers.add(player.uniqueId)
        updatePlayer(player)
        ensureTaskRunning()
    }

    /** Removes a player from the refresh loop. */
    fun remove(player: Player) {
        activePlayers.remove(player.uniqueId)
        if (activePlayers.isEmpty()) {
            task?.cancel()
            task = null
        }
    }

    private fun ensureTaskRunning() {
        if (task != null) return
        task = plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            if (activePlayers.isEmpty()) {
                task?.cancel()
                task = null
                return@Runnable
            }
            val iterator = activePlayers.iterator()
            while (iterator.hasNext()) {
                val uuid = iterator.next()
                val player = Bukkit.getPlayer(uuid)
                if (player == null || !player.isOnline) {
                    iterator.remove()
                    continue
                }
                updatePlayer(player)
            }
        }, UPDATE_PERIOD_TICKS, UPDATE_PERIOD_TICKS)
    }

    /** Refreshes the sidebar lines for a single player. */
    private fun updatePlayer(player: Player) {
        val balance = fetchVaultBalance(player)

        islandStatsSync.fetchAsync(player.uniqueId) { stats ->
            Bukkit.getScheduler().runTask(plugin, Runnable {
                if (!player.isOnline) return@Runnable
                val scoreboard = player.scoreboard
                val objective = scoreboard.getObjective(OBJECTIVE_NAME) ?: return@Runnable

                val levelLine = Component.text("Island Level: ", NamedTextColor.GRAY)
                    .append(Component.text(stats?.level ?: 0L, NamedTextColor.GREEN))
                    .let {
                        val rank = stats?.rank ?: 0
                        if (rank > 0) {
                            it.append(Component.text(" (#$rank)", NamedTextColor.YELLOW))
                        } else {
                            it
                        }
                    }

                val lines = listOf(
                    levelLine,
                    Component.text("Bank: ", NamedTextColor.GRAY)
                        .append(Component.text("$" + "%.2f".format(stats?.bankBalance ?: 0.0), NamedTextColor.GOLD)),
                    Component.empty(),
                    Component.text("Balance: ", NamedTextColor.GRAY)
                        .append(Component.text("$" + "%.2f".format(balance), NamedTextColor.GOLD)),
                    Component.text("Online: ", NamedTextColor.GRAY)
                        .append(Component.text(Bukkit.getOnlinePlayers().size, NamedTextColor.GREEN))
                )

                for ((index, line) in lines.withIndex()) {
                    if (index >= LINE_ENTRIES.size) break
                    objective.getScore(LINE_ENTRIES[index]).customName(line)
                }
            })
        }
    }

    private fun fetchVaultBalance(player: Player): Double {
        val economy = Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider ?: return 0.0
        return try {
            economy.getBalance(player)
        } catch (e: Exception) {
            0.0
        }
    }
}

/** Listener that wires up the lobby scoreboard on join/quit. */
class LobbyScoreboardListener(private val manager: LobbyScoreboardManager) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        manager.setup(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        manager.remove(event.player)
    }
}
