package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.command.ReloaderCommands;
import dev.modreloader.dev.AddCommandCheck;
import dev.modreloader.dev.DeleteCheck;
import dev.modreloader.dev.LanScreenCheck;
import dev.modreloader.dev.MenuEntryCheck;
import dev.modreloader.dev.RollbackCheck;
import dev.modreloader.dev.WatchCheck;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.MixinProbe;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.ui.ModReloaderScreen;
import dev.modreloader.ui.ModReloaderToasts;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.List;

/**
 * Client-only wiring: the tick loop that starts the watcher, desktop toasts, the
 * candidate prompt screen, client commands and the dev-only verification checks.
 */
public final class ClientBootstrap {
    private static boolean installed;
    private static int watcherTicks;
    private static boolean watcherStarted;
    private static int autoAddTicks;
    private static boolean autoAddDone;
    private static int probeTicks;
    private static boolean probeDone;

    private ClientBootstrap() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(ReloaderCommands::registerClient);
        MenuEntry.install();
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        installAutoAdd();
        installDevChecks();
    }

    private static void tick() {
        if (!watcherStarted) {
            if (++watcherTicks < 80) {
                return;
            }
            watcherStarted = true;
            ModReloader.startWatcher(ClientBootstrap::onCandidates);
            if (!MixinRuntimeSupport.available()) {
                ModReloaderToasts.info(
                        Component.translatable("modreloader.toast.mixinruntime.title"),
                        Component.translatable("modreloader.toast.mixinruntime.message",
                                MixinRuntimeSupport.name()));
            }
        }
        String probe = System.getProperty("modreloader.probe", "").trim();
        if (!probe.isEmpty() && !probeDone && ++probeTicks >= 100) {
            probeDone = true;
            MixinProbe.probe(Path.of(probe)).forEach(ModReloader.LOGGER::info);
        }
    }

    private static void installAutoAdd() {
        String autoAdd = System.getProperty("modreloader.autoAdd", "").trim();
        boolean verifyLan = Boolean.getBoolean("modreloader.verifyLan");
        boolean verifyRollback = Boolean.getBoolean("modreloader.verifyRollback");
        if (autoAdd.isEmpty()) {
            if (verifyLan || verifyRollback) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] verifyLan/verifyRollback need autoAdd; not starting the check");
            }
            return;
        }
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
            ModReloader.LOGGER.info("[ModReloader] autoAdd result: {}", result.message());
            if (verifyRollback) {
                RollbackCheck.start(jar, result);
            } else if (verifyLan && result.ok()) {
                LanScreenCheck.start();
            }
        });
    }

    private static void installDevChecks() {
        if (Boolean.getBoolean("modreloader.verifyMenu")) {
            MenuEntryCheck.start();
        }
        if (Boolean.getBoolean("modreloader.verifyDelete")) {
            DeleteCheck.start();
        }
        String verifyAddCommand = System.getProperty("modreloader.verifyAddCommand", "").trim();
        if (!verifyAddCommand.isEmpty()) {
            AddCommandCheck.start(Path.of(verifyAddCommand));
        }
        String watchJar = System.getProperty("modreloader.watchJar", "").trim();
        if (Boolean.getBoolean("modreloader.verifyWatch")) {
            if (watchJar.isEmpty()) {
                ModReloader.LOGGER.warn("[ModReloader] verifyWatch needs watchJar; not starting");
            } else {
                WatchCheck.start(Path.of(watchJar));
            }
        }
    }

    /** Detection callback: in world → toast only; at the title screen → the prompt. */
    private static void onCandidates(List<ModsFolderWatcher.Candidate> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        if (ModReloader.WATCHER_CONFIG.toastOnDetect) {
            ModReloaderToasts.detected(candidates);
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        boolean mainMenu = minecraft.level == null
                && (minecraft.screen == null || minecraft.screen instanceof TitleScreen);
        if (ModReloader.WATCHER_CONFIG.openPromptInMainMenu && mainMenu) {
            minecraft.setScreen(new ModReloaderScreen(candidates));
        } else if (minecraft.level != null) {
            ModReloader.LOGGER.info(
                    "[ModReloader] new mod(s) detected in world; toast only (run /modreloader gui to open)");
        }
    }
}
