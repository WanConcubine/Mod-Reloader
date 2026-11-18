package dev.modreloader.hotadd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the jar-in-jar path handling: {@code META-INF/jarjar/metadata.json} is
 * attacker-controlled as far as this runtime is concerned, so a declared path must
 * never escape the outer jar, and the extracted file name must stay a plain file
 * name. No game is needed — only the two pure helpers are exercised.
 */
class JarInJarTest {
    @TempDir
    Path root;

    @Test
    void modsTomlNestedJarIsAMod() throws IOException {
        Path jar = nestedJar("Manifest-Version: 1.0\n\n", "META-INF/neoforge.mods.toml");
        assertTrue(JarInJar.declaresMod(jar));
    }

    @Test
    void gameLibraryNestedJarIsALibraryEvenWithModsToml() throws IOException {
        // Supplementaries bundles MixinSquared this way; treating it as a mod
        // produced DefaultModFileInfo -> ModFileInfo ClassCastException.
        Path jar = nestedJar("Manifest-Version: 1.0\nFMLModType: GAMELIBRARY\n\n",
                "META-INF/mods.toml");
        assertFalse(JarInJar.declaresMod(jar));
    }

    @Test
    void nestedJarWithoutMetadataIsALibrary() throws IOException {
        Path jar = nestedJar("Manifest-Version: 1.0\n\n");
        assertFalse(JarInJar.declaresMod(jar));
    }

    @Test
    void manifestMixinConfigsAreReadAndSplit() throws IOException {
        Path jar = nestedJar("Manifest-Version: 1.0\nMixinConfigs: a.mixins.json, b.mixins.json\n\n");
        assertEquals(List.of("a.mixins.json", "b.mixins.json"),
                JarInJar.manifestMixinConfigs(jar));
    }

    private Path nestedJar(String manifest, String... entries) throws IOException {
        Path jar = Files.createTempFile(root, "nested", ".jar");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar))) {
            if (manifest != null) {
                out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                out.write(manifest.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
            for (String entry : entries) {
                out.putNextEntry(new ZipEntry(entry));
                out.closeEntry();
            }
        }
        return jar;
    }

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
