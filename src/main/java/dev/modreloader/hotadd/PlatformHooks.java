package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.network.Connection;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import java.util.List;
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

    /** Client: adds and selects the mod's resource packs. Server: logs that it is skipped. */
    void injectResourcePack(ModFile modFile, ModFileInfo fileInfo, Set<String> modIds, Rollback rollback);

    /**
     * Re-fires {@code RegisterCommandsEvent} so a hot-added mod's commands exist.
     * Both sides have a server: the dedicated one, or the integrated one.
     */
    void replayCommands();

    /** Connections only this side knows about: on a client, the link to the server. */
    List<Connection> extraConnections();

    /**
     * Called after registry content was committed (T4). The client rebuilds the
     * creative tabs so freshly added items show up without reopening the game;
     * a dedicated server has nothing to do.
     */
    void afterRegistryContent();

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
