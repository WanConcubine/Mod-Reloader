package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pushes the server's id mapping to clients that are already connected.
 *
 * <p>Why this exists: NeoForge syncs registries only during the login
 * configuration phase, so a registry change made while somebody is connected is
 * invisible to them. That is the remaining multiplayer gap of guifei-tier
 * hot-add: single-player works because the client and the integrated server share
 * {@code BuiltInRegistries}; a dedicated server + separate client does not.
 *
 * <p>Shape of the mechanism:
 * <ul>
 *   <li>after {@link RegistryStage#commit}, the committing side takes
 *       {@code RegistryManager.takeSnapshot(SYNC_TO_CLIENT)} and sends one
 *       {@link RegistrySyncPayload} per registry to every non-memory connection;</li>
 *   <li>the client accumulates the batch and, on the payload marked {@code last},
 *       applies it with {@code RegistryManager.applySnapshot(map, false)} — the
 *       very call the login handler uses, including the unfreeze / clear / id
 *       remap / freeze cycle and the block-state id rebuild that follows from the
 *       bake callbacks;</li>
 *   <li>if the client does not know every entry the server has, applying would
 *       leave a partial mapping. That is not something to "continue with": the
 *       client reverts to its login snapshot ({@code revertToFrozen}) and tells the
 *       player to install the mod locally. It is not disconnected — a mid-game
 *       kick is worse than a clear message.</li>
 * </ul>
 *
 * <p>Both sides must run this runtime (it owns the channel), which matches how
 * payload adaptation already works. The hot-added mod itself does not need to
 * know about any of this.
 */
public final class RegistrySync {
    /** Accumulates one batch on the receiving side; client thread only. */
    private static final Map<ResourceLocation, RegistrySnapshot> PENDING = new LinkedHashMap<>();

    private RegistrySync() {
    }

    /** Mod-bus listener: declares the two channels. Runs once at start-up on both sides. */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(RegistrySyncPayload.TYPE, RegistrySyncPayload.STREAM_CODEC,
                        RegistrySync::handle)
                .playToServer(RegistrySyncRequestPayload.TYPE, RegistrySyncRequestPayload.STREAM_CODEC,
                        RegistrySync::handleRequest);
    }

    /**
     * Server side of the pull: answer one player with the current mapping.
     *
     * <p>Sent even when the server has fewer entries than the client — the client
     * detects that and reverts with a message, which is better than the payload
     * silently never arriving.
     */
    private static void handleRequest(RegistrySyncRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            List<RegistrySyncPayload> payloads = snapshotPayloads();
            for (RegistrySyncPayload out : payloads) {
                player.connection.send(out);
            }
            ModReloader.LOGGER.info(
                    "[ModReloader] answered a registry sync request from {} with {} registries",
                    player.getGameProfile().getName(), payloads.size());
        });
    }

    private static void handle(RegistrySyncPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            PENDING.put(payload.registry(), payload.snapshot());
            if (!payload.last()) {
                return;
            }
            Map<ResourceLocation, RegistrySnapshot> batch = new LinkedHashMap<>(PENDING);
            PENDING.clear();
            PlatformHooks.get().acceptRegistrySnapshot(batch);
        });
    }

    /**
     * Sends the current sync-to-client registries to every connected player.
     *
     * <p>Best effort by design: the registry content is already committed and
     * cannot be rolled back, so a failure here is logged and never fails the add.
     *
     * @return how many players the batch was sent to (0 when nobody is connected)
     */
    public static int broadcastAfterCommit() {
        List<RegistrySyncPayload> payloads = snapshotPayloads();
        if (payloads.isEmpty()) {
            return 0;
        }
        int players = sendTo(PlatformHooks.get().connectedPlayers(), payloads);
        ModReloader.LOGGER.info(
                "[ModReloader] registry snapshot: {} registries -> {} connected player(s){}",
                payloads.size(), players,
                players == 0 ? " (nobody connected; late joiners get it at login)" : "");
        return players;
    }

    private static List<RegistrySyncPayload> snapshotPayloads() {
        Map<ResourceLocation, RegistrySnapshot> snapshot =
                RegistryManager.takeSnapshot(RegistryManager.SnapshotType.SYNC_TO_CLIENT);
        if (snapshot.isEmpty()) {
            return List.of();
        }
        List<RegistrySyncPayload> payloads = new ArrayList<>(snapshot.size());
        int index = 0;
        for (Map.Entry<ResourceLocation, RegistrySnapshot> entry : snapshot.entrySet()) {
            payloads.add(new RegistrySyncPayload(entry.getKey(), entry.getValue(),
                    ++index == snapshot.size()));
        }
        return payloads;
    }

    private static int sendTo(List<ServerPlayer> players, List<RegistrySyncPayload> payloads) {
        int sent = 0;
        for (ServerPlayer player : players) {
            for (RegistrySyncPayload payload : payloads) {
                player.connection.send(payload);
            }
            sent++;
        }
        return sent;
    }
}
