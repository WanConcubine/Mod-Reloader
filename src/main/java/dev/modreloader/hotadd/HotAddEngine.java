package dev.modreloader.hotadd;

import cpw.mods.jarhandling.JarContents;
import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.server.ServerCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.neoforged.neoforge.resource.ResourcePackLoader;
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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hot-add engine.
 *
 * <p>Pipeline: read the jar → inject its module into the game layer → register
 * its mixin configs (redefining already-loaded targets with JBR) → rebuild
 * {@link ModList} → construct the mod and replay client events → inject its
 * resource pack.
 *
 * <p>Still out of scope: registry content (T4/T5) and soft restart.
 */
public final class HotAddEngine {
    public enum Stage {
        NONE, VALIDATE, MODULE, MIXIN, MODLIST, CONSTRUCT, EVENT, PACK
    }

    public enum FailureKind {
        NONE, MISSING_FILE, NOT_A_MOD, INVALID_METADATA, ALREADY_LOADED, MISSING_DEPENDENCY, JBR_REQUIRED,
        MIXIN_RUNTIME, NETWORK_PAYLOAD, UNSUPPORTED_TIER, MIXIN_CONFIG, MIXIN_TARGET, REDEFINE, MODULE, MODLIST,
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

            // 4. construct + replay client lifecycle events.
            stage = Stage.CONSTRUCT;
            rollback.add("event listeners", () -> {
                if (constructed[0]) {
                    ModReloader.LOGGER.warn(
                            "[ModReloader] rollback: mod constructors/event listeners cannot be "
                                    + "unregistered; only ModList/module/mixin state is restored");
                }
            });
            int keysBefore = Platform.isClient()
                    ? Minecraft.getInstance().options.keyMappings.length : -1;
            List<String> loaded = new ArrayList<>();
            for (ModContainer container : containers) {
                FmlEvents.constructAndInit(container);
                constructed[0] = true;
                loaded.add(container.getModId());
            }
            int keysAfter = Platform.isClient()
                    ? Minecraft.getInstance().options.keyMappings.length : -1;

            // 4a. payloads: NeoForge forbids registering them after startup, so a
            //     mod that needs them would load but never talk to clients.
            for (ModContainer container : containers) {
                String payloadError = FmlEvents.probeNetworkPayloads(container);
                if (payloadError != null) {
                    return fail(rollback, Stage.CONSTRUCT, FailureKind.NETWORK_PAYLOAD,
                            container.getModId() + " registers network payloads, which NeoForge "
                                    + "forbids after startup: " + payloadError);
                }
            }

            // 4b. commands: the server fired RegisterCommandsEvent long ago, so a
            //     hot-added mod's listener never ran. Re-fire it now.
            if (ServerCommands.available()) {
                ServerCommands.replay();
            }

            // 5. resource packs, after the mod had a chance to register content.
            //    A dedicated server has no resource pack repository.
            stage = Stage.PACK;
            if (Platform.isClient()) {
                injectResourcePack(modFile, newFileInfo, ids, rollback);
            } else {
                ModReloader.LOGGER.info(
                        "[ModReloader] server side: skipping resource pack injection for {}", ids);
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

    private static void injectResourcePack(ModFile modFile, ModFileInfo fileInfo, Set<String> modIds,
                                           Rollback rollback) {
        try {
            Minecraft mc = Minecraft.getInstance();
            Map<IModFile, Object> packs = UnsafeAccess.getStatic(ResourcePackLoader.class, "modResourcePacks");
            if (packs == null) {
                packs = new HashMap<>();
                UnsafeAccess.setStatic(ResourcePackLoader.class, "modResourcePacks", packs);
            }
            var repository = mc.getResourcePackRepository();
            Map<IModFile, Object> packsBefore = new HashMap<>(packs);
            List<String> selectedBefore = repository.getSelectedPacks().stream()
                    .map(Pack::getId)
                    .toList();
            rollback.add("resource packs", () -> {
                Map<IModFile, Object> current = UnsafeAccess.getStatic(
                        ResourcePackLoader.class, "modResourcePacks");
                if (current == null) {
                    current = new HashMap<>();
                    UnsafeAccess.setStatic(ResourcePackLoader.class, "modResourcePacks", current);
                }
                // Restore in place: pack finders hold a reference to this map.
                current.clear();
                current.putAll(packsBefore);
                List<String> selected = new ArrayList<>(selectedBefore);
                selected.removeIf(id -> modIds.stream().anyMatch(modId ->
                        id.equals("mod/" + modId) || id.startsWith("mod/" + modId + "/")));
                repository.setSelected(selected);
                repository.reload();
                ModReloader.LOGGER.info("[ModReloader] rollback: resource packs selected={}",
                        repository.getSelectedPacks().stream().map(Pack::getId).toList());
                mc.reloadResourcePacks();
            });
            packs.put(modFile, ResourcePackLoader.createPackForMod(fileInfo));

            ResourcePackLoader.populatePackRepository(repository, PackType.CLIENT_RESOURCES, true);
            repository.reload();

            // Mod packs are not "required", so PackRepository does not select
            // them automatically. Select the packs that belong to the new mods
            // and reload; reloadResourcePacks() also refreshes the language
            // manager, so the mod's translations are usable immediately.
            var added = repository.getAvailablePacks().stream()
                    .map(Pack::getId)
                    .filter(id -> modIds.stream().anyMatch(modId ->
                            id.equals("mod/" + modId) || id.startsWith("mod/" + modId + "/")))
                    .toList();
            if (!added.isEmpty()) {
                var selected = new ArrayList<String>(repository.getSelectedIds());
                for (String id : added) {
                    if (!selected.contains(id)) {
                        selected.add(0, id);
                    }
                }
                repository.setSelected(selected);
                repository.reload();
                ModReloader.LOGGER.info("[ModReloader] resource packs selected after add: {} (new {})",
                        repository.getSelectedPacks().stream().map(Pack::getId).toList(), added);
                mc.reloadResourcePacks().thenRun(() -> ModReloader.LOGGER.info(
                        "[ModReloader] resource reload finished after adding {}", added));
            } else {
                ModReloader.LOGGER.info("[ModReloader] no resource pack found for {}", modIds);
            }
            var ids = repository.getAvailablePacks().stream().map(Pack::getId).toList();
            ModReloader.LOGGER.info("[ModReloader] resource packs after add: {}", ids);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] resource pack injection failed", t);
        }
    }
}
