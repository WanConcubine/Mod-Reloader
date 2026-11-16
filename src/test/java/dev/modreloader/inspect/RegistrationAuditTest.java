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
                "net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent",
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
    void behaviourGapsAreReportedButDoNotBlockTheAdd() {
        // Ex Nihilo (the dogfood) registers capability providers. They are not
        // replayed, so machines silently do nothing - but refusing the mod would
        // make the runtime unusable for its main content mod.
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent");

        assertEquals(RegistrationAudit.Severity.BEHAVIOR,
                report.entries().get(0).severity());
        assertFalse(report.fatal());
        assertTrue(report.line().contains("behavior [capabilities.RegisterCapabilitiesEvent]"),
                report.line());
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
        RegistrationAudit.Report report = classify(true,
                "net.neoforged.neoforge.client.event.RegisterGuiLayersEvent");

        assertEquals(RegistrationAudit.Severity.SILENT,
                report.entries().get(0).severity());
        assertFalse(report.fatal());
        assertTrue(report.line().contains("silent [client.event.RegisterGuiLayersEvent]"),
                report.line());
    }

    @Test
    void clientEventsAreNotGradedOnADedicatedServer() {
        String guiLayers = "net.neoforged.neoforge.client.event.RegisterGuiLayersEvent";
        assertEquals(RegistrationAudit.Severity.NOT_THIS_SIDE,
                RegistrationAudit.classify(Set.of(guiLayers), false, List.of())
                        .entries().get(0).severity());
        assertEquals(RegistrationAudit.Severity.SILENT,
                RegistrationAudit.classify(Set.of(guiLayers), true, List.of())
                        .entries().get(0).severity());
        // A server-side gap is still reported on the server.
        assertEquals(RegistrationAudit.Severity.BEHAVIOR,
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
