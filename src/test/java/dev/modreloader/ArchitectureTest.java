package dev.modreloader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Pins the package layering the engine relies on.
 *
 * <p>Bottom-up: {@code reflect} (no engine deps) → {@code inspect} → {@code capability}
 * / {@code i18n} / {@code launch} / {@code watch} / {@code command} → {@code hotadd} →
 * {@code client} / {@code server} → {@code ui}/{@code diagnose}. The engine must not
 * depend on the sides; the UI must not depend on the client services it sits on top
 * of. These edges used to be real cycles; this test fails on a new one instead of
 * letting it rot until a refactor hurts.
 *
 * <p>Source scan, not reflection: the point is the import statements, and this keeps
 * the test runnable without the game.
 */
class ArchitectureTest {
    private static final Path SOURCE = Path.of("src/main/java/dev/modreloader");

    private static final Pattern PACKAGE = Pattern.compile("^package (dev\\.modreloader[\\w.]*);", Pattern.MULTILINE);
    private static final Pattern IMPORT = Pattern.compile("^import (dev\\.modreloader\\.[\\w.]+);", Pattern.MULTILINE);

    /** For each layer: packages it must not import from. */
    private static final Map<String, Set<String>> FORBIDDEN = Map.ofEntries(
            entry("reflect", "inspect", "capability", "i18n", "launch", "watch", "command",
                    "hotadd", "client", "server", "ui", "diagnose"),
            entry("inspect", "capability", "launch", "watch", "command", "hotadd",
                    "client", "server", "ui", "diagnose"),
            entry("capability", "launch", "command", "hotadd", "client", "server", "ui",
                    "diagnose", "watch"),
            entry("i18n", "command", "hotadd", "client", "server", "ui", "diagnose",
                    "watch", "launch", "capability"),
            entry("launch", "command", "hotadd", "client", "server", "ui", "diagnose",
                    "watch", "capability"),
            entry("watch", "command", "hotadd", "client", "server", "ui", "diagnose",
                    "launch", "capability"),
            entry("diagnose", "command", "client", "server", "ui", "watch", "launch"),
            // The engine is side- and frontend-neutral: PlatformHooks is the only door.
            entry("hotadd", "command", "client", "server", "ui", "diagnose", "watch"),
            entry("command", "client", "server", "ui"),
            entry("client", "server"),
            entry("server", "client"),
            entry("ui", "client", "server", "command"));

    private static Map.Entry<String, Set<String>> entry(String layer, String... forbidden) {
        return Map.entry(layer, Set.of(forbidden));
    }

    @Test
    void packagesOnlyDependDownwards() throws IOException {
        if (!Files.isDirectory(SOURCE)) {
            fail("source root not found: " + SOURCE.toAbsolutePath()
                    + " (run the test from the project directory)");
        }
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                check(file, violations);
            }
        }
        assertTrue(violations.isEmpty(),
                "forbidden package dependencies:\n  " + String.join("\n  ", violations));
    }

    private static void check(Path file, List<String> violations) throws IOException {
        String text = Files.readString(file);
        Matcher pkg = PACKAGE.matcher(text);
        if (!pkg.find()) {
            return;
        }
        String[] parts = pkg.group(1).split("\\.");
        if (parts.length < 3) {
            return; // dev.modreloader.ModReloader / Platform: entry points, no layer
        }
        String layer = parts[2];
        Set<String> forbidden = FORBIDDEN.get(layer);
        if (forbidden == null) {
            return;
        }
        Matcher imports = IMPORT.matcher(text);
        while (imports.find()) {
            String[] target = imports.group(1).split("\\.");
            if (target.length > 2 && forbidden.contains(target[2])) {
                violations.add(file + " (" + layer + ") imports " + imports.group(1));
            }
        }
    }
}