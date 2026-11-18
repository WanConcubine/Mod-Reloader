package dev.modreloader.hotadd;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.LoadingModList;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds FML's {@link ModList} with the existing mods plus the newly loaded one.
 */
public final class ModListInjector {
    public record Snapshot(List<IModFileInfo> files, List<IModInfo> infos,
                           List<ModContainer> containers,
                           List<ModFileInfo> loadingFiles,
                           List<ModInfo> loadingInfos,
                           Map<String, ModFileInfo> loadingFileById,
                           Map<ModInfo, List<ModInfo>> loadingDependencies) {
    }

    private ModListInjector() {
    }

    public static Snapshot snapshot() {
        ModList current = ModList.get();
        if (current == null) {
            throw new IllegalStateException("ModList is not initialized");
        }
        List<IModFileInfo> files = new ArrayList<>(UnsafeAccess.get(current, ModList.class, "modFiles"));
        List<IModInfo> infos = new ArrayList<>(UnsafeAccess.get(current, ModList.class, "sortedList"));
        List<ModContainer> containers = new ArrayList<>(
                UnsafeAccess.get(current, ModList.class, "mods"));
        LoadingModList loading = FMLLoader.getLoadingModList();
        if (loading == null) {
            return new Snapshot(files, infos, containers,
                    List.of(), List.of(), Map.of(), Map.of());
        }
        return new Snapshot(files, infos, containers,
                new ArrayList<>(UnsafeAccess.get(loading, LoadingModList.class, "modFiles")),
                new ArrayList<>(UnsafeAccess.get(loading, LoadingModList.class, "sortedList")),
                new HashMap<>(UnsafeAccess.get(loading, LoadingModList.class, "fileById")),
                new HashMap<>(UnsafeAccess.get(loading, LoadingModList.class, "modDependencies")));
    }

    public static void restore(Snapshot snapshot) throws Throwable {
        List<ModFile> files = new ArrayList<>(snapshot.files().size());
        for (IModFileInfo info : snapshot.files()) {
            files.add((ModFile) info.getFile());
        }
        List<ModInfo> infos = new ArrayList<>(snapshot.infos().size());
        for (IModInfo info : snapshot.infos()) {
            infos.add((ModInfo) info);
        }
        ModList.of(files, infos);
        MethodHandle setLoaded = UnsafeAccess.trusted().findVirtual(
                ModList.class, "setLoadedMods", MethodType.methodType(void.class, List.class));
        setLoaded.invoke(ModList.get(), snapshot.containers());
        UnsafeAccess.setStatic(ModLoader.class, "modList", ModList.get());

        LoadingModList loading = FMLLoader.getLoadingModList();
        if (loading != null) {
            UnsafeAccess.set(loading, LoadingModList.class, "modFiles",
                    new ArrayList<>(snapshot.loadingFiles()));
            UnsafeAccess.set(loading, LoadingModList.class, "sortedList",
                    new ArrayList<>(snapshot.loadingInfos()));
            UnsafeAccess.set(loading, LoadingModList.class, "fileById",
                    new HashMap<>(snapshot.loadingFileById()));
            UnsafeAccess.set(loading, LoadingModList.class, "modDependencies",
                    new HashMap<>(snapshot.loadingDependencies()));
        }
    }

    public static List<ModContainer> rebuild(List<ModFileInfo> newFiles, List<ModInfo> newInfos) throws Throwable {
        ModList current = ModList.get();
        if (current == null) {
            throw new IllegalStateException("ModList is not initialized");
        }

        List<IModFileInfo> oldFiles = UnsafeAccess.get(current, ModList.class, "modFiles");
        List<IModInfo> oldInfos = UnsafeAccess.get(current, ModList.class, "sortedList");
        List<ModContainer> oldContainers = UnsafeAccess.get(current, ModList.class, "mods");

        List<ModFile> modFiles = new ArrayList<>(oldFiles.size() + newFiles.size());
        for (IModFileInfo info : oldFiles) {
            modFiles.add((ModFile) info.getFile());
        }

        List<ModInfo> modInfos = new ArrayList<>(oldInfos.size() + newInfos.size());
        for (IModInfo info : oldInfos) {
            modInfos.add((ModInfo) info);
        }

        List<ModContainer> containers = new ArrayList<>(oldContainers);

        MethodHandle buildMods = UnsafeAccess.trusted().findStatic(
                ModLoader.class, "buildMods", MethodType.methodType(List.class, IModFile.class));

        for (ModFileInfo fileInfo : newFiles) {
            ModFile modFile = (ModFile) fileInfo.getFile();
            modFiles.add(modFile);
            @SuppressWarnings("unchecked")
            List<ModContainer> built = (List<ModContainer>) buildMods.invoke(modFile);
            containers.addAll(built);
        }
        modInfos.addAll(newInfos);

        ModList.of(modFiles, modInfos);

        MethodHandle setLoaded = UnsafeAccess.trusted().findVirtual(
                ModList.class, "setLoadedMods", MethodType.methodType(void.class, List.class));
        setLoaded.invoke(ModList.get(), containers);

        // FML keeps a second reference to the list in ModLoader.
        UnsafeAccess.setStatic(ModLoader.class, "modList", ModList.get());
        // Mods that call FMLLoader.getLoadingModList().getModFileById(their own
        // id) during setup (Xaero's World Map does) would otherwise get null:
        // LoadingModList is a separate static list that was never updated for a
        // hot-added jar.
        updateLoadingModList(newFiles, newInfos);

        return new ArrayList<>(containers.subList(oldContainers.size(), containers.size()));
    }

    private static void updateLoadingModList(List<ModFileInfo> newFiles,
                                             List<ModInfo> newInfos) throws Throwable {
        LoadingModList loading = FMLLoader.getLoadingModList();
        if (loading == null) {
            return;
        }
        List<ModFileInfo> files = new ArrayList<>(
                UnsafeAccess.get(loading, LoadingModList.class, "modFiles"));
        List<ModInfo> infos = new ArrayList<>(
                UnsafeAccess.get(loading, LoadingModList.class, "sortedList"));
        Map<String, ModFileInfo> fileById = new HashMap<>(
                UnsafeAccess.get(loading, LoadingModList.class, "fileById"));
        Map<ModInfo, List<ModInfo>> dependencies = new HashMap<>(
                UnsafeAccess.get(loading, LoadingModList.class, "modDependencies"));

        for (ModFileInfo file : newFiles) {
            if (!files.contains(file)) {
                files.add(file);
            }
        }
        for (ModInfo info : newInfos) {
            if (!infos.contains(info)) {
                infos.add(info);
            }
            fileById.put(info.getModId(), info.getOwningFile());
            dependencies.putIfAbsent(info, List.of());
        }

        UnsafeAccess.set(loading, LoadingModList.class, "modFiles", files);
        UnsafeAccess.set(loading, LoadingModList.class, "sortedList", infos);
        UnsafeAccess.set(loading, LoadingModList.class, "fileById", fileById);
        UnsafeAccess.set(loading, LoadingModList.class, "modDependencies", dependencies);
    }
}
