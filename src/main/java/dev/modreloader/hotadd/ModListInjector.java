package dev.modreloader.hotadd;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * Rebuilds FML's {@link ModList} with the existing mods plus the newly loaded one.
 */
public final class ModListInjector {
    public record Snapshot(List<IModFileInfo> files, List<IModInfo> infos,
                           List<ModContainer> containers) {
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
        return new Snapshot(files, infos, containers);
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

        return new ArrayList<>(containers.subList(oldContainers.size(), containers.size()));
    }
}
