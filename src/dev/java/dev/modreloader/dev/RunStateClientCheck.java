package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.ui.ModReloaderScreen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Client half of the dirty-state check: runs {@link RunStateCheck#runChecks},
 * opens the watcher menu so the dirty badge is on screen, screenshots it and
 * exits. Server-safe code stays in {@link RunStateCheck}.
 */
public final class RunStateClientCheck {
    private static final int START_TICKS = 60;
    private static final int SCREENSHOT_TICKS = 10;

    private static Path jar;
    private static boolean started;
    private static boolean checksDone;
    private static boolean ok;
    private static int ticks;
    private static int waitTicks;

    private RunStateClientCheck() {
    }

    public static void startClient(Path jarPath) {
        if (started) {
            return;
        }
        started = true;
        jar = jarPath.toAbsolutePath().normalize();
        ModReloader.LOGGER.info("[RunStateCheck] armed with {} (client)", jar);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        if (checksDone) {
            if (++waitTicks < SCREENSHOT_TICKS) {
                return;
            }
            captureMenu();
            ModReloader.LOGGER.info("[RunStateCheck] RESULT: {}", ok ? "PASS" : "FAIL");
            started = false;
            Minecraft.getInstance().stop();
            return;
        }
        if (++ticks < START_TICKS) {
            return;
        }
        ok = RunStateCheck.runChecks(jar);
        checksDone = true;
        Minecraft.getInstance().setScreen(new ModReloaderScreen(List.of()));
        ModReloader.LOGGER.info("[RunStateCheck] menu opened; screenshot in {} ticks",
                SCREENSHOT_TICKS);
    }

    private static void captureMenu() {
        Minecraft mc = Minecraft.getInstance();
        Path file = mc.gameDirectory.toPath().resolve("screenshots").resolve("runstate-dirty.png");
        try {
            Files.createDirectories(file.getParent());
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                image.writeToFile(file);
            } finally {
                image.close();
            }
            ModReloader.LOGGER.info("[RunStateCheck] screenshot: {}", file.toAbsolutePath());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[RunStateCheck] screenshot failed", t);
        }
    }
}