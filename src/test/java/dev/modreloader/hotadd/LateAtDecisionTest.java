package dev.modreloader.hotadd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "prepare once" contract: late AT follows enhanced JBR unless the user
 * explicitly overrides it. A missing/blank property must not become a second
 * switch that the player has to discover.
 */
class LateAtDecisionTest {
    @Test
    void enhancedJbrEnablesLateAtWithoutTheProperty() {
        assertTrue(LateAccessTransformers.lateAtEnabled(null, true));
        assertTrue(LateAccessTransformers.lateAtEnabled(" ", true));
    }

    @Test
    void stockStaysOffWithoutTheProperty() {
        assertFalse(LateAccessTransformers.lateAtEnabled(null, false));
        assertFalse(LateAccessTransformers.lateAtEnabled("", false));
    }

    @Test
    void explicitPropertyWinsInBothDirections() {
        assertTrue(LateAccessTransformers.lateAtEnabled("true", false));
        assertFalse(LateAccessTransformers.lateAtEnabled("false", true));
    }
}
