package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.ChannelAttributes;
import net.neoforged.neoforge.network.registration.NetworkPayloadSetup;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Dev-only end-to-end check for payload adaptation ({@code -PverifyPayload=<jar>}).
 *
 * <p>Hot-adds a payload-registering mod at the title screen, creates a world, and
 * waits for the marker file the mod writes when it receives its own payload. That
 * proves the channel was registered, negotiated and actually used.
 */
public final class PayloadCheck {
    private enum Stage { WAIT_TITLE, ADD, OPEN_CREATE, WAIT_CREATE_SCREEN, WAIT_WORLD, WAIT_PING, DONE }

    private static final int TIMEOUT_TICKS = 20 * 180;
    private static final Path MARKER = Path.of("payload-received.txt");
    private static final ResourceLocation PING =
            ResourceLocation.fromNamespaceAndPath("payloadfix", "ping");

    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static boolean started;
    private static boolean failed;
    private static boolean pressedCreate;
    private static Path jar;
    private static final boolean AFTER_JOIN =
            Boolean.getBoolean("modreloader.verifyPayloadAfterJoin");
    private static int worldTicks;
    private static int serverTicks;
    private static boolean dumpedWorldSides;

    private PayloadCheck() {
    }

    public static void start(Path target) {
        if (started) {
            return;
        }
        started = true;
        jar = target;
        try {
            Files.deleteIfExists(MARKER);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[PayloadCheck] cannot remove an old marker", t);
        }
        ModReloader.LOGGER.info("[PayloadCheck] started; hot-adding {}", jar);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
        installProbes();
        startDiagnostics();
    }

    /**
     * Independent probes: do not rely on the fixture's own listeners. If these stay
     * silent, the problem is bus dispatch, not the payload negotiation.
     */
    private static void installProbes() {
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event ->
                ModReloader.LOGGER.info("[PayloadCheck] probe: PlayerLoggedInEvent fired for {}",
                        event.getEntity().getName().getString()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> {
            if (serverTicks++ == 20) {
                ModReloader.LOGGER.info("[PayloadCheck] probe: ServerTickEvent.Post reached (tick {})",
                        serverTicks);
            }
        });
    }

    private static void logRegistrations(String when) {
        try {
            Map<?, ?> registrations = UnsafeAccess.getStatic(NetworkRegistry.class, "PAYLOAD_REGISTRATIONS");
            if (registrations == null) {
                ModReloader.LOGGER.info("[PayloadCheck] {}: PAYLOAD_REGISTRATIONS unavailable", when);
                return;
            }
            for (Map.Entry<?, ?> entry : registrations.entrySet()) {
                if (entry.getValue() instanceof Map<?, ?> byId) {
                    ModReloader.LOGGER.info("[PayloadCheck] {}: registrations {} -> {} (ping={})",
                            when, entry.getKey(), byId.keySet(), byId.containsKey(PING));
                }
            }
            ModReloader.LOGGER.info("[PayloadCheck] {}: NetworkRegistry.setup={}", when,
                    UnsafeAccess.getStaticBoolean(NetworkRegistry.class, "setup"));
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[PayloadCheck] cannot read the payload registrations", t);
        }
    }

    private static void logSetup(String side, Connection connection) {
        if (connection == null) {
            ModReloader.LOGGER.info("[PayloadCheck] {}: no connection", side);
            return;
        }
        NetworkPayloadSetup setup = ChannelAttributes.getPayloadSetup(connection);
        if (setup == null) {
            ModReloader.LOGGER.info("[PayloadCheck] {}: no PayloadSetup on the connection", side);
            return;
        }
        ModReloader.LOGGER.info("[PayloadCheck] {}: PLAY channels={} pingChannel={} hasChannel={}",
                side, setup.getChannels(ConnectionProtocol.PLAY).keySet(),
                setup.getChannel(ConnectionProtocol.PLAY, PING) != null,
                NetworkRegistry.hasChannel(connection, ConnectionProtocol.PLAY, PING));
    }

    private static void logWorldSides(Minecraft mc) {
        logSetup("client connection",
                mc.getConnection() == null ? null : mc.getConnection().getConnection());
        if (mc.getSingleplayerServer() != null) {
            mc.getSingleplayerServer().getPlayerList().getPlayers().forEach(player ->
                    logSetup("server player " + player.getName().getString(),
                            player.connection.getConnection()));
        }
    }

    /** Client ticks stall inside {@code doWorldLoad}, so a separate thread reports state. */
    private static void startDiagnostics() {
        Thread thread = new Thread(() -> {
            while (started) {
                try {
                    Thread.sleep(2000L);
                } catch (InterruptedException e) {
                    return;
                }
                Minecraft mc = Minecraft.getInstance();
                if (mc == null) {
                    continue;
                }
                ModReloader.LOGGER.info("[PayloadCheck] diag: stage={}, overlay={}, server={}, ready={}",
                        stage,
                        mc.getOverlay() == null ? "null" : mc.getOverlay().getClass().getSimpleName(),
                        mc.getSingleplayerServer() == null ? "null" : "present",
                        mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isReady());
            }
        }, "modreloader-payload-diag");
        thread.setDaemon(true);
        thread.start();
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        if (ticks > TIMEOUT_TICKS) {
            fail("timeout at stage " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WAIT_TITLE -> waitTitle(mc);
            case ADD -> add();
            case OPEN_CREATE -> openCreate(mc);
            case WAIT_CREATE_SCREEN -> waitCreateScreen(mc);
            case WAIT_WORLD -> waitWorld(mc);
            case WAIT_PING -> waitPing();
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void waitTitle(Minecraft mc) {
        if (!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)
                || mc.getOverlay() != null
                || ModReloader.WATCHER == null) {
            return;
        }
        stage = AFTER_JOIN ? Stage.OPEN_CREATE : Stage.ADD;
        ticks = 0;
    }

    private static void add() {
        HotAddEngine.Result result = HotAddEngine.add(jar);
        ModReloader.LOGGER.info("[PayloadCheck] hot-add result: ok={}, kind={}, message={}",
                result.ok(), result.kind(), result.message());
        logRegistrations("after add");
        if (!result.ok()) {
            fail("hot-add failed: " + result.kind() + ": " + result.message());
            return;
        }
        if (AFTER_JOIN) {
            stage = Stage.WAIT_PING;
            ticks = 0;
            return;
        }
        stage = Stage.OPEN_CREATE;
        ticks = 0;
    }

    private static void openCreate(Minecraft mc) {
        // The hot-add triggers a resource-pack reload; opening the create-world
        // screen under its overlay swallows the button press.
        if (mc.getOverlay() != null) {
            if (ticks > 20 * 60) {
                fail("resource reload overlay did not clear");
            }
            return;
        }
        try {
            CreateWorldScreen.openFresh(mc, mc.screen);
            stage = Stage.WAIT_CREATE_SCREEN;
            ticks = 0;
        } catch (Throwable t) {
            fail("CreateWorldScreen.openFresh failed: " + t);
        }
    }

    private static void waitCreateScreen(Minecraft mc) {
        if (!(mc.screen instanceof CreateWorldScreen screen)) {
            if (ticks > 20 * 30) {
                fail("create-world screen did not appear");
            }
            return;
        }
        if (pressedCreate) {
            return;
        }
        String label = I18n.get("selectWorld.create");
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button
                    && label.equals(button.getMessage().getString())) {
                pressedCreate = true;
                button.onPress();
                stage = Stage.WAIT_WORLD;
                ticks = 0;
                worldTicks = 0;
                return;
            }
        }
    }

    private static void waitWorld(Minecraft mc) {
        if (ticks % 100 == 0) {
            ModReloader.LOGGER.info("[PayloadCheck] waiting: level={}, player={}, overlay={}, "
                            + "singleplayerServer={}, ready={}",
                    mc.level != null, mc.player != null,
                    mc.getOverlay() == null ? "null" : mc.getOverlay().getClass().getSimpleName(),
                    mc.getSingleplayerServer() == null ? "null" : "present",
                    mc.getSingleplayerServer() != null && mc.getSingleplayerServer().isReady());
        }
        if (mc.level == null || mc.player == null) {
            worldTicks = 0;
            if (ticks > 20 * 120) {
                fail("world did not load");
            }
            return;
        }
        worldTicks++;
        if (worldTicks < 20) {
            return;
        }
        if (AFTER_JOIN) {
            ModReloader.LOGGER.info("[PayloadCheck] in world; hot-adding now (after the connection exists)");
            stage = Stage.ADD;
            ticks = 0;
            return;
        }
        ModReloader.LOGGER.info("[PayloadCheck] in world; waiting for the payload marker");
        if (!dumpedWorldSides) {
            dumpedWorldSides = true;
            logRegistrations("in world");
            logWorldSides(mc);
        }
        stage = Stage.WAIT_PING;
        ticks = 0;
    }

    private static void waitPing() {
        if (Files.exists(MARKER)) {
            ModReloader.LOGGER.info("[PayloadCheck] marker written: {}", MARKER.toAbsolutePath());
            ModReloader.LOGGER.info("[PayloadCheck] RESULT: PASS");
            stage = Stage.DONE;
            ticks = 0;
            return;
        }
        if (ticks > 20 * 40) {
            fail("no payload received within 40s (marker missing)");
        }
    }

    private static void stopWhenDone(Minecraft mc) {
        if (ticks >= 20) {
            started = false;
            try {
                Files.deleteIfExists(MARKER);
            } catch (Throwable ignored) {
                // Best effort.
            }
            ModReloader.LOGGER.info("[PayloadCheck] stopping client");
            mc.stop();
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[PayloadCheck] FAILED: {}", reason);
        ModReloader.LOGGER.error("[PayloadCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
