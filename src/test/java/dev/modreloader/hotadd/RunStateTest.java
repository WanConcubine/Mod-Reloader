package dev.modreloader.hotadd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The dirty decision is pure, so it is pinned here without a game. */
class RunStateTest {
    private static RunState.Flags flags(boolean rollbackPartial, boolean registry, boolean custom,
                                        boolean constructed, boolean mixins, boolean payloads,
                                        boolean capabilities, boolean commands) {
        return new RunState.Flags(rollbackPartial, registry, custom, constructed, mixins,
                payloads, capabilities, commands);
    }

    private static final RunState.Flags CLEAN =
            flags(false, false, false, false, false, false, false, false);

    @Test
    void nothingTouchedStaysClean() {
        assertNull(RunState.reasonFor(CLEAN),
                "a failure before any mutation must stay retryable");
    }

    @Test
    void registryCommitIsIrreversible() {
        assertNotNull(RunState.reasonFor(flags(false, true, false, false, false, false, false, false)));
    }

    @Test
    void constructorSideEffectsCount() {
        // A constructor that ran cannot be un-run; retries after it were measured
        // to fail even with listeners unregistered.
        assertNotNull(RunState.reasonFor(flags(false, false, false, true, false, false, false, false)));
    }

    @Test
    void additiveGlobalsCount() {
        assertNotNull(RunState.reasonFor(flags(false, false, false, false, true, false, false, false)));
        assertNotNull(RunState.reasonFor(flags(false, false, false, false, false, true, false, false)));
        assertNotNull(RunState.reasonFor(flags(false, false, false, false, false, false, true, false)));
        assertNotNull(RunState.reasonFor(flags(false, false, false, false, false, false, false, true)));
    }

    @Test
    void rollbackFailureOutranksEverything() {
        RunState.Reason reason = RunState.reasonFor(flags(true, true, true, true, true, true, true, true));
        assertEquals(RunState.Reason.ROLLBACK_PARTIAL, reason);
    }

    @Test
    void firstIrreversibleOperationIsReported() {
        assertEquals(RunState.Reason.REGISTRY,
                RunState.reasonFor(flags(false, true, true, false, false, false, false, false)));
        assertEquals(RunState.Reason.CUSTOM_REGISTRY,
                RunState.reasonFor(flags(false, false, true, true, false, false, false, false)));
    }
}