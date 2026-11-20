package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.command.ReloaderCommands;
import dev.modreloader.hotadd.LateAccessTransformers;
import dev.modreloader.hotadd.MixinProbe;
import dev.modreloader.hotadd.PlatformHooks;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.command.ServerCommands;
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
import java.util.Optional;
import dev.modreloader.launch.PendingAdd;
import dev.modreloader.launch.Relauncher;

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
    private static boolean pendingChecked;

    private ClientBootstrap() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        // Tell the old JVM we are alive *before* waiting for it (it waits for this
        // marker; on Windows it cannot read our command line).
        Relauncher.signalStarted();
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
        // A managed relaunch left the jar the user was retrying; after the title
        // screen is up again, reopen the prompt on it (once per session).
        if (!pendingChecked) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.level == null
                    && minecraft.screen instanceof TitleScreen) {
                pendingChecked = true;
                resumePending(minecraft);
            }
        }
        String probe = System.getProperty("modreloader.probe", "").trim();
        if (!probe.isEmpty() && !probeDone && ++probeTicks >= 100) {
            probeDone = true;
            MixinProbe.probe(Path.of(probe)).forEach(ModReloader.LOGGER::info);
        }
        // Shader JSONs arrive with the hot-add pack; wait for its reload.
        ClientRegistrationReplay.processPendingShaderReplay();
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

    /**
     * Re-open the prompt on the jar a relaunch was retrying.
     *
     * <p>The scan is done off-thread (it inspects every jar in the watched
     * folders), and the screen is only set if the client is still at the title
     * screen. The pending file is consumed once, so a crash cannot resurrect a
     * stale retry forever.
     */
    private static void resumePending(Minecraft minecraft) {
        Path gameDir = minecraft.gameDirectory.toPath();
        Optional<Path> pending = PendingAdd.read(gameDir);
        if (pending.isEmpty()) {
            return;
        }
        Thread thread = new Thread(() -> {
            Optional<Path> jar = PendingAdd.take(gameDir);
            if (jar.isEmpty()) {
                return;
            }
            ModsFolderWatcher watcher = ModReloader.WATCHER;
            List<ModsFolderWatcher.Candidate> candidates =
                    watcher == null ? List.of() : watcher.scanNow();
            int index = indexOf(candidates, jar.get());
            Platform.runOnMainThread(() -> {
                Minecraft client = Minecraft.getInstance();
                if (client == null || client.level != null
                        || !(client.screen instanceof TitleScreen)) {
                    return;
                }
                if (index < 0) {
                    // The jar is in mods/ and loaded at startup, or it vanished:
                    // the retry is not needed / not possible.
                    ModReloaderToasts.info(
                            Component.translatable("modreloader.toast.resume.loaded.title"),
                            Component.translatable("modreloader.toast.resume.loaded.message",
                                    jar.get().getFileName().toString()));
                    return;
                }
                ModReloaderToasts.info(
                        Component.translatable("modreloader.toast.resume.title"),
                        Component.translatable(LateAccessTransformers.enabled()
                                        ? "modreloader.toast.resume.late_at"
                                        : "modreloader.toast.resume.message",
                                jar.get().getFileName().toString()));
                client.setScreen(new ModReloaderScreen(candidates, jar.get()));
            });
        }, "modreloader-pending");
        thread.setDaemon(true);
        thread.start();
    }

    private static int indexOf(List<ModsFolderWatcher.Candidate> candidates, Path jar) {
        Path wanted = jar.toAbsolutePath().normalize();
        for (int i = 0; i < candidates.size(); i++) {
            if (candidates.get(i).path().toAbsolutePath().normalize().equals(wanted)) {
                return i;
            }
        }
        return -1;
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
