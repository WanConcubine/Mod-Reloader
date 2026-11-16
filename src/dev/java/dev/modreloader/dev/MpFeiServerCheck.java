package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dev-only server half of the "both sides fei-tier" check.
 *
 * <p>A fei-tier mod (mixins) hot-added on <em>both</em> a dedicated server and a
 * connected client is the last P2-4 item: the two sides run the add
 * independently, and the connection has to survive it (payload channels, registry
 * ids, namesake mixins). The client half ({@link MpFeiClientCheck}) connects,
 * hot-adds its copy, then writes {@code mp-fei-client-ready.txt}; this side waits
 * for that marker plus a grace period and hot-adds its own copy on the server
 * thread, then watches the player stay connected.
 *
 * <p>{@code -PmpFeiServer="<jar>|<modId>"}. The server halts itself once the check
 * is done, so a background job does not linger.
 */
public final class MpFeiServerCheck {
    private static final Path READY = Path.of("mp-fei-client-ready.txt");
    private static final int TIMEOUT_TICKS = 20 * 240;
    /** Grace after the client's marker: let its add and resource reload settle. */
    private static final int GRACE_TICKS = 20 * 3;
    /** How long the player must stay connected after the server-side add. */
    private static final int OBSERVE_TICKS = 20 * 10;

    private static Path jar;
    private static String modId;
    private static MinecraftServer server;
    private static boolean armed;
    private static boolean serverUp;
    private static boolean added;
    private static boolean done;
    private static int ticks;
    private static int grace;
    private static int observe;

    private MpFeiServerCheck() {
    }

    public static void start(String spec) {
        if (armed) {
            return;
        }
        int bar = spec.indexOf('|');
        if (bar <= 0) {
            ModReloader.LOGGER.error("[MpFei] bad spec '{}': need <jar>|<modId>", spec);
            return;
        }
        jar = Path.of(spec.substring(0, bar).trim()).toAbsolutePath().normalize();
        modId = spec.substring(bar + 1).trim();
        armed = true;
        try {
            Files.deleteIfExists(READY);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpFei] cannot clear {}", READY, t);
        }
        ModReloader.LOGGER.info("[MpFei] server side armed: {} (mod {})", jar, modId);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            server = event.getServer();
            serverUp = true;
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!armed || !serverUp || server == null || done) {
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            ModReloader.LOGGER.error("[MpFei] TIMEOUT (stage: {} added={})",
                    Files.isRegularFile(READY) ? "observing" : "waiting for the client", added);
            ModReloader.LOGGER.error("[MpFei] RESULT: FAIL");
            done = true;
            haltLater();
            return;
        }
        if (!added) {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty() || !Files.isRegularFile(READY)) {
                return;
            }
            if (grace < GRACE_TICKS) {
                grace++;
                return;
            }
            ModReloader.LOGGER.info("[MpFei] client is ready and {} player(s) connected; hot-adding "
                    + "the server-side copy", players.size());
            // Tick handler: already on the server thread.
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[MpFei] server hot-add {}: ok={} kind={} msg={}",
                    modId, result.ok(), result.kind(), result.message());
            boolean loaded = ModList.get().isLoaded(modId);
            if (!result.ok() || !loaded) {
                ModReloader.LOGGER.error("[MpFei] server-side fei-tier add failed (isLoaded={})", loaded);
                ModReloader.LOGGER.error("[MpFei] RESULT: FAIL");
                done = true;
                haltLater();
                return;
            }
            added = true;
            observe = 0;
            return;
        }
        // Observe: the connection must survive both adds.
        if (++observe >= OBSERVE_TICKS) {
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            boolean connected = !players.isEmpty();
            ModReloader.LOGGER.info(
                    "[MpFei] both sides hot-added {} (server isLoaded={}, players still connected={})",
                    modId, ModList.get().isLoaded(modId), connected);
            if (connected) {
                ModReloader.LOGGER.info("[MpFei] RESULT: PASS "
                        + "(server + client both hot-added a fei-tier mod, connection survived)");
            } else {
                ModReloader.LOGGER.error("[MpFei] RESULT: FAIL (the player was disconnected)");
            }
            done = true;
            haltLater();
        }
    }

    private static void haltLater() {
        if (server == null) {
            return;
        }
        server.execute(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            server.halt(false);
        });
    }
}
