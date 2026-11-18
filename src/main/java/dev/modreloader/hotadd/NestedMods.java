package dev.modreloader.hotadd;

import cpw.mods.jarhandling.JarContents;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import net.neoforged.fml.loading.moddiscovery.ModInfo;
import net.neoforged.fml.loading.moddiscovery.readers.JarModsDotTomlModFileReader;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Nested jars that are mods, not just libraries.
 *
 * <p>NeoForge's startup {@code JarInJarDependencyLocator} turns every nested jar
 * with a {@code mods.toml} into a real mod file and container. A hot-added outer
 * mod skipped that locator; {@link JarInJar} restores the class path but, until
 * this class existed, the nested mod had no container. Real jars hit that:
 * Xaero's Minimap bundles XaeroLib and declares it as a required dependency,
 * JourneyMap bundles commonnetworking.
 *
 * <p>This builds the same {@link ModFile}/{@link ModFileInfo}/{@link ModInfo}
 * objects the outer jar gets, so the engine can register the nested mods as
 * containers before the outer mod, satisfying required dependencies in the
 * order FML would have used at startup.
 */
public final class NestedMods {
    /** One nested jar that declares at least one mod. */
    public record Loaded(Path path, ModFile file, ModFileInfo info, List<ModInfo> infos,
                         List<ModFileParser.MixinConfig> mixins) {
    }

    /** A nested jar that declares an access transformer: still startup-only. */
    public static final class AccessTransformer extends Exception {
        public AccessTransformer(String message) {
            super(message);
        }
    }

    private NestedMods() {
    }

    /**
     * @param alreadyLoaded mod ids that are already in the ModList (a dependency
     *                      was installed normally); that nested jar is not rebuilt
     */
    public static List<Loaded> prepare(JarInJar.Result extracted, Set<String> alreadyLoaded)
            throws Exception {
        List<Loaded> prepared = new ArrayList<>();
        for (JarInJar.Nested nested : extracted.nested()) {
            if (!nested.declaresMod()) {
                continue;
            }
            IModFile candidate = new JarModsDotTomlModFileReader()
                    .read(JarContents.of(nested.file()), ModFileDiscoveryAttributes.DEFAULT);
            if (!(candidate instanceof ModFile file)) {
                throw new IllegalStateException("no ModFile for nested jar " + nested.declaredPath());
            }
            if (!file.identifyMods()) {
                throw new IllegalStateException("cannot identify nested mod in " + nested.declaredPath());
            }
            file.identifyLanguage();
            var scanData = file.compileContent();
            if (scanData == null) {
                throw new IllegalStateException("annotation scan returned nothing for "
                        + nested.declaredPath());
            }
            file.setScanResult(scanData, null);
            // FML lists an empty AT file too (codecui ships one); only files with
            // content are a real access need.
            List<Path> usedAts = new ArrayList<>();
            for (Path at : file.getAccessTransformers()) {
                try {
                    if (Files.size(at) > 0) {
                        usedAts.add(at);
                    }
                } catch (Throwable unreadable) {
                    usedAts.add(at);
                }
            }
            if (!usedAts.isEmpty()) {
                throw new AccessTransformer("nested mod " + nested.declaredPath()
                        + " ships " + usedAts.size()
                        + " access transformer file(s); access transformers only apply at startup");
            }
            net.neoforged.neoforgespi.language.IModFileInfo rawInfo = file.getModFileInfo();
            if (!(rawInfo instanceof ModFileInfo info)) {
                // A GAMELIBRARY has a DefaultModFileInfo and no ModContainer; it is
                // handled as a library by JarInJar and must not reach this path.
                throw new IllegalStateException("nested jar is not a mod file: "
                        + nested.declaredPath());
            }
            List<ModInfo> infos = new ArrayList<>();
            boolean loaded = false;
            for (IModInfo modInfo : file.getModInfos()) {
                infos.add((ModInfo) modInfo);
                loaded |= alreadyLoaded.contains(modInfo.getModId());
            }
            if (infos.isEmpty()) {
                throw new IllegalStateException("nested jar declares no mods: " + nested.declaredPath());
            }
            if (loaded) {
                // Already provided by a normally installed jar; adding the same
                // mod id twice would create a duplicate container.
                continue;
            }
            prepared.add(new Loaded(nested.file(), file, info, List.copyOf(infos),
                    file.getMixinConfigs() == null ? List.of() : file.getMixinConfigs()));
        }
        return prepared;
    }

    public static Set<String> ids(List<Loaded> loaded) {
        Set<String> ids = new LinkedHashSet<>();
        for (Loaded entry : loaded) {
            for (ModInfo info : entry.infos()) {
                ids.add(info.getModId());
            }
        }
        return ids;
    }

    public static List<ModFileInfo> fileInfos(List<Loaded> loaded) {
        return loaded.stream().map(Loaded::info).toList();
    }

    public static List<ModInfo> modInfos(List<Loaded> loaded) {
        List<ModInfo> result = new ArrayList<>();
        for (Loaded entry : loaded) {
            result.addAll(entry.infos());
        }
        return result;
    }

    public static List<ModFileParser.MixinConfig> mixins(List<Loaded> loaded) {
        List<ModFileParser.MixinConfig> result = new ArrayList<>();
        for (Loaded entry : loaded) {
            result.addAll(entry.mixins());
        }
        return result;
    }
}
