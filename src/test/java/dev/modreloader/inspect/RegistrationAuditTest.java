package dev.modreloader.inspect;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the audit's grading rules. No game and no bytecode on the test
 * classpath on purpose: this half of the audit must stay pure, because the
 * interesting part is which event lands in which bucket (and whether the bucket
 * is fatal), not how the class file was read.
 *
 * <p>The real-jar half (class references -> {@code Class.forName} ->
 * {@code IModBusEvent}) is verified in game by {@code dev/RegAuditCheck}; see
 * {@code docs/features/client-registration-audit.md}.
 */
class RegistrationAuditTest {
    private static RegistrationAudit.Report classify(boolean clientSide, String... events) {
        return RegistrationAudit.classify(Set.of(events), clientSide, List.of());
    }

    @Test
    void coveredEventsAreNotUnhandled() {
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent",
                "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent",
                "net.neoforged.neoforge.registries.RegisterEvent",
                "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent");

        assertEquals(4, report.events(RegistrationAudit.Severity.COVERED).size());
        assertTrue(report.events(RegistrationAudit.Severity.SILENT).isEmpty());
        assertFalse(report.fatal());
    }

    @Test
    void eventsThatCrashWhenUsedAreFatal() {
        for (String event : List.of(
                "net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent",
                "net.neoforged.neoforge.client.event.RegisterShadersEvent",
                "net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent")) {
            RegistrationAudit.Report report = classify(true, event);
            assertEquals(RegistrationAudit.Severity.CRASH,
                    report.entries().get(0).severity(), event);
            assertTrue(report.fatal(), event);
            assertTrue(report.shortNames(RegistrationAudit.Severity.CRASH).contains(
                    event.substring(event.lastIndexOf('.') + 1)), event);
        }
    }

    @Test
    void particleProvidersAreCoveredByTheReplay() {
        // 2026-11-16: ClientRegistrationReplay#replayParticleProviders put-only replay.
        // Measured with Balm 21.0.67, which adds the listener to the consuming mod's bus.
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent");
        assertEquals(RegistrationAudit.Severity.COVERED,
                report.entries().get(0).severity());
        assertFalse(report.fatal());
    }

    @Test
    void behaviourGapsAreReportedButDoNotBlockTheAdd() {
        // Default components are still not replayed: the mod keeps running, but a
        // component it adds to an item is simply absent. Reported loudly, no refusal.
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.event.ModifyDefaultComponentsEvent");

        assertEquals(RegistrationAudit.Severity.BEHAVIOR,
                report.entries().get(0).severity());
        assertFalse(report.fatal());
        assertTrue(report.line().contains("behavior [event.ModifyDefaultComponentsEvent]"),
                report.line());
    }

    @Test
    void capabilityProvidersAreCoveredSinceTheReplayExists() {
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent");

        assertEquals(RegistrationAudit.Severity.COVERED, report.entries().get(0).severity());
        assertFalse(report.fatal());
    }

    @Test
    void guiLayersAreCoveredSinceTheReplayExists() {
        // Was the first "silent" bucket entry (AppleSkin's hunger overlays): the
        // mod keeps working, its HUD element just never draws. ClientRegistrationReplay
        // -> GuiLayerReplay now replays the event against a copy of the live list.
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.client.event.RegisterGuiLayersEvent");

        assertEquals(RegistrationAudit.Severity.COVERED, report.entries().get(0).severity());
        assertTrue(report.events(RegistrationAudit.Severity.SILENT).isEmpty());
        assertFalse(report.fatal());
    }

    @Test
    void itemDecorationsAreCoveredSinceTheReplayExists() {
        // ItemDecoratorHandler bakes the event into a static immutable lookup; the
        // replay copies the per-item lists, appends and swaps a rebuilt lookup in.
        // Fixture: experiments/client-fixture (decorates minecraft:diamond).
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent");

        assertEquals(RegistrationAudit.Severity.COVERED, report.entries().get(0).severity());
        assertTrue(report.events(RegistrationAudit.Severity.SILENT).isEmpty());
        assertFalse(report.fatal());
    }

    @Test
    void recurringAndDatagenEventsNeedNoReplay() {
        assertEquals(RegistrationAudit.Severity.RECURRING, classify(true,
                "net.neoforged.neoforge.client.event.RenderLevelStageEvent")
                .entries().get(0).severity());
        assertEquals(RegistrationAudit.Severity.RECURRING, classify(true,
                "net.neoforged.neoforge.client.event.ModelEvent$RegisterAdditional")
                .entries().get(0).severity());
        assertEquals(RegistrationAudit.Severity.RECURRING, classify(true,
                "net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent")
                .entries().get(0).severity());
        assertEquals(RegistrationAudit.Severity.DATAGEN, classify(true,
                "net.neoforged.neoforge.data.event.GatherDataEvent")
                .entries().get(0).severity());
        assertFalse(classify(true,
                "net.neoforged.neoforge.client.event.RenderLevelStageEvent").fatal());
    }

    @Test
    void unknownEventsAreSilentWarnings() {
        // Dimension special effects are still the "no replay yet" bucket; GUI
        // layers and item decorations moved to covered on 2026-11-16 (see
        // guiLayersAreCoveredSinceTheReplayExists / itemDecorationsAreCovered...).
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent");

        assertEquals(RegistrationAudit.Severity.SILENT,
                report.entries().get(0).severity());
        assertFalse(report.fatal());
        assertTrue(report.line().contains("silent [client.event.RegisterDimensionSpecialEffectsEvent]"),
                report.line());
    }

    @Test
    void clientEventsAreNotGradedOnADedicatedServer() {
        String dimensionEffects =
                "net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent";
        assertEquals(RegistrationAudit.Severity.NOT_THIS_SIDE,
                RegistrationAudit.classify(Set.of(dimensionEffects), false, List.of())
                        .entries().get(0).severity());
        assertEquals(RegistrationAudit.Severity.SILENT,
                RegistrationAudit.classify(Set.of(dimensionEffects), true, List.of())
                        .entries().get(0).severity());
        // A server-side gap is still reported on the server.
        assertEquals(RegistrationAudit.Severity.BEHAVIOR,
                RegistrationAudit.classify(
                                Set.of("net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent"),
                                false, List.of())
                        .entries().get(0).severity());
        // Capabilities are replayed, so they are covered on both sides.
        assertEquals(RegistrationAudit.Severity.COVERED,
                RegistrationAudit.classify(
                                Set.of("net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent"),
                                false, List.of())
                        .entries().get(0).severity());
    }

    @Test
    void abstractEventBasesAreMarkedAsBasesNotGaps() {
        // Mekanism has a helper taking RegisterColorHandlersEvent (the abstract
        // base) and registers on its Block/Item subclasses. Without this grade
        // every such helper would look like an unhandled event.
        String base = "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent";
        RegistrationAudit.Report report = RegistrationAudit.classify(
                Set.of(base), Set.of(base), true, List.of());

        assertEquals(RegistrationAudit.Severity.BASE, report.entries().get(0).severity());
        assertFalse(report.fatal());
        assertTrue(report.line().contains("base [client.event.RegisterColorHandlersEvent]"),
                report.line());
    }

    @Test
    void unresolvedNamesAndShortNamesShowUpInTheLine() {
        RegistrationAudit.Report report = RegistrationAudit.classify(
                Set.of("net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent"),
                true,
                List.of("net.neoforged.neoforge.client.event.RegisterFromMissingDepEvent"));

        assertTrue(report.line().contains(
                "unresolved [client.event.RegisterFromMissingDepEvent]"), report.line());
        assertEquals("client.event.RegisterParticleProvidersEvent",
                RegistrationAudit.shortName(
                        "net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent"));
        assertEquals("fml.event.lifecycle.FMLCommonSetupEvent",
                RegistrationAudit.shortName(
                        "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent"));
    }
}
