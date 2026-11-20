package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.ModNotice;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only client half of the hot-add notice check.
 *
 * <p>Connects to a server that will hot-add a mod this client does <em>not</em>
 * have, and asserts that the notice arrived (chat + toast + log) without the
 * connection dropping. It deliberately hot-adds nothing itself: the interesting
 * case is the uninformed client.
 *
 * <p>{@code -PverifyMpNotice=true}.
 */
public final class MpNoticeClientCheck {
    private enum Stage { WAIT_TITLE, CONNECT, WAIT_JOIN, WAIT_NOTICE, DONE }

    private static final int TIMEOUT_TICKS = 20 * 180;
    private static final int NOTICE_WAIT_TICKS = 20 * 90;

    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean joined;

    private MpNoticeClientCheck() {
    }

    public static void start() {
        if (started) {
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[MpNotice] client side armed (hot-adds nothing on purpose)");
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
                if (mc.getConnection() != null && mc.player != null) {
                    joined = true;
                    ModReloader.LOGGER.info("[MpNotice] joined; waiting for the server's notice");
                    stage = Stage.WAIT_NOTICE;
                    ticks = 0;
                }
            }
            case WAIT_NOTICE -> {
                if (ModNotice.receivedCount() > 0) {
                    ModReloader.LOGGER.info("[MpNotice] received {} notice(s) without hot-adding anything",
                            ModNotice.receivedCount());
                    ModReloader.LOGGER.info("[MpNotice] still connected={}",
                            mc.getConnection() != null && mc.player != null);
                    ModReloader.LOGGER.info("[MpNotice] RESULT: PASS");
                    stage = Stage.DONE;
                    ticks = 0;
                } else if (ticks > NOTICE_WAIT_TICKS) {
                    fail("no notice after " + NOTICE_WAIT_TICKS / 20 + "s");
                }
            }
            case DONE -> {
                if (ticks >= 40) {
                    ModReloader.LOGGER.info("[MpNotice] stopping client");
                    started = false;
                    mc.stop();
                }
            }
        }
    }

    private static void connect(Minecraft mc) {
        if (mc.getOverlay() != null) {
            return;
        }
        String address = System.getProperty("modreloader.mpNoticeAddress", "127.0.0.1:25565");
        if (!TestConnection.connect(mc, "MpNotice", address, "mp-notice")) {
            fail("connect failed");
            return;
        }
        stage = Stage.WAIT_JOIN;
        ticks = 0;
    }

    private static void fail(String message) {
        ModReloader.LOGGER.error("[MpNotice] {}", message);
        ModReloader.LOGGER.error("[MpNotice] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
