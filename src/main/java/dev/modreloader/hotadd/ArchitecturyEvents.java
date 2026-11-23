package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Replays Architectury's one-shot lifecycle events for a hot-added mod.
 *
 * <p>Architectury bridges {@code FMLCommonSetupEvent} / {@code FMLClientSetupEvent}
 * (mod-bus events) to its own {@code LifecycleEvent.SETUP} /
 * {@code ClientLifecycleEvent.CLIENT_SETUP}, and the bridge listener lives on the
 * <em>architectury</em> mod bus. A hot-added mod that calls
 * {@code LifecycleEvent.SETUP.register(this::commonSetup)} in its constructor
 * misses the event, so its common setup never runs. Measured 2026-11-23 with FTB
 * Ultimine 2101.1.15: the shape registry stayed empty and the first ultimine
 * attempt crashed with {@code ShapeRegistry.getShape(int)} returning null on both
 * the client and the integrated server.
 *
 * <p>The listeners live in an {@code ArrayList} inside Architectury's event impl.
 * A snapshot of that list is taken before the construct and only the listeners
 * that appeared afterwards are run: re-firing Architectury's bridge would run
 * every loaded mod's setup a second time.
 */
public final class ArchitecturyEvents {
    private static final String COMMON_LIFECYCLE = "dev.architectury.event.events.common.LifecycleEvent";
    private static final String CLIENT_LIFECYCLE = "dev.architectury.event.events.client.ClientLifecycleEvent";
    private static final String KEY_MAPPING_REGISTRY =
            "dev.architectury.registry.client.keymappings.forge.KeyMappingRegistryImpl";

    public record Snapshot(List<Object> setup, List<Object> clientSetup, List<Object> keyMappings) {
    }

    private static final Map<Class<?>, Method> STATE_CHANGED = new ConcurrentHashMap<>();
    private static boolean warned;

    private ArchitecturyEvents() {
    }

    /** Listeners the two setup events hold before the hot-added mod is constructed. */
    public static Snapshot snapshot() {
        if (!architecturyLoaded()) {
            return new Snapshot(List.of(), List.of(), List.of());
        }
        List<Object> keys = Platform.isClient()
                ? staticList(KEY_MAPPING_REGISTRY, "MAPPINGS") : List.of();
        return new Snapshot(listeners(COMMON_LIFECYCLE, "SETUP"),
                listeners(CLIENT_LIFECYCLE, "CLIENT_SETUP"), keys);
    }

    /** {@code LifecycleEvent.SETUP} listeners registered since the snapshot. */
    public static List<Object> newSetup(Snapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        return addedSince(listeners(COMMON_LIFECYCLE, "SETUP"), snapshot.setup());
    }

    /** {@code ClientLifecycleEvent.CLIENT_SETUP} listeners registered since the snapshot. */
    public static List<Object> newClientSetup(Snapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        return addedSince(listeners(CLIENT_LIFECYCLE, "CLIENT_SETUP"), snapshot.clientSetup());
    }

    /**
     * Key mappings a hot-added mod queued in Architectury's registry after
     * {@code RegisterKeyMappingsEvent} already fired. They would never reach
     * {@code Options.keyMappings} otherwise, so the key would stay dead.
     */
    public static List<Object> newKeyMappings(Snapshot snapshot) {
        if (snapshot == null || !Platform.isClient()) {
            return List.of();
        }
        return addedSince(staticList(KEY_MAPPING_REGISTRY, "MAPPINGS"), snapshot.keyMappings());
    }

    /** Appends the given mappings the same way Architectury does after the event. */
    public static int registerKeyMappings(List<Object> mappings) {
        Minecraft client = Minecraft.getInstance();
        if (client == null || mappings.isEmpty()) {
            return 0;
        }
        KeyMapping[] current = client.options.keyMappings;
        Set<KeyMapping> present = Collections.newSetFromMap(new IdentityHashMap<>());
        Collections.addAll(present, current);
        int added = 0;
        for (Object candidate : mappings) {
            if (candidate instanceof KeyMapping mapping && present.add(mapping)) {
                current = Arrays.copyOf(current, current.length + 1);
                current[current.length - 1] = mapping;
                added++;
            }
        }
        if (added > 0) {
            client.options.keyMappings = current;
        }
        return added;
    }

    /** Runs the listeners (Architectury's SETUP is an {@code Event<Runnable>}). */
    public static void runSetup(List<Object> listeners) {
        for (Object listener : listeners) {
            if (!(listener instanceof Runnable runnable)) {
                throw new IllegalStateException("not a Runnable: " + listener);
            }
            runnable.run();
        }
    }

    /** Runs the client-setup listeners; CLIENT_SETUP passes the current client. */
    public static void runClientSetup(List<Object> listeners) throws ReflectiveOperationException {
        Minecraft client = Minecraft.getInstance();
        for (Object listener : listeners) {
            stateChanged(listener.getClass()).invoke(listener, client);
        }
    }

    private static List<Object> addedSince(List<Object> current, List<Object> before) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(before);
        List<Object> added = new ArrayList<>();
        for (Object listener : current) {
            if (seen.add(listener)) {
                added.add(listener);
            }
        }
        return List.copyOf(added);
    }

    private static List<Object> listeners(String className, String fieldName) {
        try {
            Class<?> type = Class.forName(className, false, ArchitecturyEvents.class.getClassLoader());
            Object event = type.getField(fieldName).get(null);
            Object list = UnsafeAccess.get(event, event.getClass(), "listeners");
            return list instanceof List<?> live ? List.copyOf(live) : List.of();
        } catch (ClassNotFoundException e) {
            return List.of();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                ModReloader.LOGGER.warn("[ModReloader] cannot read Architectury's lifecycle listeners; "
                        + "a hot-added mod's common setup may not run", t);
            }
            return List.of();
        }
    }

    private static List<Object> staticList(String className, String fieldName) {
        try {
            Class<?> type = Class.forName(className, false, ArchitecturyEvents.class.getClassLoader());
            Object list = type.getField(fieldName).get(null);
            return list instanceof List<?> live ? List.copyOf(live) : List.of();
        } catch (ClassNotFoundException e) {
            return List.of();
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                ModReloader.LOGGER.warn("[ModReloader] cannot read Architectury's " + className
                        + "." + fieldName + "; deferred registrations may be missed", t);
            }
            return List.of();
        }
    }

    private static Method stateChanged(Class<?> listenerClass) {
        Method cached = STATE_CHANGED.get(listenerClass);
        if (cached != null) {
            return cached;
        }
        for (Method method : listenerClass.getMethods()) {
            if (method.getName().equals("stateChanged") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Minecraft.class) {
                STATE_CHANGED.put(listenerClass, method);
                return method;
            }
        }
        throw new IllegalStateException("no stateChanged(Minecraft) on " + listenerClass.getName());
    }

    private static boolean architecturyLoaded() {
        ModList list = ModList.get();
        return list != null && list.isLoaded("architectury");
    }
}