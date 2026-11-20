package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-only cross-process check ({@code -PverifyPayloadRemote=<jar>}).
 *
 * <p>Hot-adds a payload mod at the title screen, connects to a dedicated server
 * that hot-added the same jar, and waits for the marker the mod writes when it
 * receives its own payload. Unlike E1/E2 this runs two JVMs, so nothing is shared
 * through static state.
 */
public final class RemotePayloadCheck {
    private enum Stage { WAIT_TITLE, ADD, CONNECT, WAIT_JOIN, WAIT_PING, DONE }

    private static final int TIMEOUT_TICKS = 20 * 180;
    private static final Path MARKER = Path.of("payload-received-remote.txt");

    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static boolean started;
    private static boolean failed;
    private static Path jar;

    private RemotePayloadCheck() {
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
            ModReloader.LOGGER.warn("[RemotePayloadCheck] cannot remove an old marker", t);
        }
        ModReloader.LOGGER.info("[RemotePayloadCheck] started; hot-adding {}", jar);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        startDiagnostics();
    }

    private static void startDiagnostics() {
        Thread thread = new Thread(() -> {
            while (started) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) {
                    continue;
                }
                ModReloader.LOGGER.info("[RemotePayloadCheck] diag: stage={}, overlay={}, connected={}, "
                                + "player={}",
                        stage,
                        mc.getOverlay() == null ? "null" : mc.getOverlay().getClass().getSimpleName(),
                        mc.getConnection() != null, mc.player != null);
            }
        }, "modreloader-remote-diag");
        thread.setDaemon(true);
        thread.start();
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
            case CONNECT -> connect(mc);
            case WAIT_JOIN -> waitJoin(mc);
            case WAIT_PING -> waitPing();
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void waitTitle(Minecraft mc) {
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) {
            return;
        }
        stage = Stage.ADD;
        ticks = 0;
    }

    private static void add() {
        HotAddEngine.Result result = HotAddEngine.add(jar);
        ModReloader.LOGGER.info("[RemotePayloadCheck] hot-add result: ok={}, kind={}, message={}",
                result.ok(), result.kind(), result.message());
        if (!result.ok()) {
            fail("hot-add failed: " + result.kind() + ": " + result.message());
            return;
        }
        stage = Stage.CONNECT;
        ticks = 0;
    }

    private static void connect(Minecraft mc) {
        if (mc.getOverlay() != null) {
            if (ticks > 20 * 60) {
                fail("resource reload overlay did not clear");
            }
            return;
        }
        String address = System.getProperty("modreloader.verifyPayloadRemoteAddress", "127.0.0.1:25565");
        if (!TestConnection.connect(mc, "RemotePayloadCheck", address, "e3")) {
            fail("connect failed");
            return;
        }
        stage = Stage.WAIT_JOIN;
        ticks = 0;
    }

    private static void waitJoin(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            return;
        }
        ModReloader.LOGGER.info("[RemotePayloadCheck] joined {}; waiting for the payload",
                mc.getConnection().getServerData() == null ? "?" : mc.getConnection().getServerData().ip);
        stage = Stage.WAIT_PING;
        ticks = 0;
    }

    private static void waitPing() {
        if (Files.exists(MARKER)) {
            ModReloader.LOGGER.info("[RemotePayloadCheck] marker written: {}", MARKER.toAbsolutePath());
            ModReloader.LOGGER.info("[RemotePayloadCheck] RESULT: PASS");
            stage = Stage.DONE;
            ticks = 0;
            return;
        }
        if (ticks > 20 * 60) {
            fail("no payload received within 60s (marker missing)");
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
            ModReloader.LOGGER.info("[RemotePayloadCheck] stopping client");
            mc.stop();
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[RemotePayloadCheck] FAILED: {}", reason);
        ModReloader.LOGGER.error("[RemotePayloadCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
