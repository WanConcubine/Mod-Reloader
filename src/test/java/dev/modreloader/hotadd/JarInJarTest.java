package dev.modreloader.hotadd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Guards the jar-in-jar path handling: {@code META-INF/jarjar/metadata.json} is
 * attacker-controlled as far as this runtime is concerned, so a declared path must
 * never escape the outer jar, and the extracted file name must stay a plain file
 * name. No game is needed — only the two pure helpers are exercised.
 */
class JarInJarTest {
    @Test
    void acceptsPlainJarPathsInsideTheJar() {
        assertEquals("META-INF/jarjar/PinIn-1.6.0.jar",
                JarInJar.checkedPath("META-INF/jarjar/PinIn-1.6.0.jar"));
        assertEquals("META-INF/jarjar/PinIn-1.6.0.jar",
                JarInJar.checkedPath("META-INF\\jarjar\\PinIn-1.6.0.jar"));
        assertEquals("a.jar", JarInJar.checkedPath("  a.jar  "));
    }

    @Test
    void rejectsEscapingOrNonJarPaths() {
        assertNull(JarInJar.checkedPath(null));
        assertNull(JarInJar.checkedPath(""));
        assertNull(JarInJar.checkedPath("/etc/evil.jar"), "absolute path");
        assertNull(JarInJar.checkedPath("../../evil.jar"), "parent traversal");
        assertNull(JarInJar.checkedPath("a/../../evil.jar"), "nested traversal");
        assertNull(JarInJar.checkedPath("C:/evil.jar"), "drive letter");
        assertNull(JarInJar.checkedPath("META-INF/jarjar/notes.txt"), "not a jar");
        assertNull(JarInJar.checkedPath("META-INF/jarjar/pinin.jar/"), "directory");
    }

    @Test
    void extractedFileNameIsTheLastSegmentAndSafe() {
        assertEquals("PinIn-1.6.0.jar", JarInJar.fileNameOf("META-INF/jarjar/PinIn-1.6.0.jar"));
        assertEquals("a.jar", JarInJar.fileNameOf("a.jar"));
        // A path can only get here after checkedPath(), but keep names plain anyway.
        assertEquals("weird_name.jar", JarInJar.fileNameOf("dir/weird name.jar"));
        assertEquals("nested.jar", JarInJar.fileNameOf("dir/nested"));
    }
}
