package dev.modreloader.hotadd;

import cpw.mods.jarhandling.JarContents;
import dev.modreloader.ModReloader;
import dev.modreloader.capability.Capabilities;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.inspect.ModInspection;
import dev.modreloader.inspect.ModInspector;
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
 * resource pack.
 *
 * <p>Still out of scope: datapack registries, guifei/huanghou content that needs a
 * full restart, and soft restart.
 */
public final class HotAddEngine {
    public enum Stage {
        NONE, VALIDATE, MODULE, MIXIN, MODLIST, CONSTRUCT, REGISTRY, EVENT, PACK
    }

    public enum FailureKind {
        NONE, MISSING_FILE, NOT_A_MOD, INVALID_METADATA, ALREADY_LOADED, MISSING_DEPENDENCY, JBR_REQUIRED,
        MIXIN_RUNTIME, NETWORK_PAYLOAD, UNSUPPORTED_TIER, CAPABILITY, MIXIN_CONFIG, MIXIN_TARGET, REDEFINE, MODULE, MODLIST, REGISTRY,
        CONSTRUCT, EVENT, RESOURCE_PACK, ROLLBACK_PARTIAL, UNKNOWN
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
        try {
            IModFile candidate = new JarModsDotTomlModFileReader()
                    .read(JarContents.of(jar), ModFileDiscoveryAttributes.DEFAULT);
            if (!(candidate instanceof ModFile modFile)) {
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
                return fail(rollback, Stage.VALIDATE, FailureKind.INVALID_METADATA, "jar declares no mods: " + jar.getFileName());
            }

            Set<String> ids = new LinkedHashSet<>();
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

            List<ModFileParser.MixinConfig> mixinConfigs = modFile.getMixinConfigs();
            if (mixinConfigs == null) {
                mixinConfigs = List.of();
            }

            ModReloader.LOGGER.info("[ModReloader] hot-adding {} from {} (mixin configs: {})",
                    ids, jar.getFileName(),
                    mixinConfigs.stream().map(ModFileParser.MixinConfig::config).toList());

            // Guifei-tier detection: the analyzer already predicts registry content for
            // the UI; use the same signal to decide whether the engine must open
            // a registry window around construction.
            boolean registryContent = false;
            try {
                ModInspection inspection = ModInspector.inspect(jar);
                registryContent = inspection.ok() && inspection.registry();
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] cannot predict registry content for {}", jar, t);
            }
            ModReloader.LOGGER.info("[ModReloader] registry content detected: {}", registryContent);

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

            // 1. module: the new mod's classes must be resolvable before Mixin
            //    reads its config or the mod constructor runs.
            stage = Stage.MODULE;
            ModuleInjector.Snapshot moduleSnapshot = ModuleInjector.snapshot();
            rollback.add("module layer", () -> ModuleInjector.restore(moduleSnapshot));
            Set<String> moduleNames = ModuleInjector.addToGameLayer(List.of(newFileInfo));

            // 2. mixin: register configs, then redefine already-loaded targets.
            //    MixinStage keeps every MixinRuntime/MixinSupport reference behind
            //    one call, so a Type2 mod still works when mr is not installed.
            stage = Stage.MIXIN;
            List<String> mixinSummary = new ArrayList<>();
            if (!mixinConfigs.isEmpty()) {
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
                List<String> mixinLog = new ArrayList<>();
                String mixinError = MixinStage.register(mixinConfigs, ids, primaryId, rollback,
                        mixinLog, mixinSummary);
                mixinLog.forEach(ModReloader.LOGGER::info);
                if (mixinError != null) {
                    return fail(rollback, Stage.MIXIN, FailureKind.MIXIN_CONFIG, "mixin setup failed for " + ids + ": " + mixinError);
                }
            } else {
                ModReloader.LOGGER.info("[ModReloader] no mixin configs declared by {}", ids);
            }

            // 3. ModList: FML now knows about the mod.
            stage = Stage.MODLIST;
            ModListInjector.Snapshot modListSnapshot = ModListInjector.snapshot();
            rollback.add("mod list", () -> ModListInjector.restore(modListSnapshot));
            List<ModContainer> containers = ModListInjector.rebuild(List.of(newFileInfo), newInfos);
            if (containers.isEmpty()) {
                return fail(rollback, Stage.MODLIST, FailureKind.MODLIST, "no ModContainer built for " + ids);
            }

            if (Boolean.getBoolean("modreloader.failAfterModList")) {
                return fail(rollback, Stage.MODLIST, FailureKind.UNKNOWN, "injected failure after ModList rebuild");
            }

            // 4. construct (+ guifei-tier registry content) + replay lifecycle events.
            //    A registry window must open before construction: some mods call
            //    Registry.register directly in the constructor. For registry content the normal
            //    startup order is construct → RegisterEvent → freeze → common setup.
            stage = Stage.CONSTRUCT;
            rollback.add("event listeners", () -> {
                if (constructed[0]) {
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
            }
            stage = Stage.EVENT;
            for (ModContainer container : containers) {
                FmlEvents.finish(container);
            }
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
                hooks.injectResourcePack(modFile, newFileInfo, ids, rollback);
            } else {
                // Resources are cosmetic; never fail an otherwise good add for this.
                ModReloader.LOGGER.warn("[ModReloader] resource pack injection skipped: {}",
                        packCapability.describe());
            }
            if (Boolean.getBoolean("modreloader.failAfterPack")) {
                return fail(rollback, Stage.PACK, FailureKind.UNKNOWN, "injected failure after resource pack");
            }

            for (String id : loaded) {
                ModReloader.LOGGER.info(
                        "[ModReloader] verify {}: isLoaded={}, container={}, keyMappings {} -> {}",
                        id, ModList.get().isLoaded(id),
                        ModList.get().getModContainerById(id).isPresent(),
                        keysBefore, keysAfter);
            }

            String message = "hot-added " + loaded + " (modules " + moduleNames + ")";
            if (!mixinSummary.isEmpty()) {
                message += mixinSummary.get(0);
            }
            ModReloader.LOGGER.info("[ModReloader] {}", message);
            return new Result(true, message);
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] hot-add failed for {}", jar, t);
            String message = "failed: " + t.getClass().getSimpleName() + ": " + t.getMessage();
            if (constructed[0]) {
                message += " (post-construct; event listeners may remain)";
            }
            return fail(rollback, stage, kindFor(stage), message);
        }
    }

    private static FailureKind kindFor(Stage stage) {
        return switch (stage) {
            case MIXIN -> FailureKind.MIXIN_CONFIG;
            case MODULE -> FailureKind.MODULE;
            case MODLIST -> FailureKind.MODLIST;
            case CONSTRUCT -> FailureKind.CONSTRUCT;
            case REGISTRY -> FailureKind.REGISTRY;
            case EVENT -> FailureKind.EVENT;
            case PACK -> FailureKind.RESOURCE_PACK;
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
