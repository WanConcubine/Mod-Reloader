package dev.modreloader.hotadd;

import cpw.mods.jarhandling.JarContents;
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
 * {@link ModList} → construct the mod and replay client events → inject its
 * resource pack → apply its server data pack (recipes/loot/tags).
 *
 * <p>Still out of scope: datapack registries (dimension/biome/…), guifei/huanghou
 * content that needs a full restart, and soft restart.
 */
public final class HotAddEngine {
    public enum Stage {
        NONE, VALIDATE, MODULE, MIXIN, COREMOD, MODLIST, CONSTRUCT, REGISTRY, EVENT, PACK, DATAPACK
    }

    public enum FailureKind {
        NONE, MISSING_FILE, NOT_A_MOD, LIBRARY, INVALID_METADATA, ALREADY_LOADED, MISSING_DEPENDENCY, JBR_REQUIRED,
        MIXIN_RUNTIME, NETWORK_PAYLOAD, UNSUPPORTED_TIER, CAPABILITY, MIXIN_CONFIG, MIXIN_TARGET, REDEFINE, MODULE, MODLIST, REGISTRY,
        CONSTRUCT, EVENT, RESOURCE_PACK, DATAPACK, ACCESS_TRANSFORMER, CLIENT_REGISTRATION, JAR_IN_JAR,
        COREMOD, ROLLBACK_PARTIAL, UNKNOWN
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

        Rollback rollback = new Rollback();
        boolean[] constructed = {false};
        Stage stage = Stage.VALIDATE;
        // Visible to the catch block: a failure after mixins were applied leaves
        // injected handlers in already-loaded classes and needs a restart.
        Set<String> ids = new LinkedHashSet<>();
        boolean mixinsApplied = false;
        try {
            IModFile candidate = new JarModsDotTomlModFileReader()
                    .read(JarContents.of(jar), ModFileDiscoveryAttributes.DEFAULT);
            if (!(candidate instanceof ModFile modFile)) {
                if (ModInspector.isGameLibrary(jar)) {
                    return fail(rollback, Stage.VALIDATE, FailureKind.LIBRARY,
                            "library jar (FMLModType: GAMELIBRARY), not a mod: " + jar.getFileName());
                }
                return fail(rollback, Stage.VALIDATE, FailureKind.NOT_A_MOD, "not a NeoForge mod jar (no mods.toml): " + jar.getFileName());
            }

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
                ids.add(info.getModId());
            }
            String primaryId = ids.iterator().next();

            // A0 deliberately bypasses ModSorter/ModValidator: the isolated
            // validator would either mis-report already-loaded dependencies or
            // try to register mixin configs after Mixin is closed.
            modFile.identifyLanguage();
            ModFileScanData scanData = modFile.compileContent();
            if (scanData == null) {
                return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "annotation scan returned nothing for " + ids);
            }
            modFile.setScanResult(scanData, null);

            IModFileInfo rawFileInfo = modFile.getModFileInfo();
            if (!(rawFileInfo instanceof ModFileInfo newFileInfo)) {
                return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "no ModFileInfo produced for " + ids);
            }
            List<ModInfo> newInfos = new ArrayList<>();
            for (IModInfo info : modFile.getModInfos()) {
                newInfos.add((ModInfo) info);
            }
            if (newInfos.isEmpty()) {
                return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "no ModInfo produced for " + ids);
            }

            // Nested mods (jar-in-jar with a mods.toml) normally get a ModFile and
            // a container from FML's JarJar locator at startup. A hot-add skips
            // that locator, so build them here *before* the outer mod's dependency
            // check: Xaero's Minimap requires XaeroLib, which it bundles itself.
            stage = Stage.MODULE;
            JarInJar.Result nested;
            try {
                nested = JarInJar.extract(jar);
            } catch (Throwable t) {
                ModReloader.LOGGER.error("[ModReloader] cannot read jar-in-jar metadata of {}", jar, t);
                return fail(rollback, Stage.MODULE, FailureKind.JAR_IN_JAR,
                        "cannot read the jars nested in " + jar.getFileName() + ": " + t);
            }
            for (String skip : nested.skipped()) {
                ModReloader.LOGGER.warn("[ModReloader] jar-in-jar skipped {}: {}",
                        jar.getFileName(), skip);
            }
            Set<String> loadedIds = new LinkedHashSet<>();
            for (IModInfo modInfo : ModList.get().getMods()) {
                loadedIds.add(modInfo.getModId());
            }
            List<NestedMods.Loaded> nestedMods;
            try {
                nestedMods = NestedMods.prepare(nested, loadedIds);
            } catch (NestedMods.AccessTransformer e) {
                return fail(rollback, Stage.VALIDATE, FailureKind.ACCESS_TRANSFORMER,
                        ids + ": " + e.getMessage() + "; restart with the jar in mods/.");
            } catch (Throwable t) {
                ModReloader.LOGGER.error("[ModReloader] cannot prepare nested mods of {}", jar, t);
                return fail(rollback, Stage.MODULE, FailureKind.JAR_IN_JAR,
                        "cannot prepare the mods nested in " + jar.getFileName() + ": " + t);
            }
            Set<String> nestedIds = NestedMods.ids(nestedMods);
            if (!nested.nested().isEmpty()) {
                ModReloader.LOGGER.info("[ModReloader] jar-in-jar: {} nested jar(s) from {} -> modules {}; containers {}",
                        nested.nested().size(), jar.getFileName(),
                        nested.nested().stream().map(JarInJar.Nested::moduleName).toList(),
                        nestedIds);
            }

            List<ModFileParser.MixinConfig> declaredMixins = modFile.getMixinConfigs();
            List<ModFileParser.MixinConfig> mixinConfigs = new ArrayList<>(
                    declaredMixins == null ? List.of() : declaredMixins);
            mixinConfigs.addAll(NestedMods.mixins(nestedMods));
            // GAMELIBRARY nested jars have no ModFile, so their manifest
            // MixinConfigs are not part of getMixinConfigs().
            Set<String> knownConfigs = new LinkedHashSet<>();
            mixinConfigs.forEach(config -> knownConfigs.add(config.config()));
            for (String config : nested.libraryMixinConfigs()) {
                if (knownConfigs.add(config)) {
                    mixinConfigs.add(new ModFileParser.MixinConfig(config, List.of()));
                }
            }

            ModReloader.LOGGER.info("[ModReloader] hot-adding {} from {} (mixin configs: {})",
                    ids, jar.getFileName(),
                    mixinConfigs.stream().map(ModFileParser.MixinConfig::config).toList());

            // Guifei-tier detection: the analyzer already predicts registry content for
            // the UI; use the same signal to decide whether the engine must open
            // a registry window around construction.
            boolean registryContent = false;
            ModInspection inspectionForNotice = null;
            try {
                ModInspection inspection = ModInspector.inspect(jar);
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
                if (inspection.ok() && inspection.accessTransformers()) {
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
                RegistrationAudit.Report audit = RegistrationAuditScanner.audit(jar, Platform.isClient());
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
            if (DatapackStage.hasData(newFileInfo)) {
                Capabilities.Result datapackCapability =
                        Capabilities.get(Capabilities.Capability.DATAPACK_RELOAD);
                if (!datapackCapability.available()) {
                    return fail(rollback, Stage.VALIDATE, FailureKind.CAPABILITY,
                            datapackCapability.describe());
                }
            }

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

            ModuleInjector.Snapshot moduleSnapshot = ModuleInjector.snapshot();
            rollback.add("module layer", () -> ModuleInjector.restore(moduleSnapshot));
            List<ModFileInfo> newFileInfos = new ArrayList<>(NestedMods.fileInfos(nestedMods));
            newFileInfos.add(newFileInfo);
            Set<String> moduleNames = ModuleInjector.addToGameLayer(
                    newFileInfos, nested.libraryJars());

            // 2. mixin: register configs, then redefine already-loaded targets.
            //    MixinStage keeps every MixinRuntime/MixinSupport reference behind
            //    one call, so a Type2 mod still works when mr is not installed.
            stage = Stage.MIXIN;
            List<String> mixinSummary = new ArrayList<>();
            Set<String> hotIds = new LinkedHashSet<>(ids);
            hotIds.addAll(nestedIds);
            if (!mixinConfigs.isEmpty()) {
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
                mixinsApplied = true;
                List<String> mixinLog = new ArrayList<>();
                String mixinError = MixinStage.register(mixinConfigs, hotIds, primaryId, rollback,
                        mixinLog, mixinSummary);
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
            } else {
                ModReloader.LOGGER.info("[ModReloader] no mixin configs declared by {}", ids);
            }

            // 2b. coremods: ModLauncher ran the mod's ICoreMod transformers while
            //     loading classes, which a hot-added jar missed entirely - the mod
            //     loads and its coremod feature is silently dead (jecharacters ->
            //     pinyin search). Apply the same transformers late.
            stage = Stage.COREMOD;
            List<String> coremodSummary = new ArrayList<>();
            LateCoremod.Report coremods = LateCoremod.apply(coremodJars, rollback);
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
                coremodSummary.add(" (coremods " + coremods.coremods() + ", redefined "
                        + coremods.redefined() + ", transformed-at-load " + coremods.eager() + ")");
            }

            // 3. ModList: FML now knows about the mod.
            stage = Stage.MODLIST;
            ModListInjector.Snapshot modListSnapshot = ModListInjector.snapshot();
            rollback.add("mod list", () -> ModListInjector.restore(modListSnapshot));
            List<ModContainer> containers = new ArrayList<>();
            if (!nestedMods.isEmpty()) {
                List<ModContainer> nestedContainers = ModListInjector.rebuild(
                        NestedMods.fileInfos(nestedMods), NestedMods.modInfos(nestedMods));
                if (nestedContainers.isEmpty()) {
                    return fail(rollback, Stage.MODLIST, FailureKind.MODLIST,
                            "no ModContainer built for nested mods " + nestedIds);
                }
                ModReloader.LOGGER.info("[ModReloader] nested mods registered: {}",
                        nestedContainers.stream().map(ModContainer::getModId).toList());
                containers.addAll(nestedContainers);
            }
            List<ModContainer> outerContainers = ModListInjector.rebuild(List.of(newFileInfo), newInfos);
            if (outerContainers.isEmpty()) {
                return fail(rollback, Stage.MODLIST, FailureKind.MODLIST, "no ModContainer built for " + ids);
            }
            containers.addAll(outerContainers);

            if (Boolean.getBoolean("modreloader.failAfterModList")) {
                return fail(rollback, Stage.MODLIST, FailureKind.UNKNOWN, "injected failure after ModList rebuild");
            }

            // 4. construct (+ guifei-tier registry content) + replay lifecycle events.
            //    A registry window must open before construction: some mods call
            //    Registry.register directly in the constructor. For registry content the normal
            //    startup order is construct → RegisterEvent → freeze → common setup.
            stage = Stage.CONSTRUCT;
            // Snapshot the game-bus owners before the mod is constructed: FML registers
            // its @EventBusSubscriber methods during construct, and a later failure must
            // take them off again (otherwise a retry registers them twice).
            EventBusSnapshot.Snapshot busSnapshot = EventBusSnapshot.of();
            rollback.add("event listeners", () -> {
                if (!constructed[0]) {
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
            if (registryContent) {
                Capabilities.Result registryCapability =
                        Capabilities.get(Capabilities.Capability.REGISTRY_ADAPTATION);
                if (!registryCapability.available()) {
                    return fail(rollback, Stage.VALIDATE, FailureKind.CAPABILITY,
                            registryCapability.describe());
                }
                RegistryStage.begin(rollback);
            }
            PlatformHooks hooks = PlatformHooks.get();
            int keysBefore = hooks.keyMappingCount();
            List<String> loaded = new ArrayList<>();
            for (ModContainer container : containers) {
                FmlEvents.construct(container);
                constructed[0] = true;
                loaded.add(container.getModId());
            }
            if (Boolean.getBoolean("modreloader.failAfterConstruct")) {
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
                ModNotice.notifyPlayers(jar, outerContainers, registryContent, inspectionForNotice);
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] cannot tell connected clients about {}", ids, t);
            }
            if (registryContent) {
                int players = hooks.connectedPlayers().size();
                if (players > 0) {
                    ModReloader.LOGGER.warn(
                            "[ModReloader] {} player(s) connected: registry content cannot be undone. "
                                    + "A client without this mod will be disconnected by the data pack "
                                    + "sync and cannot rejoin until it installs the same jar.",
                            players);
                }
            }

            if (registryContent) {
                stage = Stage.REGISTRY;
                RegistryStage.commit(containers);
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
            }
            // 4a. capabilities: RegisterCapabilitiesEvent fires once at start-up
            //     (after the registry events, before common setup). A hot-added mod
            //     missed it, so item handlers / fluid tanks / energy providers are
            //     never registered and its blocks silently do nothing. Additive and
            //     dispatched to the new container only; never fail the add for it.
            Capabilities.Result capabilityReplay =
                    Capabilities.get(Capabilities.Capability.CAPABILITY_REPLAY);
            if (capabilityReplay.available()) {
                for (ModContainer container : containers) {
                    CapabilityReplay.replay(container);
                }
            } else {
                ModReloader.LOGGER.warn("[ModReloader] capability replay skipped: {}",
                        capabilityReplay.describe());
            }
            stage = Stage.EVENT;
            FmlEvents.finishAll(containers);
            int keysAfter = hooks.keyMappingCount();

            // 4a. payloads: NeoForge closes its network registry after start-up, so
            //     reopen it for this mod only. Connections that already exist keep
            //     their stale setup and cannot use the new channels.
            for (ModContainer container : containers) {
                NetworkAdaptation.Result payloads = NetworkAdaptation.adapt(container);
                if (!payloads.ok()) {
                    return fail(rollback, Stage.CONSTRUCT, FailureKind.NETWORK_PAYLOAD,
                            container.getModId() + " needs network payloads but they could not "
                                    + "be adapted: " + payloads.error());
                }
                if (!payloads.added().isEmpty()) {
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
            hooks.replayCommands();

            // 5. resource packs, after the mod had a chance to register content.
            //    A dedicated server has no resource pack repository.
            stage = Stage.PACK;
            Capabilities.Result packCapability =
                    Capabilities.get(Capabilities.Capability.RESOURCE_PACKS);
            if (packCapability.available()) {
                List<ModFileInfo> packInfos = new ArrayList<>(NestedMods.fileInfos(nestedMods));
                packInfos.add(newFileInfo);
                Set<String> packIds = new LinkedHashSet<>(ids);
                packIds.addAll(nestedIds);
                hooks.injectResourcePacks(packInfos, packIds, rollback);
            } else {
                // Resources are cosmetic; never fail an otherwise good add for this.
                ModReloader.LOGGER.warn("[ModReloader] resource pack injection skipped: {}",
                        packCapability.describe());
            }
            if (Boolean.getBoolean("modreloader.failAfterPack")) {
                return fail(rollback, Stage.PACK, FailureKind.UNKNOWN, "injected failure after resource pack");
            }

            // 6. datapacks: the mod's data/ (recipes, loot tables, tags) is loaded
            //    by the server's pack repository at world load, so a hot-added mod
            //    was never in it. Inject the jar as a data pack and run the vanilla
            //    /reload path. Ordering matters: the registry commit above must be
            //    done, otherwise recipe ingredients and loot item ids resolve to air.
            stage = Stage.DATAPACK;
            DatapackStage.Result datapack = hooks.reloadDataPacks(newFileInfo, ids);
            ModReloader.LOGGER.info("[ModReloader] datapack {}: {}",
                    datapack.applied() ? "applied" : "skipped", datapack.detail());

            for (String id : loaded) {
                ModReloader.LOGGER.info(
                        "[ModReloader] verify {}: isLoaded={}, container={}, keyMappings {} -> {}",
                        id, ModList.get().isLoaded(id),
                        ModList.get().getModContainerById(id).isPresent(),
                        keysBefore, keysAfter);
            }
            try {
                // Client: shader JSONs are in the new pack, so replay after its reload.
                hooks.afterHotAdd(containers);
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] post-add client hook failed", t);
            }

            String message = "hot-added " + loaded + " (modules " + moduleNames + ")";
            if (!mixinSummary.isEmpty()) {
                message += mixinSummary.get(0);
            }
            if (!coremodSummary.isEmpty()) {
                message += coremodSummary.get(0);
            }
            ModReloader.LOGGER.info("[ModReloader] {}", message);
            return new Result(true, message);
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] hot-add failed for {}", jar, t);
            String message = "failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            if (constructed[0]) {
                message += " (post-construct; event listeners may remain)";
            }
            if (stage == Stage.DATAPACK) {
                message += " (registry content is already committed; the runtime is dirty)";
            }
            if (mixinsApplied && stage != Stage.MIXIN && stage != Stage.MODULE
                    && stage != Stage.VALIDATE) {
                // Mixin-injected code cannot be removed by class redefinition; a
                // later failure leaves handlers referencing classes that the
                // rollback just unloaded (NoClassDefFoundError at render time).
                message += " (mixins were already applied and cannot be undone; restart the game)";
                ModReloader.LOGGER.error(
                        "[ModReloader] mixins of {} were applied before the failure; the client is "
                                + "unstable until restarted", ids);
            }
            return fail(rollback, stage, kindFor(stage), message);
        }
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

    private static FailureKind kindFor(Stage stage) {
        return switch (stage) {
            case MIXIN -> FailureKind.MIXIN_CONFIG;
            case COREMOD -> FailureKind.COREMOD;
            case MODULE -> FailureKind.MODULE;
            case MODLIST -> FailureKind.MODLIST;
            case CONSTRUCT -> FailureKind.CONSTRUCT;
            case REGISTRY -> FailureKind.REGISTRY;
            case EVENT -> FailureKind.EVENT;
            case PACK -> FailureKind.RESOURCE_PACK;
            case DATAPACK -> FailureKind.DATAPACK;
            case VALIDATE -> FailureKind.INVALID_METADATA;
            case NONE -> FailureKind.UNKNOWN;
        };
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
