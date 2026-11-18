package dev.modreloader.inspect;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The watcher dialog's Load button and the engine's VALIDATE gate must agree on
 * access transformers. FML applies ATs only while it loads classes, so a hot add
 * of an AT jar is refused on a stock JVM — the experimental {@code lateAt} path
 * is the only opt-in. The dialog used to keep the button disabled while telling
 * the user "experimental hot-add" (Mekanism 10.7.19, 2026-11-17).
 */
class ModInspectionTest {
    @Test
    void accessTransformerBlocksGuifeiUnlessLateAtIsEnabled() {
        ModInspection mekanism = inspection(ModInspection.Tier.GUIFEI, true);

        assertFalse(mekanism.canHotLoad(true), "AT must refuse even when registry content is allowed");
        assertTrue(mekanism.canHotLoad(true, true), "late AT is the documented opt-in escape hatch");
    }

    @Test
    void accessTransformerBlocksPinTierToo() {
        ModInspection pin = inspection(ModInspection.Tier.PIN, true);

        assertFalse(pin.canHotLoad(false), "AT is not a guifei-only gate");
        assertTrue(pin.canHotLoad(false, true));
    }

    @Test
    void guifeiWithoutAccessTransformerStillNeedsTheRegistryPath() {
        ModInspection guifei = inspection(ModInspection.Tier.GUIFEI, false);

        assertFalse(guifei.canHotLoad(false), "registry content is gated on REGISTRY_ADAPTATION");
        assertTrue(guifei.canHotLoad(true));
    }

    @Test
    void alreadyLoadedAndHuanghouStayRefused() {
        assertFalse(inspection(ModInspection.Tier.PIN, false, true).canHotLoad(false, true));
        assertFalse(inspection(ModInspection.Tier.HUANGHOU, false).canHotLoad(true, true));
    }

    private static ModInspection inspection(ModInspection.Tier tier, boolean accessTransformers) {
        return inspection(tier, accessTransformers, false);
    }

    private static ModInspection inspection(ModInspection.Tier tier, boolean accessTransformers,
                                            boolean alreadyLoaded) {
        return new ModInspection(Path.of("Mekanism-1.21.1-10.7.19.85.jar"),
                "Mekanism-1.21.1-10.7.19.85.jar",
                List.of(new ModInspection.Mod("mekanism", "Mekanism", "10.7.19")),
                tier, false, tier == ModInspection.Tier.GUIFEI, false, alreadyLoaded, false,
                accessTransformers, List.of(), List.of(), null);
    }
}
