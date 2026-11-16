package dev.modreloader.hotadd;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * "The server just hot-added these mods" — sent to every connected player.
 *
 * <p>This is the informing half of hot-add: it does not make anything work, it
 * makes the situation <em>legible</em>. A player who cannot see why a block is
 * missing, or why they were just disconnected for an unknown payload, has no way
 * to take the one action that actually helps (putting the same jar on their
 * client). The notice carries exactly what that decision needs: which mod, which
 * version, which jar, and whether it brought registry content.
 */
public record ServerModNoticePayload(List<Notice> notices) implements CustomPacketPayload {
    /**
     * @param modId         e.g. {@code exnihilosatellitium}
     * @param displayName   what the player should look for in a mod list
     * @param version       the version string the server runs
     * @param jarName       file name the server loaded, so the client can compare
     * @param hash          first 8 hex chars of the jar's SHA-256 (identifies the build)
     * @param tier          {@code pin}/{@code fei}/{@code guifei}, for expectations
     * @param registryContent whether ids must match for the client to see anything
     */
    public record Notice(String modId, String displayName, String version, String jarName,
                         String hash, String tier, boolean registryContent) {
    }

    public static final Type<ServerModNoticePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath("modreloader", "server_mod_notice"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ServerModNoticePayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public ServerModNoticePayload decode(RegistryFriendlyByteBuf buf) {
                    int count = buf.readVarInt();
                    List<Notice> notices = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        notices.add(new Notice(
                                buf.readUtf(64), buf.readUtf(128), buf.readUtf(64),
                                buf.readUtf(256), buf.readUtf(16), buf.readUtf(16),
                                buf.readBoolean()));
                    }
                    return new ServerModNoticePayload(List.copyOf(notices));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ServerModNoticePayload payload) {
                    buf.writeVarInt(payload.notices().size());
                    for (Notice notice : payload.notices()) {
                        buf.writeUtf(notice.modId(), 64);
                        buf.writeUtf(notice.displayName(), 128);
                        buf.writeUtf(notice.version(), 64);
                        buf.writeUtf(notice.jarName(), 256);
                        buf.writeUtf(notice.hash(), 16);
                        buf.writeUtf(notice.tier(), 16);
                        buf.writeBoolean(notice.registryContent());
                    }
                }
            };

    @Override
    public Type<ServerModNoticePayload> type() {
        return TYPE;
    }
}
