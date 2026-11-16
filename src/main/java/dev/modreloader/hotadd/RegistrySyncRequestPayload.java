package dev.modreloader.hotadd;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: "my registry changed, send me your authoritative id mapping".
 *
 * <p>The push in {@link RegistrySync#broadcastAfterCommit} covers a hot-add on the
 * server. This pull covers the other order — the client hot-adds first (or
 * second), and only the server can say which id each entry has. Without it the
 * client would keep locally assigned ids and desync silently.
 *
 * <p>Empty body on purpose: the server answers with whatever it has, and the
 * client decides (revert + message) when the sets do not match.
 */
public record RegistrySyncRequestPayload() implements CustomPacketPayload {
    public static final RegistrySyncRequestPayload INSTANCE = new RegistrySyncRequestPayload();

    public static final Type<RegistrySyncRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("modreloader", "registry_sync_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RegistrySyncRequestPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public Type<RegistrySyncRequestPayload> type() {
        return TYPE;
    }
}
