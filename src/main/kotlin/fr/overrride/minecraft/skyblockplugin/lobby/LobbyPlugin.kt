package fr.overrride.minecraft.skyblockplugin.lobby

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import fr.overrride.minecraft.skyblockplugin.common.CommonSetup
import fr.overrride.minecraft.skyblockplugin.sync.IslandStatsSyncManager
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.GameRule
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * The lobby-side plugin. Everything shared with the island server is wired by
 * [CommonSetup.enable] (cross-server sync, sell stats, hopper-disable, keep-inventory); this class
 * adds only the lobby-specific behaviour: the `/is` redirect to the skyblock server, a balance/island
 * scoreboard, the admin display commands, a midday time-lock, and block protection.
 */
class LobbyPlugin : JavaPlugin(), Listener {

    override fun onEnable() {
        val common = CommonSetup.enable(this)

        server.pluginManager.registerEvents(this, this)

        // Lobby is a static map: lock time to midday on every world.
        server.worlds.forEach { world ->
            world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false)
            world.time = 6000L
        }

        // /is and /island (with any subcommand, e.g. "/is upgrade") send the player to the skyblock
        // server, where the real BSkyBlock command (shared-database-backed) handles it. Registered as
        // real Brigadier commands so they appear in the client command tree and tab-complete. The
        // save-before-switch handshake lives in IslandRedirectCommand (uses the shared PlayerSync).
        val islandRedirectCmd = IslandRedirectCommand(this, common.playerSync)

        // Sidebar scoreboard with the player's balance and synced island stats.
        val islandStatsSyncManager = IslandStatsSyncManager(this)
        val lobbyScoreboardManager = LobbyScoreboardManager(this, islandStatsSyncManager)
        server.pluginManager.registerEvents(LobbyScoreboardListener(lobbyScoreboardManager), this)

        val displayCmd = SpawnDisplayCommand()
        val removeDisplayCmd = RemoveDisplayCommand()

        @Suppress("UnstableApiUsage")
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            for (alias in listOf("is", "island")) {
                event.registrar().register(
                    Commands.literal(alias)
                        .executes { ctx ->
                            (ctx.source.sender as? Player)?.let { islandRedirectCmd.execute(it) }
                            com.mojang.brigadier.Command.SINGLE_SUCCESS
                        }
                        .then(
                            Commands.argument("args", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    (ctx.source.sender as? Player)?.let { islandRedirectCmd.execute(it) }
                                    com.mojang.brigadier.Command.SINGLE_SUCCESS
                                }
                        )
                        .build(),
                    "Go to your island on the skyblock server"
                )
            }

            event.registrar().register(
                Commands.literal("spawndisplay")
                    .requires { it.sender.hasPermission("skyblock.admin") }
                    .executes { ctx ->
                        (ctx.source.sender as? Player)?.let { displayCmd.execute(it, 0.5f, 0.5f * 1.45f) }
                            ?: com.mojang.brigadier.Command.SINGLE_SUCCESS
                    }
                    .then(
                        Commands.argument("itemScale", FloatArgumentType.floatArg(0.01f, 16f))
                            .executes { ctx ->
                                val item = FloatArgumentType.getFloat(ctx, "itemScale")
                                (ctx.source.sender as? Player)?.let { displayCmd.execute(it, item, item * 1.45f) }
                                    ?: com.mojang.brigadier.Command.SINGLE_SUCCESS
                            }
                            .then(
                                Commands.argument("glassScale", FloatArgumentType.floatArg(0.01f, 16f))
                                    .executes { ctx ->
                                        val item  = FloatArgumentType.getFloat(ctx, "itemScale")
                                        val glass = FloatArgumentType.getFloat(ctx, "glassScale")
                                        (ctx.source.sender as? Player)?.let { displayCmd.execute(it, item, glass) }
                                            ?: com.mojang.brigadier.Command.SINGLE_SUCCESS
                                    }
                            )
                    )
                    .build(),
                "Spawn a glass-block and hand-item display at your location"
            )

            event.registrar().register(
                Commands.literal("removedisplay")
                    .requires { it.sender.hasPermission("skyblock.admin") }
                    .executes { ctx ->
                        (ctx.source.sender as? Player)?.let { removeDisplayCmd.execute(it) }
                            ?: com.mojang.brigadier.Command.SINGLE_SUCCESS
                    }
                    .build(),
                "Remove the nearest display entity"
            )
        }
    }

    // --- Lobby block protection: non-ops can't break/place/interact in the lobby world -----------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.clickedBlock?.world?.name == "lobby" && !event.player.isOp) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.block.world.name == "lobby" && !event.player.isOp) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (event.block.world.name == "lobby" && !event.player.isOp) {
            event.isCancelled = true
        }
    }
}
