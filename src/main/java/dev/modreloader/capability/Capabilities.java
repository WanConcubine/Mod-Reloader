package dev.modreloader.capability;

import cpw.mods.cl.ModuleClassLoader;
import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.hotadd.MixinRuntimeApi;
import dev.modreloader.hotadd.UnsafeAccess;
import dev.modreloader.inspect.MixinRuntimeSupport;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.neoforged.fml.ModList;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.locating.IModFile;

import java.lang.module.Configuration;
import java.lang.module.ModuleDescriptor;
import java.lang.module.ModuleReference;
import java.lang.module.ResolvedModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Start-up check of every private framework anchor the hot-add engine leans on.
 *
 * <p>All the risky work happens through field and method names that NeoForge
 * marks {@code @ApiStatus.Internal}. When one of them is renamed, the failure
 * mode is either a crash deep inside a hot-add or — worse — a silent no-op. So
 * the anchors are probed once at start-up, reported in one log line, and the
 * engine refuses the affected work up front with a human message.
 *
 * <p>The probe is read-only: it looks names up, it never writes or injects.
 */
public final class Capabilities {
    public enum Capability {
        MODULE_INJECTION("module injection", true),
        MODLIST_REBUILD("mod list rebuild", true),
        NETWORK_ADAPTATION("network payload adaptation", false),
        MIXIN_HOTADD("mixin hot-add", false),
        REGISTRY_ADAPTATION("registry content hot-add (guifei)", false),
        REGISTRY_SYNC("registry snapshot sync to connected clients", false),
        DATAPACK_RELOAD("data pack reload (guifei)", false),
        RESOURCE_PACKS("resource pack injection", false);

        private final String label;
        private final boolean essential;

        Capability(String label, boolean essential) {
            this.label = label;
            this.essential = essential;
        }

        public String label() {
            return label;
        }

        /** Essential capabilities block every hot-add; the others only block what needs them. */
        public boolean essential() {
            return essential;
        }
    }

    public record Result(Capability capability, boolean available, boolean applicable,
                         List<String> missing) {
        /** True when this capability is unavailable and this side would actually need it. */
        public boolean blocks() {
            return applicable && !available;
        }

        public String describe() {
            return capability.label() + " is unavailable (missing: " + String.join(", ", missing) + ")";
        }
    }

    private static final Map<Capability, Result> RESULTS = new EnumMap<>(Capability.class);

    private Capabilities() {
    }

    /** Probes once and logs the report; safe to call again. */
    public static synchronized void probe() {
        RESULTS.clear();
        RESULTS.put(Capability.MODULE_INJECTION, checkModuleInjection());
        RESULTS.put(Capability.MODLIST_REBUILD, checkModListRebuild());
        RESULTS.put(Capability.NETWORK_ADAPTATION, checkNetworkAdaptation());
        RESULTS.put(Capability.MIXIN_HOTADD, checkMixinHotAdd());
        RESULTS.put(Capability.REGISTRY_ADAPTATION, checkRegistryAdaptation());
        RESULTS.put(Capability.REGISTRY_SYNC, checkRegistrySync());
        RESULTS.put(Capability.DATAPACK_RELOAD, checkDatapackReload());
        RESULTS.put(Capability.RESOURCE_PACKS, checkResourcePacks());

        List<String> summary = new ArrayList<>();
        for (Capability capability : Capability.values()) {
            Result result = RESULTS.get(capability);
            String name = capability.name().toLowerCase(Locale.ROOT);
            summary.add(capability == Capability.RESOURCE_PACKS && !result.applicable()
                    ? name + "=n/a"
                    : name + "=" + (result.available() ? "ok" : "missing"));
        }
        ModReloader.LOGGER.info("[ModReloader] capabilities: {}", String.join(" ", summary));

        for (Capability capability : Capability.values()) {
            Result result = RESULTS.get(capability);
            if (result.blocks()
                    && !(capability == Capability.MIXIN_HOTADD && !MixinRuntimeSupport.available())) {
                // A missing MixinRuntime is reported by name elsewhere; this line
                // is for framework drift that would otherwise crash mid-add.
                ModReloader.LOGGER.warn(
                        "[ModReloader] {}; {} will fail up front instead of crashing",
                        result.describe(), capability.essential() ? "every hot-add" : "mods that need it");
            }
        }
    }

    /** Probes lazily so a caller can never see an empty report. */
    public static synchronized Result get(Capability capability) {
        if (RESULTS.isEmpty()) {
            probe();
        }
        return RESULTS.get(capability);
    }

    public static boolean available(Capability capability) {
        return get(capability).available();
    }

    private static Result checkModuleInjection() {
        List<String> missing = new ArrayList<>();
        try {
            var layer = FMLLoader.getGameLayer();
            if (layer == null) {
                missing.add("FMLLoader.getGameLayer()");
            } else {
                ClassLoader loader = layer.findLoader("neoforge");
                if (loader instanceof ModuleClassLoader) {
                    fields(ModuleClassLoader.class, List.of("resolvedRoots", "packageLookup"), missing);
                } else {
                    missing.add("game layer loader is not ModuleClassLoader");
                }
            }
        } catch (Throwable t) {
            missing.add("FMLLoader.getGameLayer(): " + t.getClass().getSimpleName());
        }
        fields(ModuleLayer.class, List.of("modules", "nameToModule", "servicesCatalog"), missing);
        fields(Configuration.class, List.of("modules", "nameToModule"), missing);
        constructor(Module.class,
                new Class<?>[]{ModuleLayer.class, ClassLoader.class, ModuleDescriptor.class, URI.class},
                missing);
        constructor(ResolvedModule.class,
                new Class<?>[]{Configuration.class, ModuleReference.class}, missing);
        return result(Capability.MODULE_INJECTION, missing);
    }

    private static Result checkModListRebuild() {
        List<String> missing = new ArrayList<>();
        try {
            if (ModList.get() == null) {
                missing.add("ModList.get()");
            }
        } catch (Throwable t) {
            missing.add("ModList.get(): " + t.getClass().getSimpleName());
        }
        fields(ModList.class, List.of("modFiles", "sortedList", "mods"), missing);
        method(ModList.class, "setLoadedMods", new Class<?>[]{List.class}, missing);
        method(ModList.class, "of", new Class<?>[]{List.class, List.class}, missing);
        statics(ModLoader.class, List.of("modList"), missing);
        method(ModLoader.class, "buildMods", new Class<?>[]{IModFile.class}, missing);
        return result(Capability.MODLIST_REBUILD, missing);
    }

    private static Result checkNetworkAdaptation() {
        List<String> missing = new ArrayList<>();
        try {
            statics(NetworkRegistry.class, List.of("setup", "PAYLOAD_REGISTRATIONS"), missing);
            method(ChannelAttributes.class, "getPayloadSetup",
                    new Class<?>[]{Connection.class}, missing);
            method(ChannelAttributes.class, "setPayloadSetup",
                    new Class<?>[]{Connection.class, NetworkPayloadSetup.class}, missing);
        } catch (Throwable t) {
            missing.add("network registry: " + t.getClass().getSimpleName());
        }
        return result(Capability.NETWORK_ADAPTATION, missing);
    }

    private static Result checkMixinHotAdd() {
        List<String> missing = new ArrayList<>();
        if (!MixinRuntimeSupport.available()) {
            missing.add(MixinRuntimeSupport.name());
            return result(Capability.MIXIN_HOTADD, missing);
        }
        try {
            Class<?> environment = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment",
                    false, Capabilities.class.getClassLoader());
            method(environment, "getActiveTransformer", new Class<?>[0], missing);
            statics(environment, List.of("compatibility"), missing);

            // The exact MixinRuntime surface is resolved by the facade that calls
            // it, so there is one list to keep in sync instead of two.
            missing.addAll(MixinRuntimeApi.probe());
        } catch (Throwable t) {
            missing.add("mixin classes: " + t.getClass().getSimpleName());
        }
        return result(Capability.MIXIN_HOTADD, missing);
    }

    private static Result checkRegistryAdaptation() {
        List<String> missing = new ArrayList<>();
        try {
            java.lang.reflect.Method unfreeze = MappedRegistry.class.getMethod("unfreeze");
            if (!java.lang.reflect.Modifier.isPublic(unfreeze.getModifiers())) {
                missing.add("MappedRegistry#unfreeze is not public");
            }
            net.neoforged.neoforge.registries.GameData.class.getDeclaredMethod("unfreezeData");
            net.neoforged.neoforge.registries.GameData.class.getDeclaredMethod("freezeData");
            net.neoforged.neoforge.registries.GameData.class.getDeclaredMethod("getRegistrationOrder");
            net.neoforged.neoforge.registries.RegisterEvent.class.getDeclaredConstructor(
                    net.minecraft.resources.ResourceKey.class, Registry.class);

            Class<?> callbacks = Class.forName(
                    "net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks");
            Object map = UnsafeAccess.getStatic(callbacks, "BLOCKSTATE_TO_ID_MAP");
            map.getClass().getDeclaredMethod("clear");

            if (BuiltInRegistries.BLOCK.size() == 0 || BuiltInRegistries.ITEM.size() == 0) {
                missing.add("BuiltInRegistries.BLOCK/ITEM is empty");
            }
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            missing.add(e instanceof NoSuchMethodException
                    ? "registry anchor method missing: " + e.getMessage()
                    : "NeoForge registry callbacks missing");
        } catch (Throwable t) {
            missing.add("registry anchors: " + t.getClass().getSimpleName());
        }
        return result(Capability.REGISTRY_ADAPTATION, missing);
    }

    /**
     * Anchors the datapack stage needs: the vanilla reload entry point, the pack
     * repository's mutable source set, and the mod pack supplier/reader. A drift
     * here would only show up <em>after</em> the registry commit, which is
     * already unrecoverable, so it is worth knowing up front.
     */
    /**
     * Anchors the play-phase registry sync needs. The client half reuses NeoForge's
     * own snapshot application (the login-phase code path), so drift here would
     * silently desync ids instead of failing loudly.
     */
    private static Result checkRegistrySync() {
        List<String> missing = new ArrayList<>();
        try {
            statics(net.neoforged.neoforge.registries.RegistrySnapshot.class,
                    List.of("STREAM_CODEC"), missing);
            method(net.neoforged.neoforge.registries.RegistryManager.class, "takeSnapshot",
                    new Class<?>[]{net.neoforged.neoforge.registries.RegistryManager.SnapshotType.class},
                    missing);
            method(net.neoforged.neoforge.registries.RegistryManager.class, "applySnapshot",
                    new Class<?>[]{java.util.Map.class, boolean.class}, missing);
            method(net.neoforged.neoforge.registries.RegistryManager.class, "revertToFrozen",
                    new Class<?>[0], missing);
        } catch (Throwable t) {
            missing.add("registry sync anchors: " + t.getClass().getSimpleName());
        }
        return result(Capability.REGISTRY_SYNC, missing);
    }

    private static Result checkDatapackReload() {
        List<String> missing = new ArrayList<>();
        try {
            method(net.minecraft.server.MinecraftServer.class, "reloadResources",
                    new Class<?>[]{java.util.Collection.class}, missing);
            method(net.minecraft.server.MinecraftServer.class, "reloadableRegistries",
                    new Class<?>[0], missing);
            method(net.minecraft.server.packs.repository.PackRepository.class, "addPackFinder",
                    new Class<?>[]{net.minecraft.server.packs.repository.RepositorySource.class}, missing);
            method(ResourcePackLoader.class, "createPackForMod",
                    new Class<?>[]{IModFileInfo.class}, missing);
            method(ResourcePackLoader.class, "readWithOptionalMeta",
                    new Class<?>[]{net.minecraft.server.packs.PackLocationInfo.class,
                            net.minecraft.server.packs.repository.Pack.ResourcesSupplier.class,
                            net.minecraft.server.packs.PackType.class,
                            net.minecraft.server.packs.PackSelectionConfig.class}, missing);
            statics(ResourcePackLoader.class, List.of("MOD_DATA_ID"), missing);
        } catch (Throwable t) {
            missing.add("datapack anchors: " + t.getClass().getSimpleName());
        }
        return result(Capability.DATAPACK_RELOAD, missing);
    }

    private static Result checkResourcePacks() {
        if (!Platform.isClient()) {
            return new Result(Capability.RESOURCE_PACKS, true, false, List.of());
        }
        List<String> missing = new ArrayList<>();
        try {
            statics(ResourcePackLoader.class, List.of("modResourcePacks"), missing);
            method(ResourcePackLoader.class, "createPackForMod",
                    new Class<?>[]{IModFileInfo.class}, missing);
            method(ResourcePackLoader.class, "populatePackRepository",
                    new Class<?>[]{net.minecraft.server.packs.repository.PackRepository.class,
                            net.minecraft.server.packs.PackType.class, boolean.class}, missing);
        } catch (Throwable t) {
            missing.add("resource pack loader: " + t.getClass().getSimpleName());
        }
        return result(Capability.RESOURCE_PACKS, missing);
    }

    private static Result result(Capability capability, List<String> missing) {
        return new Result(capability, missing.isEmpty(), true, List.copyOf(missing));
    }

    private static void fields(Class<?> type, List<String> names, List<String> missing) {
        for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                field.getName();
            } catch (NoSuchFieldException e) {
                missing.add(type.getSimpleName() + "#" + name);
            } catch (Throwable t) {
                missing.add(type.getSimpleName() + "#" + name + " (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    private static void statics(Class<?> type, List<String> names, List<String> missing) {
        for (String name : names) {
            try {
                Field field = type.getDeclaredField(name);
                if (!java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    missing.add(type.getSimpleName() + "#" + name + " is not static");
                }
            } catch (NoSuchFieldException e) {
                missing.add(type.getSimpleName() + "#" + name);
            } catch (Throwable t) {
                missing.add(type.getSimpleName() + "#" + name + " (" + t.getClass().getSimpleName() + ")");
            }
        }
    }

    /** {@code parameters == null} means "any signature". */
    private static void method(Class<?> type, String name, Class<?>[] parameters, List<String> missing) {
        try {
            Method method = parameters == null
                    ? anyMethod(type, name)
                    : type.getDeclaredMethod(name, parameters);
            if (method == null) {
                missing.add(type.getSimpleName() + "#" + name);
            }
        } catch (NoSuchMethodException e) {
            missing.add(type.getSimpleName() + "#" + name);
        } catch (Throwable t) {
            missing.add(type.getSimpleName() + "#" + name + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static Method anyMethod(Class<?> type, String name) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                return method;
            }
        }
        return null;
    }

    private static void constructor(Class<?> type, Class<?>[] parameters, List<String> missing) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameters);
            constructor.getName();
        } catch (NoSuchMethodException e) {
            missing.add(type.getSimpleName() + "(" + join(parameters) + ")");
        } catch (Throwable t) {
            missing.add(type.getSimpleName() + "(" + join(parameters) + ") ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static String join(Class<?>[] types) {
        List<String> names = new ArrayList<>(types.length);
        for (Class<?> type : types) {
            names.add(type.getSimpleName());
        }
        return String.join(", ", names);
    }
}
