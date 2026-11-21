package dev.modreloader.inspect;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the single list that the registration audit grades as "covered".
 *
 * <p>The replay call sites name their event through these constants, so adding a
 * replay without adding it here (or the reverse) is meant to be a visible,
 * deliberate change rather than a silently drifting second list.
 */
class ReplayCatalogTest {
    @Test
    void namesAreUniqueAndQualified() {
        List<String> entries = ReplayCatalog.entries();
        assertEquals(entries.size(), new HashSet<>(entries).size(), "duplicate event names");
        for (String entry : entries) {
            assertTrue(entry.contains("."), entry);
            assertTrue(entry.startsWith("net.neoforged."), entry);
        }
    }

    @Test
    void auditCoverageIncludesEveryLateReplay() {
        for (String event : List.of(
                ReplayCatalog.REGISTER_LAYER_DEFINITIONS,
                ReplayCatalog.REGISTER_PARTICLE_PROVIDERS,
                ReplayCatalog.REGISTER_RECIPE_BOOK_CATEGORIES,
                ReplayCatalog.REGISTER_SHADERS,
                ReplayCatalog.REGISTER_PAYLOAD_HANDLERS,
                ReplayCatalog.REGISTER_EVENT,
                ReplayCatalog.REGISTER_NEW_REGISTRY,
                ReplayCatalog.REGISTER_DATA_PACK_REGISTRIES,
                ReplayCatalog.REGISTER_CAPABILITIES)) {
            assertTrue(ReplayCatalog.covered().contains(event), event);
        }
    }

    @Test
    void catalogSizeIsPinned() {
        // A new replay must make this fail once, on purpose, together with the
        // audit docs and the client registration test that exercises it.
        assertEquals(23, ReplayCatalog.entries().size());
    }
}