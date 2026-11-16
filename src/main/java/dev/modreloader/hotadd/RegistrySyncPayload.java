package dev.modreloader.hotadd;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.registries.RegistrySnapshot;

/**
 * One registry's authoritative id mapping, pushed to an already-connected client.
 *
 * <p>NeoForge only synchronises registries during the login <em>configuration</em>
 * phase ({@code SyncRegistries} + {@code FrozenRegistryPayload}); there is no
 * play-phase equivalent. A mod hot-added on a running server therefore never
 * reaches clients that are already connected: their block/item ids keep the
 * values they computed locally, so chunk sections, item stacks and entity
 * spawns resolve to the wrong entries.
 *
 * <p>This payload carries the same data {@code FrozenRegistryPayload} carries —
 * a {@link RegistrySnapshot} (id → key, plus aliases) — but on a play channel
 * this runtime owns, so it can be sent at any time. The receiver applies the
 * whole batch at once; see {@link RegistrySync}.
 *
 * @param registry the registry's name (e.g. {@code minecraft:item})
 * @param snapshot id → key mapping, byte-identical to the login-phase payload
 * @param last     {@code true} on the final payload of a batch: apply now
 */
public record RegistrySyncPayload(ResourceLocation registry, RegistrySnapshot snapshot,
                                  boolean last) implements CustomPacketPayload {
    public static final Type<RegistrySyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("modreloader", "registry_sync"));

    public static final StreamCodec<FriendlyByteBuf, RegistrySyncPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public RegistrySyncPayload decode(FriendlyByteBuf buf) {
                    ResourceLocation registry = buf.readResourceLocation();
                    RegistrySnapshot snapshot = RegistrySnapshot.STREAM_CODEC.decode(buf);
                    boolean last = buf.readBoolean();
                    return new RegistrySyncPayload(registry, snapshot, last);
                }

                @Override
                public void encode(FriendlyByteBuf buf, RegistrySyncPayload payload) {
                    buf.writeResourceLocation(payload.registry());
                    RegistrySnapshot.STREAM_CODEC.encode(buf, payload.snapshot());
                    buf.writeBoolean(payload.last());
                }
            };

    @Override
    public Type<RegistrySyncPayload> type() {
        return TYPE;
    }
}
