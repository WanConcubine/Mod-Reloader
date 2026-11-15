package dev.modreloader.payloadfix;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fixture that registers a custom payload, to prove hot-add rejects it precisely. */
@Mod(PayloadMod.MOD_ID)
public final class PayloadMod {
    public static final String MOD_ID = "payloadfix";
    private static final Logger LOGGER = LoggerFactory.getLogger("PayloadFix");

    public PayloadMod(IEventBus modBus) {
        modBus.addListener(PayloadMod::onPayloads);
        LOGGER.info("[PayloadFix] @Mod constructed");
    }

    private static void onPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playBidirectional(Ping.TYPE, Ping.CODEC, (payload, context) -> {
        });
        LOGGER.info("[PayloadFix] payload registered");
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
