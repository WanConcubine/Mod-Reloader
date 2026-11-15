package dev.modreloader;

import com.mojang.logging.LogUtils;
import dev.modreloader.command.ReloaderCommands;
import dev.modreloader.dev.LanScreenCheck;
import dev.modreloader.dev.RollbackCheck;
import dev.modreloader.dev.WatchCheck;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.MixinProbe;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.ui.ModReloaderScreen;
import dev.modreloader.ui.ModReloaderToasts;
import dev.modreloader.watch.ModWatcherConfig;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.List;

/**
 * Mod Reloader — runtime hot-add for NeoForge client mods.
 *
 * <p>Scope: load a client mod from disk into a running client, register its
 * mixins (JBR), rebuild {@code ModList}, construct it, deliver client lifecycle
 * events and inject its resource pack. Registry content is still unsupported.
 */
@Mod(value = ModReloader.MOD_ID, dist = Dist.CLIENT)
public final class ModReloader {
    public static final String MOD_ID = "modreloader";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static ModWatcherConfig WATCHER_CONFIG;
    public static ModsFolderWatcher WATCHER;

    private static int watcherStartTicks;
    private static boolean watcherStarted;
    private static int autoAddTicks;
    private static boolean autoAddDone;
    private static int probeTicks;
    private static boolean probeDone;

    public ModReloader(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(ReloaderCommands::register);
        WATCHER_CONFIG = ModWatcherConfig.load();
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
            if (watcherStarted) {
                return;
            }
            if (++watcherStartTicks < 80) {
                return;
            }
            watcherStarted = true;
            WATCHER = new ModsFolderWatcher(WATCHER_CONFIG, ModReloader::onCandidates);
            WATCHER.start();
            notifyMixinRuntime();
        });

        String autoAdd = System.getProperty("modreloader.autoAdd", "").trim();
        boolean verifyLan = Boolean.parseBoolean(System.getProperty("modreloader.verifyLan", "false"));
        boolean verifyRollback = Boolean.parseBoolean(
                System.getProperty("modreloader.verifyRollback", "false"));
        if (!autoAdd.isEmpty()) {
            Path jar = Path.of(autoAdd);
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
                if (autoAddDone) {
                    return;
                }
                if (++autoAddTicks < 100) {
                    return;
                }
                autoAddDone = true;
                HotAddEngine.Result result = HotAddEngine.add(jar);
                LOGGER.info("[ModReloader] autoAdd result: {}", result.message());
                if (verifyRollback) {
                    RollbackCheck.start(jar, result);
                } else if (verifyLan && result.ok()) {
                    LanScreenCheck.start();
                }
            });
        } else if (verifyLan || verifyRollback) {
            LOGGER.warn("[ModReloader] verifyLan/verifyRollback need autoAdd; not starting the check");
        }

        boolean enhancedRedefinition = java.lang.management.ManagementFactory.getRuntimeMXBean()
                .getInputArguments().stream()
                .anyMatch(arg -> arg.contains("AllowEnhancedClassRedefinition"));
        LOGGER.info("[ModReloader] JVM {} {}; enhancedRedefinition={}",
                System.getProperty("java.vendor"), System.getProperty("java.version"),
                enhancedRedefinition);
        boolean verifyWatch = Boolean.parseBoolean(
                System.getProperty("modreloader.verifyWatch", "false"));
        String watchJar = System.getProperty("modreloader.watchJar", "").trim();
        if (verifyWatch) {
            if (watchJar.isEmpty()) {
                LOGGER.warn("[ModReloader] verifyWatch needs watchJar; not starting");
            } else {
                WatchCheck.start(Path.of(watchJar));
            }
        }

        String probe = System.getProperty("modreloader.probe", "").trim();
        if (!probe.isEmpty()) {
            Path jar = Path.of(probe);
            NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> {
                if (probeDone) {
                    return;
                }
                if (++probeTicks < 100) {
                    return;
                }
                probeDone = true;
                MixinProbe.probe(jar).forEach(LOGGER::info);
            });
        }

        LOGGER.info("[ModReloader] loaded; hot-add engine ready (module -> mixin -> ModList -> events -> packs)");
    }

    /**
     * Speaks about the optional MixinRuntime dependency in our own words.
     *
     * <p>It stays optional on purpose: with {@code type = "required"} FML aborts
     * the whole launch and prints the bare modId {@code mr}, so the only text a
     * user would ever see is the one nobody can interpret. Loading anyway lets
     * us say the product name, what still works, and what to install.
     */
    private static void notifyMixinRuntime() {
        if (MixinRuntimeSupport.available()) {
            LOGGER.info("[ModReloader] {} detected; T3 (mixin) hot-add enabled",
                    MixinRuntimeSupport.name());
            return;
        }
        LOGGER.warn("[ModReloader] {} is not installed: T2 hot-add works, T3 (mixins) is "
                        + "blocked. Install it into mods/ (version >= 1.0.2) and restart.",
                MixinRuntimeSupport.name());
        ModReloaderToasts.info(
                Component.translatable("modreloader.toast.mixinruntime.title"),
                Component.translatable("modreloader.toast.mixinruntime.message",
                        MixinRuntimeSupport.name()));
    }

    private static void onCandidates(List<ModsFolderWatcher.Candidate> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        if (WATCHER_CONFIG.toastOnDetect) {
            ModReloaderToasts.detected(candidates);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        boolean mainMenu = minecraft.level == null
                && (minecraft.screen == null || minecraft.screen instanceof TitleScreen);
        if (WATCHER_CONFIG.openPromptInMainMenu && mainMenu) {
            minecraft.setScreen(new ModReloaderScreen(candidates));
        } else if (minecraft.level != null) {
            LOGGER.info("[ModReloader] new mod(s) detected in world; toast only (run /modreloader gui to open)");
        }
    }
}
