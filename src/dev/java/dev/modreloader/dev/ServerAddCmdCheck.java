package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Dev-only check for the dedicated-server console path: {@code /modreloader add}.
 *
 * <p>This is the flow a human actually uses when the watcher says
 * {@code new mod detected on server: …; run /modreloader add … in the console}:
 * the jar sits in {@code mods/}, the operator types a <em>short name</em>, and
 * TAB completion offers the names. The harness reproduces exactly that:
 *
 * <ol>
 *   <li>copy the jar into {@code <gamedir>/mods} (as if dropped there),</li>
 *   <li>ask Brigadier for completions of {@code modreloader add } and log them,</li>
 *   <li>run {@code modreloader add <file name>} through the server's own command
 *       manager (the same path the console uses),</li>
 *   <li>assert the mod is in {@code ModList}, then remove the copied jar.</li>
 * </ol>
 *
 * <p>{@code -PserverAddCommand=<jar>}. The jar is only used as a source; the
 * copy is deleted afterwards so it does not leak into later runs.
 */
public final class ServerAddCmdCheck {
    private static final int START_DELAY_TICKS = 60;
    private static final int TIMEOUT_TICKS = 20 * 120;

    private static Path source;
    private static Path copied;
    private static MinecraftServer server;
    private static boolean armed;
    private static boolean serverUp;
    private static int ticks;

    private ServerAddCmdCheck() {
    }

    public static void start(String jarPath) {
        if (armed) {
            return;
        }
        source = Path.of(jarPath).toAbsolutePath().normalize();
        armed = true;
        ModReloader.LOGGER.info("[ServerAddCmd] armed with {}", source);
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
            ModReloader.LOGGER.error("[ServerAddCmd] TIMEOUT");
            ModReloader.LOGGER.error("[ServerAddCmd] RESULT: FAIL");
            armed = false;
            return;
        }
        if (ticks < START_DELAY_TICKS) {
            return;
        }
        armed = false;
        run();
    }

    private static void run() {
        boolean ok = true;
        try {
            Path modsDir = FMLPaths.MODSDIR.get();
            Files.createDirectories(modsDir);
            copied = modsDir.resolve(source.getFileName().toString());
            Files.copy(source, copied, StandardCopyOption.REPLACE_EXISTING);
            ModReloader.LOGGER.info("[ServerAddCmd] dropped {} into {}", copied.getFileName(), modsDir);

            String typed = copied.getFileName().toString();
            CommandSourceStack source = server.createCommandSourceStack();

            // The console flow the watcher advertises: scan → short name → add.
            int scanned = server.getCommands().getDispatcher().execute("modreloader scan", source);
            ModReloader.LOGGER.info("[ServerAddCmd] /modreloader scan returned {} (its lines are in "
                    + "the log above/below with the ready-to-type hint)", scanned);
            ok &= expect(scanned >= 1, "scan did not list the dropped jar");

            int missing = server.getCommands().getDispatcher()
                    .execute("modreloader add definitely-not-here-1.0.jar", source);
            ModReloader.LOGGER.info("[ServerAddCmd] /modreloader add definitely-not-here-1.0.jar "
                    + "returned {} (0 = not found, message in the log)", missing);
            ok &= expect(missing == 0, "an unknown name must not be loaded");

            List<String> suggestions = completions(source, "modreloader add ");
            ModReloader.LOGGER.info("[ServerAddCmd] TAB completion for 'modreloader add ': {}", suggestions);
            ok &= expect(suggestions.contains(typed),
                    "completion list does not offer " + typed + " -> " + suggestions);

            // A jar hot-add is an operator action on a shared server: console (4)
            // and OPs (2+) may run it, a regular player (0) must not.
            CommandSourceStack player = server.createCommandSourceStack()
                    .withPermission(0).withSuppressedOutput();
            boolean denied;
            try {
                denied = server.getCommands().getDispatcher()
                        .execute("modreloader ping", player) == 0;
            } catch (Throwable deniedSyntax) {
                denied = true;
            }
            ModReloader.LOGGER.info("[ServerAddCmd] permission gate: permission-0 source denied={}",
                    denied);
            ok &= expect(denied, "a permission-0 source must not run /modreloader");

            // The console ends up in the same place: a Brigadier parse + execute on
            // the server's dispatcher with the console command source.
            String command = "modreloader add " + typed;
            int result = server.getCommands().getDispatcher().execute(command, source);
            ModReloader.LOGGER.info("[ServerAddCmd] /{} returned {}", command, result);

            String modId = modIdOf(copied);
            boolean loaded = modId != null && ModList.get().isLoaded(modId);
            ModReloader.LOGGER.info("[ServerAddCmd] ModList {}: isLoaded={}", modId, loaded);
            ok &= expect(loaded, "mod was not loaded after the console command");
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ServerAddCmd] threw", t);
            ok = false;
        } finally {
            cleanup();
        }
        ModReloader.LOGGER.info("[ServerAddCmd] RESULT: {}", ok ? "PASS" : "FAIL");
    }

    private static List<String> completions(CommandSourceStack source, String command) {
        try {
            var parse = server.getCommands().getDispatcher().parse(command, source);
            Suggestions suggestions = server.getCommands().getDispatcher()
                    .getCompletionSuggestions(parse)
                    .get(5, TimeUnit.SECONDS);
            return suggestions.getList().stream().map(s -> s.getText()).toList();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ServerAddCmd] cannot compute completions", t);
            return List.of();
        }
    }

    private static String modIdOf(Path jar) {
        try {
            dev.modreloader.inspect.ModInspection inspection =
                    dev.modreloader.inspect.ModInspector.inspect(jar);
            return inspection.ok() && !inspection.mods().isEmpty()
                    ? inspection.mods().get(0).modId()
                    : null;
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ServerAddCmd] cannot inspect {}", jar, t);
            return null;
        }
    }

    private static void cleanup() {
        if (copied == null) {
            return;
        }
        try {
            Files.deleteIfExists(copied);
            ModReloader.LOGGER.info("[ServerAddCmd] removed the copied jar again ({})", copied.getFileName());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ServerAddCmd] cannot delete {}; delete it by hand", copied, t);
        }
    }

    private static boolean expect(boolean condition, String failure) {
        if (!condition) {
            ModReloader.LOGGER.error("[ServerAddCmd] FAIL: {}", failure);
        }
        return condition;
    }
}
