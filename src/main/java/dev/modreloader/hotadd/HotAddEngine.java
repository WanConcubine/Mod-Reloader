package dev.modreloader.hotadd;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.capability.Capabilities;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.inspect.ModInspection;
import dev.modreloader.inspect.ModInspector;
import dev.modreloader.inspect.RegistrationAudit;
import dev.modreloader.inspect.RegistrationAuditScanner;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.language.ModFileScanData;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Hot-add engine.
 *
 * <p>Pipeline: read the jar → inject its module into the game layer → register
 * its mixin configs (redefining already-loaded targets with JBR) → rebuild
 * {@link ModList} → construct the mod and commit registry content → replay
 * common setup → inject its resource pack and start the reload → replay the
 * side setup tasks and payloads → apply its server data pack (recipes/loot/tags).
 *
 * <p>Each stage lives in its own method and reports a {@link Result} on failure
 * (or {@code null} to continue); the order itself is pinned by
 * {@link AddStagePlan}, not by the layout of this file.
 *
 * <p>Still out of scope: datapack registries (dimension/biome/…), guifei/huanghou
 * content that needs a full restart, and soft restart.
 */
public final class HotAddEngine {
    public enum Stage {
        NONE, VALIDATE, MODULE, MIXIN, COREMOD, MODLIST, CONSTRUCT, REGISTRY, EVENT, PACK, CLIENT, DATAPACK
    }

    public enum FailureKind {
        NONE, MISSING_FILE, NOT_A_MOD, LIBRARY, INVALID_METADATA, ALREADY_LOADED, MISSING_DEPENDENCY, JBR_REQUIRED,
        MIXIN_RUNTIME, NETWORK_PAYLOAD, UNSUPPORTED_TIER, CAPABILITY, MIXIN_CONFIG, MIXIN_TARGET, REDEFINE, MODULE, MODLIST, REGISTRY,
        CONSTRUCT, EVENT, RESOURCE_PACK, DATAPACK, ACCESS_TRANSFORMER, CLIENT_REGISTRATION, JAR_IN_JAR, CUSTOM_REGISTRY,
        COREMOD, ROLLBACK_PARTIAL, RUNTIME_DIRTY, UNKNOWN
    }

    public record Result(boolean ok, String message, Stage stage, FailureKind kind,
                         List<String> rollbackErrors) {
        public Result(boolean ok, String message) {
            this(ok, message, Stage.NONE, FailureKind.NONE, List.of());
        }

        public boolean rollbackPartial() {
            return !rollbackErrors.isEmpty();
        }
    }

    /** State of one add that outlives a single stage. */
    private static final class Run {
        final Path jar;
        final Rollback rollback = new Rollback();
        final AddStagePlan stagePlan = new AddStagePlan();
        final Set<String> ids = new LinkedHashSet<>();

        // VALIDATE (file) / MODULE (nested + gates)
        ModFile modFile;
        String primaryId;
        ModFileInfo newFileInfo;
        List<ModInfo> newInfos;
        JarInJar.Result nested;
        List<NestedMods.Loaded> nestedMods;
        Set<String> nestedIds;
        NestedMods.Loaded primaryNested;
        Path primaryJar;
        ModFileInfo primaryInfo;
        List<ModInfo> primaryInfos;
        List<ModFileParser.MixinConfig> mixinConfigs;
        boolean registryContent;
        ModInspection inspectionForNotice;
        List<Path> coremodJars;
        Set<String> moduleNames;

        // MIXIN / COREMOD
        boolean mixinsApplied;
        boolean mixinsSettled;
        final List<String> mixinSummary = new ArrayList<>();
        final List<String> coremodSummary = new ArrayList<>();

        // MODLIST / CONSTRUCT / EVENT
        List<ModContainer> containers = List.of();
        List<ModContainer> outerContainers;
        EventBusSnapshot.Snapshot busSnapshot;
        final List<String> loaded = new ArrayList<>();
        PlatformHooks hooks;
        int keysBefore;
        int keysAfter;
        boolean constructed;
        // Irreversible operations reached; see RunState.
        boolean registryCommitted;
        boolean customRegistryOpened;
        boolean payloadsAdapted;
        boolean capabilitiesReplayed;
        boolean commandsReplayed;

        Run(Path jar) {
            this.jar = jar;
        }
    }

    private HotAddEngine() {
    }

    public static Result add(Path rawPath) {
        Path jar = rawPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(jar)) {
            return new Result(false, "not a file: " + jar, Stage.VALIDATE,
                    FailureKind.MISSING_FILE, List.of());
        }
        if (!jar.getFileName().toString().endsWith(".jar")) {
            return new Result(false, "not a jar: " + jar);
        }
        if (RunState.isDirty()) {
            return new Result(false,
                    "the runtime is dirty (" + RunState.describe() + "); restart "
                            + (Platform.isClient() ? "the game" : "the server")
                            + " with the working jars in mods/ before adding more",
                    Stage.VALIDATE, FailureKind.RUNTIME_DIRTY, List.of());
        }

        // A distribution wrapper (MezzConfig "standalone") has no mods.toml of its
        // own; at startup FML's jarjar locator turns the nested jar into the mod.
        // Do the same here so the regular pipeline runs on the nested file.
        Path effectiveJar = jar;
        try {
            WrapperJar.Unwrapped unwrapped = WrapperJar.resolve(jar);
            if (unwrapped != null) {
                effectiveJar = unwrapped.effective();
                ModReloader.LOGGER.info(
                        "[ModReloader] wrapper {}: hot-adding the nested mod {}",
                        jar.getFileName(), unwrapped.nestedPath());
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot unwrap {}; using the outer jar",
                    jar.getFileName(), t);
        }

        Run run = new Run(effectiveJar);
        try {
            Result failure;
            if ((failure = readModFile(run)) != null) return recordFailure(run, failure);
            if ((failure = prepareModules(run)) != null) return recordFailure(run, failure);
            if ((failure = applyMixins(run)) != null) return recordFailure(run, failure);
            if ((failure = applyCoremods(run)) != null) return recordFailure(run, failure);
            // ModernUI starts its UI thread in @Mod construct and that thread creates
            // the recording context from the GPU device present at that moment, so its
            // renderer init has to be replayed before the constructor runs.
            String modernUi = ModernUIAdapter.beforeConstruct(run.ids);
            if (modernUi != null) {
                return fail(run.rollback, Stage.VALIDATE, FailureKind.UNKNOWN,
                        "modernui adapter failed: " + modernUi + "; restart required");
            }
            if ((failure = registerModList(run)) != null) return recordFailure(run, failure);
            if ((failure = construct(run)) != null) return recordFailure(run, failure);
            if ((failure = commitRegistry(run)) != null) return recordFailure(run, failure);
            if ((failure = replayCommonStartup(run)) != null) return recordFailure(run, failure);
            if ((failure = injectPacks(run)) != null) return recordFailure(run, failure);
            if ((failure = runSidedStartup(run)) != null) return recordFailure(run, failure);
            if ((failure = reloadData(run)) != null) return recordFailure(run, failure);
            return succeed(run);
        } catch (Throwable t) {
            return recordFailure(run, unexpected(run, t));
        }
    }

    /** VALIDATE: parse the jar and reject what cannot be hot-added at all. */
    private static Result readModFile(Run run) throws Throwable {
        Path jar = run.jar;
        Rollback rollback = run.rollback;
        run.stagePlan.enter(Stage.VALIDATE);
        // Visible to the failure path: a failure after mixins were applied leaves
        // injected handlers in already-loaded classes and needs a restart.
        IModFile candidate = new JarModsDotTomlModFileReader()
                .read(JarContents.of(jar), ModFileDiscoveryAttributes.DEFAULT);
        if (!(candidate instanceof ModFile modFile)) {
            if (ModInspector.isGameLibrary(jar)) {
                return fail(rollback, Stage.VALIDATE, FailureKind.LIBRARY,
                        "library jar (FMLModType: GAMELIBRARY), not a mod: " + jar.getFileName());
            }
            return fail(rollback, Stage.VALIDATE, FailureKind.NOT_A_MOD, "not a NeoForge mod jar (no mods.toml): " + jar.getFileName());
        }
        run.modFile = modFile;

        // identifyMods() also parses [[mixins]] declarations and access
        // transformers. A0 used to call it only when getModInfos() was
        // empty; always run it so mixin configs are available.
        if (!modFile.identifyMods()) {
            return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "cannot identify mods in " + jar.getFileName());
        }

        List<IModInfo> infos = modFile.getModInfos();
        if (infos.isEmpty()) {
            if (ModInspector.isGameLibrary(jar)) {
                return fail(rollback, Stage.VALIDATE, FailureKind.LIBRARY,
                        "library jar (FMLModType: GAMELIBRARY), not a mod: " + jar.getFileName());
            }
            return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "jar declares no mods: " + jar.getFileName());
        }

        for (IModInfo info : infos) {
            if (ModList.get().isLoaded(info.getModId())) {
                return fail(rollback, Stage.VALIDATE, FailureKind.ALREADY_LOADED, "already loaded: " + info.getModId());
            }
            run.ids.add(info.getModId());
        }
        run.primaryId = run.ids.iterator().next();

        // A0 deliberately bypasses ModSorter/ModValidator: the isolated
        // validator would either mis-report already-loaded dependencies or
        // try to register mixin configs after Mixin is closed.
        modFile.identifyLanguage();
        ModFileScanData scanData = modFile.compileContent();
        if (scanData == null) {
            return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "annotation scan returned nothing for " + run.ids);
        }
        modFile.setScanResult(scanData, null);

        IModFileInfo rawFileInfo = modFile.getModFileInfo();
        if (!(rawFileInfo instanceof ModFileInfo newFileInfo)) {
            return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "no ModFileInfo produced for " + run.ids);
        }
        List<ModInfo> newInfos = new ArrayList<>();
        for (IModInfo info : modFile.getModInfos()) {
            newInfos.add((ModInfo) info);
        }
        if (newInfos.isEmpty()) {
            return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "no ModInfo produced for " + run.ids);
        }
        run.newFileInfo = newFileInfo;
        run.newInfos = newInfos;
        return null;
    }

    /** MODULE: nested mods, mixin config list, gates, then inject the module. */
    private static Result prepareModules(Run run) throws Throwable {
        Path jar = run.jar;
        Rollback rollback = run.rollback;
        AddStagePlan stagePlan = run.stagePlan;
        Set<String> ids = run.ids;
        stagePlan.enter(Stage.MODULE);

        // Nested mods (jar-in-jar with a mods.toml) normally get a ModFile and
        // a container from FML's JarJar locator at startup. A hot-add skips
        // that locator, so build them here *before* the outer mod's dependency
        // check: Xaero's Minimap requires XaeroLib, which it bundles itself.
        JarInJar.Result nested;
        try {
            nested = JarInJar.extract(jar);
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] cannot read jar-in-jar metadata of {}", jar, t);
            return fail(rollback, Stage.MODULE, FailureKind.JAR_IN_JAR,
                    "cannot read the jars nested in " + jar.getFileName() + ": " + t);
        }
        run.nested = nested;
        for (String skip : nested.skipped()) {
            ModReloader.LOGGER.warn("[ModReloader] jar-in-jar skipped {}: {}",
                    jar.getFileName(), skip);
        }
        Set<String> loadedIds = new LinkedHashSet<>();
        for (IModInfo modInfo : ModList.get().getMods()) {
            loadedIds.add(modInfo.getModId());
        }
        List<NestedMods.Loaded> nestedMods;
        boolean lateAt = LateAccessTransformers.enabled();
        Set<String> outerIds = new LinkedHashSet<>();
        run.newInfos.forEach(info -> outerIds.add(info.getModId()));
        try {
            nestedMods = NestedMods.prepare(nested, loadedIds, outerIds, lateAt);
        } catch (NestedMods.AccessTransformer e) {
            return fail(rollback, Stage.VALIDATE, FailureKind.ACCESS_TRANSFORMER,
                    ids + ": " + e.getMessage() + "; restart with the jar in mods/.");
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] cannot prepare nested mods of {}", jar, t);
            return fail(rollback, Stage.MODULE, FailureKind.JAR_IN_JAR,
                    "cannot prepare the mods nested in " + jar.getFileName() + ": " + t);
        }
        run.nestedMods = nestedMods;
        // Nested ATs use the same late path as the outer jar; applying them here
        // is still before any class of this add loads.
        for (NestedMods.Loaded nestedMod : nestedMods) {
            for (Path nestedAt : nestedMod.accessTransformers()) {
                LateAccessTransformers.Result late =
                        LateAccessTransformers.apply(nestedAt, rollback);
                if (!late.ok()) {
                    return fail(rollback, Stage.VALIDATE, FailureKind.ACCESS_TRANSFORMER,
                            ids + ": nested " + nestedAt.getFileName()
                                    + " late AT failed: " + late.messages()
                                    + ". Hot-add failed (runtime gap).");
                }
                ModReloader.LOGGER.info("[ModReloader] nested late AT for {} ({}): {}",
                        ids, nestedAt.getFileName(), late.summary());
                late.messages().forEach(message ->
                        ModReloader.LOGGER.info("[ModReloader] nested late AT {}", message));
            }
        }
        Set<String> nestedIds = NestedMods.ids(nestedMods);
        run.nestedIds = nestedIds;
        if (!nested.nested().isEmpty()) {
            ModReloader.LOGGER.info("[ModReloader] jar-in-jar: {} nested jar(s) from {} -> modules {}; containers {}",
                    nested.nested().size(), jar.getFileName(),
                    nested.nested().stream().map(JarInJar.Nested::moduleName).toList(),
                    nestedIds);
        }
        // Sodium-style packaging: the outer jar is only a shell (mods.toml +
        // services); the real mod (entrypoint, mixins, assets) is the nested jar
        // with the same modId, and FML picks that nested file at startup. Use it
        // as the mod file and demote the shell to a classes-only module; otherwise
        // both files claim module "sodium" and the shell has no @Mod to construct.
        NestedMods.Loaded primaryNested = nestedMods.stream()
                .filter(NestedMods.Loaded::sharesOuterId)
                .findFirst().orElse(null);
        run.primaryNested = primaryNested;
        Path primaryJar = jar;
        ModFileInfo primaryInfo = run.newFileInfo;
        List<ModInfo> primaryInfos = run.newInfos;
        if (primaryNested != null) {
            primaryJar = primaryNested.path();
            primaryInfo = primaryNested.info();
            primaryInfos = new ArrayList<>(primaryNested.infos());
            ModReloader.LOGGER.info(
                    "[ModReloader] nested implementation shares modId {}; using {} as the mod "
                            + "file and demoting the shell {} to a library",
                    primaryInfos.stream().map(ModInfo::getModId).toList(),
                    primaryJar.getFileName(), jar.getFileName());
        }
        run.primaryJar = primaryJar;
        run.primaryInfo = primaryInfo;
        run.primaryInfos = primaryInfos;

        ModFile primaryModFile = (ModFile) primaryInfo.getFile();
        List<ModFileParser.MixinConfig> declaredMixins = primaryModFile.getMixinConfigs();
        Set<String> knownConfigs = new LinkedHashSet<>();
        List<ModFileParser.MixinConfig> mixinConfigs = new ArrayList<>();
        if (declaredMixins != null) {
            for (ModFileParser.MixinConfig config : declaredMixins) {
                if (knownConfigs.add(config.config())) {
                    mixinConfigs.add(config);
                }
            }
        }
        for (ModFileParser.MixinConfig config : NestedMods.mixins(nestedMods)) {
            if (knownConfigs.add(config.config())) {
                mixinConfigs.add(config);
            }
        }
        // GAMELIBRARY nested jars have no ModFile, so their manifest
        // MixinConfigs are not part of getMixinConfigs().
        for (String config : nested.libraryMixinConfigs()) {
            if (knownConfigs.add(config)) {
                mixinConfigs.add(new ModFileParser.MixinConfig(config, List.of()));
            }
        }
        run.mixinConfigs = mixinConfigs;

        ModReloader.LOGGER.info("[ModReloader] hot-adding {} from {} (mixin configs: {})",
                ids, jar.getFileName(),
                mixinConfigs.stream().map(ModFileParser.MixinConfig::config).toList());

        Result failure;
        if ((failure = inspectAndGate(run)) != null) return failure;
        return injectModules(run);
    }

    /** MODULE: dependency/AT/audit/capability gates before anything is mutated. */
    private static Result inspectAndGate(Run run) throws Throwable {
        Path jar = run.jar;
        Rollback rollback = run.rollback;
        Set<String> ids = run.ids;
        Set<String> nestedIds = run.nestedIds;
        NestedMods.Loaded primaryNested = run.primaryNested;
        Path primaryJar = run.primaryJar;
        // Guifei-tier detection: the analyzer already predicts registry content for
        // the UI; use the same signal to decide whether the engine must open
        // a registry window around construction.
        boolean registryContent = false;
        ModInspection inspectionForNotice = null;
        try {
            ModInspection inspection = ModInspector.inspect(primaryJar);
            inspectionForNotice = inspection.ok() ? inspection : null;
            registryContent = inspection.ok() && inspection.registry();
            if (inspection.ok()) {
                // A required dependency that is not loaded makes ModListInjector
                // build no container ("no ModContainer built"), which is a
                // confusing failure after the module was already injected.
                List<String> missingDeps = inspection.dependencies().stream()
                        .filter(dep -> dep.required() && !dep.satisfied()
                                && !nestedIds.contains(dep.modId()))
                        .map(dep -> dep.modId() + " " + dep.versionRange())
                        .toList();
                for (var dependency : inspection.dependencies()) {
                    if (dependency.required() && !dependency.satisfied()
                            && nestedIds.contains(dependency.modId())) {
                        ModReloader.LOGGER.info(
                                "[ModReloader] dependency {} is bundled as a nested mod of {}; "
                                        + "it will be registered before the outer mod",
                                dependency.modId(), jar.getFileName());
                    }
                }
                if (!missingDeps.isEmpty()) {
                    return fail(rollback, Stage.VALIDATE, FailureKind.MISSING_DEPENDENCY,
                            ids + " needs " + missingDeps + " first; hot-add it in that order");
                }
            }
            // With a shared-id nested implementation (Sodium), primaryJar is the
            // nested jar and its ATs were already applied in the nested loop.
            if (primaryNested == null && inspection.ok() && inspection.accessTransformers()) {
                // Access transformers are applied when the game layer loads
                // classes; a hot-added mod's ATs are not. The default gate is
                // still "refuse before touching anything": widening a private
                // vanilla member after construction causes IllegalAccessError.
                //
                // `-Dmodreloader.lateAt=true` is the experimental alternative:
                // JBR's enhanced redefinition can change access flags of an
                // already-loaded class, so apply the ATs to the captured current
                // bytes and redefine the targets BEFORE this mod's classes load.
                // Stock JVMs reject the modifier change; this is JBR-only.
                if (LateAccessTransformers.enabled()) {
                    LateAccessTransformers.Result late = LateAccessTransformers.apply(jar, rollback);
                    if (!late.ok()) {
                        return fail(rollback, Stage.VALIDATE, FailureKind.ACCESS_TRANSFORMER,
                                ids + " ships " + dev.modreloader.inspect.AccessTransformerRules.ENTRY
                                        + " (" + accessTransformerComposition(jar) + "); late AT failed: "
                                        + late.messages() + ". Hot-add failed (runtime gap).");
                    }
                    ModReloader.LOGGER.info("[ModReloader] late AT applied for {}: {}",
                            ids, late.summary());
                    late.messages().forEach(message ->
                            ModReloader.LOGGER.info("[ModReloader] late AT {}", message));
                } else {
                    return fail(rollback, Stage.VALIDATE, FailureKind.ACCESS_TRANSFORMER,
                            ids + " ships " + dev.modreloader.inspect.AccessTransformerRules.ENTRY
                                    + " (" + accessTransformerComposition(jar) + "); access "
                                    + "transformers only apply at startup. Use the failure screen's "
                                    + "JBR restart once; the runtime enables what is needed.");
                }
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot predict registry content for {}", jar, t);
        }
        run.registryContent = registryContent;
        run.inspectionForNotice = inspectionForNotice;
        ModReloader.LOGGER.info("[ModReloader] registry content detected: {}", registryContent);

        // Registration audit (read-only): a hot-added mod misses every
        // startup-only mod-bus event, and the client replay above is a
        // hand-written list. Report every event the jar registers against and
        // refuse the ones whose absence is known to crash as soon as the
        // registered content is used (particles, recipe book, shaders,
        // sprite sources). Behaviour-only gaps (capabilities, components,
        // mod registries) are reported loudly but do not block the add.
        // See docs/features/client-registration-audit.md.
        try {
            RegistrationAudit.Report audit = RegistrationAuditScanner.audit(primaryJar, Platform.isClient());
            ModReloader.LOGGER.info("[ModReloader] registration audit {}: {}",
                    ids, audit.line());
            if (audit.fatal()) {
                return fail(rollback, Stage.VALIDATE, FailureKind.CLIENT_REGISTRATION,
                        ids + " registers "
                                + audit.shortNames(RegistrationAudit.Severity.CRASH)
                                + " on the mod bus, and this runtime does not replay that event "
                                + "yet; the content it registers would crash when used. "
                                + "Restart with the jar in mods/.");
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] registration audit failed for {}", jar, t);
        }

        // Framework anchors first: if NeoForge drifted under us, say so
        // before anything is mutated instead of crashing halfway through.
        Capabilities.Result moduleCapability =
                Capabilities.get(Capabilities.Capability.MODULE_INJECTION);
        if (!moduleCapability.available()) {
            return fail(rollback, Stage.VALIDATE, FailureKind.CAPABILITY,
                    moduleCapability.describe());
        }
        Capabilities.Result modListCapability =
                Capabilities.get(Capabilities.Capability.MODLIST_REBUILD);
        if (!modListCapability.available()) {
            return fail(rollback, Stage.VALIDATE, FailureKind.CAPABILITY,
                    modListCapability.describe());
        }
        // A data pack has its own framework anchors. Check them before any
        // mutation: failing at Stage.DATAPACK happens after the registry
        // commit, which cannot be rolled back.
        if (DatapackStage.hasData(run.primaryInfo)) {
            Capabilities.Result datapackCapability =
                    Capabilities.get(Capabilities.Capability.DATAPACK_RELOAD);
            if (!datapackCapability.available()) {
                return fail(rollback, Stage.VALIDATE, FailureKind.CAPABILITY,
                        datapackCapability.describe());
            }
        }
        return null;
    }

    /** MODULE: the new mod's classes must be resolvable before Mixin or construction. */
    private static Result injectModules(Run run) throws Throwable {
        Path jar = run.jar;
        Rollback rollback = run.rollback;
        Set<String> ids = run.ids;
        JarInJar.Result nested = run.nested;
        NestedMods.Loaded primaryNested = run.primaryNested;

        // 1. module: the new mod's classes must be resolvable before Mixin
        //    reads its config or the mod constructor runs.
        //
        //    A mod's own META-INF/jarjar libraries are normally registered by
        //    FML's jar-in-jar locator at startup. A hot-added mod skipped that
        //    locator, so its bundled classes do not exist in the game layer
        //    (jecharacters -> NoClassDefFoundError: me/towdium/pinin/Keyboard).
        //    Extract them and put them on the module path in the same step.
        //
        // Coremod jars get their own module layer, and it has to exist before the
        // same jar is injected into the game layer: two modules with the same
        // packages cannot be resolved against each other. Preparing it here also
        // fails the add before any state is mutated if the coremod is unusable.
        List<Path> coremodJars = new ArrayList<>();
        coremodJars.add(jar);
        nested.nested().forEach(inner -> coremodJars.add(inner.file()));
        List<String> coremodPrepare = LateCoremod.prepare(coremodJars);
        if (!coremodPrepare.isEmpty()) {
            return fail(rollback, Stage.COREMOD, FailureKind.COREMOD,
                    ids + ": " + coremodPrepare);
        }
        run.coremodJars = coremodJars;

        ModuleInjector.Snapshot moduleSnapshot = ModuleInjector.snapshot();
        rollback.add("module layer", () -> ModuleInjector.restore(moduleSnapshot));
        List<ModFileInfo> newFileInfos = new ArrayList<>(NestedMods.fileInfos(run.nestedMods));
        List<SecureJar> extraJars = new ArrayList<>(nested.libraryJars());
        if (primaryNested == null) {
            newFileInfos.add(run.newFileInfo);
        } else {
            // The shell keeps its own classes (pre-launch services) but must not
            // shape a mod container; it becomes a library module named from its
            // manifest, so it no longer collides with the nested "sodium" module.
            extraJars.add(SecureJar.from(JarContents.of(jar)));
        }
        run.moduleNames = ModuleInjector.addToGameLayer(newFileInfos, extraJars);
        return null;
    }

    /** MIXIN: register configs, then redefine already-loaded targets. */
    private static Result applyMixins(Run run) throws Throwable {
        Rollback rollback = run.rollback;
        Set<String> ids = run.ids;
        run.stagePlan.enter(Stage.MIXIN);
        // MixinStage keeps every MixinRuntime/MixinSupport reference behind
        // one call, so a Type2 mod still works when mr is not installed.
        Set<String> hotIds = new LinkedHashSet<>(ids);
        hotIds.addAll(run.nestedIds);
        if (!run.mixinConfigs.isEmpty()) {
            // JBR is not required for every mixin mod: a mixin that keeps the
            // class layout still applies on a stock JVM (method bodies only), and
            // a target that is not loaded yet is simply transformed on load.
            // Only a layout change on an already-loaded class needs enhanced
            // redefinition, so that check lives per target in MixinSupport -
            // measured 2026-11-16: a blanket gate refused Mouse Tweaks-style
            // mods even when nothing in them needed JBR.
            if (!MixinRuntimeSupport.available()) {
                return fail(rollback, Stage.MIXIN, FailureKind.MIXIN_RUNTIME,
                        ids + " declares mixins but " + MixinRuntimeSupport.name()
                                + " is not installed");
            }
            Capabilities.Result mixinCapability =
                    Capabilities.get(Capabilities.Capability.MIXIN_HOTADD);
            if (!mixinCapability.available()) {
                return fail(rollback, Stage.MIXIN, FailureKind.CAPABILITY,
                        mixinCapability.describe());
            }
            run.mixinsApplied = true;
            List<String> mixinLog = new ArrayList<>();
            String mixinError = MixinStage.register(run.mixinConfigs, hotIds, run.primaryId, rollback,
                    mixinLog, run.mixinSummary);
            mixinLog.forEach(ModReloader.LOGGER::info);
            if (mixinError != null) {
                // A refused layout change is the JBR case; say so instead of the
                // generic "mixin setup failed".
                boolean needsJbr = !dev.modreloader.inspect.JbrSupport.available()
                        && (mixinError.contains("layout") || mixinError.contains("enhanced"));
                return fail(rollback, Stage.MIXIN,
                        needsJbr ? FailureKind.JBR_REQUIRED : FailureKind.MIXIN_CONFIG,
                        "mixin setup failed for " + ids + ": " + mixinError);
            }
            run.mixinsSettled = true;
        } else {
            ModReloader.LOGGER.info("[ModReloader] no mixin configs declared by {}", ids);
        }
        return null;
    }

    /** COREMOD: apply the mod's ICoreMod transformers late. */
    private static Result applyCoremods(Run run) throws Throwable {
        Rollback rollback = run.rollback;
        Set<String> ids = run.ids;
        // 2b. coremods: ModLauncher ran the mod's ICoreMod transformers while
        //     loading classes, which a hot-added jar missed entirely - the mod
        //     loads and its coremod feature is silently dead (jecharacters ->
        //     pinyin search). Apply the same transformers late.
        run.stagePlan.enter(Stage.COREMOD);
        LateCoremod.Report coremods = LateCoremod.apply(run.coremodJars, rollback);
        if (!coremods.ok()) {
            return fail(rollback, Stage.COREMOD, FailureKind.COREMOD,
                    "coremod setup failed for " + ids + ": " + coremods.errors());
        }
        coremods.redefined().forEach(target ->
                ModReloader.LOGGER.info("[ModReloader] coremod redefined target {}", target));
        if (!coremods.coremods().isEmpty() && !coremods.applied()) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] coremod of {} found no applicable target ({}); the feature it "
                            + "implements will not work until restart",
                    ids, coremods.summary());
        }
        if (coremods.applied()) {
            run.coremodSummary.add(" (coremods " + coremods.coremods() + ", redefined "
                    + coremods.redefined() + ", transformed-at-load " + coremods.eager() + ")");
        }
        return null;
    }

    /** MODLIST: FML now knows about the mod. */
    private static Result registerModList(Run run) throws Throwable {
        Rollback rollback = run.rollback;
        Set<String> ids = run.ids;
        Set<String> nestedIds = run.nestedIds;
        List<NestedMods.Loaded> nestedMods = run.nestedMods;
        run.stagePlan.enter(Stage.MODLIST);
        ModListInjector.Snapshot modListSnapshot = ModListInjector.snapshot();
        rollback.add("mod list", () -> ModListInjector.restore(modListSnapshot));
        List<ModContainer> containers = new ArrayList<>();
        List<ModContainer> nestedContainers = List.of();
        if (!nestedMods.isEmpty()) {
            nestedContainers = ModListInjector.rebuild(
                    NestedMods.fileInfos(nestedMods), NestedMods.modInfos(nestedMods));
            if (nestedContainers.isEmpty()) {
                return fail(rollback, Stage.MODLIST, FailureKind.MODLIST,
                        "no ModContainer built for nested mods " + nestedIds);
            }
            ModReloader.LOGGER.info("[ModReloader] nested mods registered: {}",
                    nestedContainers.stream().map(ModContainer::getModId).toList());
            containers.addAll(nestedContainers);
        }
        List<ModContainer> outerContainers;
        if (run.primaryNested != null) {
            // The nested implementation already carries the container for this id.
            outerContainers = new ArrayList<>(nestedContainers);
        } else {
            outerContainers = ModListInjector.rebuild(List.of(run.newFileInfo), run.newInfos);
            if (outerContainers.isEmpty()) {
                return fail(rollback, Stage.MODLIST, FailureKind.MODLIST, "no ModContainer built for " + ids);
            }
            containers.addAll(outerContainers);
        }
        run.containers = containers;
        run.outerContainers = outerContainers;

        String failAfterModListMod =
                System.getProperty("modreloader.failAfterModListModId", "").trim();
        if (Boolean.getBoolean("modreloader.failAfterModList")
                && (failAfterModListMod.isEmpty() || ids.contains(failAfterModListMod))) {
            return fail(rollback, Stage.MODLIST, FailureKind.UNKNOWN, "injected failure after ModList rebuild");
        }
        return null;
    }

    /** CONSTRUCT (+ guifei registry window) and the notice to connected clients. */
    private static Result construct(Run run) throws Throwable {
        Rollback rollback = run.rollback;
        Set<String> ids = run.ids;
        // 4. construct (+ guifei-tier registry content) + replay lifecycle events.
        //    A registry window must open before construction: some mods call
        //    Registry.register directly in the constructor. For registry content the normal
        //    startup order is construct → RegisterEvent → freeze → common setup.
        run.stagePlan.enter(Stage.CONSTRUCT);
        // Snapshot the game-bus owners before the mod is constructed: FML registers
        // its @EventBusSubscriber methods during construct, and a later failure must
        // take them off again (otherwise a retry registers them twice).
        EventBusSnapshot.Snapshot busSnapshot = EventBusSnapshot.of();
        run.busSnapshot = busSnapshot;
        rollback.add("event listeners", () -> {
            if (!run.constructed) {
                return;
            }
            int removed = EventBusSnapshot.restore(busSnapshot);
            if (removed >= 0) {
                ModReloader.LOGGER.info("[ModReloader] rollback: unregistered {} game-bus "
                        + "listener owner(s) registered by the failed construct", removed);
            } else {
                ModReloader.LOGGER.warn(
                        "[ModReloader] rollback: mod constructors/event listeners cannot be "
                                + "unregistered; only ModList/module/mixin state is restored");
            }
        });
        if (run.registryContent) {
            Capabilities.Result registryCapability =
                    Capabilities.get(Capabilities.Capability.REGISTRY_ADAPTATION);
            if (!registryCapability.available()) {
                return fail(rollback, Stage.VALIDATE, FailureKind.CAPABILITY,
                        registryCapability.describe());
            }
            RegistryStage.begin(rollback);
            CustomRegistries.open(rollback);
        }
        // XaeroLib freezes its config-channel registry at startup; the Xaero
        // mods register their channels during construct/common setup.
        if (XaeroLibChannels.appliesTo(run.primaryJar)) {
            XaeroLibChannels.open(rollback);
        }
        PlatformHooks hooks = PlatformHooks.get();
        run.hooks = hooks;
        run.keysBefore = hooks.keyMappingCount();
        for (ModContainer container : run.containers) {
            FmlEvents.construct(container);
            run.constructed = true;
            run.loaded.add(container.getModId());
        }
        if (run.registryContent) {
            run.customRegistryOpened = true;
            try {
                CustomRegistries.replay(run.containers);
            } catch (Throwable t) {
                return fail(rollback, Stage.CONSTRUCT, FailureKind.CUSTOM_REGISTRY,
                        "custom registry replay failed: " + t.getClass().getSimpleName()
                                + ": " + t.getMessage() + ". Restart with the jar in mods/.");
            }
        }
        String failAfterConstructMod =
                System.getProperty("modreloader.failAfterConstructModId", "").trim();
        if (Boolean.getBoolean("modreloader.failAfterConstruct")
                && (failAfterConstructMod.isEmpty() || ids.contains(failAfterConstructMod))) {
            return fail(rollback, Stage.CONSTRUCT, FailureKind.UNKNOWN,
                    "injected failure after construct (listeners are already registered)");
        }
        // Informing layer — deliberately BEFORE the registry commit and the data
        // pack reload. Measured: a connected client that lacks a content mod is
        // disconnected by vanilla's recipe sync (decoding `update_recipes` fails
        // on the unknown items) and afterwards cannot even rejoin (the login
        // snapshot has entries it does not know). A notice sent after that would
        // arrive at a dead connection, so it goes first, while the players can
        // still act on it.
        try {
            ModNotice.notifyPlayers(run.primaryJar, run.outerContainers, run.registryContent,
                    run.inspectionForNotice);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot tell connected clients about {}", ids, t);
        }
        if (run.registryContent) {
            int players = hooks.connectedPlayers().size();
            if (players > 0) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] {} player(s) connected: registry content cannot be undone. "
                                + "A client without this mod will be disconnected by the data pack "
                                + "sync and cannot rejoin until it installs the same jar.",
                        players);
            }
        }
        return null;
    }

    /** REGISTRY: commit content and refresh/push the client view. */
    private static Result commitRegistry(Run run) throws Throwable {
        if (!run.registryContent) {
            return null;
        }
        PlatformHooks hooks = run.hooks;
        run.stagePlan.enter(Stage.REGISTRY);
        // Set before the call: a commit that throws halfway has already added
        // entries that cannot be removed.
        run.registryCommitted = true;
        RegistryStage.commit(run.containers);
        if (Boolean.getBoolean("modreloader.failAfterRegistry")) {
            return fail(run.rollback, Stage.REGISTRY, FailureKind.UNKNOWN,
                    "injected failure after registry commit");
        }
        try {
            // Cosmetic refresh (creative tabs); never fail a committed add for it.
            hooks.afterRegistryContent();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] post-registry client refresh failed", t);
        }
        // Clients that are already connected cannot see the new entries
        // otherwise: NeoForge only syncs registries at login. Best effort —
        // the commit above cannot be undone.
        try {
            RegistrySync.broadcastAfterCommit();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot push the registry snapshot to connected clients", t);
        }
        // The other order: this side may itself be a client that just
        // added content, and only the server can say which ids to use.
        try {
            hooks.requestRegistrySnapshot();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot ask the server for its registry mapping", t);
        }
        return null;
    }

    /** EVENT: capabilities and common setup. */
    private static Result replayCommonStartup(Run run) throws Throwable {
        // 4a. capabilities: RegisterCapabilitiesEvent fires once at start-up
        //     (after the registry events, before common setup). A hot-added mod
        //     missed it, so item handlers / fluid tanks / energy providers are
        //     never registered and its blocks silently do nothing. Additive and
        //     dispatched to the new container only; never fail the add for it.
        Capabilities.Result capabilityReplay =
                Capabilities.get(Capabilities.Capability.CAPABILITY_REPLAY);
        if (capabilityReplay.available()) {
            run.capabilitiesReplayed = true;
            for (ModContainer container : run.containers) {
                CapabilityReplay.replay(container);
            }
        } else {
            ModReloader.LOGGER.warn("[ModReloader] capability replay skipped: {}",
                    capabilityReplay.describe());
        }
        run.stagePlan.enter(Stage.EVENT);
        FmlEvents.commonSetupAll(run.containers);
        return null;
    }

    /** PACK: client registrations, resource packs and the reload they need. */
    private static Result injectPacks(Run run) throws Throwable {
        Rollback rollback = run.rollback;
        PlatformHooks hooks = run.hooks;
        Set<String> ids = run.ids;
        Set<String> nestedIds = run.nestedIds;
        // 5. resource packs, after the mod had a chance to register content.
        //    A dedicated server has no resource pack repository.
        run.stagePlan.enter(Stage.PACK);
        // Startup-only client registrations (reload listeners among them) must be
        // in the manager before the reload starts: createReload snapshots the
        // listener list and only the live ResourceManager contains the mod pack.
        // This also is what makes client setup see the pack (issue 53): startup
        // runs the deferred tasks inside the initial reload, after the new
        // MultiPackResourceManager (which already lists every mod pack) is live.
        for (ModContainer container : run.containers) {
            hooks.registerClientRegistrations(container);
        }
        Capabilities.Result packCapability =
                Capabilities.get(Capabilities.Capability.RESOURCE_PACKS);
        if (packCapability.available()) {
            List<ModFileInfo> packInfos = new ArrayList<>(NestedMods.fileInfos(run.nestedMods));
            if (run.primaryNested == null) {
                packInfos.add(run.newFileInfo);
            }
            Set<String> packIds = new LinkedHashSet<>(ids);
            packIds.addAll(nestedIds);
            hooks.injectResourcePacks(packInfos, packIds, rollback);
        } else {
            // Resources are cosmetic; never fail an otherwise good add for this.
            ModReloader.LOGGER.warn("[ModReloader] resource pack injection skipped: {}",
                    packCapability.describe());
        }
        // A mixin may have added fields to already-created ShaderInstance objects
        // (Sodium's uniformCache) and the reload's loading overlay would dereference
        // the JVM default (null). Rebuild them after the pack is live: the event
        // runs for every container, so doing it before injection made a hot-added
        // mod's own shader JSON unreadable (ModernUI: modernui:shaders/... not found).
        if (run.mixinsApplied) {
            hooks.rebuildShaderInstances();
        }
        if (Boolean.getBoolean("modreloader.failAfterPack")) {
            return fail(rollback, Stage.PACK, FailureKind.UNKNOWN, "injected failure after resource pack");
        }
        return null;
    }

    /** CLIENT: side setup tasks, staged replay, payloads and commands. */
    private static Result runSidedStartup(Run run) throws Throwable {
        Rollback rollback = run.rollback;
        PlatformHooks hooks = run.hooks;
        // Client setup runs after the pack reload was started (PACK stage): its
        // deferred tasks read resources through the live ResourceManager, which
        // already lists the hot-added pack, exactly as at startup. Payload
        // adaptation must wait until those tasks ran (XaeroLib freezes its packet
        // handler registry in one of them), hence this stage sits in between.
        run.stagePlan.enter(Stage.CLIENT);
        FmlEvents.sidedSetupAll(run.containers);
        try {
            // Startup order: registries frozen -> common setup -> client setup ->
            // renderers. A mod's RegisterRenderers may resolve its own registry
            // entries (Mekanism's `mekanism:robit` is a DeferredHolder), so this
            // must run after the registry commit above; before it, the holder is
            // unbound and the event throws (measured: client crashed on the title
            // screen right after a "successful" hot-add). Layer definitions for
            // every container still run before any renderer rebuild, so nested
            // containers keep their outer layers.
            hooks.afterClientLifecycles(run.containers);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] staged client replay failed", t);
        }
        SodiumBootstrap.afterClientStartup(run.ids);
        run.keysAfter = hooks.keyMappingCount();

        // 4a. payloads: NeoForge closes its network registry after start-up, so
        //     reopen it for this mod only. Connections that already exist keep
        //     their stale setup and cannot use the new channels.
        for (ModContainer container : run.containers) {
            NetworkAdaptation.Result payloads = NetworkAdaptation.adapt(container);
            if (!payloads.ok()) {
                return fail(rollback, Stage.CONSTRUCT, FailureKind.NETWORK_PAYLOAD,
                        container.getModId() + " needs network payloads but they could not "
                                + "be adapted: " + payloads.error());
            }
            if (!payloads.added().isEmpty()) {
                run.payloadsAdapted = true;
                ModReloader.LOGGER.info(
                        "[ModReloader] adapted payloads for {}: {}; existing connections were "
                                + "patched too (requires the runtime on the other side)",
                        container.getModId(), payloads.added());
            }
        }

        // 4b. commands: the server fired RegisterCommandsEvent long ago, so a
        //     hot-added mod's listener never ran. Re-fire it now. On a client
        //     this targets the integrated server (and is a no-op at the title
        //     screen); the side decides, the engine does not.
        run.commandsReplayed = true;
        hooks.replayCommands();
        return null;
    }

    /** DATAPACK: the mod's data/ (recipes, loot, tags) is loaded by /reload. */
    private static Result reloadData(Run run) throws Throwable {
        PlatformHooks hooks = run.hooks;
        Set<String> ids = run.ids;
        // 6. datapacks: the mod's data/ (recipes, loot tables, tags) is loaded
        //    by the server's pack repository at world load, so a hot-added mod
        //    was never in it. Inject the jar as a data pack and run the vanilla
        //    /reload path. Ordering matters: the registry commit above must be
        //    done, otherwise recipe ingredients and loot item ids resolve to air.
        run.stagePlan.enter(Stage.DATAPACK);
        DatapackStage.Result datapack = hooks.reloadDataPacks(run.primaryInfo, ids);
        ModReloader.LOGGER.info("[ModReloader] datapack {}: {}",
                datapack.applied() ? "applied" : "skipped", datapack.detail());
        return null;
    }

    /** Post-success: verification log, client hook, one-shot server events. */
    private static Result succeed(Run run) throws Throwable {
        Set<String> ids = run.ids;
        for (String id : run.loaded) {
            ModReloader.LOGGER.info(
                    "[ModReloader] verify {}: isLoaded={}, container={}, keyMappings {} -> {}",
                    id, ModList.get().isLoaded(id),
                    ModList.get().getModContainerById(id).isPresent(),
                    run.keysBefore, run.keysAfter);
        }
        try {
            // Client: shader JSONs are in the new pack, so replay after its reload.
            run.hooks.afterHotAdd(run.containers);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] post-add client hook failed", t);
        }

        String message = "hot-added " + run.loaded + " (modules " + run.moduleNames + ")";
        if (!run.mixinSummary.isEmpty()) {
            message += run.mixinSummary.get(0);
        }
        if (!run.coremodSummary.isEmpty()) {
            message += run.coremodSummary.get(0);
        }
        // Server-level one-shot events (ServerStarting/ServerStarted) only for
        // this add's listeners. Runs last: nothing failure-prone follows, and
        // the mod's registries/data packs are already in place.
        ServerLifecycleReplay.replay(run.busSnapshot);
        XaeroLibChannels.close();
        ModReloader.LOGGER.info("[ModReloader] {}", message);
        return new Result(true, message);
    }

    /**
     * Marks the process dirty when this failure left something that cannot be
     * undone. The Result stays the same apart from a note, so existing failure
     * screens keep their kind; {@link RunState} is the machine-readable half.
     */
    private static Result recordFailure(Run run, Result failure) {
        RunState.Flags flags = new RunState.Flags(failure.rollbackPartial(),
                run.registryCommitted || RegistryStage.residue(),
                run.customRegistryOpened, run.constructed,
                run.mixinsSettled, run.payloadsAdapted, run.capabilitiesReplayed,
                run.commandsReplayed);
        RunState.Reason reason = RunState.reasonFor(flags);
        if (reason == null) {
            return failure;
        }
        RunState.markDirty(reason);
        RunStateWarner.ensureStarted(Platform.isClient());
        ModReloader.LOGGER.error(
                "[ModReloader] runtime is now dirty: {}. Further hot-adds are refused until "
                        + "a restart", reason.english());
        if (failure.message().contains("runtime is dirty")) {
            return failure;
        }
        String note = " (runtime is dirty: " + reason.english() + "; restart "
                + (Platform.isClient() ? "the game" : "the server") + " before adding more)";
        return new Result(false, failure.message() + note, failure.stage(), failure.kind(),
                failure.rollbackErrors());
    }

    /** Failure path for anything a stage threw instead of reporting. */
    private static Result unexpected(Run run, Throwable t) {
        Stage stage = run.stagePlan.current();
        ModReloader.LOGGER.error("[ModReloader] hot-add failed for {}", run.jar, t);
        String message = "failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
        if (run.constructed) {
            message += " (post-construct; event listeners may remain)";
        }
        if (stage == Stage.DATAPACK) {
            message += " (registry content is already committed; the runtime is dirty)";
        }
        if (run.mixinsApplied && stage != Stage.MIXIN && stage != Stage.MODULE
                && stage != Stage.VALIDATE) {
            // Mixin-injected code cannot be removed by class redefinition; a
            // later failure leaves handlers referencing classes that the
            // rollback just unloaded (NoClassDefFoundError at render time).
            message += " (mixins were already applied and cannot be undone; restart the game)";
            ModReloader.LOGGER.error(
                    "[ModReloader] mixins of {} were applied before the failure; the client is "
                            + "unstable until restarted", run.ids);
        }
        FailureKind kind = AddStagePlan.defaultFailure(stage);
        if (kind == FailureKind.CONSTRUCT || kind == FailureKind.UNKNOWN) {
            if (mentionsCustomRegistry(t)) {
                kind = FailureKind.CUSTOM_REGISTRY;
                message += " (the mod creates its own registry during construction; hot-add "
                        + "cannot do that without the registry window — restart with the jar in mods/)";
            }
        }
        return fail(run.rollback, stage, kind, message);
    }

    private static boolean mentionsCustomRegistry(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            String text = current.getMessage();
            if (text != null && text.contains("after NewRegistryEvent was fired")) {
                return true;
            }
        }
        return false;
    }

    /** AT composition for the refusal message; never throws. */
    private static String accessTransformerComposition(Path jar) {
        try {
            return dev.modreloader.inspect.AccessTransformerRules.read(jar).describe();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot read the access transformer of {}", jar, t);
            return "composition unavailable";
        }
    }

    private static Result fail(Rollback rollback, String message) {
        return fail(rollback, Stage.NONE, FailureKind.UNKNOWN, message);
    }

    private static Result fail(Rollback rollback, Stage stage, FailureKind kind, String message) {
        List<String> errors = rollback.run();
        if (!errors.isEmpty()) {
            kind = FailureKind.ROLLBACK_PARTIAL;
            message += "; rollback errors: " + errors;
        }
        ModReloader.LOGGER.error("[ModReloader] {}", message);
        return new Result(false, message, stage, kind, List.copyOf(errors));
    }
}