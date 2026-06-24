package fr.overrride.minecraft.skyblockplugin.lobby

import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import fr.overrride.minecraft.skyblockplugin.common.CommonSetup
import fr.overrride.minecraft.skyblockplugin.sync.IslandStatsSyncManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.GameRule
import org.bukkit.GameRules
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

/**
 * The lobby-side plugin. Everything shared with the island server is wired by
 * [CommonSetup.enable] (cross-server sync, sell stats, hopper-disable, keep-inventory); this class
 * adds only the lobby-specific behaviour: the `/is` redirect to the skyblock server, a balance/island
 * scoreboard, the admin display commands, a midday time-lock, and block protection.
 */
class LobbyPlugin : JavaPlugin(), Listener {

    /** Shared Ktor coroutine client + scope for central API calls (e.g. /pair). */
    private val http: HttpClient = HttpClient(CIO)
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Surfaces a one-click transfer to a player's paired island server. */
    private val islandServerLocator: IslandServerLocator by lazy { IslandServerLocator(this, scope, http) }

    /** Central private-API client for minting/invalidating player island-session tokens. */
    private val islandSessions: IslandSessionClient by lazy { IslandSessionClient(this, http) }

    /** Mints a session token, attaches it as a transfer cookie, and transfers the player. */
    private val islandTransfer: IslandTransfer by lazy { IslandTransfer(this, scope, islandSessions) }

    override fun onEnable() {
        val common = CommonSetup.enable(this)

        server.pluginManager.registerEvents(this, this)

        // Lobby is a static map: lock time to midday on every world.
        server.worlds.forEach { world ->
            world.setGameRule(GameRules.ADVANCE_TIME, false)
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
        val transferCmd = TransferCommand()
        val pairCmd = PairCommand(this, scope, http, islandServerLocator)

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

            // /pair <code> — claim the pairing code shown in the player's island-server console.
            event.registrar().register(
                Commands.literal("pair")
                    .then(
                        Commands.argument("code", StringArgumentType.string())
                            .executes { ctx ->
                                val code = StringArgumentType.getString(ctx, "code")
                                (ctx.source.sender as? Player)?.let { pairCmd.execute(it, code) }
                                    ?: com.mojang.brigadier.Command.SINGLE_SUCCESS
                            }
                    )
                    .executes { ctx ->
                        (ctx.source.sender as? Player)?.sendMessage(
                            net.kyori.adventure.text.Component.text(
                                "Usage: /pair <code>",
                                net.kyori.adventure.text.format.NamedTextColor.RED
                            )
                        )
                        com.mojang.brigadier.Command.SINGLE_SUCCESS
                    }
                    .build(),
                "Pair your island server with your account"
            )

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
                                        val item = FloatArgumentType.getFloat(ctx, "itemScale")
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

            // /joinisland — mint a session token, stash it in a transfer cookie, and transfer the
            // player to their paired island. Triggered by the locator's "click to join" prompt.
            event.registrar().register(
                Commands.literal("joinisland")
                    .executes { ctx ->
                        (ctx.source.sender as? Player)?.let { islandTransfer.transferToIsland(it) }
                        com.mojang.brigadier.Command.SINGLE_SUCCESS
                    }
                    .build(),
                "Transfer to your paired island"
            )

            // /transfer <ip>[:<port>] — send the player to an external server via the native
            // transfer packet. The address is a single token (host or host:port, no spaces).
            event.registrar().register(
                Commands.literal("transfer")
                    .then(
                        Commands.argument("address", StringArgumentType.string())
                            .executes { ctx ->
                                val address = StringArgumentType.getString(ctx, "address")
                                (ctx.source.sender as? Player)?.let { transferCmd.execute(it, address) }
                                    ?: com.mojang.brigadier.Command.SINGLE_SUCCESS
                            }
                    )
                    .executes { ctx ->
                        (ctx.source.sender as? Player)?.sendMessage(
                            net.kyori.adventure.text.Component.text(
                                "Usage: /transfer <ip>[:<port>]",
                                net.kyori.adventure.text.format.NamedTextColor.RED
                            )
                        )
                        com.mojang.brigadier.Command.SINGLE_SUCCESS
                    }
                    .build(),
                "Transfer to an external server (ip or ip:port)"
            )
        }
    }

    override fun onDisable() {
        scope.cancel()
        http.close()
    }

    // On join, invalidate any island-session tokens the player still holds — returning to the lobby
    // must void any session that was active on an island server — then offer a one-click transfer
    // back to their paired island (which mints a fresh token).
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val uuid = event.player.uniqueId
        scope.launch { islandSessions.invalidateSessions(uuid) }
        islandServerLocator.promptIfPaired(event.player)
    }

    // Drop any chat message mentioning "/pair" so players can't leak a pairing code in public chat
    // (it's claimed via the command, never typed into chat).
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onChatPairLeak(event: AsyncChatEvent) {
        val text = PlainTextComponentSerializer.plainText().serialize(event.message())
        if (text.contains("/pair", ignoreCase = true)) {
            event.isCancelled = true
            event.player.sendMessage(
                Component.text(
                    "Your message was blocked: never share your pairing code in chat. Use /pair <code> directly.",
                    NamedTextColor.RED,
                )
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
