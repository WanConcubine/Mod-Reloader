package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.command.ReloaderCommands;
import dev.modreloader.inspect.HotAddWhy;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only check for {@code /modreloader why} on a dedicated server.
 *
 * <p>{@code why} is a read-only diagnosis: tier, content shape, access-transformer
 * composition, dependencies, registration audit and the verdict the engine would
 * reach. The check does two things per jar:
 *
 * <ol>
 *   <li>{@link HotAddWhy#of(Path)} directly, asserting the verdict contains an
 *       expected token (the token is part of the harness input on purpose: the
 *       point is "the report says <em>this</em> for <em>this</em> jar");</li>
 *   <li>the console command path ({@code modreloader why <path>} through the
 *       server dispatcher) once, asserting it returns 1 and prints a verdict
 *       line.</li>
 * </ol>
 *
 * <p>{@code -PserverWhy="<jar>|<expected verdict token>,<jar>|<token>"}. The
 * server halts itself when done, like the other server harnesses.
 */
public final class ServerWhyCheck {
    private static final int TIMEOUT_TICKS = 20 * 120;

    private static final List<Pair> PAIRS = new ArrayList<>();
    private static MinecraftServer server;
    private static boolean armed;
    private static int ticks;

    private record Pair(Path jar, String expected) {
    }

    private ServerWhyCheck() {
    }

    public static void start(String spec) {
        if (armed) {
            return;
        }
        for (String part : spec.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int bar = trimmed.indexOf('|');
            if (bar <= 0) {
                ModReloader.LOGGER.error("[ServerWhy] bad entry '{}' (need <jar>|<expected token>)", trimmed);
                continue;
            }
            PAIRS.add(new Pair(Path.of(trimmed.substring(0, bar).trim()).toAbsolutePath().normalize(),
                    trimmed.substring(bar + 1).trim()));
        }
        if (PAIRS.isEmpty()) {
            ModReloader.LOGGER.warn("[ServerWhy] no jars given; not starting");
            return;
        }
        armed = true;
        ModReloader.LOGGER.info("[ServerWhy] armed with {}", PAIRS);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            server = event.getServer();
            run();
        });
        NeoForge.EVENT_BUS.addListener(
                net.neoforged.neoforge.event.tick.ServerTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!armed || server == null) {
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            ModReloader.LOGGER.error("[ServerWhy] TIMEOUT");
            ModReloader.LOGGER.error("[ServerWhy] RESULT: FAIL");
            armed = false;
            server.halt(false);
        }
    }

    private static void run() {
        boolean ok = true;
        for (Pair pair : PAIRS) {
            HotAddWhy.Report report = HotAddWhy.of(pair.jar());
            for (String line : report.lines()) {
                ModReloader.LOGGER.info("[ServerWhy] {}", line);
            }
            String verdict = report.verdict();
            // The token may live in any line (e.g. the client-class warning), so match
            // the whole report, not only the verdict.
            boolean matches = report.lines().stream()
                    .anyMatch(line -> line.toLowerCase().contains(pair.expected().toLowerCase()));
            ModReloader.LOGGER.info("[ServerWhy] {}: expected~'{}' matches={} (verdict='{}')",
                    pair.jar().getFileName(), pair.expected(), matches, verdict);
            ok &= matches;
            ok &= !report.lines().isEmpty();
        }

        // The console path: Brigadier parse + execute on the server dispatcher, same
        // as a human typing in the console.
        if (server != null && !PAIRS.isEmpty()) {
            CommandSourceStack source = server.createCommandSourceStack();
            String command = "modreloader why " + PAIRS.get(0).jar();
            try {
                int result = server.getCommands().getDispatcher().execute(command, source);
                ModReloader.LOGGER.info("[ServerWhy] /{} returned {}", command, result);
                ok &= result == 1;
            } catch (Exception e) {
                ModReloader.LOGGER.error("[ServerWhy] /{} threw {}", command, e.toString());
                ok = false;
            }
        }

        ModReloader.LOGGER.info("[ServerWhy] RESULT: {}", ok ? "PASS" : "FAIL");
        armed = false;
        server.halt(false);
    }
}
