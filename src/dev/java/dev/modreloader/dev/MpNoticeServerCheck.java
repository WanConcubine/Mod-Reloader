package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Path;

/**
 * Dev-only server half of the hot-add notice check.
 *
 * <p>Waits for a player to be connected, then hot-adds the jar. The point is that
 * the <em>client does not have this mod</em>: it should still be told about it
 * (chat + toast + log) and must not be disconnected by the notice itself.
 *
 * <p>{@code -PmpNoticeServer=<jar>}. The server keeps running; the visible result
 * is on the client side ({@code MpNoticeClientCheck}) and in both logs.
 */
public final class MpNoticeServerCheck {
    private static final int JOIN_GRACE_TICKS = 20 * 5;
    private static final int TIMEOUT_TICKS = 20 * 180;

    private static Path jar;
    private static MinecraftServer server;
    private static boolean armed;
    private static boolean serverUp;
    private static int ticks;
    private static int grace;

    private MpNoticeServerCheck() {
    }

    public static void start(String jarPath) {
        if (armed) {
            return;
        }
        jar = Path.of(jarPath).toAbsolutePath().normalize();
        armed = true;
        ModReloader.LOGGER.info("[MpNotice] server side armed with {}", jar);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            server = event.getServer();
            serverUp = true;
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!armed || !serverUp || server == null) {
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            ModReloader.LOGGER.error("[MpNotice] TIMEOUT waiting for a player");
            armed = false;
            return;
        }
        if (server.getPlayerList().getPlayers().isEmpty()) {
            return;
        }
        if (++grace < JOIN_GRACE_TICKS) {
            return;
        }
        armed = false;
        ModReloader.LOGGER.info("[MpNotice] player connected; hot-adding {} (the client does NOT have it)",
                jar.getFileName());
        HotAddEngine.Result result = HotAddEngine.add(jar);
        ModReloader.LOGGER.info("[MpNotice] server hot-add ok={} kind={} msg={}",
                result.ok(), result.kind(), result.message());
        ModReloader.LOGGER.info("[MpNotice] RESULT: {}", result.ok() ? "PASS" : "FAIL");
    }
}
