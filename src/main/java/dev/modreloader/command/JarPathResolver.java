package dev.modreloader.command;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns what an operator typed into a jar path.
 *
 * <p>The console is a hostile input device: you cannot drag a file, you cannot
 * browse, and the watcher's hint used to demand the full absolute path
 * ({@code /modreloader add /home/me/server/mods/foo-1.0.jar}). That is one typo
 * per attempt. This resolver accepts, in order:
 *
 * <ol>
 *   <li>an absolute path,</li>
 *   <li>a path relative to the working directory (the server's game dir),</li>
 *   <li>a bare file name looked up in the given base directories — for the
 *       console the mods folder and the inbox, in that order,</li>
 *   <li>anything above wrapped in double quotes (greedy string arguments keep
 *       them).</li>
 * </ol>
 *
 * <p>Plain {@link java.nio} on purpose: the search order is the part that can be
 * wrong, so it is unit-tested without a game.
 */
public final class JarPathResolver {
    /**
     * @param file     the resolved jar, or {@code null} when nothing matched
     * @param searched every candidate tried, in order, for the error message
     */
    public record Resolution(Path file, List<Path> searched) {
        public boolean found() {
            return file != null;
        }
    }

    private JarPathResolver() {
    }

    /**
     * @param raw      what the user typed
     * @param baseDirs directories searched for a bare file name, in order
     */
    public static Resolution resolve(String raw, List<Path> baseDirs) {
        List<Path> searched = new ArrayList<>();
        String trimmed = unquote(raw);
        if (trimmed.isEmpty()) {
            return new Resolution(null, searched);
        }

        Path typed = Path.of(trimmed);
        Path direct = typed.isAbsolute()
                ? typed
                : Path.of("").toAbsolutePath().resolve(typed);
        direct = direct.normalize();
        searched.add(direct);
        if (Files.isRegularFile(direct)) {
            return new Resolution(direct, searched);
        }

        // Only a bare name can be looked up by directory; "sub/foo.jar" stays a
        // path so a typo cannot silently pick an unrelated file from mods/.
        if (!typed.isAbsolute() && typed.getNameCount() == 1) {
            for (Path base : baseDirs) {
                Path candidate = base.resolve(trimmed).normalize();
                if (searched.contains(candidate)) {
                    continue;
                }
                searched.add(candidate);
                if (Files.isRegularFile(candidate)) {
                    return new Resolution(candidate, searched);
                }
            }
        }
        return new Resolution(null, searched);
    }

    private static String unquote(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        }
        return trimmed;
    }
}
