package dev.modreloader.payloadfix;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Payload fixture: registers a custom payload, sends it to a player when they
 * join, and writes a marker file on the receiving side. Used to prove that a
 * hot-added mod's network channel actually works.
 */
@Mod(PayloadMod.MOD_ID)
public final class PayloadMod {
    public static final String MOD_ID = "payloadfix";
    private static final Logger LOGGER = LoggerFactory.getLogger("PayloadFix");

    public PayloadMod(IEventBus modBus) {
        modBus.addListener(PayloadMod::onPayloads);
        NeoForge.EVENT_BUS.addListener(PayloadMod::onLogin);
        NeoForge.EVENT_BUS.addListener(PayloadMod::onServerTick);
        LOGGER.info("[PayloadFix] @Mod constructed");
    }

    private static void onPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playBidirectional(Ping.TYPE, Ping.CODEC, PayloadMod::onPing);
        LOGGER.info("[PayloadFix] payload registered");
    }

    private static void onPing(Ping payload, IPayloadContext context) {
        boolean client = context.player().level().isClientSide();
        LOGGER.info("[PayloadFix] PING RECEIVED on side {}", client ? "client" : "server");
        if (!client) {
            return;
        }
        try {
            Files.writeString(Path.of("payload-received.txt"), "ok " + System.currentTimeMillis());
        } catch (Exception e) {
            LOGGER.warn("[PayloadFix] cannot write the marker file", e);
        }
    }

    private static int ticks;

    /** Keeps sending, so a mod hot-added after the player joined still gets traffic. */
    private static void onServerTick(ServerTickEvent.Post event) {
        if (++ticks % 60 != 0) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!NetworkRegistry.hasChannel(player.connection.getConnection(),
                    ConnectionProtocol.PLAY, Ping.TYPE.id())) {
                continue;
            }
            try {
                PacketDistributor.sendToPlayer(player, new Ping());
            } catch (Throwable t) {
                LOGGER.warn("[PayloadFix] cannot send ping: {}", t.toString());
            }
        }
    }

    private static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player
                && NetworkRegistry.hasChannel(player.connection.getConnection(),
                ConnectionProtocol.PLAY, Ping.TYPE.id())) {
            LOGGER.info("[PayloadFix] sending ping to {}", player.getName().getString());
            PacketDistributor.sendToPlayer(player, new Ping());
        } else {
            LOGGER.info("[PayloadFix] no channel yet at login; the tick loop will retry");
        }
    }

    public record Ping() implements CustomPacketPayload {
        public static final Type<Ping> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(MOD_ID, "ping"));
        public static final StreamCodec<FriendlyByteBuf, Ping> CODEC = StreamCodec.unit(new Ping());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
