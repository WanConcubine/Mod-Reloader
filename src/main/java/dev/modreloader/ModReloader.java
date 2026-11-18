package dev.modreloader;

import com.mojang.logging.LogUtils;
import dev.modreloader.capability.Capabilities;
import dev.modreloader.client.ClientBootstrap;
import dev.modreloader.hotadd.ModNotice;
import dev.modreloader.hotadd.RegistrySync;
import dev.modreloader.inspect.JbrSupport;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.server.ServerBootstrap;
import dev.modreloader.watch.ModWatcherConfig;
import dev.modreloader.watch.ModsFolderWatcher;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Mod Reloader — runtime hot-add for NeoForge mods, on both sides.
 *
 * <p>This class and the hot-add engine reference no client types, so a dedicated
 * server can load them. Client-only behavior (UI, toasts, resource packs, key
 * mappings, dev checks) lives in {@code dev.modreloader.client}; server-only
 * wiring (console commands, start-up triggers) lives in
 * {@code dev.modreloader.server}.
 *
 * <p>Scope: load a mod from disk into a running game, register its mixins (JBR),
 * rebuild {@code ModList}, construct it, deliver lifecycle events and — on the
 * client — inject its resource pack. Registry content still requires a restart
 * (ADR-0006).
 */
@Mod(ModReloader.MOD_ID)
public final class ModReloader {
    public static final String MOD_ID = "modreloader";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static ModWatcherConfig WATCHER_CONFIG;
    public static ModsFolderWatcher WATCHER;

    public ModReloader(IEventBus modBus) {
        WATCHER_CONFIG = ModWatcherConfig.load();
        // Our own play channel: the only way to hand a freshly hot-added
        // registry to clients that are already connected (NeoForge only syncs
        // registries during the login configuration phase).
        modBus.addListener(RegistrySync::register);
        // Hot-add notices: always tell connected players what changed and what to do.
        modBus.addListener(ModNotice::register);
        if (Platform.isClient()) {
            ClientBootstrap.install();
        } else {
            ServerBootstrap.install();
        }
        // Players often unpack JBR next to .minecraft; include the game dir and
        // its parent in the install scan (recomputed on every lookup).
        JbrSupport.includeDirectory(FMLPaths.GAMEDIR.get());
        String userDir = System.getProperty("user.dir", "");
        if (!userDir.isBlank()) {
            JbrSupport.includeDirectory(Path.of(userDir));
        }
        LOGGER.info("[ModReloader] game directory = {} (JBR detection roots: game dir + 3 ancestors)",
                FMLPaths.GAMEDIR.get());
        Capabilities.probe();
        logRuntime();
    }

    /**
     * Starts the mods/inbox watcher exactly once. The side bootstrap supplies the
     * callback so this class never touches UI code.
     */
    public static synchronized void startWatcher(Consumer<List<ModsFolderWatcher.Candidate>> listener) {
        if (WATCHER != null) {
            return;
        }
        WATCHER = new ModsFolderWatcher(WATCHER_CONFIG, listener);
        WATCHER.start();
        logMixinRuntime();
    }

    public static void logMixinRuntime() {
        if (MixinRuntimeSupport.available()) {
            LOGGER.info("[ModReloader] {} detected; fei-tier (mixin) hot-add enabled",
                    MixinRuntimeSupport.name());
        } else {
            LOGGER.warn("[ModReloader] {} is not installed: pin-tier hot-add works, fei-tier (mixins) is "
                            + "blocked. Install it into mods/ (version >= 1.0.2) and restart.",
                    MixinRuntimeSupport.name());
        }
    }

    private static void logRuntime() {
        boolean enhanced = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(arg -> arg.contains("AllowEnhancedClassRedefinition"));
        LOGGER.info("[ModReloader] loaded on {}; JVM {} {}; enhancedRedefinition={}",
                Platform.side(), System.getProperty("java.vendor"),
                System.getProperty("java.version"), enhanced);
    }
}
