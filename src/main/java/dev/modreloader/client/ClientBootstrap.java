package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.command.ReloaderCommands;
import dev.modreloader.hotadd.MixinProbe;
import dev.modreloader.hotadd.PlatformHooks;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.server.ServerCommands;
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
    private static int probeTicks;
    private static boolean probeDone;

    private ClientBootstrap() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        // Relaunched process: wait for the old JVM before writing the instance dir.
        Relauncher.awaitPreviousProcess();
        PlatformHooks.install(new ClientPlatformHooks());
        // The integrated server fires RegisterCommandsEvent too, so capture it on
        // a client as well; otherwise a hot-added mod's commands never exist in
        // single-player (they only ever worked on a dedicated server).
        ServerCommands.install();
        NeoForge.EVENT_BUS.addListener(ClientCommands::register);
        MenuEntry.install();
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        installDevEntry();
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

    /**
     * Dev harnesses live in the {@code dev} source set, which is not part of the
     * production jar. Reached by name so the main source set cannot depend on it.
     */
    private static void installDevEntry() {
        try {
            Class.forName("dev.modreloader.dev.DevEntry")
                    .getMethod("install")
                    .invoke(null);
        } catch (ClassNotFoundException notShipped) {
            ModReloader.LOGGER.debug("[ModReloader] dev harnesses are not on the classpath");
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] cannot install dev harnesses", t);
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
