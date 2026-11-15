package dev.modreloader.dev;

import com.mojang.blaze3d.platform.NativeImage;
import dev.modreloader.ModReloader;
import dev.modreloader.ui.LoadFailureScreen;
import dev.modreloader.ui.ModReloaderScreen;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Dev-only end-to-end check for the mods-folder watcher and prompt.
 *
 * <p>Copies a jar into {@code run/modreloader/inbox} after startup, waits for
 * the watcher, screenshots the prompt, asks the prompt to load, then verifies
 * the mod is loaded.
 */
public final class WatchCheck {
    private static final String MOD_ID = "easylan";
    private static final boolean EXPECT_FAILURE = Boolean.getBoolean("modreloader.watchExpectFailure");

    private static boolean started;
    private static boolean copied;
    private static boolean loaded;
    private static int ticks;
    private static int waitTicks;
    private static int promptTicks;
    private static Path inboxJar;

    private WatchCheck() {
    }

    public static void start(Path jar) {
        if (started) {
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[WatchCheck] will copy {} into the inbox after startup", jar);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick(jar));
    }

    private static void tick(Path jar) {
        if (!started) {
            return;
        }
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (!copied) {
            if (ticks < 120) {
                return;
            }
            try {
                Path inbox = ModReloader.WATCHER == null ? null : ModReloader.WATCHER.inboxDir();
                if (inbox == null) {
                    fail("watcher not started");
                    return;
                }
                Files.createDirectories(inbox);
                inboxJar = inbox.resolve("watchcheck-" + jar.getFileName());
                Files.deleteIfExists(inboxJar);
                Files.copy(jar, inboxJar, StandardCopyOption.REPLACE_EXISTING);
                copied = true;
                waitTicks = 0;
                ModReloader.LOGGER.info("[WatchCheck] copied to {}", inboxJar);
            } catch (Throwable t) {
                fail("copy failed: " + t);
            }
            return;
        }
        if (!loaded) {
            waitTicks++;
            var candidates = ModReloader.WATCHER == null ? java.util.List.<ModsFolderWatcher.Candidate>of()
                    : ModReloader.WATCHER.lastCandidates();
            if (candidates.isEmpty()) {
                if (waitTicks > 20 * 20) {
                    fail("watcher did not report a candidate");
                }
                return;
            }
            if (!(mc.screen instanceof ModReloaderScreen screen)) {
                if (waitTicks > 20 * 20) {
                    fail("prompt screen did not open; screen="
                            + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
                }
                return;
            }
            if (promptTicks++ < 10) {
                return;
            }
            ModReloader.LOGGER.info("[WatchCheck] prompt opened for {} ({} candidate(s))",
                    inboxJar.getFileName(), candidates.size());
            screenshot(mc, "watch-prompt.png");
            loaded = true;
            screen.loadFirstForTest();
            if (EXPECT_FAILURE) {
                waitTicks = 0;
            }
            return;
        }
        if (EXPECT_FAILURE) {
            if (mc.screen instanceof LoadFailureScreen) {
                if (promptTicks++ < 10) {
                    return;
                }
                screenshot(mc, "watch-failure.png");
                cleanup();
                ModReloader.LOGGER.info("[WatchCheck] failure screen opened; RESULT: PASS");
                started = false;
                mc.stop();
                return;
            }
            if (waitTicks > 20 * 20) {
                fail("failure screen did not open; screen="
                        + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
            }
            return;
        }
        if (waitTicks % 20 == 0) {
            ModReloader.LOGGER.info("[WatchCheck] waiting for load: isLoaded={}",
                    net.neoforged.fml.ModList.get().isLoaded(MOD_ID));
        }
        if (net.neoforged.fml.ModList.get().isLoaded(MOD_ID)) {
            cleanup();
            ModReloader.LOGGER.info("[WatchCheck] RESULT: PASS");
            started = false;
            mc.stop();
            return;
        }
        if (waitTicks > 20 * 30) {
            fail("mod was not loaded");
        }
    }

    private static void screenshot(Minecraft mc, String name) {
        try {
            Path dir = mc.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(dir);
            Path file = dir.resolve(name);
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                image.writeToFile(file);
            } finally {
                image.close();
            }
            ModReloader.LOGGER.info("[WatchCheck] screenshot: {}", file.toAbsolutePath());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[WatchCheck] screenshot failed", t);
        }
    }

    private static void cleanup() {
        if (inboxJar != null) {
            try {
                Files.deleteIfExists(inboxJar);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void fail(String reason) {
        ModReloader.LOGGER.error("[WatchCheck] FAILED: {}", reason);
        ModReloader.LOGGER.error("[WatchCheck] RESULT: FAIL");
        cleanup();
        started = false;
        Minecraft.getInstance().stop();
    }
}
