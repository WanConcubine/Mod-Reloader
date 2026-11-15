package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-only end-to-end check for payload adaptation ({@code -PverifyPayload=<jar>}).
 *
 * <p>Hot-adds a payload-registering mod at the title screen, creates a world, and
 * waits for the marker file the mod writes when it receives its own payload. That
 * proves the channel was registered, negotiated and actually used.
 */
public final class PayloadCheck {
    private enum Stage { WAIT_TITLE, ADD, OPEN_CREATE, WAIT_CREATE_SCREEN, WAIT_WORLD, WAIT_PING, DONE }

    private static final int TIMEOUT_TICKS = 20 * 180;
    private static final Path MARKER = Path.of("payload-received.txt");

    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static boolean started;
    private static boolean failed;
    private static boolean pressedCreate;
    private static Path jar;
    private static int worldTicks;

    private PayloadCheck() {
    }

    public static void start(Path target) {
        if (started) {
            return;
        }
        started = true;
        jar = target;
        try {
            Files.deleteIfExists(MARKER);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[PayloadCheck] cannot remove an old marker", t);
        }
        ModReloader.LOGGER.info("[PayloadCheck] started; hot-adding {}", jar);
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
            case ADD -> add();
            case OPEN_CREATE -> openCreate(mc);
            case WAIT_CREATE_SCREEN -> waitCreateScreen(mc);
            case WAIT_WORLD -> waitWorld(mc);
            case WAIT_PING -> waitPing();
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void waitTitle(Minecraft mc) {
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)
                || mc.getOverlay() != null
                || ModReloader.WATCHER == null) {
            return;
        }
        stage = Stage.ADD;
        ticks = 0;
    }

    private static void add() {
        HotAddEngine.Result result = HotAddEngine.add(jar);
        ModReloader.LOGGER.info("[PayloadCheck] hot-add result: ok={}, kind={}, message={}",
                result.ok(), result.kind(), result.message());
        if (!result.ok()) {
            fail("hot-add failed: " + result.kind() + ": " + result.message());
            return;
        }
        stage = Stage.OPEN_CREATE;
        ticks = 0;
    }

    private static void openCreate(Minecraft mc) {
        try {
            CreateWorldScreen.openFresh(mc, mc.screen);
            stage = Stage.WAIT_CREATE_SCREEN;
            ticks = 0;
        } catch (Throwable t) {
            fail("CreateWorldScreen.openFresh failed: " + t);
        }
    }

    private static void waitCreateScreen(Minecraft mc) {
        if (!(mc.screen instanceof CreateWorldScreen screen)) {
            if (ticks > 20 * 30) {
                fail("create-world screen did not appear");
            }
            return;
        }
        if (pressedCreate) {
            return;
        }
        String label = I18n.get("selectWorld.create");
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button
                    && label.equals(button.getMessage().getString())) {
                pressedCreate = true;
                button.onPress();
                stage = Stage.WAIT_WORLD;
                ticks = 0;
                worldTicks = 0;
                return;
            }
        }
    }

    private static void waitWorld(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            worldTicks = 0;
            if (ticks > 20 * 120) {
                fail("world did not load");
            }
            return;
        }
        worldTicks++;
        if (worldTicks < 20) {
            return;
        }
        ModReloader.LOGGER.info("[PayloadCheck] in world; waiting for the payload marker");
        stage = Stage.WAIT_PING;
        ticks = 0;
    }

    private static void waitPing() {
        if (Files.exists(MARKER)) {
            ModReloader.LOGGER.info("[PayloadCheck] marker written: {}", MARKER.toAbsolutePath());
            ModReloader.LOGGER.info("[PayloadCheck] RESULT: PASS");
            stage = Stage.DONE;
            ticks = 0;
            return;
        }
        if (ticks > 20 * 40) {
            fail("no payload received within 40s (marker missing)");
        }
    }

    private static void stopWhenDone(Minecraft mc) {
        if (ticks >= 20) {
            started = false;
            try {
                Files.deleteIfExists(MARKER);
            } catch (Throwable ignored) {
                // Best effort.
            }
            ModReloader.LOGGER.info("[PayloadCheck] stopping client");
            mc.stop();
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[PayloadCheck] FAILED: {}", reason);
        ModReloader.LOGGER.error("[PayloadCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
