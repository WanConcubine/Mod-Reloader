package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventListener;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.modreloader.reflect.UnsafeAccess;

/**
 * Replays the server-level one-shot events a hot-added mod missed <em>only for
 * that mod's listeners</em>.
 *
 * <p>These are game-bus events, not mod-bus events: {@code container.acceptEvent}
 * cannot dispatch them, and posting them globally would run every loaded mod's
 * handler again (duplicate server setup). The listeners the add registered are
 * already known — {@link EventBusSnapshot#addedSince} diffs the bus around the
 * construct — so we can invoke exactly those for a synthetic event instance.
 *
 * <p>Why it matters: Xaero's World Map creates {@code WorldMap.serverPlayerTickHandler}
 * in {@code ServerStartingEvent}. Hot-adding it into a running world left that
 * field null and the next player tick threw (integrated server crash). Replaying
 * the event at the very end of a successful add (after registry/datapack stages)
 * initialises the mod's server side the same way startup would.
 *
 * <p>Deliberately narrow: only {@link ServerStartingEvent} and
 * {@link ServerStartedEvent}, and only when a server is running. Listeners are
 * matched through the bus's own {@code ListenerList} for the event class (and its
 * supertypes), so a listener subscribed to a superclass still receives it.
 */
public final class ServerLifecycleReplay {
    private ServerLifecycleReplay() {
    }

    /** Replays the events for the listeners added since {@code snapshot}. */
    public static void replay(EventBusSnapshot.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        net.minecraft.server.MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            // Integrated servers do not always publish themselves through
            // ServerLifecycleHooks in dev; ask the client directly (reflection keeps
            // this class loadable on a dedicated server).
            server = singleplayerServer();
        }
        if (server == null) {
            // Title screen: the event fires normally when a world starts, and the
            // listener is already registered, so nothing to do.
            ModReloader.LOGGER.info(
                    "[ModReloader] no running server; server lifecycle replay skipped");
            return;
        }
        List<EventListener> added = EventBusSnapshot.addedSince(snapshot);
        if (added.isEmpty()) {
            ModReloader.LOGGER.info(
                    "[ModReloader] no new game-bus listeners; server lifecycle replay skipped");
            return;
        }
        ModReloader.LOGGER.info(
                "[ModReloader] replaying server lifecycle for {} new game-bus listener(s)", added.size());
        Set<EventListener> newListeners = Collections.newSetFromMap(new IdentityHashMap<>());
        newListeners.addAll(added);
        replayOne(new ServerStartingEvent(server), newListeners);
        replayOne(new ServerStartedEvent(server), newListeners);
    }

    private static net.minecraft.server.MinecraftServer singleplayerServer() {
        try {
            Class<?> client = Class.forName("net.minecraft.client.Minecraft");
            Object instance = client.getMethod("getInstance").invoke(null);
            Object server = client.getMethod("getSingleplayerServer").invoke(instance);
            return server instanceof net.minecraft.server.MinecraftServer value ? value : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void replayOne(Event event, Set<EventListener> newListeners) {
        String name = event.getClass().getSimpleName();
        List<EventListener> matched = listenersFor(event.getClass());
        int invoked = 0;
        for (EventListener listener : matched) {
            if (!newListeners.contains(listener)) {
                continue;
            }
            try {
                listener.invoke(event);
                invoked++;
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] replayed {} listener threw", name, t);
            }
        }
        if (invoked == 0) {
            // The ListenerList holds the dispatch-time wrappers, which are not
            // guaranteed to be identity-equal to the owner-map entries. Fall back
            // to trying the new listeners directly: a listener subscribed to
            // another event rejects this one with a type error before running.
            for (EventListener listener : newListeners) {
                try {
                    listener.invoke(event);
                    invoked++;
                } catch (ClassCastException | IllegalArgumentException expected) {
                    // Not subscribed to this event.
                } catch (Throwable t) {
                    ModReloader.LOGGER.warn("[ModReloader] replayed {} listener threw", name, t);
                }
            }
        }
        if (invoked > 0) {
            ModReloader.LOGGER.info("[ModReloader] replayed {} for {} new game-bus listener(s)",
                    name, invoked);
        }
    }

    /**
     * Listeners the bus would dispatch this event to: the class's own list plus
     * the lists of its supertypes (the bus registers a listener under the class it
     * subscribed to, and dispatch walks upwards).
     */
    private static List<EventListener> listenersFor(Class<?> eventType) {
        Map<Object, Object> lists = listenerLists();
        List<EventListener> out = new ArrayList<>();
        Set<EventListener> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Class<?> type = eventType; type != null && Event.class.isAssignableFrom(type);
                type = type.getSuperclass()) {
            Object value = lists.get(type);
            if (!(value instanceof ListenerList list)) {
                continue;
            }
            try {
                for (EventListener listener : list.getListeners()) {
                    if (seen.add(listener)) {
                        out.add(listener);
                    }
                }
            } catch (Throwable t) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] cannot read listeners of {}", type.getName(), t);
            }
        }
        return out;
    }

    private static Map<Object, Object> listenerLists() {
        try {
            Object bus = NeoForge.EVENT_BUS;
            Object helper = UnsafeAccess.get(bus, net.neoforged.bus.EventBus.class, "listenerLists");
            Object backing = UnsafeAccess.get(helper, helper.getClass(), "backingMap");
            if (backing instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> typed = (Map<Object, Object>) map;
                return typed;
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot read the game bus listener lists", t);
        }
        return Map.of();
    }
}
