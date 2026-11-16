package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only server half of the multiplayer registry-sync check.
 *
 * <p>Waits for a client to be connected <em>and</em> to have written
 * {@code mp-client-ready.txt} (i.e. it already hot-added its copy), then hot-adds
 * the jars in its own order and writes {@code mp-registry-server.txt} with the
 * authoritative id mapping. The client compares against that file.
 *
 * <p>{@code -PmpRegistryServer=<jar>[,<jar>...]}. The server keeps running: the
 * point is a live connection, so this check cannot stop the JVM itself.
 */
public final class MpRegistryServerCheck {
    private static final Path READY = Path.of("mp-client-ready.txt");
    private static final Path DUMP = Path.of("mp-registry-server.txt");
    private static final int TIMEOUT_TICKS = 20 * 300;
    /** Grace after the client says it is ready, so its resource reload settles first. */
    private static final int GRACE_TICKS = 20 * 3;

    private static final List<Path> JARS = new ArrayList<>();
    private static boolean started;
    private static boolean serverUp;
    private static int ticks;
    private static int grace;
    private static MinecraftServer server;

    private MpRegistryServerCheck() {
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
            Files.deleteIfExists(DUMP);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpRegistry] cannot clear old markers", t);
        }
        ModReloader.LOGGER.info("[MpRegistry] server side armed; jars (in this order)={}", JARS);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            server = event.getServer();
            serverUp = true;
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started || !serverUp || server == null) {
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            ModReloader.LOGGER.error("[MpRegistry] TIMEOUT: no ready client after {}s",
                    TIMEOUT_TICKS / 20);
            started = false;
            return;
        }
        int players = server.getPlayerList().getPlayers().size();
        if (players == 0) {
            return;
        }
        if (!Files.isRegularFile(READY)) {
            if (ticks % 100 == 0) {
                ModReloader.LOGGER.info("[MpRegistry] {} player(s) connected; waiting for {}", players, READY);
            }
            return;
        }
        if (++grace < GRACE_TICKS) {
            return;
        }
        started = false;
        ModReloader.LOGGER.info("[MpRegistry] client is ready; hot-adding {} in server order", JARS);
        for (Path jar : JARS) {
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[MpRegistry] server hot-add {}: ok={} kind={} msg={}",
                    jar.getFileName(), result.ok(), result.kind(), result.message());
            if (!result.ok()) {
                ModReloader.LOGGER.error("[MpRegistry] RESULT: FAIL (server hot-add failed)");
                return;
            }
        }
        try {
            Files.write(DUMP, RegistryDump.lines());
            ModReloader.LOGGER.info("[MpRegistry] wrote {} (blocks={}, items={})", DUMP,
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.size(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[MpRegistry] cannot write {}", DUMP, t);
        }
    }
}
