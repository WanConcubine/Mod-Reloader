package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.ReplayCatalog;
import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.core.Registry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceKey;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.DataPackRegistriesHooks;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replays {@code DataPackRegistryEvent.NewRegistry} for a hot-added mod.
 *
 * <p>Datapack registries ({@code create:potato_projectile/type} and friends) are
 * not built-in registries: their codecs are appended to
 * {@code DataPackRegistriesHooks} at startup, and the next world load reads that
 * list to create the registries and load their JSON entries from datapacks. A
 * hot-added mod missed the event, so the client's level {@code RegistryAccess}
 * never knows the registry and the first lookup throws
 * {@code IllegalStateException: Registry create:potato_projectile/type not found}
 * (measured rendering Create's potato cannon in the creative inventory).
 *
 * <p>The lists the world loader and the network sync use are live views over
 * mutable lists, so replaying at the title screen is enough for the next world
 * and connection. A world that is already open keeps its frozen
 * {@code RegistryAccess}: the registries exist for the next load, not for the
 * current one, and that is logged.
 *
 * <p>Duplicates are refused: {@code addRegistryCodec} appends without a check, and
 * two entries for one key would break the registry loader. Seeing a key that is
 * already registered therefore means this add is a retry; the codec cannot be
 * replaced in place (restart with the jar in {@code mods/}).
 */
public final class DatapackRegistries {
    private DatapackRegistries() {
    }

    /** @return the registry keys this add declared, as {@code namespace:path} */
    public static List<String> replay(List<ModContainer> containers) throws Throwable {
        DataPackRegistryEvent.NewRegistry event = new DataPackRegistryEvent.NewRegistry();
        for (ModContainer container : containers) {
            OneShotReplay.fire(container, ReplayCatalog.REGISTER_DATA_PACK_REGISTRIES, event);
        }
        List<ResourceKey<? extends Registry<?>>> keys = keysOf(event);
        if (keys.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (ResourceKey<? extends Registry<?>> key : keys) {
            names.add(key.location().toString());
        }
        Set<ResourceKey<? extends Registry<?>>> existing = new HashSet<>();
        for (RegistryDataLoader.RegistryData<?> data : DataPackRegistriesHooks.getDataPackRegistries()) {
            existing.add(data.key());
        }
        for (ResourceKey<? extends Registry<?>> key : keys) {
            if (existing.contains(key)) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] datapack registry {} is already registered; its codec cannot be "
                                + "replaced in place (retry skipped, restart to change it)", key.location());
                return names;
            }
        }
        // process() hands the registry data to DataPackRegistriesHooks.
        MethodHandle process = UnsafeAccess.trusted().findVirtual(
                DataPackRegistryEvent.NewRegistry.class, "process",
                MethodType.methodType(void.class));
        process.invoke(event);
        ModReloader.LOGGER.info(
                "[ModReloader] datapack registries registered: {} (created and filled when the next "
                        + "world/connection starts; an already open world keeps its registry view)",
                names);
        return names;
    }

    @SuppressWarnings("unchecked")
    private static List<ResourceKey<? extends Registry<?>>> keysOf(
            DataPackRegistryEvent.NewRegistry event) {
        List<ResourceKey<? extends Registry<?>>> keys = new ArrayList<>();
        Object raw = UnsafeAccess.get(event, event.getClass(), "registryDataList");
        if (!(raw instanceof List<?> registrations)) {
            return keys;
        }
        for (Object registration : registrations) {
            try {
                // DataPackRegistryData is a record; Unsafe refuses offsets on record
                // fields, so read the component through a trusted getter instead.
                Object loaderData = UnsafeAccess.trusted()
                        .findGetter(registration.getClass(), "loaderData",
                                RegistryDataLoader.RegistryData.class)
                        .invoke(registration);
                Object key = loaderData.getClass().getMethod("key").invoke(loaderData);
                if (key instanceof ResourceKey<?> resourceKey) {
                    keys.add((ResourceKey<? extends Registry<?>>) resourceKey);
                }
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] cannot read a datapack registry key", t);
            }
        }
        return keys;
    }
}