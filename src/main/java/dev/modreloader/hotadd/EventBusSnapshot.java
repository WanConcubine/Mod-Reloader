package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.EventListener;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.modreloader.reflect.UnsafeAccess;

/**
 * Rolls back the <em>game-bus</em> listeners a failed hot-add registered.
 *
 * <p>Why this exists: FML registers a mod's {@code @EventBusSubscriber} methods
 * (and any {@code NeoForge.EVENT_BUS.addListener} call in its constructor) while
 * the mod is constructed. If the add fails afterwards, the rollback restores
 * ModList / module / mixin / packs — but NeoForge's event bus had no way to take
 * those listeners off, so they stayed registered. Measured before this class
 * existed: a forced post-construct failure left {@code 38 -> 41} listeners on the
 * global bus, and a retry would have registered them a second time (each event
 * would run the leaked handler too).
 *
 * <p>Mechanism: snapshot which listener instances the bus holds per owner before
 * the construct; afterwards compute the instances that appeared and remove exactly
 * those from every dispatch list (public {@code ListenerList#unregister(EventListener)}),
 * then trim the owner map the bus keeps for {@code unregister(Object)}. Removing by
 * listener rather than by owner matters: one of the measured leaks was appended to
 * an owner that already existed, which owner-level {@code unregister} cannot touch.
 *
 * <p>Two internal names are read ({@code EventBus#listeners} and
 * {@code EventBus#listenerLists}, the latter appears in
 * {@code Capabilities.EVENT_BUS_ROLLBACK}); the removal itself is public API. When
 * either drifts the rollback degrades to the old behaviour: log that the listeners
 * stay registered.
 */
public final class EventBusSnapshot {
    /** Owner -> the listeners it had, copied before the construct. Identity semantics. */
    public record Snapshot(Map<Object, List<EventListener>> byOwner) {
    }

    /**
     * Outcome of the last {@link #restore}: how many listener instances the failed
     * construct added, and the bus total before/after the restore. {@code after ==
     * before} means the rollback put the bus back exactly as it was.
     */
    public record RestoreResult(int added, int before, int after) {
        public boolean clean() {
            return added >= 0 && after == before;
        }
    }

    private static volatile RestoreResult lastRestore;

    private static boolean warned;

    private EventBusSnapshot() {
    }

    /**
     * @return the bus contents before the construct, or {@code null} when they
     *         cannot be read (the caller then keeps the old behaviour of only
     *         logging that listeners cannot be removed)
     */
    public static Snapshot of() {
        try {
            Map<?, ?> live = ownerMap();
            if (live == null) {
                return null;
            }
            Map<Object, List<EventListener>> copy = new IdentityHashMap<>();
            for (Map.Entry<?, ?> entry : live.entrySet()) {
                List<EventListener> listeners = entry.getValue() instanceof List<?> list
                        ? copyOf(list) : List.of();
                copy.put(entry.getKey(), listeners);
            }
            return new Snapshot(copy);
        } catch (Throwable t) {
            warnOnce(t);
            return null;
        }
    }

    /**
     * Listener instances that appeared after {@code snapshot} was taken, in bus
     * order. Used to replay one-shot <em>game-bus</em> events for the hot-added
     * mod only (posting them globally would re-run every other mod's handler).
     *
     * @return the new listeners, or an empty list when the bus cannot be read
     */
    public static List<EventListener> addedSince(Snapshot snapshot) {
        if (snapshot == null) {
            return List.of();
        }
        try {
            Map<?, ?> live = ownerMap();
            if (live == null) {
                return List.of();
            }
            Set<EventListener> before = Collections.newSetFromMap(new IdentityHashMap<>());
            for (List<EventListener> listeners : snapshot.byOwner().values()) {
                before.addAll(listeners);
            }
            List<EventListener> added = new ArrayList<>();
            for (Object value : live.values()) {
                if (!(value instanceof List<?> listeners)) {
                    continue;
                }
                for (Object candidate : listeners) {
                    if (candidate instanceof EventListener listener && !before.contains(listener)) {
                        added.add(listener);
                    }
                }
            }
            return List.copyOf(added);
        } catch (Throwable t) {
            warnOnce(t);
            return List.of();
        }
    }

    /**
     * Removes every listener that appeared after {@code snapshot} was taken.
     *
     * @return how many listener instances were removed, or -1 when it could not run
     */
    @SuppressWarnings("unchecked")
    public static int restore(Snapshot snapshot) {
        if (snapshot == null) {
            return -1;
        }
        try {
            Map<Object, List<EventListener>> live = (Map<Object, List<EventListener>>) ownerMap();
            if (live == null) {
                return -1;
            }
            Set<EventListener> before = Collections.newSetFromMap(new IdentityHashMap<>());
            for (List<EventListener> listeners : snapshot.byOwner().values()) {
                before.addAll(listeners);
            }
            List<EventListener> added = new ArrayList<>();
            for (List<EventListener> listeners : live.values()) {
                for (EventListener listener : listeners) {
                    if (!before.contains(listener)) {
                        added.add(listener);
                    }
                }
            }
            if (added.isEmpty()) {
                return 0;
            }
            // Remove exactly these instances from every dispatch list. A listener may
            // appear in several lists (a class and its supertypes), so try them all.
            for (Object value : listenerLists().values()) {
                if (!(value instanceof ListenerList list)) {
                    continue;
                }
                for (EventListener listener : added) {
                    try {
                        list.unregister(listener);
                    } catch (Throwable t) {
                        ModReloader.LOGGER.warn(
                                "[ModReloader] cannot unregister a leaked event listener", t);
                    }
                }
            }
            // Keep the owner map consistent with the dispatch lists.
            for (List<EventListener> listeners : live.values()) {
                listeners.removeAll(added);
            }
            live.entrySet().removeIf(entry -> entry.getValue().isEmpty());
            int total = 0;
            for (Object value : live.values()) {
                if (value instanceof List<?> list) {
                    total += list.size();
                }
            }
            int beforeTotal = 0;
            for (List<EventListener> listeners : snapshot.byOwner().values()) {
                beforeTotal += listeners.size();
            }
            lastRestore = new RestoreResult(added.size(), beforeTotal, total);
            ModReloader.LOGGER.info("[ModReloader] rollback: game-bus listeners {} -> {} ({} removed)",
                    beforeTotal, total, added.size());
            return added.size();
        } catch (Throwable t) {
            warnOnce(t);
            return -1;
        }
    }

    /** The outcome of the last restore, or {@code null} when none ran yet. */
    public static RestoreResult lastRestore() {
        return lastRestore;
    }

    /** Total listeners across all owners, or -1 when the bus cannot be read. */
    public static int ownerCount() {
        Map<?, ?> live = ownerMap();
        if (live == null) {
            return -1;
        }
        int total = 0;
        for (Object value : live.values()) {
            if (value instanceof List<?> list) {
                total += list.size();
            }
        }
        return total;
    }

    @SuppressWarnings("unchecked")
    private static List<EventListener> copyOf(List<?> list) {
        return List.copyOf((List<EventListener>) list);
    }

    private static Map<?, ?> ownerMap() {
        Object bus = NeoForge.EVENT_BUS;
        if (!(bus instanceof net.neoforged.bus.EventBus)) {
            return null;
        }
        Object listeners = UnsafeAccess.get(bus, net.neoforged.bus.EventBus.class, "listeners");
        return listeners instanceof Map<?, ?> map ? map : null;
    }

    /** The bus's Class -> ListenerList cache (derived structure, read-only here). */
    @SuppressWarnings("unchecked")
    private static Map<Object, Object> listenerLists() {
        Object bus = NeoForge.EVENT_BUS;
        Object helper = UnsafeAccess.get(bus, net.neoforged.bus.EventBus.class, "listenerLists");
        Object backing = UnsafeAccess.get(helper, helper.getClass(), "backingMap");
        return backing instanceof Map<?, ?> map ? (Map<Object, Object>) map : Map.of();
    }

    private static void warnOnce(Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        ModReloader.LOGGER.warn("[ModReloader] cannot read the game event bus; listeners of a failed "
                + "add cannot be rolled back (they will stay registered)", t);
    }
}
