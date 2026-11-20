package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.ReplayCatalog;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.BaseMappedRegistry;
import net.neoforged.neoforge.registries.NewRegistryEvent;
import net.neoforged.neoforge.registries.RegistryManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import dev.modreloader.reflect.UnsafeAccess;

/**
 * Replays {@code NewRegistryEvent} for a hot-added mod so it can create its own
 * (built-in) registries, like it would at startup.
 *
 * <p>NeoForge normally runs construct → {@code NewRegistryEvent} (create and
 * register the registries) → {@code RegisterEvent} (entries) → freeze. The
 * runtime already replays {@code RegisterEvent} and the freeze
 * ({@link RegistryStage}); what is missing for custom registries is the event
 * in between:
 *
 * <ul>
 *   <li>{@link RegistryManager} keeps a private static
 *       {@code pendingModdedRegistries} set that {@code RegistryBuilder.create()}
 *       checks; it is set to {@code null} when startup finishes, which is the
 *       {@code "… after NewRegistryEvent was fired!"} error. {@link #open}
 *       opens the window again for the duration of one add.</li>
 *   <li>{@link #replay} fires the event on the new containers only (firing it
 *       for every container would duplicate the registries of mods that are
 *       already loaded) and calls the package-private {@code fill()}, which
 *       registers them into {@code BuiltInRegistries.REGISTRY}.</li>
 * </ul>
 *
 * <p>{@code GameData.getRegistrationOrder()} and {@code GameData.freezeData()}
 * both read the live root registry, so {@link RegistryStage#commit} covers the
 * new registries without changes. {@code fill()} unfreezes the root registry
 * and re-freezes it; if it throws we freeze it ourselves, because leaving it
 * unfrozen would corrupt every later registry operation.
 *
 * <p>Limits: datapack registries ({@code DataPackRegistryEvent}) and
 * {@code ModifyRegistriesEvent} are not replayed. A registry that was already
 * filled cannot be removed on rollback — that path marks the runtime dirty, like
 * any other registry content.
 */
public final class CustomRegistries {
    private static final MethodHandle EVENT_CTOR = eventConstructor();
    private static final MethodHandle FILL = fillHandle();
    private static final MethodHandle UNFREEZE = unfreezeHandle();

    private static boolean open;
    private static Object previousPending;
    private static final Set<ResourceLocation> added = new LinkedHashSet<>();
    /** Root registry keys before the window opened (construct may add registries directly). */
    private static Set<ResourceLocation> beforeOpen = Set.of();
    private static boolean rootUnfrozen;

    private CustomRegistries() {
    }

    /** Opens the creation window for one add; must be paired with {@link #close()}. */
    public static void open(Rollback rollback) {
        if (open) {
            throw new IllegalStateException("a custom-registry window is already open");
        }
        previousPending = UnsafeAccess.getStatic(RegistryManager.class, "pendingModdedRegistries");
        UnsafeAccess.setStatic(RegistryManager.class, "pendingModdedRegistries",
                new HashSet<ResourceLocation>());
        open = true;
        added.clear();
        beforeOpen = new LinkedHashSet<>(BuiltInRegistries.REGISTRY.keySet());
        // Startup order: mods construct while the root registry is still writable;
        // NeoForge only freezes it in NewRegistryEvent.fill(). Create registers its
        // registries straight into the root during construct (CreateBuiltInRegistries),
        // which fails on a frozen runtime root otherwise (known issue 59).
        try {
            UNFREEZE.invoke((BaseMappedRegistry<?>) BuiltInRegistries.REGISTRY);
            rootUnfrozen = true;
            ModReloader.LOGGER.info(
                    "[ModReloader] root registry unfrozen for construct (startup order)");
        } catch (Throwable t) {
            restore(false);
            throw new IllegalStateException("cannot unfreeze the root registry", t);
        }
        rollback.add("custom registries", CustomRegistries::restoreOnRollback);
        ModReloader.LOGGER.info(
                "[ModReloader] custom-registry window open (mods may create registries during construct)");
    }

    /**
     * Fires {@code NewRegistryEvent} for the hot-added containers and registers
     * their registries into the root registry.
     *
     * @return the names of the registries that were added
     */
    public static List<String> replay(List<ModContainer> containers) throws Throwable {
        if (!open) {
            return List.of();
        }
        Set<ResourceLocation> before = beforeOpen;
        NewRegistryEvent event = (NewRegistryEvent) EVENT_CTOR.invoke();
        for (ModContainer container : containers) {
            OneShotReplay.fire(container, ReplayCatalog.REGISTER_NEW_REGISTRY, event);
        }
        try {
            FILL.invoke(event);
        } catch (Throwable t) {
            // fill() unfreezes the root registry first; never leave it unfrozen.
            freezeRootQuietly();
            throw t;
        }
        verifyAllRegistered();

        List<String> names = new ArrayList<>();
        for (ResourceLocation name : BuiltInRegistries.REGISTRY.keySet()) {
            if (!before.contains(name)) {
                added.add(name);
                names.add(name.toString());
            }
        }
        ModReloader.LOGGER.info("[ModReloader] custom registries registered: {}",
                names.isEmpty() ? "none created" : names);
        return List.copyOf(names);
    }

    /** Restores the NeoForge guard after a successful commit; safe to call more than once. */
    public static void close() {
        restore(false);
    }

    private static void restoreOnRollback() {
        restore(true);
    }

    private static void restore(boolean rolledBack) {
        if (!open) {
            return;
        }
        open = false;
        if (rolledBack && !added.isEmpty()) {
            // Registries have no public removal API. The entries are already
            // reachable from the root registry, so only a restart is clean.
            ModReloader.LOGGER.warn(
                    "[ModReloader] custom registries are already in the root registry and cannot be "
                            + "removed: {}", added);
        }
        UnsafeAccess.setStatic(RegistryManager.class, "pendingModdedRegistries", previousPending);
        previousPending = null;
        added.clear();
        beforeOpen = Set.of();
        if (rootUnfrozen) {
            rootUnfrozen = false;
            // fill() freezes at the end of replay; this also covers failure before replay.
            freezeRootQuietly();
        }
    }

    /**
     * Mirrors the check at the end of {@code RegistryManager.postNewRegistryEvent}:
     * a registry created through {@code DeferredRegister.makeRegistry} must also be
     * attached to an event bus, otherwise it never reaches the root registry.
     */
    private static void verifyAllRegistered() {
        Object pending = UnsafeAccess.getStatic(RegistryManager.class, "pendingModdedRegistries");
        if (!(pending instanceof Set<?> set)) {
            return;
        }
        List<Object> leftover = new ArrayList<>();
        for (Object id : set) {
            if (id instanceof ResourceLocation location
                    && !BuiltInRegistries.REGISTRY.containsKey(location)) {
                leftover.add(location);
            }
        }
        if (!leftover.isEmpty()) {
            throw new IllegalStateException(
                    "registries were created but never registered (the mod did not attach its "
                            + "DeferredRegister to its event bus): " + leftover);
        }
    }

    private static void freezeRootQuietly() {
        try {
            ((WritableRegistry<?>) BuiltInRegistries.REGISTRY).freeze();
        } catch (Throwable t) {
            ModReloader.LOGGER.error(
                    "[ModReloader] cannot re-freeze the root registry after a failed custom-registry "
                            + "replay; restart is required", t);
        }
    }

    private static MethodHandle eventConstructor() {
        try {
            return UnsafeAccess.trusted().findConstructor(
                    NewRegistryEvent.class, MethodType.methodType(void.class));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private static MethodHandle fillHandle() {
        try {
            return UnsafeAccess.trusted().findVirtual(
                    NewRegistryEvent.class, "fill", MethodType.methodType(void.class));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private static MethodHandle unfreezeHandle() {
        try {
            return UnsafeAccess.trusted().findVirtual(
                    BaseMappedRegistry.class, "unfreeze", MethodType.methodType(void.class));
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }
}
