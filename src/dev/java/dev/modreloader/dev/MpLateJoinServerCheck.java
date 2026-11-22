package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dev-only server half of the late-join check.
 *
 * <p>The server hot-adds the jar <em>before any player connects</em>, publishes a
 * registry dump, then waits for a client that has the same jar loaded at startup
 * (restart path). This is the case NeoForge's login sync is supposed to cover; it
 * is deliberately different from {@link MpNoticeServerCheck}, which hot-adds only
 * after a client is already online.
 *
 * <p>{@code -PmpLateJoinServer=<jar>|<modId>}. The harness stops the server after
 * the late client has been seen for a while, so a single command pair is enough.
 */
public final class MpLateJoinServerCheck {
    static final Path READY = Path.of("mp-latejoin-ready.txt");
    static final Path DUMP = Path.of("mp-latejoin-server.txt");
    static final Path FAILED = Path.of("mp-latejoin-server-fail.txt");

    private static final int START_DELAY_TICKS = 20;
    private static final int TIMEOUT_TICKS = 20 * 300;
    private static final int PLAYER_GRACE_TICKS = 20 * 5;
    private static final int STOP_AFTER_PLAYER_TICKS = 20 * 30;

    private static Path jar;
    private static String modId;
    private static MinecraftServer server;
    private static boolean armed;
    private static boolean added;
    private static boolean serverUp;
    private static boolean resultLogged;
    private static int ticks;
    private static int grace;
    private static int afterPlayer;

    private MpLateJoinServerCheck() {
    }

    public static void start(String spec) {
        if (armed) {
            return;
        }
        int bar = spec.lastIndexOf('|');
        String rawJar = bar >= 0 ? spec.substring(0, bar).trim() : spec.trim();
        modId = bar >= 0 ? spec.substring(bar + 1).trim() : "";
        jar = Path.of(rawJar).toAbsolutePath().normalize();
        armed = true;
        try {
            Files.deleteIfExists(READY);
            Files.deleteIfExists(DUMP);
            Files.deleteIfExists(FAILED);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpLateJoin] cannot clear old markers", t);
        }
        ModReloader.LOGGER.info("[MpLateJoin] server side armed: jar={} modId={}", jar, modId);
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
            fail("TIMEOUT waiting for a late client");
            return;
        }
        if (!added) {
            if (ticks < START_DELAY_TICKS) {
                return;
            }
            add();
            return;
        }
        if (!resultLogged) {
            if (server.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            if (++grace < PLAYER_GRACE_TICKS) {
                return;
            }
            resultLogged = true;
            boolean loaded = modId.isEmpty() || ModList.get().isLoaded(modId);
            int players = server.getPlayerList().getPlayers().size();
            ModReloader.LOGGER.info(
                    "[MpLateJoin] late client joined; players={}, isLoaded={}, dumpLines={}",
                    players, loaded, dumpLineCount());
            ModReloader.LOGGER.info("[MpLateJoin] RESULT: {}", loaded ? "PASS" : "FAIL");
        }
        // Count down after the result even if the client has disconnected; the
        // harness must stop the server by itself for a one-command-pair run.
        if (++afterPlayer >= STOP_AFTER_PLAYER_TICKS) {
            armed = false;
            ModReloader.LOGGER.info("[MpLateJoin] stopping the server after the late-join check");
            server.halt(false);
        }
    }

    private static void add() {
        added = true;
        ModReloader.LOGGER.info(
                "[MpLateJoin] no players online; hot-adding {} before any client connects",
                jar.getFileName());
        HotAddEngine.Result result = HotAddEngine.add(jar);
        boolean loaded = modId.isEmpty() || ModList.get().isLoaded(modId);
        ModReloader.LOGGER.info("[MpLateJoin] server hot-add: ok={} kind={} msg={} isLoaded={}",
                result.ok(), result.kind(), result.message(), loaded);
        if (!result.ok() || !loaded) {
            fail("server hot-add failed before the late client connected");
            return;
        }
        try {
            List<String> lines = RegistryDump.lines();
            Files.write(DUMP, lines);
            Files.writeString(READY, (modId.isEmpty() ? "?" : modId) + "\n"
                    + jar.getFileName() + "\n" + lines.size() + "\n");
            ModReloader.LOGGER.info(
                    "[MpLateJoin] wrote {} ({} lines) and {}; waiting for a late-joining client",
                    DUMP, lines.size(), READY);
        } catch (Throwable t) {
            fail("cannot write " + DUMP + ": " + t);
        }
    }

    private static int dumpLineCount() {
        try {
            return Files.readAllLines(DUMP).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void fail(String message) {
        armed = false;
        ModReloader.LOGGER.error("[MpLateJoin] {}", message);
        ModReloader.LOGGER.error("[MpLateJoin] RESULT: FAIL");
        try {
            Files.writeString(FAILED, message + "\n");
        } catch (Throwable ignored) {
            // The log already has the failure; the marker is only for the shell.
        }
    }
}