package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.ModInspection;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforgespi.language.IModInfo;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tells connected players what the server just hot-added.
 *
 * <p>Written after a user question that exposed a real gap: a hot-add on the
 * server can affect clients in three different ways — nothing at all (a
 * server-side mod), a refused id snapshot (registry content the client lacks), or
 * a mid-session disconnect (a payload channel the client never registered) — and
 * in none of those cases did the client learn <em>which mod</em> caused it. That
 * leaves exactly one useful action (install/hot-add the same jar on the client)
 * undiscoverable.
 *
 * <p>So this is a deliberately dumb informing layer: no gating, no negotiation,
 * no promises. It sends the facts (id, name, version, jar file, hash prefix, tier,
 * whether ids must match) and the client turns them into a message that says what
 * to do. The client answers with the ids it already has, and the server logs who
 * is missing what, so an admin can act.
 *
 * <p>Ordering caveat: the notice is sent after the whole pipeline succeeds, so a
 * mod that ships a payload and sends it immediately could still get a client
 * disconnected before the notice arrives. Informing cannot outrun a bad
 * interleaving; it can only make the aftermath legible.
 */
public final class ModNotice {
    /** Notices received on this client since start-up; dev harnesses assert on it. */
    private static final AtomicInteger RECEIVED = new AtomicInteger();

    private ModNotice() {
    }

    /** Mod-bus listener: declares the notice channel and its ack. */
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(ServerModNoticePayload.TYPE, ServerModNoticePayload.STREAM_CODEC,
                        ModNotice::handleNotice)
                .playToServer(ModNoticeAckPayload.TYPE, ModNoticeAckPayload.STREAM_CODEC,
                        ModNotice::handleAck);
    }

    private static void handleNotice(ServerModNoticePayload payload, IPayloadContext context) {
        context.enqueueWork(() ->
                PlatformHooks.get().acceptServerModNotice(payload.notices()));
    }

    private static void handleAck(ModNoticeAckPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            ServerPlayer player = context.player() instanceof ServerPlayer p ? p : null;
            String name = player == null ? "?" : player.getGameProfile().getName();
            PlatformHooks.get().acceptModNoticeAck(name, payload.knownModIds());
        });
    }

    /**
     * @param jar              the jar that was hot-added
     * @param containers       the containers that were built (one jar can declare several mods)
     * @param registryContent  the mod brought block/item content, so ids must match
     * @param inspection       the prediction used for the tier label; may be {@code null}
     * @return how many players the notice was sent to
     */
    public static int notifyPlayers(Path jar, List<ModContainer> containers, boolean registryContent,
                                    ModInspection inspection) {
        if (containers.isEmpty()) {
            return 0;
        }
        List<ServerPlayer> targets = PlatformHooks.get().connectedPlayers();
        if (targets.isEmpty()) {
            // Nobody to tell; the server log stays the only record.
            ModReloader.LOGGER.info(
                    "[ModReloader] no players online; the next join gets the mod through the normal "
                            + "login path");
            return 0;
        }
        String jarName = jar.getFileName() == null ? jar.toString() : jar.getFileName().toString();
        String hash = hashPrefix(jar);
        String tier = inspection != null && inspection.ok() ? inspection.tier().id() : "";

        List<ServerModNoticePayload.Notice> notices = new ArrayList<>(containers.size());
        for (ModContainer container : containers) {
            IModInfo info = container.getModInfo();
            notices.add(new ServerModNoticePayload.Notice(
                    container.getModId(),
                    info == null ? container.getModId() : info.getDisplayName(),
                    info == null ? "" : String.valueOf(info.getVersion()),
                    jarName, hash, tier, registryContent));
        }

        ServerModNoticePayload payload = new ServerModNoticePayload(List.copyOf(notices));
        for (ServerPlayer player : targets) {
            player.connection.send(payload);
        }
        ModReloader.LOGGER.info(
                "[ModReloader] told {} connected player(s) about {}: {} {} (jar {}, sha256:{}…)",
                targets.size(), notices.stream().map(ServerModNoticePayload.Notice::modId).toList(),
                notices.get(0).displayName(), notices.get(0).version(), jarName, hash);
        return targets.size();
    }

    /** Client half: a notice arrived. Counted so the dev harness can assert on it. */
    public static void recordReceived() {
        RECEIVED.incrementAndGet();
    }

    public static int receivedCount() {
        return RECEIVED.get();
    }

    /** First 8 hex chars of the jar's SHA-256; lets the client compare builds. */
    static String hashPrefix(Path jar) {
        try (InputStream in = Files.newInputStream(jar)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest()).substring(0, 8);
        } catch (Throwable t) {
            ModReloader.LOGGER.debug("[ModReloader] cannot hash {}", jar, t);
            return "????????";
        }
    }
}
