package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The engine's only door to side-specific behaviour.
 *
 * <p>The dependency direction is one-way: {@code client} and {@code server}
 * depend on the engine, never the other way round. {@link HotAddEngine} used to
 * call {@code ServerCommands} directly, read {@code Minecraft.options} and reach
 * into {@code ClientConnections}; that made the engine unrunnable if either side
 * was absent and hid a real bug (on a client, hot-added commands were never
 * replayed because nobody had installed the command capture).
 *
 * <p>Implementations are installed once by {@code ClientBootstrap} /
 * {@code ServerBootstrap}, before anything can hot-add.
 */
public interface PlatformHooks {
    /** Client: the current key-mapping count. Server: {@code -1}. */
    int keyMappingCount();

    /** Client: replays {@code FMLClientSetupEvent} + {@code RegisterKeyMappingsEvent}. Server: no-op. */
    void acceptClientLifecycle(ModContainer container) throws Throwable;

    /**
     * Client: adds and selects the packs of the outer mod and its nested mods in
     * one reload. Server: logs that it is skipped.
     *
     * @return client: whether a resource reload was requested. A reload also runs
     *         {@code GameRenderer.reloadShaders}, which fires
     *         {@code RegisterShadersEvent} for every loaded container, so the
     *         caller does not need its own shader replay on that path
     */
    boolean injectResourcePacks(java.util.List<ModFileInfo> fileInfos, Set<String> modIds,
                                Rollback rollback);

    /**
     * Re-fires {@code RegisterCommandsEvent} so a hot-added mod's commands exist.
     * Both sides have a server: the dedicated one, or the integrated one.
     */
    void replayCommands();

    /** Connections only this side knows about: on a client, the link to the server. */
    List<Connection> extraConnections();

    /**
     * Called after registry content (guifei tier) was committed. The client rebuilds the
     * creative tabs so freshly added items show up without reopening the game;
     * a dedicated server has nothing to do.
     */
    void afterRegistryContent();

    /**
     * Client: startup-only registrations that need the whole add's containers, in
     * a fixed order (layer definitions for every container, then the renderer
     * rebuild). Called once after all containers are constructed. Server: no-op.
     */
    default void afterClientLifecycles(List<ModContainer> containers) {
    }

    /**
     * The add completed. The client queues shader replay until its resource reload
     * has finished (shader JSONs live in the new pack); the server does nothing.
     */
    default void afterHotAdd(List<ModContainer> containers) {
    }

    /**
     * Players reachable from this side's server. Used for anything that has to be
     * pushed to already-connected clients (registry snapshots, hot-add notices).
     *
     * <p>NeoForge only syncs registries in the login configuration phase, so a
     * registry change on a running server is invisible to connected players. The
     * side hook is only asked <em>who</em> to send to: a dedicated server returns
     * its player list, a client returns the integrated server's non-memory
     * connections (the host shares {@code BuiltInRegistries} with its own
     * integrated server, so it must be skipped — the same rule NeoForge applies at
     * login).
     */
    List<ServerPlayer> connectedPlayers();

    /**
     * A registry snapshot arrived from the server. The client applies it; a
     * dedicated server has no one to apply it to and logs that it was ignored.
     */
    void acceptRegistrySnapshot(Map<ResourceLocation, RegistrySnapshot> snapshots);

    /**
     * Asks the server for its authoritative id mapping. Client only, and only when
     * the client is <em>not</em> the server (single-player shares the registries,
     * so the local mapping is already authoritative there).
     */
    void requestRegistrySnapshot();

    /**
     * The server hot-added mods: turn the notice into something the player can act
     * on (chat + toast), and answer with the ids this client already has.
     */
    void acceptServerModNotice(List<ServerModNoticePayload.Notice> notices);

    /** A client answered a notice; the server logs who still misses what. */
    void acceptModNoticeAck(String playerName, List<String> knownModIds);

    /**
     * Applies the hot-added mod's server data pack (recipes, loot tables, tags)
     * with {@code /reload} semantics. Client: the integrated server, when in a
     * world. Server: the dedicated server. Returns {@code applied=false} when
     * there is no server yet (title screen — the client defers the pack to the
     * next world start) or the jar carries no {@code data/}.
     *
     * @throws IllegalStateException when the pack cannot be built or the reload
     *         fails (the registry commit has already happened by then)
     */
    DatapackStage.Result reloadDataPacks(IModFileInfo fileInfo, Set<String> modIds);

    static void install(PlatformHooks hooks) {
        if (hooks == null) {
            throw new IllegalArgumentException("hooks must not be null");
        }
        PlatformHooksHolder.instance = hooks;
        ModReloader.LOGGER.info("[ModReloader] platform hooks installed: {}",
                hooks.getClass().getName());
    }

    static PlatformHooks get() {
        PlatformHooks hooks = PlatformHooksHolder.instance;
        if (hooks == null) {
            // Loud on purpose: a silent fallback would skip key mappings, resource
            // packs and command replay while still reporting a successful add.
            throw new IllegalStateException(
                    "platform hooks are not installed; the side bootstrap never ran");
        }
        return hooks;
    }
}

final class PlatformHooksHolder {
    static PlatformHooks instance;

    private PlatformHooksHolder() {
    }
}
