package dev.modreloader.launch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import dev.modreloader.launch.RelaunchFlags;

/**
 * The relaunch flags decide whether the replacement JVM can open the experimental
 * late-AT path at all. Pure so it runs on every build, without a game.
 */
class RelaunchFlagsTest {
    @Test
    void stockJvmNeedsTheEnhancedFlag() {
        assertEquals(List.of(RelaunchFlags.ENHANCED_REDEFINITION),
                RelaunchFlags.jvmFlags(false, false));
    }

    @Test
    void lateAtAddsItsProperty() {
        assertEquals(List.of(RelaunchFlags.ENHANCED_REDEFINITION, RelaunchFlags.LATE_AT),
                RelaunchFlags.jvmFlags(false, true));
    }

    @Test
    void jbrWithEnhancedRedefinitionOnlyAddsLateAt() {
        assertEquals(List.of(RelaunchFlags.LATE_AT),
                RelaunchFlags.jvmFlags(true, true));
    }

    @Test
    void nothingToAddWhenNothingIsNeeded() {
        assertTrue(RelaunchFlags.jvmFlags(true, false).isEmpty());
    }
}
