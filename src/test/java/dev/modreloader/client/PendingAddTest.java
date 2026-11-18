package dev.modreloader.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pending jar is the only thing that survives a managed relaunch, so its
 * file format has to be boring: one normalized path, read once, then deleted.
 */
class PendingAddTest {
    @TempDir
    Path gameDir;

    @Test
    void writeReadAndTake() throws Exception {
        Path jar = gameDir.resolve("mods").resolve("Some Mod-1.0.jar");
        Files.createDirectories(jar.getParent());
        Files.writeString(jar, "stub");

        assertTrue(PendingAdd.write(gameDir, jar));
        Path expected = jar.toAbsolutePath().normalize();
        assertEquals(Optional.of(expected), PendingAdd.read(gameDir));

        // take() consumes: the next session must not retry forever.
        assertEquals(Optional.of(expected), PendingAdd.take(gameDir));
        assertFalse(Files.exists(PendingAdd.file(gameDir)));
        assertTrue(PendingAdd.read(gameDir).isEmpty());
    }

    @Test
    void missingOrBlankFileIsIgnored() throws Exception {
        assertTrue(PendingAdd.read(gameDir).isEmpty());

        Files.createDirectories(PendingAdd.file(gameDir).getParent());
        Files.writeString(PendingAdd.file(gameDir), "   \n");
        assertTrue(PendingAdd.read(gameDir).isEmpty());
        assertTrue(PendingAdd.take(gameDir).isEmpty());
        assertFalse(Files.exists(PendingAdd.file(gameDir)));
    }

    @Test
    void clearIsIdempotent() {
        PendingAdd.clear(gameDir);
        PendingAdd.clear(gameDir);
        assertFalse(Files.exists(PendingAdd.file(gameDir)));
    }
}
