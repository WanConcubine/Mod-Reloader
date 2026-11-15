package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.client.LogoButton;
import dev.modreloader.ui.ModReloaderScreen;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only check for the persistent menu entry ({@code -PverifyMenu=true}).
 *
 * <p>Asserts that the title screen gets our button, that pressing it opens the
 * pending-mods screen, and that the screen exposes the ignored-list toggle (so an
 * ignored jar stays reachable).
 */
public final class MenuEntryCheck {
    private enum Stage { WAIT_TITLE, PRESS, VERIFY, SHOT, TOGGLE, RESTORE, DONE }

    private static final int TIMEOUT_TICKS = 20 * 120;

    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static boolean started;
    private static boolean failed;
    private static Path pendingJar;

    private MenuEntryCheck() {
    }

    public static void start() {
        if (started) {
            return;
        }
        started = true;
        seedIgnored();
        ModReloader.LOGGER.info("[MenuCheck] started; waiting for the title screen");
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        if (ticks > TIMEOUT_TICKS) {
            fail("timeout at stage " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WAIT_TITLE -> waitTitle(mc);
            case PRESS -> press(mc);
            case VERIFY -> verify(mc);
            case SHOT -> shot(mc);
            case TOGGLE -> toggle(mc);
            case RESTORE -> restore(mc);
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void waitTitle(Minecraft mc) {
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) {
            return;
        }
        // The watcher starts ~80 ticks in; the pending list needs it.
        if (ModReloader.WATCHER == null) {
            return;
        }
        LogoButton button = findEntryButton(mc.screen);
        if (button == null) {
            fail("title screen has no logo button; buttons: " + describe(mc.screen));
            return;
        }
        boolean topRight = button.getX() > mc.screen.width / 2 && button.getY() <= 40;
        ModReloader.LOGGER.info("[MenuCheck] logo button at ({},{}) size {}x{}; topRight={}; tooltip='{}'",
                button.getX(), button.getY(), button.getWidth(), button.getHeight(), topRight,
                button.getMessage().getString());
        if (!topRight) {
            fail("logo button is not in the top-right corner; at (" + button.getX() + ","
                    + button.getY() + ")");
            return;
        }
        screenshot(mc);
        stage = Stage.PRESS;
        ticks = 0;
    }

    private static void screenshot(Minecraft mc) {
        screenshot(mc, "menu-logo-entry.png");
    }

    private static void screenshot(Minecraft mc, String name) {
        var file = mc.gameDirectory.toPath().resolve("screenshots").resolve(name);
        try {
            java.nio.file.Files.createDirectories(file.getParent());
            NativeImage image = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            try {
                image.writeToFile(file);
            } finally {
                image.close();
            }
            ModReloader.LOGGER.info("[MenuCheck] screenshot: {}", file.toAbsolutePath());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MenuCheck] screenshot failed", t);
        }
    }

    private static void press(Minecraft mc) {
        seedPendingJar();
        AbstractButton button = findEntryButton(mc.screen);
        if (button == null) {
            fail("menu entry disappeared before it could be pressed");
            return;
        }
        button.onPress();
        stage = Stage.VERIFY;
        ticks = 0;
    }

    private static void verify(Minecraft mc) {
        if (!(mc.screen instanceof ModReloaderScreen screen)) {
            fail("pressing the entry did not open ModReloaderScreen; screen="
                    + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
            return;
        }
        List<String> labels = describeButtons(screen);
        boolean showsPending = pendingJar != null && labels.stream()
                .anyMatch(label -> label.contains(pendingJar.getFileName().toString()));
        if (!showsPending) {
            fail("pending list does not show the unloaded jar " + pendingJar + "; buttons=" + labels);
            return;
        }
        int ignored = ModReloader.WATCHER_CONFIG == null ? 0 : ModReloader.WATCHER_CONFIG.ignoredCount();
        String expectedToggle = I18n.get("modreloader.gui.ignored_list", ignored);
        ModReloader.LOGGER.info("[MenuCheck] pending screen opened; showingIgnored={}, buttons={}, "
                + "expectedToggle='{}'", screen.isShowingIgnored(), labels, expectedToggle);
        if (!labels.contains(expectedToggle)) {
            fail("no ignored-list toggle '" + expectedToggle + "' on the pending screen; buttons=" + labels);
            return;
        }
        stage = Stage.SHOT;
        ticks = 0;
    }

    /** Lets the pending screen render for a frame before capturing it. */
    private static void shot(Minecraft mc) {
        if (ticks < 4) {
            return;
        }
        screenshot(mc, "pending-screen.png");
        int ignored = ModReloader.WATCHER_CONFIG == null ? 0 : ModReloader.WATCHER_CONFIG.ignoredCount();
        String expectedToggle = I18n.get("modreloader.gui.ignored_list", ignored);
        AbstractButton toggle = findButton(mc.screen, expectedToggle);
        if (toggle == null) {
            fail("toggle button not found by label after the screenshot");
            return;
        }
        toggle.onPress();
        stage = Stage.TOGGLE;
        ticks = 0;
    }

    private static void toggle(Minecraft mc) {
        if (!(mc.screen instanceof ModReloaderScreen screen)) {
            fail("screen changed after pressing the toggle");
            return;
        }
        if (!screen.isShowingIgnored()) {
            fail("pressing the toggle did not switch to the ignored view; buttons="
                    + describeButtons(screen));
            return;
        }
        ModReloader.LOGGER.info("[MenuCheck] ignored view opened; buttons={}", describeButtons(screen));
        stage = Stage.RESTORE;
        ticks = 0;
    }

    /**
     * Seeds two samples: an ignored fingerprint (to exercise Restore) and an
     * unloaded jar in the inbox (to prove a deferred/present jar is still listed).
     */
    private static void seedIgnored() {
        try {
            Path sample = Path.of("/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar");
            if (!Files.isRegularFile(sample) || ModReloader.WATCHER_CONFIG == null) {
                ModReloader.LOGGER.warn("[MenuCheck] no sample jar; the Restore path will not be tested");
                return;
            }

            ModReloader.WATCHER_CONFIG.ignore(sample, Files.size(sample),
                    Files.getLastModifiedTime(sample).toMillis());
            ModReloader.LOGGER.info("[MenuCheck] seeded an ignored entry for {}; ignoredCount={}",
                    sample.getFileName(), ModReloader.WATCHER_CONFIG.ignoredCount());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MenuCheck] cannot seed an ignored entry", t);
        }
    }

    private static void restore(Minecraft mc) {
        if (!(mc.screen instanceof ModReloaderScreen screen)) {
            fail("screen changed before Restore");
            return;
        }
        int before = ModReloader.WATCHER_CONFIG == null ? 0 : ModReloader.WATCHER_CONFIG.ignoredCount();
        AbstractButton restoreButton = findButton(screen, I18n.get("modreloader.gui.restore"));
        if (restoreButton == null) {
            fail("no Restore button in the ignored view");
            return;
        }
        if (!restoreButton.active) {
            ModReloader.LOGGER.info("[MenuCheck] Restore disabled (nothing selected/ignored); "
                    + "ignoredCount=" + before);
        }
        restoreButton.onPress();
        int after = ModReloader.WATCHER_CONFIG == null ? 0 : ModReloader.WATCHER_CONFIG.ignoredCount();
        ModReloader.LOGGER.info("[MenuCheck] restore pressed: ignoredCount {} -> {}, showingIgnored={}",
                before, after, screen.isShowingIgnored());
        if (before > 0 && after >= before) {
            fail("Restore did not remove the ignored entry (" + before + " -> " + after + ")");
            return;
        }
        ModReloader.LOGGER.info("[MenuCheck] RESULT: PASS");
        if (pendingJar != null) {
            try {
                Files.deleteIfExists(pendingJar);
                ModReloader.LOGGER.info("[MenuCheck] removed seeded jar {}", pendingJar.getFileName());
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[MenuCheck] could not remove {}", pendingJar, t);
            }
        }
        stage = Stage.DONE;
        ticks = 0;
    }

    /** Copies the sample into the inbox once the watcher (and its dir) exists. */
    private static void seedPendingJar() {
        if (pendingJar != null || ModReloader.WATCHER == null) {
            return;
        }
        try {
            Path sample = Path.of("/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar");
            if (!Files.isRegularFile(sample)) {
                return;
            }
            Path inbox = ModReloader.WATCHER.inboxDir();
            Files.createDirectories(inbox);
            pendingJar = inbox.resolve("menutest-EasyLAN-neoforge-1.21.1-v1.6a.jar");
            Files.copy(sample, pendingJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            ModReloader.LOGGER.info("[MenuCheck] seeded a pending jar {}", pendingJar.getFileName());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MenuCheck] cannot seed the pending jar", t);
        }
    }

    private static AbstractButton findButton(Screen screen, String label) {
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button && label.equals(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }

    private static LogoButton findEntryButton(Screen screen) {
        for (var child : screen.children()) {
            if (child instanceof LogoButton button) {
                return button;
            }
        }
        return null;
    }

    private static List<String> describeButtons(Screen screen) {
        List<String> labels = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button) {
                labels.add(button.getMessage().getString());
            }
        }
        return labels;
    }

    private static String describe(Screen screen) {
        return String.join(" | ", describeButtons(screen));
    }

    private static void stopWhenDone(Minecraft mc) {
        if (ticks >= 20) {
            started = false;
            ModReloader.LOGGER.info("[MenuCheck] stopping client");
            mc.stop();
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[MenuCheck] FAILED: {}", reason);
        ModReloader.LOGGER.error("[MenuCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
