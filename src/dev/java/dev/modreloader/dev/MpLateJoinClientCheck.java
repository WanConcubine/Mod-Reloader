package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.ModNotice;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dev-only client half of the late-join check.
 *
 * <p>The jar must already be in {@code run/mods} so FML loads it at client
 * startup (the restart path). The server hot-added it before this client
 * connected, so the only way the client can see the same ids is NeoForge's
 * normal login registry sync. The check compares every block/item/state id
 * against the server's dump and keeps the connection alive for ten seconds.
 *
 * <p>{@code -PverifyMpLateJoin=<jar>|<modId>} (the jar path is informational;
 * the startup-loaded copy is what matters).
 */
public final class MpLateJoinClientCheck {
    private enum Stage { WAIT_TITLE, CONNECT, WAIT_JOIN, SETTLE, COMPARE, PLAY, DONE }

    private static final Path SERVER_DUMP = MpLateJoinServerCheck.DUMP;
    private static final Path CLIENT_DUMP = Path.of("mp-latejoin-client.txt");
    private static final int TIMEOUT_TICKS = 20 * 300;
    private static final int SETTLE_TICKS = 20 * 8;
    private static final int PLAY_TICKS = 20 * 10;

    private static String modId;
    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;

    private MpLateJoinClientCheck() {
    }

    public static void start(String spec) {
        if (started) {
            return;
        }
        int bar = spec.lastIndexOf('|');
        modId = bar >= 0 ? spec.substring(bar + 1).trim() : "";
        started = true;
        try {
            Files.deleteIfExists(CLIENT_DUMP);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpLateJoin] cannot clear old {}", CLIENT_DUMP, t);
        }
        ModReloader.LOGGER.info(
                "[MpLateJoin] client side armed: modId={} (jar must already be in run/mods)", modId);
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
            case WAIT_JOIN -> waitJoin(mc);
            case SETTLE -> {
                if (ticks >= SETTLE_TICKS) {
                    stage = Stage.COMPARE;
                    ticks = 0;
                }
            }
            case COMPARE -> compare(mc);
            case PLAY -> play(mc);
            case DONE -> {
                if (ticks >= 40) {
                    ModReloader.LOGGER.info("[MpLateJoin] stopping client");
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
        String address = System.getProperty("modreloader.mpLateJoinAddress", "127.0.0.1:25565");
        if (!TestConnection.connect(mc, "MpLateJoin", address, "mp-latejoin")) {
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
        boolean loaded = modId.isEmpty() || ModList.get().isLoaded(modId);
        ModReloader.LOGGER.info(
                "[MpLateJoin] joined; isLoaded={} (modId={}), noticeCount={} (late joins are not notified)",
                loaded, modId, ModNotice.receivedCount());
        ModReloader.LOGGER.info("[MpLateJoin] settling for {}s to let the login registry sync finish",
                SETTLE_TICKS / 20);
        stage = Stage.SETTLE;
        ticks = 0;
    }

    private static void compare(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            fail("connection dropped before the registry comparison");
            return;
        }
        if (modId != null && !modId.isEmpty() && !ModList.get().isLoaded(modId)) {
            fail("mod " + modId + " is not loaded on the client");
            return;
        }
        List<String> serverLines;
        try {
            serverLines = Files.readAllLines(SERVER_DUMP);
        } catch (Throwable t) {
            fail("cannot read " + SERVER_DUMP + ": " + t);
            return;
        }
        List<String> clientLines = RegistryDump.lines();
        try {
            Files.write(CLIENT_DUMP, clientLines);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpLateJoin] cannot write {}", CLIENT_DUMP, t);
        }
        List<String> differences = RegistryDump.differences(serverLines, clientLines);
        if (!differences.isEmpty()) {
            ModReloader.LOGGER.error("[MpLateJoin] registry mismatch ({} server lines, {} client lines):",
                    serverLines.size(), clientLines.size());
            differences.forEach(line -> ModReloader.LOGGER.error("[MpLateJoin]   {}", line));
            fail("registry ids differ from the server after a late join");
            return;
        }
        ModReloader.LOGGER.info("[MpLateJoin] ids match the server exactly ({} lines, blocks={}, items={})",
                clientLines.size(),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.size(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
        stage = Stage.PLAY;
        ticks = 0;
    }

    private static void play(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || mc.level == null) {
            fail("connection dropped during the play window");
            return;
        }
        if (ticks < PLAY_TICKS) {
            return;
        }
        ModReloader.LOGGER.info(
                "[MpLateJoin] still connected={}, playerAlive={}, level={} after {}s in world",
                true, mc.player.isAlive(), mc.level.dimension().location(), PLAY_TICKS / 20);
        ModReloader.LOGGER.info("[MpLateJoin] RESULT: PASS");
        stage = Stage.DONE;
        ticks = 0;
    }

    private static void fail(String message) {
        ModReloader.LOGGER.error("[MpLateJoin] {}", message);
        ModReloader.LOGGER.error("[MpLateJoin] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}