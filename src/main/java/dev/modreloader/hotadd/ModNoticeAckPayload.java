package dev.modreloader.hotadd;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Client → server answer to {@link ServerModNoticePayload}: which of the noticed
 * mod ids this client already has.
 *
 * <p>This turns "the server broadcast into the void" into "the admin can see who
 * still needs to install what". It is advisory only — no behaviour is gated on
 * it yet, because gating would need a confirmed handshake *before* the hot-added
 * mod is allowed to send anything, and that ordering is not something we can
 * promise yet.
 */
public record ModNoticeAckPayload(List<String> knownModIds) implements CustomPacketPayload {
    public static final Type<ModNoticeAckPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("modreloader", "mod_notice_ack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ModNoticeAckPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ModNoticeAckPayload decode(RegistryFriendlyByteBuf buf) {
                    int count = buf.readVarInt();
                    List<String> ids = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        ids.add(buf.readUtf(64));
                    }
                    return new ModNoticeAckPayload(List.copyOf(ids));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ModNoticeAckPayload payload) {
                    buf.writeVarInt(payload.knownModIds().size());
                    for (String id : payload.knownModIds()) {
                        buf.writeUtf(id, 64);
                    }
                }
            };

    @Override
    public Type<ModNoticeAckPayload> type() {
        return TYPE;
    }
}
