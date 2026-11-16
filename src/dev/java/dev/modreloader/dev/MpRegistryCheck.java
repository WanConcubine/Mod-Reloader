package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only client half of the multiplayer registry-sync check.
 *
 * <p>Sequence: connect to a dedicated server, hot-add this side's copies while in
 * world (so the local ids are genuinely computed from a different registry order
 * than the server's), write {@code mp-client-ready.txt}, then wait for the server
 * to hot-add its own copies and publish {@code mp-registry-server.txt}. The server
 * pushes its snapshot when its commit lands; the client applies it and the check
 * compares <em>every</em> block id, item id and block-state id against the server's
 * file.
 *
 * <p>{@code -PverifyMpRegistry=<jar>[,<jar>...]} lists the client's jars in the
 * client's own order — deliberately different from the server's so ids differ.
 */
public final class MpRegistryCheck {
    private enum Stage { WAIT_TITLE, CONNECT, WAIT_JOIN, ADD, WRITE_READY, WAIT_SERVER, SETTLE, COMPARE, DONE }

    private static final Path READY = Path.of("mp-client-ready.txt");
    private static final Path SERVER_DUMP = Path.of("mp-registry-server.txt");
    private static final Path CLIENT_DUMP = Path.of("mp-registry-client.txt");
    private static final int TIMEOUT_TICKS = 20 * 300;
    /** Time for the snapshot payload to arrive and be applied after the server's file shows up. */
    private static final int SYNC_SETTLE_TICKS = 20 * 8;

    private static final List<Path> JARS = new ArrayList<>();
    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean failed;

    private MpRegistryCheck() {
    }

    public static void start(String csv) {
        if (started) {
            return;
        }
        for (String part : csv.split(",")) {
            String raw = part.trim();
            if (!raw.isEmpty()) {
                JARS.add(Path.of(raw).toAbsolutePath().normalize());
            }
        }
        if (JARS.isEmpty()) {
            ModReloader.LOGGER.warn("[MpRegistry] no jars given; not starting");
            return;
        }
        started = true;
        try {
            Files.deleteIfExists(READY);
            Files.deleteIfExists(CLIENT_DUMP);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpRegistry] cannot clear old markers", t);
        }
        ModReloader.LOGGER.info("[MpRegistry] client side armed; jars (in this order)={}", JARS);
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
            case WAIT_TITLE -> waitTitle(mc);
            case CONNECT -> connect(mc);
            case WAIT_JOIN -> waitJoin(mc);
            case ADD -> add(mc);
            case WRITE_READY -> writeReady(mc);
            case WAIT_SERVER -> waitServer(mc);
            case SETTLE -> settle(mc);
            case COMPARE -> compare(mc);
            case DONE -> {
                if (ticks >= 40) {
                    ModReloader.LOGGER.info("[MpRegistry] stopping client");
                    started = false;
                    mc.stop();
                }
            }
        }
    }

    private static void waitTitle(Minecraft mc) {
        if (ticks < 100 || mc.getOverlay() != null) {
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
        String address = System.getProperty("modreloader.mpRegistryAddress", "127.0.0.1:25565");
        try {
            ServerAddress serverAddress = ServerAddress.parseString(address);
            ServerData data = new ServerData("mp", address, ServerData.Type.OTHER);
            ModReloader.LOGGER.info("[MpRegistry] connecting to {}", address);
            ConnectScreen.startConnecting(mc.screen, mc, serverAddress, data, false, null);
        } catch (Throwable t) {
            fail("connect failed: " + t);
            return;
        }
        stage = Stage.WAIT_JOIN;
        ticks = 0;
    }

    private static void waitJoin(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || mc.getOverlay() != null) {
            return;
        }
        stage = Stage.ADD;
        ticks = 0;
    }

    private static void add(Minecraft mc) {
        if (mc.getOverlay() != null) {
            return;
        }
        for (Path jar : JARS) {
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[MpRegistry] client hot-add {}: ok={} kind={} msg={}",
                    jar.getFileName(), result.ok(), result.kind(), result.message());
            if (!result.ok()) {
                fail("client hot-add failed: " + result.kind() + ": " + result.message());
                return;
            }
        }
        ModReloader.LOGGER.info("[MpRegistry] client registries after its own adds: blocks={} items={}",
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.size(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
        stage = Stage.WRITE_READY;
        ticks = 0;
    }

    private static void writeReady(Minecraft mc) {
        // Let the add's own resource reload finish: the server waits 3s after this
        // marker, and a half-finished reload would interleave with the sync.
        if (mc.getOverlay() != null || ticks < 40) {
            return;
        }
        try {
            Files.writeString(READY, "ready");
            ModReloader.LOGGER.info("[MpRegistry] wrote {}; waiting for the server's ids", READY);
            stage = Stage.WAIT_SERVER;
            ticks = 0;
        } catch (Throwable t) {
            fail("cannot write " + READY + ": " + t);
        }
    }

    private static void waitServer(Minecraft mc) {
        if (!Files.isRegularFile(SERVER_DUMP)) {
            if (ticks % 100 == 0) {
                ModReloader.LOGGER.info("[MpRegistry] waiting for {}", SERVER_DUMP);
            }
            return;
        }
        stage = Stage.SETTLE;
        ticks = 0;
    }

    private static void settle(Minecraft mc) {
        if (ticks < SYNC_SETTLE_TICKS) {
            return;
        }
        stage = Stage.COMPARE;
        ticks = 0;
    }

    private static void compare(Minecraft mc) {
        try {
            List<String> client = RegistryDump.lines();
            List<String> server = Files.readAllLines(SERVER_DUMP);
            Files.write(CLIENT_DUMP, client);
            List<String> differences = RegistryDump.differences(server, client);
            if (differences.isEmpty()) {
                ModReloader.LOGGER.info(
                        "[MpRegistry] ids match the server exactly ({} lines, blocks={}, items={})",
                        client.size(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.size(),
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
                ModReloader.LOGGER.info("[MpRegistry] RESULT: PASS");
            } else {
                ModReloader.LOGGER.error("[MpRegistry] client ids differ from the server: {}",
                        differences);
                ModReloader.LOGGER.error("[MpRegistry] RESULT: FAIL (registry mapping was not synced)");
            }
        } catch (Throwable t) {
            fail("compare failed: " + t);
            return;
        }
        stage = Stage.DONE;
        ticks = 0;
    }

    private static void fail(String message) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[MpRegistry] {}", message);
        ModReloader.LOGGER.error("[MpRegistry] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
