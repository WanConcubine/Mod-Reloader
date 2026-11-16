package dev.modreloader.inspect;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class RegistrationAudit {
    /** How a referenced mod-bus event is handled by this runtime. */
    public enum Severity {
        /** Replayed or constructed by this runtime; the mod will see it. */
        COVERED("covered"),
        /** Fires again after start-up (every frame/reload); no replay needed. */
        RECURRING("recurring"),
        /** Datagen / game-test only; irrelevant at runtime. */
        DATAGEN("datagen"),
        /** Known to crash when the registered feature is used: refuse the add. */
        CRASH("crash"),
        /** Feature silently does something else (capabilities, components, registries). */
        BEHAVIOR("behavior"),
        /** No known symptom yet; probably a missing HUD element or visual. */
        SILENT("silent"),
        /** A client-only event on a dedicated server; it never fires there anyway. */
        NOT_THIS_SIDE("other-side"),
        /**
         * An abstract event base class (the bus fires its concrete subclasses).
         * Listed for completeness: a mod that listens on the base is served by
         * replaying the subclasses, so this is not a gap by itself.
         */
        BASE("base");

        private final String label;

        Severity(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Entry(String event, Severity severity) {
    }

    /**
     * @param entries    every referenced mod-bus event and how it is handled
     * @param unresolved type names that looked like an event but could not be
     *                   loaded (a missing optional dependency, usually)
     */
    public record Report(List<Entry> entries, List<String> unresolved) {
        public Report {
            entries = List.copyOf(entries);
            unresolved = List.copyOf(unresolved);
        }

        public List<String> events(Severity severity) {
            List<String> out = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry.severity() == severity) {
                    out.add(entry.event());
                }
            }
            return out;
        }

        public List<String> crashEvents() {
            return events(Severity.CRASH);
        }

        /** True when the mod registers an event whose absence crashes on use. */
        public boolean fatal() {
            return !crashEvents().isEmpty();
        }

        /** One log line; short names, grouped by severity. */
        public String line() {
            StringBuilder sb = new StringBuilder();
            sb.append(entries.size()).append(" mod-bus event(s)");
            for (Severity severity : Severity.values()) {
                List<String> names = events(severity);
                if (names.isEmpty()) {
                    continue;
                }
                sb.append("; ").append(severity.label()).append(' ');
                sb.append(shortList(names));
            }
            if (!unresolved.isEmpty()) {
                sb.append("; unresolved ").append(shortList(unresolved));
            }
            return sb.toString();
        }

        /** Short names of one severity, comma separated (for failure messages). */
        public String shortNames(Severity severity) {
            return shortList(events(severity));
        }
    }

    // ---------------------------------------------------------------------
    // The three name lists. Keep them in sync with the replay code.
    // ---------------------------------------------------------------------

    /**
     * Events this runtime replays or constructs.
     *
     * <p>Client entries are implemented by {@code ClientRegistrationReplay} /
     * {@code ClientPlatformHooks}; the lifecycle and registry entries by
     * {@code FmlEvents} / {@code RegistryStage}; payloads by
     * {@code NetworkAdaptation}.
     */
    private static final Set<String> COVERED = Set.of(
            // FmlEvents (both sides)
            "net.neoforged.fml.event.lifecycle.FMLConstructModEvent",
            "net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent",
            "net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent",
            // FmlEvents -> PlatformHooks#acceptClientLifecycle
            "net.neoforged.fml.event.lifecycle.FMLClientSetupEvent",
            // ClientRegistrationReplay / ClientPlatformHooks
            "net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent",
            "net.neoforged.neoforge.client.event.RegisterMenuScreensEvent",
            "net.neoforged.neoforge.client.event.EntityRenderersEvent$RegisterRenderers",
            "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent$Block",
            "net.neoforged.neoforge.client.event.RegisterColorHandlersEvent$Item",
            "net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent",
            "net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent",
            "net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent",
            // client/GuiLayerReplay (copy of the live GuiLayerManager list, then swap)
            "net.neoforged.neoforge.client.event.RegisterGuiLayersEvent",
            // client/ItemDecoratorReplay (copy of the live DECORATOR_LOOKUP, then swap)
            "net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent",
            // NetworkAdaptation / RegistryStage / CapabilityReplay
            "net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent",
            "net.neoforged.neoforge.registries.RegisterEvent",
            "net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent");

    /**
     * Fired again after start-up, so a hot-added mod's listener will run on the
     * next frame/reload/tab rebuild without any replay.
     */
    private static final Set<String> RECURRING = Set.of(
            "net.neoforged.neoforge.client.event.RenderLevelStageEvent",
            "net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent",
            "net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent",
            "net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent",
            "net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent",
            // Not a registration: compared once at start-up, irrelevant later.
            "net.neoforged.neoforge.event.ModMismatchEvent");

    /** Datagen / test-only; never fired in a playing client or server. */
    private static final Set<String> DATAGEN = Set.of(
            "net.neoforged.neoforge.data.event.GatherDataEvent",
            "net.neoforged.neoforge.event.RegisterGameTestsEvent");

    /**
     * Absence throws as soon as the mod's content is used (particles spawn,
     * recipe book opens, a shader/sprite source is requested). Refused before
     * the module is injected, like an access transformer.
     */
    private static final Set<String> CRASH = Set.of(
            "net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent",
            "net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent",
            "net.neoforged.neoforge.client.event.RegisterShadersEvent",
            "net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent");

    /**
     * The mod keeps running but the feature behaves differently: capabilities
     * and default components are simply absent, a mod-registered registry does
     * not exist, spawn placements / POI extensions are not applied. Reported
     * loudly; not refused (see the class comment).
     */
    private static final Set<String> BEHAVIOR = Set.of(
            "net.neoforged.neoforge.event.ModifyDefaultComponentsEvent",
            "net.neoforged.neoforge.registries.NewRegistryEvent",
            "net.neoforged.neoforge.registries.ModifyRegistriesEvent",
            "net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent",
            "net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent",
            "net.neoforged.neoforge.event.entity.RegisterSpawnPlacementsEvent",
            "net.neoforged.neoforge.event.BlockEntityTypeAddBlocksEvent",
            "net.neoforged.neoforge.registries.datamaps.RegisterDataMapTypesEvent",
            "net.neoforged.neoforge.fluids.RegisterCauldronFluidContentEvent",
            "net.neoforged.neoforge.event.AddPackFindersEvent",
            "net.neoforged.neoforge.common.world.poi.ExtendPoiTypesEvent",
            // Model layers are baked at start-up: a renderer that needs a new
            // layer will fail when the entity is first drawn.
            "net.neoforged.neoforge.client.event.EntityRenderersEvent$RegisterLayerDefinitions");

    private static final Set<String> BEHAVIOR_PREFIXES = Set.of(
            "net.neoforged.neoforge.registries.DataPackRegistryEvent");

    private static final String MODEL_EVENT_PREFIX =
            "net.neoforged.neoforge.client.event.ModelEvent$";

    private static final String CLIENT_PACKAGE = "net.neoforged.neoforge.client.";

    private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;

    private RegistrationAudit() {
    }

    /**
     * Classifies the mod-bus events a jar references.
     *
     * <p>Pure: no game classes and no bytecode. Split out so the rules can be
     * unit-tested in a JVM that cannot even load NeoForge.
     */
    public static Report classify(Set<String> eventClasses, boolean clientSide, List<String> unresolved) {
        return classify(eventClasses, Set.of(), clientSide, unresolved);
    }

    /**
     * @param abstractBases events whose class is abstract; the bus only fires
     *                      their concrete subclasses, so they are graded
     *                      {@link Severity#BASE} rather than as a gap
     */
    public static Report classify(Set<String> eventClasses, Set<String> abstractBases,
                                  boolean clientSide, List<String> unresolved) {
        List<Entry> entries = new ArrayList<>();
        for (String event : new TreeSet<>(eventClasses)) {
            entries.add(new Entry(event, severityOf(event, clientSide, abstractBases)));
        }
        return new Report(entries, unresolved == null ? List.of() : unresolved);
    }

    static Severity severityOf(String event, boolean clientSide, Set<String> abstractBases) {
        if (!clientSide && event.startsWith(CLIENT_PACKAGE)) {
            return Severity.NOT_THIS_SIDE;
        }
        if (abstractBases.contains(event)) {
            return Severity.BASE;
        }
        if (COVERED.contains(event)) {
            return Severity.COVERED;
        }
        if (RECURRING.contains(event) || event.startsWith(MODEL_EVENT_PREFIX)) {
            return Severity.RECURRING;
        }
        if (DATAGEN.contains(event)) {
            return Severity.DATAGEN;
        }
        if (CRASH.contains(event)) {
            return Severity.CRASH;
        }
        if (BEHAVIOR.contains(event) || BEHAVIOR_PREFIXES.stream().anyMatch(event::startsWith)) {
            return Severity.BEHAVIOR;
        }
        return Severity.SILENT;
    }

    /** Short names in brackets, for log lines. */
    private static String shortList(List<String> names) {
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(shortName(name));
        }
        return "[" + String.join(", ", out) + "]";
    }

    static String shortName(String event) {
        String name = event;
        if (name.startsWith("net.neoforged.neoforge.")) {
            name = name.substring("net.neoforged.neoforge.".length());
        } else if (name.startsWith("net.neoforged.")) {
            name = name.substring("net.neoforged.".length());
        }
        return name;
    }

}
