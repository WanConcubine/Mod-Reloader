package dev.modreloader.inspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "standalone" distribution wrapper (MezzConfig): no mods.toml on the
 * outside, one nested jar that is a mod. At startup FML unwraps it; a hot-add
 * must resolve to the same nested jar instead of refusing the wrapper.
 */
class WrapperJarsTest {
    @TempDir
    Path dir;

    @Test
    void detectsAWrapperAndReadsTheNestedModId() throws IOException {
        Path wrapper = zip(dir.resolve("wrapper.jar"), zip -> {
            entry(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nFMLModType: GAMELIBRARY\r\n");
            entry(zip, WrapperJars.METADATA,
                    "{\"jars\":[{\"path\":\"META-INF/jarjar/inner.jar\"}]}");
            entry(zip, "META-INF/jarjar/inner.jar", nestedMod("mezz_config", "0.7.1"));
        });

        assertTrue(WrapperJars.isWrapper(wrapper));
        WrapperJars.Nested nested = WrapperJars.nestedMod(wrapper);
        assertNotNull(nested);
        assertEquals("META-INF/jarjar/inner.jar", nested.nestedPath());
        assertEquals(List.of("mezz_config"), WrapperJars.nestedModIds(wrapper));
    }

    @Test
    void aJarWithItsOwnModsIsNotAWrapper() throws IOException {
        Path jar = zip(dir.resolve("mod.jar"), zip -> {
            entry(zip, "META-INF/neoforge.mods.toml", modsToml("jei", "19.71.0"));
            entry(zip, WrapperJars.METADATA, "{\"jars\":[{\"path\":\"inner.jar\"}]}");
            entry(zip, "inner.jar", nestedMod("mezz_config", "0.7.1"));
        });

        assertFalse(WrapperJars.isWrapper(jar));
        assertNull(WrapperJars.nestedMod(jar));
        assertEquals(List.of("mezz_config"), WrapperJars.nestedModIds(jar));
    }

    @Test
    void aPlainLibraryWithOnlyANonModNestedJarIsNotAWrapper() throws IOException {
        Path jar = zip(dir.resolve("lib.jar"), zip -> {
            entry(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nFMLModType: GAMELIBRARY\r\n");
            entry(zip, WrapperJars.METADATA, "{\"jars\":[{\"path\":\"inner.jar\"}]}");
            entry(zip, "inner.jar", zipBytes(zip2 -> entry(zip2, "a/b/C.class", new byte[]{1})));
        });

        assertFalse(WrapperJars.isWrapper(jar));
        assertTrue(WrapperJars.nestedModIds(jar).isEmpty());
    }

    @Test
    void aJarWithoutJarJarIsNotAWrapper() throws IOException {
        Path jar = zip(dir.resolve("plain.jar"), zip ->
                entry(zip, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n"));

        assertFalse(WrapperJars.isWrapper(jar));
    }

    private static byte[] nestedMod(String modId, String version) throws IOException {
        return zipBytes(zip -> entry(zip, "META-INF/neoforge.mods.toml", modsToml(modId, version)));
    }

    private static String modsToml(String modId, String version) {
        return "modLoader=\"javafml\"\nloaderVersion=\"[4,)\"\n\n[[mods]]\nmodId=\"" + modId
                + "\"\nversion=\"" + version + "\"\ndisplayName=\"" + modId + "\"\n";
    }

    private interface ZipBody {
        void write(ZipOutputStream zip) throws IOException;
    }

    private static Path zip(Path path, ZipBody body) throws IOException {
        try (OutputStream out = Files.newOutputStream(path); ZipOutputStream zip = new ZipOutputStream(out)) {
            body.write(zip);
        }
        return path;
    }

    private static byte[] zipBytes(ZipBody body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            body.write(zip);
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        entry(zip, name, text.getBytes(StandardCharsets.UTF_8));
    }

    private static void entry(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}