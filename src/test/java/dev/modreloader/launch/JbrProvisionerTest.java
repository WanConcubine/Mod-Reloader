package dev.modreloader.launch;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.modreloader.launch.JbrProvisioner;

class JbrProvisionerTest {
    @Test
    void everyPinnedAssetHasARealSha512() {
        List<JbrProvisioner.Asset> assets = JbrProvisioner.assets();
        assertEquals(5, assets.size());
        for (JbrProvisioner.Asset asset : assets) {
            assertEquals(128, asset.sha512().length(), asset.name());
            assertTrue(asset.sha512().matches("[0-9a-f]{128}"), asset.name());
            assertTrue(asset.name().contains("21.0.11"), asset.name());
        }
    }

    @Test
    void downloadUrlIsTheOfficialJetBrainsRedirector() {
        JbrProvisioner.Asset asset = new JbrProvisioner.Asset("jbr-test.zip", "0".repeat(128), true);
        assertEquals("https://cache-redirector.jetbrains.com/intellij-jbr/jbr-test.zip",
                JbrProvisioner.downloadUrl(asset));
    }

    @Test
    void installDirIsADirectChildOfTheGameDir() {
        assertEquals(Path.of("/tmp/game/jbr-21.0.11"),
                JbrProvisioner.installDir(Path.of("/tmp/game")));
    }

    @Test
    void thisPlatformHasAPinnedBuildOrSaysSo() {
        // aarch64 macOS (this repo's dev box) must be pinned; Windows/Linux too.
        boolean supported = JbrProvisioner.asset().isPresent();
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")
                && !System.getProperty("os.arch", "").contains("aarch64")
                && !System.getProperty("os.arch", "").contains("arm64")) {
            Assumptions.assumeFalse(supported, "Intel macOS intentionally has no auto-download asset");
        } else {
            assertTrue(supported, "expected a pinned asset on this platform");
            assertFalse(JbrProvisioner.asset().get().name().isBlank());
        }
    }
}
