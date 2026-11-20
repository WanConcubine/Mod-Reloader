package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dev-only client half of the "both sides fei-tier" check.
 *
 * <p>Connects to the dedicated server (started with {@code -PmpFeiServer}),
 * hot-adds its own copy of the same fei-tier jar while in world, then writes
 * {@code mp-fei-client-ready.txt} so the server adds its copy. Afterwards the
 * client must still be connected — a fei-tier mod with mixins and a payload setup
 * that only one side knows about is exactly where a connection would die.
 *
 * <p>{@code -PverifyMpFei="<jar>|<modId>"}.
 */
public final class MpFeiClientCheck {
    private enum Stage { WAIT_TITLE, CONNECT, WAIT_JOIN, ADD, WRITE_READY, OBSERVE, DONE }

    private static final Path READY = Path.of("mp-fei-client-ready.txt");
    private static final int TIMEOUT_TICKS = 20 * 300;
    /** How long to stay connected after the marker (the server observes for 10 s). */
    private static final int OBSERVE_TICKS = 20 * 16;

    private static Path jar;
    private static String modId;
    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean failed;

    private MpFeiClientCheck() {
    }

    public static void start(String spec) {
        if (started) {
            return;
        }
        int bar = spec.indexOf('|');
        if (bar <= 0) {
            ModReloader.LOGGER.error("[MpFei] bad spec '{}': need <jar>|<modId>", spec);
            return;
        }
        jar = Path.of(spec.substring(0, bar).trim()).toAbsolutePath().normalize();
        modId = spec.substring(bar + 1).trim();
        started = true;
        try {
            Files.deleteIfExists(READY);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpFei] cannot clear {}", READY, t);
        }
        ModReloader.LOGGER.info("[MpFei] client side armed: {} (mod {})", jar, modId);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            fail("timeout at stage " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WAIT_TITLE -> {
                if (ticks >= 100 && mc.getOverlay() == null) {
                    stage = Stage.CONNECT;
                    ticks = 0;
                }
            }
            case CONNECT -> connect(mc);
            case WAIT_JOIN -> {
                if (mc.getConnection() != null && mc.player != null && mc.getOverlay() == null) {
                    ModReloader.LOGGER.info("[MpFei] joined; hot-adding the client-side copy");
                    stage = Stage.ADD;
                    ticks = 0;
                }
            }
            case ADD -> add(mc);
            case WRITE_READY -> writeReady(mc);
            case OBSERVE -> observe(mc);
            case DONE -> {
                if (ticks >= 40) {
                    ModReloader.LOGGER.info("[MpFei] stopping client");
                    started = false;
                    mc.stop();
                }
            }
        }
    }

    private static void connect(Minecraft mc) {
        if (mc.getOverlay() != null) {
            if (ticks > 20 * 60) {
                fail("resource reload overlay did not clear");
            }
            return;
        }
        String address = System.getProperty("modreloader.mpFeiAddress", "127.0.0.1:25565");
        if (!TestConnection.connect(mc, "MpFei", address, "mp-fei")) {
            fail("connect failed");
            return;
        }
        stage = Stage.WAIT_JOIN;
        ticks = 0;
    }

    private static void add(Minecraft mc) {
        if (mc.getOverlay() != null || ticks < 20) {
            return;
        }
        HotAddEngine.Result result = HotAddEngine.add(jar);
        ModReloader.LOGGER.info("[MpFei] client hot-add {}: ok={} kind={} msg={}",
                modId, result.ok(), result.kind(), result.message());
        if (!result.ok() || !ModList.get().isLoaded(modId)) {
            fail("client-side fei-tier add failed: " + result.kind() + ": " + result.message());
            return;
        }
        stage = Stage.WRITE_READY;
        ticks = 0;
    }

    private static void writeReady(Minecraft mc) {
        if (mc.getOverlay() != null || ticks < 40) {
            return;
        }
        try {
            Files.writeString(READY, "ready");
            ModReloader.LOGGER.info("[MpFei] wrote {}; the server adds its copy now", READY);
            stage = Stage.OBSERVE;
            ticks = 0;
        } catch (Throwable t) {
            fail("cannot write " + READY + ": " + t);
        }
    }

    private static void observe(Minecraft mc) {
        if (ticks < OBSERVE_TICKS) {
            return;
        }
        boolean connected = mc.getConnection() != null && mc.player != null;
        boolean loaded = ModList.get().isLoaded(modId);
        ModReloader.LOGGER.info(
                "[MpFei] after both adds: still connected={} client isLoaded={}", connected, loaded);
        if (connected && loaded) {
            ModReloader.LOGGER.info("[MpFei] RESULT: PASS "
                    + "(client + server both hot-added a fei-tier mod, connection survived)");
            stage = Stage.DONE;
            ticks = 0;
        } else {
            fail("connection or mod state lost after the two-sided add");
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[MpFei] FAILED: {}", reason);
        ModReloader.LOGGER.error("[MpFei] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
