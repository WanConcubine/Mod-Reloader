package dev.modreloader.inspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Game-library detection is metadata-only, so it can be tested without a game.
 *
 * <p>Two shapes matter: the manifest attribute (MixinSquared) and the toml
 * {@code modType = "LIBRARY"} (codecui, which Moonlight bundles). Treating the
 * latter as a mod made the nested-jar stage refuse Moonlight for a feature that
 * is not there.
 */
class ModInspectorTest {
    @Test
    void manifestFmlModTypeLibraryIsAGameLibrary(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("manifest-library.jar");
        writeJar(jar, Map.of(
                "META-INF/MANIFEST.MF",
                "Manifest-Version: 1.0\nFMLModType: GAMELIBRARY\n"));
        assertTrue(ModInspector.isGameLibrary(jar));
    }

    @Test
    void tomlModTypeLibraryIsAGameLibrary(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("toml-library.jar");
        writeJar(jar, Map.of(
                "META-INF/neoforge.mods.toml",
                "modLoader = \"javafml\"\nmodType = \"LIBRARY\"\n\n[[mods]]\nmodId = \"codecui\"\n"));
        assertTrue(ModInspector.isGameLibrary(jar));
    }

    @Test
    void aNormalModIsNotALibrary(@TempDir Path dir) throws IOException {
        Path jar = dir.resolve("mod.jar");
        writeJar(jar, Map.of(
                "META-INF/neoforge.mods.toml",
                "modLoader = \"javafml\"\n\n[[mods]]\nmodId = \"demo\"\n"));
        assertFalse(ModInspector.isGameLibrary(jar));
    }

    private static void writeJar(Path jar, Map<String, String> entries) throws IOException {
        try (OutputStream out = Files.newOutputStream(jar);
             ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }
}