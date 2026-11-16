package dev.modreloader.command;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console only lets an operator type, so {@code /modreloader add} accepts
 * short names. The search order is the part that can silently pick the wrong
 * file, hence a game-free test.
 */
class JarPathResolverTest {
    @Test
    void absolutePathWins(@TempDir Path temp) throws Exception {
        Path jar = Files.writeString(temp.resolve("absolute-1.0.jar"), "x");
        JarPathResolver.Resolution resolution =
                JarPathResolver.resolve(jar.toAbsolutePath().toString(), List.of());
        assertTrue(resolution.found());
        assertEquals(jar.toAbsolutePath(), resolution.file());
        assertEquals(1, resolution.searched().size());
    }

    @Test
    void bareNameIsLookedUpInBaseDirectoriesInOrder(@TempDir Path mods, @TempDir Path inbox)
            throws Exception {
        Path inInbox = Files.writeString(inbox.resolve("only-in-inbox.jar"), "x");
        Files.writeString(mods.resolve("in-both.jar"), "x");
        Files.writeString(inbox.resolve("in-both.jar"), "x");

        // First hit wins: mods before inbox.
        assertEquals(mods.resolve("in-both.jar").normalize(),
                JarPathResolver.resolve("in-both.jar", List.of(mods, inbox)).file());
        // Falls through to the next directory.
        assertEquals(inInbox.normalize(),
                JarPathResolver.resolve("only-in-inbox.jar", List.of(mods, inbox)).file());
    }

    @Test
    void relativeSubPathIsNotSearchedInBaseDirectories(@TempDir Path mods) throws Exception {
        Files.createDirectories(mods.resolve("nested"));
        Files.writeString(mods.resolve("nested/only.jar"), "x");
        // "nested/only.jar" is a path, not a bare name: it must not resolve
        // against mods/ (that would load a file the operator did not name).
        JarPathResolver.Resolution resolution = JarPathResolver.resolve("nested/only.jar", List.of(mods));
        assertFalse(resolution.found());
        assertNull(resolution.file());
    }

    @Test
    void missingJarReportsEveryLocationTried(@TempDir Path mods, @TempDir Path inbox) {
        JarPathResolver.Resolution resolution =
                JarPathResolver.resolve("nowhere.jar", List.of(mods, inbox));
        assertFalse(resolution.found());
        assertEquals(3, resolution.searched().size(), "cwd + two base dirs");
        assertTrue(resolution.searched().get(1).endsWith("nowhere.jar"));
        assertTrue(resolution.searched().get(2).endsWith("nowhere.jar"));
    }

    @Test
    void quotesAreStripped(@TempDir Path mods) throws Exception {
        Path jar = Files.writeString(mods.resolve("with space.jar"), "x");
        JarPathResolver.Resolution resolution =
                JarPathResolver.resolve("\"with space.jar\"", List.of(mods));
        assertTrue(resolution.found());
        assertEquals(jar.normalize(), resolution.file());
    }

    @Test
    void emptyInputIsNotFound(@TempDir Path mods) {
        assertFalse(JarPathResolver.resolve("", List.of(mods)).found());
        assertFalse(JarPathResolver.resolve("   ", List.of(mods)).found());
    }
}
