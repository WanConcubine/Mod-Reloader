package dev.modreloader.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The jar a managed relaunch should bring back to the user.
 *
 * <p>A relaunch clears the "failed" fingerprint so the jar can be a candidate
 * again, but the prompt would still open on whatever sorts first. This one-line
 * file remembers which jar the user was retrying, so the new session can
 * preselect it instead of making them find it again.
 *
 * <p>A file, not a static: the replacement JVM shares nothing with this one.
 * {@code take()} deletes it, so a crash before the prompt cannot resurrect a
 * stale retry forever.
 */
public final class PendingAdd {
    public static final String FILE = "pending-add.txt";

    private PendingAdd() {
    }

    public static Path file(Path gameDir) {
        return gameDir.resolve("modreloader").resolve(FILE);
    }

    /** @return false when the file could not be written; the relaunch still works */
    public static boolean write(Path gameDir, Path jar) {
        try {
            Path file = file(gameDir);
            Files.createDirectories(file.getParent());
            Files.writeString(file, jar.toAbsolutePath().normalize() + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static Optional<Path> read(Path gameDir) {
        try {
            Path file = file(gameDir);
            if (!Files.isRegularFile(file)) {
                return Optional.empty();
            }
            String text = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (text.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(Path.of(text).toAbsolutePath().normalize());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Reads and deletes: the caller owns the retry from here on. */
    public static Optional<Path> take(Path gameDir) {
        Optional<Path> pending = read(gameDir);
        clear(gameDir);
        return pending;
    }

    public static void clear(Path gameDir) {
        try {
            Files.deleteIfExists(file(gameDir));
        } catch (IOException | RuntimeException ignored) {
            // A stale file is harmless: it is only a hint for the next session.
        }
    }
}
