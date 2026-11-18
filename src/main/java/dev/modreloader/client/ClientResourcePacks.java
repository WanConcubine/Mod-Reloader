package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.hotadd.Rollback;
import dev.modreloader.hotadd.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.locating.IModFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client-only resource pack injection for a hot-added mod.
 *
 * <p>Moved out of {@link dev.modreloader.hotadd.HotAddEngine} so the engine
 * carries no client types.
 */
public final class ClientResourcePacks {
    private ClientResourcePacks() {
    }

    /**
     * @param newReloadListeners client reload listeners the add registered; they
     *                           only run on a reload, so the reload must not be
     *                           skipped when this is non-zero
     */
    public static void inject(ModFile modFile, ModFileInfo fileInfo, Set<String> modIds,
                              Rollback rollback, int newReloadListeners) {
        injectAll(List.of(fileInfo), modIds, rollback, newReloadListeners);
    }

    /**
     * Adds the packs of the outer mod <em>and</em> its nested mods in one reload.
     *
     * <p>Nested mods carry their own {@code assets/}: Xaero's Minimap bundles
     * XaeroLib, whose shaders live in {@code mod/xaerolib}. Injecting only the
     * outer pack made the reload fail with
     * {@code shaders/core/xaerolib/framebuffer_lines.json not found}. Doing one
     * pack at a time would also mean one full resource reload per nested jar.
     */
    public static void injectAll(List<ModFileInfo> fileInfos, Set<String> modIds,
                                 Rollback rollback, int newReloadListeners) {
        try {
            List<ModFileInfo> withResources = new ArrayList<>();
            for (ModFileInfo fileInfo : fileInfos) {
                if (hasClientResources((ModFile) fileInfo.getFile())) {
                    withResources.add(fileInfo);
                }
            }
            if (newReloadListeners == 0 && withResources.isEmpty()) {
                // Nothing to load: the jar has no assets/ (and no nested jar that
                // could carry some) and the add registered no reload listener, so
                // an empty mod pack would be selected and the whole resource
                // manager reloaded for nothing. That reload is not free: it races
                // chunk re-tessellation on the render workers (measured once as a
                // FluidSpriteCache NPE, status known issue 29) and costs seconds.
                ModReloader.LOGGER.info(
                        "[ModReloader] no client resources in {}; skipping the resource pack "
                                + "injection and reload", modIds);
                return;
            }
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
                requestReload(mc, "rollback");
            });
            for (ModFileInfo fileInfo : withResources) {
                packs.put((ModFile) fileInfo.getFile(), ResourcePackLoader.createPackForMod(fileInfo));
            }

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
                requestReload(mc, String.valueOf(added));
            } else {
                ModReloader.LOGGER.info("[ModReloader] no resource pack found for {}", modIds);
            }
            var ids = repository.getAvailablePacks().stream().map(Pack::getId).toList();
            ModReloader.LOGGER.info("[ModReloader] resource packs after add: {}", ids);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] resource pack injection failed", t);
        }
    }

    // --- serialized reloads -------------------------------------------------
    //
    // Minecraft coalesces reload requests poorly for our use: a mod added while
    // an earlier reload is still running can have its pack selected but *not*
    // loaded by that reload, and vanilla re-inits the current screen as soon as
    // the old reload finishes. A hot-added mod's ScreenEvent listener then runs
    // with its translations missing — measured with Jade:
    //   AssertionError: Missing config translation: config.jade.plugin_...
    // (crash-2026-11-17_20.13.47-client.txt). So reloads are serialized here,
    // a request during a reload marks the set dirty and re-runs afterwards, and
    // the screen stays hidden until the *last* reload (which sees every pack).

    private static boolean reloading;
    private static boolean dirty;
    private static Screen hiddenScreen;
    private static String reloadLabel = "?";

    /** Marks that another reload is needed once the running one finishes. */
    public static void requestReload(Minecraft mc, String label) {
        reloadLabel = label;
        if (reloading) {
            dirty = true;
            ModReloader.LOGGER.info(
                    "[ModReloader] resource reload already running; queued another for {}", label);
            return;
        }
        reloading = true;
        // Hide the screen for the whole chain: a re-init mid-chain would fire the
        // new mod's screen listeners before its language file is in the manager.
        if (hiddenScreen == null && mc.screen != null) {
            hiddenScreen = mc.screen;
            mc.screen = null;
        }
        mc.reloadResourcePacks().whenComplete((ignored, error) ->
                Platform.runOnMainThread(() -> {
                    if (error != null) {
                        ModReloader.LOGGER.warn("[ModReloader] resource reload failed", error);
                    }
                    reloading = false;
                    if (dirty) {
                        dirty = false;
                        requestReload(mc, reloadLabel);
                        return;
                    }
                    ModReloader.LOGGER.info(
                            "[ModReloader] resource reload finished after adding {}", reloadLabel);
                    Screen restore = hiddenScreen;
                    hiddenScreen = null;
                    if (restore != null && mc.screen == null) {
                        mc.setScreen(restore);
                        ModReloader.LOGGER.info(
                                "[ModReloader] screen restored after resource reload: {}",
                                restore.getClass().getSimpleName());
                    }
                }));
    }

    /** True while a hot-add reload (or a queued follow-up) is still running. */
    public static boolean isReloading() {
        return reloading || dirty;
    }

    /** True when the jar carries client resources. */
    private static boolean hasClientResources(ModFile modFile) {
        try {
            java.nio.file.Path root = modFile.getSecureJar().getRootPath();
            return java.nio.file.Files.isDirectory(root.resolve("assets"));
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot inspect the resources of {}",
                    modFile.getFileName(), t);
            // Unknown: reload, so nothing is silently skipped.
            return true;
        }
    }
}
