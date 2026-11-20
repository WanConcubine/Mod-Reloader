package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import dev.modreloader.inspect.ReplayCatalog;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkChannel;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import net.neoforged.neoforge.network.registration.PayloadRegistration;

import java.util.HashMap;
import java.util.HashSet;
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
 * <p>This is deliberate framework bending, so it is defensive: any failure is
 * reported and the caller rolls back.
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

    private NetworkAdaptation() {
    }

    public static Result adapt(ModContainer container) {
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
            OneShotReplay.fire(container, ReplayCatalog.REGISTER_PAYLOAD_HANDLERS,
                    new RegisterPayloadHandlersEvent());
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
