package dev.modreloader.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JbrProvisionerArchiveTest {
    @Test
    void installsAFlatZipAndFindsTheHome() throws Exception {
        Path game = Files.createTempDirectory("jbr-game");
        Path zip = game.resolve("jbr.zip");
        writeZip(zip, "jbr-test", false);
        Path home = JbrProvisioner.installArchive(game, zip,
                new JbrProvisioner.Asset("jbr.zip", "0".repeat(128), true), message -> { });
        assertEquals(game.resolve("jbr-21.0.11"), home);
        assertTrue(JbrProvisioner.isRuntimeHome(home));
        assertTrue(Files.isRegularFile(home.resolve("bin").resolve("java")));
    }

    @Test
    void installsAMacBundleLayout() throws Exception {
        Path game = Files.createTempDirectory("jbr-game-bundle");
        Path zip = game.resolve("jbr.zip");
        writeZip(zip, "jbr-bundle", true);
        Path home = JbrProvisioner.installArchive(game, zip,
                new JbrProvisioner.Asset("jbr.zip", "0".repeat(128), true), message -> { });
        assertTrue(JbrProvisioner.isRuntimeHome(home));
        assertTrue(Files.isRegularFile(home.resolve("release")));
    }

    @Test
    void verifyAcceptsThePinnedHashAndRejectsAnother() throws Exception {
        Path file = Files.createTempFile("jbr-verify", ".zip");
        Files.writeString(file, "jbr");
        String hash = sha512(file);
        JbrProvisioner.verify(file, new JbrProvisioner.Asset("x.zip", hash, true));
        assertThrows(IOException.class, () -> JbrProvisioner.verify(file,
                new JbrProvisioner.Asset("x.zip", "f".repeat(128), true)));
    }

    private static String sha512(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
    }

    private static void writeZip(Path zip, String root, boolean bundle) throws IOException {
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            String base = bundle ? root + "/Contents/Home/" : root + "/";
            entry(zos, base + "release", "JAVA_VERSION=\"21.0.11\"\n");
            entry(zos, base + "bin/java", "#!/bin/sh\n");
        }
    }

    private static void entry(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
