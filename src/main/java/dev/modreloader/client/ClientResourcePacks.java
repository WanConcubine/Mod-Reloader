package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.Rollback;
import dev.modreloader.hotadd.UnsafeAccess;
import net.minecraft.client.Minecraft;
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

    public static void inject(ModFile modFile, ModFileInfo fileInfo, Set<String> modIds,
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
