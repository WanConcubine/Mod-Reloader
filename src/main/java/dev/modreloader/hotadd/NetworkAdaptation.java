package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
 * <p><b>Limit:</b> payloads registered this way are only negotiated for
 * connections opened afterwards. An already-connected peer keeps its old
 * {@code PayloadSetup}, so sending to it is refused by
 * {@code NetworkRegistry.checkPacket} until the connection is re-established.
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
            container.acceptEvent(new RegisterPayloadHandlersEvent());
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

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
