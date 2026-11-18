package dev.modreloader.inspect;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JBR detection must find a runtime unpacked next to the game directory, and must
 * prefer Java 21 when a newer runtime happens to sit there too (Minecraft 1.21.1
 * is only validated on 21).
 */
class JbrSupportTest {
    @TempDir
    Path root;

    @Test
    void reusesTheRunningJbrHome() throws Exception {
        Path home = Files.createDirectory(root.resolve("jbr-21.0.11"));
        createJbr(home, "21.0.11");
        assertEquals(Optional.of(home), JbrSupport.runningHome(home.toString(), true));
        // Not a JBR: never treat the running JVM as the JBR to restart with.
        assertTrue(JbrSupport.runningHome(home.toString(), false).isEmpty());
    }

    @Test
    void recognisesJbrFromVendorOrHome() {
        assertTrue(JbrSupport.looksLikeJbr("JetBrains s.r.o.", "", "", "/opt/java"));
        assertTrue(JbrSupport.looksLikeJbr("Eclipse Adoptium", "", "", "/opt/jbr-21.0.11"));
        assertTrue(JbrSupport.looksLikeJbr("", "JetBrains s.r.o.", "", "/x"));
        assertFalse(JbrSupport.looksLikeJbr("Eclipse Adoptium", "Eclipse Adoptium",
                "OpenJDK 64-Bit Server VM", "/opt/jdk-21"));
    }

    @Test
    void enhancedFlagIsDetectedInArguments() {
        assertTrue(JbrSupport.hasEnhancedFlag(
                List.of("-Xmx2G", "-XX:+AllowEnhancedClassRedefinition")));
        assertFalse(JbrSupport.hasEnhancedFlag(List.of("-Xmx2G")));
    }

    @Test
    void findsARuntimeInsideTheGameDirectory() throws Exception {
        Path gameDir = Files.createDirectory(root.resolve(".minecraft"));
        createJbr(Files.createDirectory(gameDir.resolve("jbr-21.0.11")), "21.0.11");
        assertEquals(Optional.of(gameDir.resolve("jbr-21.0.11")),
                JbrSupport.findInstall(List.of(gameDir)));
    }

    @Test
    void findsARuntimeNextToTheGameDirectory() throws Exception {
        Path gameDir = Files.createDirectory(root.resolve(".minecraft"));
        createJbr(Files.createDirectory(root.resolve("jbr-21.0.11")), "21.0.11");
        assertEquals(Optional.of(root.resolve("jbr-21.0.11")),
                JbrSupport.findInstall(List.of(root)));
        // The game dir itself has no JBR child; the parent does.
        assertTrue(JbrSupport.findInstall(List.of(gameDir)).isEmpty());
    }

    @Test
    void prefersJava21OverANewerRuntime() throws Exception {
        createJbr(Files.createDirectory(root.resolve("jbr-25.0.5")), "25.0.5");
        createJbr(Files.createDirectory(root.resolve("jbr-21.0.11")), "21.0.11");
        assertEquals(Optional.of(root.resolve("jbr-21.0.11")),
                JbrSupport.findInstall(List.of(root)));
    }

    @Test
    void ignoresANewerJbrWhenNoJava21IsPresent() throws Exception {
        createJbr(Files.createDirectory(root.resolve("jbr-25.0.5")), "25.0.5");
        assertTrue(JbrSupport.findInstall(List.of(root)).isEmpty());
    }

    @Test
    void findsAZipWrapperLayout() throws Exception {
        // zip often extracts as jbr-21.0.11-windows-x64/jbr-21.0.11/release
        Path wrapper = Files.createDirectory(root.resolve("jbr-21.0.11-windows-x64"));
        createJbr(Files.createDirectory(wrapper.resolve("jbr-21.0.11")), "21.0.11");
        assertEquals(Optional.of(wrapper.resolve("jbr-21.0.11")),
                JbrSupport.findInstall(List.of(root)));
    }

    @Test
    void ancestorRootsCoverVersionIsolatedGameDirs() throws Exception {
        Path gameDir = root.resolve(".minecraft").resolve("versions").resolve("1.21.1");
        Files.createDirectories(gameDir);
        createJbr(Files.createDirectory(root.resolve("jbr-21.0.11")), "21.0.11");
        List<Path> roots = JbrSupport.ancestorRoots(gameDir);
        assertTrue(roots.contains(gameDir));
        assertTrue(roots.contains(root.resolve(".minecraft")));
        assertEquals(Optional.of(root.resolve("jbr-21.0.11")),
                JbrSupport.findInstall(roots));
    }

    @Test
    void explicitOverrideWins() throws Exception {
        Path home = Files.createDirectory(root.resolve("custom-jbr"));
        createJbr(home, "21.0.11");
        System.setProperty("modreloader.jbr", home.toString());
        try {
            assertEquals(Optional.of(home), JbrSupport.installation());
        } finally {
            System.clearProperty("modreloader.jbr");
        }
    }

    @Test
    void ignoresDirectoriesWithoutAReleaseFile() throws Exception {
        Files.createDirectory(root.resolve("jbr-21.0.11"));
        assertTrue(JbrSupport.findInstall(List.of(root)).isEmpty());
    }

    private static void createJbr(Path home, String version) throws Exception {
        Files.writeString(home.resolve("release"),
                "IMPLEMENTOR=\"JetBrains s.r.o.\"\nJAVA_VERSION=\"" + version + "\"\n");
        Files.createDirectories(home.resolve("bin"));
    }
}
