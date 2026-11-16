package dev.modreloader.hotadd;

import cpw.mods.cl.JarModuleFinder;
import cpw.mods.cl.ModuleClassLoader;
import cpw.mods.jarhandling.SecureJar;
import dev.modreloader.ModReloader;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Inserts a mod's automatic module into FML's game {@link ModuleLayer}.
 *
 * <p>FML/CSL keeps its own maps in addition to the JDK's, so all of them have to
 * be updated. The JDK's {@code Module} and {@code ResolvedModule} constructors
 * are private; we call them through a trusted lookup and write final fields with
 * {@link UnsafeAccess}.
 */
public final class ModuleInjector {
    /**
     * The JVM cannot undefine a module. After rollback we remove the module
     * from FML's maps, but a retry must reuse the original Module object;
     * constructing a second Module with the same name throws
     * "Module ... is already defined".
     */
    private static final Map<String, Module> DEFINED_MODULES = new HashMap<>();
    private static final Map<String, java.nio.file.Path> DEFINED_PATHS = new HashMap<>();

    /**
     * Full snapshot of the maps that {@link #addToGameLayer} mutates. Read
     * edges added with {@code Instrumentation.redefineModule} cannot be
     * removed, but the layers/classloader maps can be restored.
     */
    public record Snapshot(Set<Module> modules, Map<String, Module> nameToModule,
                           Set<ResolvedModule> configurationModules,
                           Map<String, ResolvedModule> configurationNameToModule,
                           Map<String, Object> resolvedRoots,
                           Map<String, Object> packageLookup) {
    }

    private ModuleInjector() {
    }

    public static Snapshot snapshot() throws Throwable {
        var gameLayer = FMLLoader.getGameLayer();
        ClassLoader gameLoader = gameLayer.findLoader("neoforge");
        if (!(gameLoader instanceof ModuleClassLoader moduleClassLoader)) {
            throw new IllegalStateException("game layer loader is not ModuleClassLoader: " + gameLoader);
        }
        Configuration configuration = gameLayer.configuration();

        Map<String, Object> resolvedRoots = new HashMap<>();
        Object roots = UnsafeAccess.get(moduleClassLoader, ModuleClassLoader.class, "resolvedRoots");
        if (roots instanceof Map<?, ?> map) {
            map.forEach((k, v) -> resolvedRoots.put(String.valueOf(k), v));
        }

        Map<String, Object> packageLookup = new HashMap<>();
        Object packages = UnsafeAccess.get(moduleClassLoader, ModuleClassLoader.class, "packageLookup");
        if (packages instanceof Map<?, ?> map) {
            map.forEach((k, v) -> packageLookup.put(String.valueOf(k), v));
        }

        return new Snapshot(new HashSet<>(gameLayer.modules()),
                new HashMap<>(UnsafeAccess.get(gameLayer, ModuleLayer.class, "nameToModule")),
                new HashSet<>(configuration.modules()),
                new HashMap<>(UnsafeAccess.get(configuration, Configuration.class, "nameToModule")),
                resolvedRoots, packageLookup);
    }

    public static void restore(Snapshot snapshot) throws Throwable {
        var gameLayer = FMLLoader.getGameLayer();
        ClassLoader gameLoader = gameLayer.findLoader("neoforge");
        if (!(gameLoader instanceof ModuleClassLoader moduleClassLoader)) {
            throw new IllegalStateException("game layer loader is not ModuleClassLoader: " + gameLoader);
        }
        Configuration configuration = gameLayer.configuration();

        UnsafeAccess.set(gameLayer, ModuleLayer.class, "modules", new HashSet<>(snapshot.modules()));
        UnsafeAccess.set(gameLayer, ModuleLayer.class, "nameToModule",
                new HashMap<>(snapshot.nameToModule()));
        UnsafeAccess.set(configuration, Configuration.class, "modules",
                new HashSet<>(snapshot.configurationModules()));
        UnsafeAccess.set(configuration, Configuration.class, "nameToModule",
                new HashMap<>(snapshot.configurationNameToModule()));
        UnsafeAccess.set(moduleClassLoader, ModuleClassLoader.class, "resolvedRoots",
                new HashMap<>(snapshot.resolvedRoots()));
        UnsafeAccess.set(moduleClassLoader, ModuleClassLoader.class, "packageLookup",
                new HashMap<>(snapshot.packageLookup()));
        UnsafeAccess.set(gameLayer, ModuleLayer.class, "servicesCatalog", null);
    }

    public static Set<String> addToGameLayer(java.util.Collection<ModFileInfo> newFiles) throws Throwable {
        return addToGameLayer(newFiles, java.util.List.of());
    }

    /**
     * @param newFiles   mod files that carry a mods.toml (they also get a ModContainer)
     * @param extraJars  jars without a mod definition — a mod's {@code META-INF/jarjar}
     *                   libraries. They only need to be resolvable as modules; see
     *                   {@link JarInJar}.
     */
    public static Set<String> addToGameLayer(java.util.Collection<ModFileInfo> newFiles,
                                             java.util.Collection<SecureJar> extraJars) throws Throwable {
        var gameLayer = FMLLoader.getGameLayer();
        ClassLoader gameLoader = gameLayer.findLoader("neoforge");
        if (!(gameLoader instanceof ModuleClassLoader moduleClassLoader)) {
            throw new IllegalStateException("game layer loader is not ModuleClassLoader: " + gameLoader);
        }
        Configuration configuration = gameLayer.configuration();

        Set<Module> modules = new HashSet<>(gameLayer.modules());
        Map<String, Module> nameToModule = new HashMap<>();
        for (Module module : modules) {
            nameToModule.put(module.getName(), module);
        }

        Set<ResolvedModule> configurationModules = new HashSet<>(configuration.modules());
        Map<String, ResolvedModule> configurationNameToModule = new HashMap<>();
        for (ResolvedModule resolved : configurationModules) {
            configurationNameToModule.put(resolved.name(), resolved);
        }

        Map<String, Object> resolvedRoots = new HashMap<>();
        Object roots = UnsafeAccess.get(moduleClassLoader, ModuleClassLoader.class, "resolvedRoots");
        if (roots instanceof Map<?, ?> map) {
            map.forEach((k, v) -> resolvedRoots.put(String.valueOf(k), v));
        } else {
            for (ResolvedModule resolved : configurationModules) {
                resolvedRoots.put(resolved.name(), resolved.reference());
            }
        }

        Map<String, ResolvedModule> packageLookup = new HashMap<>();
        Object packages = UnsafeAccess.get(moduleClassLoader, ModuleClassLoader.class, "packageLookup");
        if (packages instanceof Map<?, ?> map) {
            map.forEach((k, v) -> {
                if (v instanceof ResolvedModule resolved) {
                    packageLookup.put(String.valueOf(k), resolved);
                }
            });
        } else {
            for (ResolvedModule resolved : configurationModules) {
                for (String pkg : resolved.reference().descriptor().packages()) {
                    packageLookup.put(pkg, resolved);
                }
            }
        }

        MethodHandle moduleCtor = UnsafeAccess.trusted().findConstructor(
                Module.class,
                MethodType.methodType(void.class, ModuleLayer.class, ClassLoader.class,
                        ModuleDescriptor.class, URI.class));
        MethodHandle resolvedCtor = UnsafeAccess.trusted().findConstructor(
                ResolvedModule.class,
                MethodType.methodType(void.class, Configuration.class, ModuleReference.class));

        Set<Module> added = new HashSet<>();
        Set<String> addedNames = new HashSet<>();

        record Source(SecureJar jar, ModuleReference reference, boolean extra) {
        }
        List<Source> sources = new ArrayList<>();
        for (ModFileInfo modFileInfo : newFiles) {
            ModFile modFile = (ModFile) modFileInfo.getFile();
            SecureJar secureJar = modFile.getSecureJar();
            for (ModuleReference reference : JarModuleFinder.of(secureJar).findAll()) {
                sources.add(new Source(secureJar, reference, false));
            }
        }
        for (SecureJar extraJar : extraJars) {
            for (ModuleReference reference : JarModuleFinder.of(extraJar).findAll()) {
                sources.add(new Source(extraJar, reference, true));
            }
        }

        for (Source source : sources) {
            SecureJar secureJar = source.jar();
            ModuleReference reference = source.reference();
            String moduleName = reference.descriptor().name();
            Module module = DEFINED_MODULES.get(moduleName);
            java.nio.file.Path primaryPath = secureJar.getPrimaryPath();
            if (module == null) {
                module = (Module) moduleCtor.invoke(gameLayer, moduleClassLoader,
                        reference.descriptor(), reference.location().orElseThrow());
                DEFINED_MODULES.put(moduleName, module);
                DEFINED_PATHS.put(moduleName, primaryPath);
            } else {
                java.nio.file.Path previous = DEFINED_PATHS.get(moduleName);
                if (previous != null && !previous.equals(primaryPath)) {
                    throw new IllegalStateException("Module name " + moduleName
                            + " was already used by " + previous
                            + "; restart is required to load " + primaryPath);
                }
            }
            ResolvedModule resolved = (ResolvedModule) resolvedCtor.invoke(configuration, reference);

            modules.add(module);
            added.add(module);
            addedNames.add(reference.descriptor().name());

            nameToModule.put(reference.descriptor().name(), module);
            nameToModule.put(secureJar.name(), module);
            configurationModules.add(resolved);
            configurationNameToModule.put(reference.descriptor().name(), resolved);
            configurationNameToModule.put(secureJar.name(), resolved);
            resolvedRoots.put(reference.descriptor().name(), reference);
            for (String pkg : reference.descriptor().packages()) {
                if (source.extra()) {
                    // A bundled library must never shadow the mod's own packages:
                    // the mod jar is the primary owner of a package it also ships.
                    ResolvedModule previous = packageLookup.putIfAbsent(pkg, resolved);
                    if (previous != null && !previous.name().equals(resolved.name())) {
                        ModReloader.LOGGER.warn(
                                "[ModReloader] nested jar {} claims package {} which already belongs "
                                        + "to {}; keeping the existing owner",
                                secureJar.name(), pkg, previous.name());
                    }
                } else {
                    packageLookup.put(pkg, resolved);
                }
            }
        }

        // The new modules must read the game modules, and the game modules must
        // read the new modules (FML reflects into mod classes).
        Instrumentation instrumentation = JvmAccess.get();
        // net.neoforged.bus / fml_loader live in the boot layer, not the game
        // layer, so collect every layer in the parent chain.
        Set<Module> readable = new HashSet<>(modules);
        java.util.ArrayDeque<ModuleLayer> layers = new java.util.ArrayDeque<>();
        layers.add(gameLayer);
        Set<ModuleLayer> seenLayers = new HashSet<>();
        while (!layers.isEmpty()) {
            ModuleLayer layer = layers.poll();
            if (!seenLayers.add(layer)) {
                continue;
            }
            readable.addAll(layer.modules());
            layers.addAll(layer.parents());
        }
        for (Module addedModule : added) {
            Set<Module> reads = new HashSet<>(readable);
            reads.remove(addedModule);
            instrumentation.redefineModule(addedModule, reads, Map.of(), Map.of(), Set.of(), Map.of());
        }
        for (Module module : modules) {
            if (added.contains(module)) {
                continue;
            }
            instrumentation.redefineModule(module, new HashSet<>(added), Map.of(), Map.of(), Set.of(), Map.of());
        }

        UnsafeAccess.set(gameLayer, ModuleLayer.class, "modules", modules);
        UnsafeAccess.set(gameLayer, ModuleLayer.class, "nameToModule", nameToModule);
        UnsafeAccess.set(configuration, Configuration.class, "modules", configurationModules);
        UnsafeAccess.set(configuration, Configuration.class, "nameToModule", configurationNameToModule);
        UnsafeAccess.set(moduleClassLoader, ModuleClassLoader.class, "resolvedRoots", resolvedRoots);
        UnsafeAccess.set(moduleClassLoader, ModuleClassLoader.class, "packageLookup", packageLookup);

        // Force the services catalog to be rebuilt on next access.
        UnsafeAccess.set(gameLayer, ModuleLayer.class, "servicesCatalog", null);

        return addedNames;
    }
}
