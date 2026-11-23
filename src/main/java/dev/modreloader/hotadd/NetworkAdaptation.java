package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import dev.modreloader.inspect.ReplayCatalog;
import net.neoforged.bus.EventBus;
import net.neoforged.bus.ListenerList;
import net.neoforged.bus.api.EventListener;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkChannel;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;
import net.neoforged.neoforgespi.language.IModInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.modreloader.reflect.UnsafeAccess;

/**
 * Registers a hot-added mod's custom payloads by temporarily reopening
 * NeoForge's network registry.
 *
 * <p>NeoForge sets {@code NetworkRegistry.setup = true} right after the start-up
 * registration phase and then throws on any further {@code register} call. The
 * registry maps are mutable, so this class flips the flag back, replays
 * {@link RegisterPayloadHandlersEvent} for the new mod only, and re-arms it.
 *
 * <p>Some libraries do not listen on the mod's own bus: Architectury's
 * {@code NetworkManager} attaches the registration listener to the
 * <em>architectury</em> mod bus through {@code EventBusesHooks.whenAvailable}.
 * At startup that bus gets the event, but a hot-added mod registers the listener
 * after the event already fired, so only replaying the new container's bus
 * misses the payload entirely. Measured 2026-11-23 with FTB Ultimine 2101.1.15:
 * the add "succeeded", then the login handler sent
 * {@code ftbultimine:sync_ultimine_time_packet} and NeoForge refused it
 * ("may not be sent to the client") because the id was never registered. The
 * listeners that appeared on other mod buses during the construct are therefore
 * replayed too (see {@link #snapshot()} / {@link #addedSince}).
 *
 * <p>Connections that already exist keep the {@code PayloadSetup} negotiated at
 * login, so a newly registered payload would be refused by
 * {@code NetworkRegistry.checkPacket}. Those setups are patched here as well, on
 * both sides of every local connection — which is why this only works when the
 * runtime runs on both ends.
 */
public final class NetworkAdaptation {
    public record Result(Set<ResourceLocation> added, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    /** Per-container copy of the payload-registration listeners on that mod bus. */
    public record Snapshot(Map<ModContainer, EventListener[]> byContainer) {
    }

    private static final EventListener[] NONE = new EventListener[0];

    private static boolean warned;

    private NetworkAdaptation() {
    }

    /**
     * Records the {@link RegisterPayloadHandlersEvent} listeners every loaded mod
     * bus holds, before the hot-added mod is constructed. Listeners added to
     * another mod's bus afterwards are the deferred registrations the container
     * replay cannot reach.
     */
    public static Snapshot snapshot() {
        Map<ModContainer, EventListener[]> copy = new IdentityHashMap<>();
        for (ModContainer container : loadedContainers()) {
            copy.put(container, payloadListeners(container));
        }
        return new Snapshot(copy);
    }

    /**
     * Listeners registered for {@link RegisterPayloadHandlersEvent} on any
     * <em>other</em> mod bus since {@code snapshot} was taken, in bus order.
     * Excluded containers are the ones being hot-added: their own bus is already
     * replayed through {@link ModContainer#acceptEvent}.
     */
    public static List<EventListener> addedSince(Snapshot snapshot, Collection<ModContainer> exclude) {
        if (snapshot == null) {
            return List.of();
        }
        Set<ModContainer> skip = Collections.newSetFromMap(new IdentityHashMap<>());
        skip.addAll(exclude);
        Set<EventListener> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (EventListener[] listeners : snapshot.byContainer().values()) {
            Collections.addAll(seen, listeners);
        }
        List<EventListener> added = new ArrayList<>();
        for (ModContainer container : loadedContainers()) {
            if (skip.contains(container)) {
                continue;
            }
            for (EventListener listener : payloadListeners(container)) {
                if (seen.add(listener)) {
                    added.add(listener);
                }
            }
        }
        return List.copyOf(added);
    }

    /**
     * @param containers          every container of the jar being added
     * @param deferredListeners   payload listeners that were registered on other
     *                            mod buses during the construct (see {@link #addedSince})
     */
    public static Result adaptAll(List<ModContainer> containers, List<EventListener> deferredListeners) {
        Set<ResourceLocation> before;
        try {
            before = payloadIds();
        } catch (Throwable t) {
            return new Result(Set.of(), "cannot read the network registry: " + t);
        }

        try {
            UnsafeAccess.setStaticBoolean(NetworkRegistry.class, "setup", false);
        } catch (Throwable t) {
            return new Result(Set.of(), "cannot reopen the network registry: " + t);
        }

        try {
            for (ModContainer container : containers) {
                OneShotReplay.fire(container, ReplayCatalog.REGISTER_PAYLOAD_HANDLERS,
                        new RegisterPayloadHandlersEvent());
            }
            if (!deferredListeners.isEmpty()) {
                RegisterPayloadHandlersEvent event = new RegisterPayloadHandlersEvent();
                for (EventListener listener : deferredListeners) {
                    listener.invoke(event);
                }
            }
        } catch (Throwable t) {
            return new Result(Set.of(), "payload registration failed: " + rootCause(t));
        } finally {
            try {
                UnsafeAccess.setStaticBoolean(NetworkRegistry.class, "setup", true);
            } catch (Throwable t) {
                ModReloader.LOGGER.error(
                        "[ModReloader] cannot re-arm the network registry; further payloads may register", t);
            }
        }

        Set<ResourceLocation> after;
        try {
            after = payloadIds();
        } catch (Throwable t) {
            return new Result(Set.of(), "cannot read the network registry afterwards: " + t);
        }
        Set<ResourceLocation> added = new HashSet<>(after);
        added.removeAll(before);
        if (!added.isEmpty()) {
            patchConnections(added);
        }
        return new Result(added, null);
    }

    /** Snapshot of every registered payload id, across protocols. */
    @SuppressWarnings("unchecked")
    private static Set<ResourceLocation> payloadIds() {
        Map<Object, Map<ResourceLocation, ?>> registrations =
                UnsafeAccess.getStatic(NetworkRegistry.class, "PAYLOAD_REGISTRATIONS");
        Set<ResourceLocation> ids = new HashSet<>();
        if (registrations != null) {
            for (Map<ResourceLocation, ?> byProtocol : registrations.values()) {
                ids.addAll(byProtocol.keySet());
            }
        }
        return ids;
    }

    private static List<ModContainer> loadedContainers() {
        List<ModContainer> result = new ArrayList<>();
        ModList list = ModList.get();
        if (list == null) {
            return result;
        }
        for (IModInfo info : list.getMods()) {
            ModContainer container = list.getModContainerById(info.getModId()).orElse(null);
            if (container != null) {
                result.add(container);
            }
        }
        return result;
    }

    /** The listeners a mod bus dispatches for the payload event, in bus order. */
    private static EventListener[] payloadListeners(ModContainer container) {
        IEventBus bus;
        try {
            bus = container.getEventBus();
        } catch (Throwable t) {
            return NONE;
        }
        if (!(bus instanceof EventBus eventBus)) {
            return NONE;
        }
        try {
            Object helper = UnsafeAccess.get(eventBus, EventBus.class, "listenerLists");
            Object backing = helper == null ? null
                    : UnsafeAccess.get(helper, helper.getClass(), "backingMap");
            if (!(backing instanceof Map<?, ?> map)) {
                return NONE;
            }
            Object list = map.get(RegisterPayloadHandlersEvent.class);
            if (!(list instanceof ListenerList listenerList)) {
                return NONE;
            }
            EventListener[] listeners = listenerList.getListeners();
            return listeners == null ? NONE : listeners;
        } catch (Throwable t) {
            if (!warned) {
                warned = true;
                ModReloader.LOGGER.warn("[ModReloader] cannot read the mod event buses; payloads a "
                        + "library defers onto its own bus will not be registered", t);
            }
            return NONE;
        }
    }

    /**
     * Adds the new payloads to every live connection's negotiated setup, so
     * {@code checkPacket} accepts them without reconnecting.
     */
    private static void patchConnections(Set<ResourceLocation> added) {
        Map<Object, Map<ResourceLocation, PayloadRegistration<?>>> registrations;
        try {
            registrations = UnsafeAccess.getStatic(NetworkRegistry.class, "PAYLOAD_REGISTRATIONS");
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot read payload registrations", t);
            return;
        }
        if (registrations == null) {
            return;
        }
        for (Connection connection : ActiveConnections.all()) {
            try {
                NetworkPayloadSetup current = ChannelAttributes.getPayloadSetup(connection);
                if (current == null) {
                    continue;
                }
                Map<ConnectionProtocol, Map<ResourceLocation, NetworkChannel>> channels = new HashMap<>();
                current.channels().forEach((protocol, byId) ->
                        channels.put(protocol, new HashMap<>(byId)));
                for (Map.Entry<Object, Map<ResourceLocation, PayloadRegistration<?>>> byProtocol
                        : registrations.entrySet()) {
                    ConnectionProtocol protocol = (ConnectionProtocol) byProtocol.getKey();
                    for (ResourceLocation id : added) {
                        PayloadRegistration<?> registration = byProtocol.getValue().get(id);
                        if (registration == null) {
                            continue;
                        }
                        channels.computeIfAbsent(protocol, key -> new HashMap<>())
                                .put(id, new NetworkChannel(id, registration.version()));
                    }
                }
                ChannelAttributes.setPayloadSetup(connection, new NetworkPayloadSetup(channels));
                ModReloader.LOGGER.info(
                        "[ModReloader] patched payload setup of an existing connection: {}", added);
            } catch (Throwable t) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] cannot patch the payload setup of a live connection", t);
            }
        }
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}