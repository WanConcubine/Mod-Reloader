package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only client half of the multiplayer registry-sync check.
 *
 * <p>Sequence: connect to a dedicated server, hot-add this side's copies while in
 * world (so the local ids are genuinely computed from a different registry order
 * than the server's), write {@code mp-client-ready.txt}, then wait for the server
 * to hot-add its own copies and publish {@code mp-registry-server.txt}. The server
 * pushes its snapshot when its commit lands; the client applies it and the check
 * compares <em>every</em> block id, item id and block-state id against the server's
 * file.
 *
 * <p>{@code -PverifyMpRegistry=<jar>[,<jar>...]} lists the client's jars in the
 * client's own order — deliberately different from the server's so ids differ.
 */
public final class MpRegistryCheck {
    private enum Stage {
        WAIT_TITLE, CONNECT, WAIT_JOIN, ADD, WRITE_READY, WAIT_SERVER, SETTLE, COMPARE,
        PREPARE_PLACE, PLACE, VERIFY, PLAY, DONE
    }

    private static final Path READY = Path.of("mp-client-ready.txt");
    private static final Path SERVER_DUMP = Path.of("mp-registry-server.txt");
    private static final Path CLIENT_DUMP = Path.of("mp-registry-client.txt");
    private static final Path PLACE_MARKER = MpRegistryServerCheck.PLACE;
    private static final Path CLIENT_PLACED = Path.of("mp-registry-client-placed.txt");
    private static final int TIMEOUT_TICKS = 20 * 300;
    /** Time for the snapshot payload to arrive and be applied after the server's file shows up. */
    private static final int SYNC_SETTLE_TICKS = 20 * 8;
    private static final int PREPARE_TIMEOUT_TICKS = 20 * 30;
    private static final int PLACE_RETRIES = 3;
    private static final int VERIFY_TIMEOUT_TICKS = 20 * 10;
    private static final int PLAY_TICKS = 20 * 10;

    private static final List<Path> JARS = new ArrayList<>();
    private static String placeModId;
    private static String placeBlockPath;
    private static Block placeBlock;
    private static Item placeItem;
    private static BlockPos targetPos;
    private static BlockPos placePos;
    private static int attempts;
    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean failed;

    private MpRegistryCheck() {
    }

    public static void start(String csv) {
        if (started) {
            return;
        }
        for (String part : csv.split(",")) {
            String raw = part.trim();
            if (!raw.isEmpty()) {
                JARS.add(Path.of(raw).toAbsolutePath().normalize());
            }
        }
        if (JARS.isEmpty()) {
            ModReloader.LOGGER.warn("[MpRegistry] no jars given; not starting");
            return;
        }
        String place = System.getProperty("modreloader.mpRegistryPlace", "").trim();
        if (!place.isEmpty()) {
            String[] parts = place.split("\\|");
            placeModId = parts.length > 0 ? parts[0].trim() : "";
            placeBlockPath = parts.length > 1 ? parts[1].trim() : "test_block";
        }
        started = true;
        try {
            Files.deleteIfExists(READY);
            Files.deleteIfExists(CLIENT_DUMP);
            Files.deleteIfExists(CLIENT_PLACED);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpRegistry] cannot clear old markers", t);
        }
        ModReloader.LOGGER.info("[MpRegistry] client side armed; jars (in this order)={}; place={}",
                JARS, place.isEmpty() ? "off" : place);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            fail("timeout at stage " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WAIT_TITLE -> waitTitle(mc);
            case CONNECT -> connect(mc);
            case WAIT_JOIN -> waitJoin(mc);
            case ADD -> add(mc);
            case WRITE_READY -> writeReady(mc);
            case WAIT_SERVER -> waitServer(mc);
            case SETTLE -> settle(mc);
            case COMPARE -> compare(mc);
            case PREPARE_PLACE -> preparePlacement(mc);
            case PLACE -> place(mc);
            case VERIFY -> verify(mc);
            case PLAY -> play(mc);
            case DONE -> {
                if (ticks >= 40) {
                    ModReloader.LOGGER.info("[MpRegistry] stopping client");
                    started = false;
                    mc.stop();
                }
            }
        }
    }

    private static void waitTitle(Minecraft mc) {
        if (ticks < 100 || mc.getOverlay() != null) {
            return;
        }
        stage = Stage.CONNECT;
        ticks = 0;
    }

    private static void connect(Minecraft mc) {
        if (mc.getOverlay() != null) {
            if (ticks > 20 * 60) {
                fail("resource reload overlay did not clear");
            }
            return;
        }
        String address = System.getProperty("modreloader.mpRegistryAddress", "127.0.0.1:25565");
        if (!TestConnection.connect(mc, "MpRegistry", address, "mp")) {
            fail("connect failed");
            return;
        }
        stage = Stage.WAIT_JOIN;
        ticks = 0;
    }

    private static void waitJoin(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || mc.getOverlay() != null) {
            return;
        }
        stage = Stage.ADD;
        ticks = 0;
    }

    private static void add(Minecraft mc) {
        if (mc.getOverlay() != null) {
            return;
        }
        for (Path jar : JARS) {
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[MpRegistry] client hot-add {}: ok={} kind={} msg={}",
                    jar.getFileName(), result.ok(), result.kind(), result.message());
            if (!result.ok()) {
                fail("client hot-add failed: " + result.kind() + ": " + result.message());
                return;
            }
        }
        ModReloader.LOGGER.info("[MpRegistry] client registries after its own adds: blocks={} items={}",
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.size(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
        stage = Stage.WRITE_READY;
        ticks = 0;
    }

    private static void writeReady(Minecraft mc) {
        // Let the add's own resource reload finish: the server waits 3s after this
        // marker, and a half-finished reload would interleave with the sync.
        if (mc.getOverlay() != null || ticks < 40) {
            return;
        }
        try {
            Files.writeString(READY, "ready");
            ModReloader.LOGGER.info("[MpRegistry] wrote {}; waiting for the server's ids", READY);
            stage = Stage.WAIT_SERVER;
            ticks = 0;
        } catch (Throwable t) {
            fail("cannot write " + READY + ": " + t);
        }
    }

    private static void waitServer(Minecraft mc) {
        if (!Files.isRegularFile(SERVER_DUMP)) {
            if (ticks % 100 == 0) {
                ModReloader.LOGGER.info("[MpRegistry] waiting for {}", SERVER_DUMP);
            }
            return;
        }
        stage = Stage.SETTLE;
        ticks = 0;
    }

    private static void settle(Minecraft mc) {
        if (ticks < SYNC_SETTLE_TICKS) {
            return;
        }
        stage = Stage.COMPARE;
        ticks = 0;
    }

    private static void compare(Minecraft mc) {
        try {
            List<String> client = RegistryDump.lines();
            List<String> server = Files.readAllLines(SERVER_DUMP);
            Files.write(CLIENT_DUMP, client);
            List<String> differences = RegistryDump.differences(server, client);
            if (differences.isEmpty()) {
                ModReloader.LOGGER.info(
                        "[MpRegistry] ids match the server exactly ({} lines, blocks={}, items={})",
                        client.size(),
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.size(),
                        net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
                if (placeModId != null) {
                    ModReloader.LOGGER.info(
                            "[MpRegistry] registry sync done; starting the client-hot-add L2 placement");
                    stage = Stage.PREPARE_PLACE;
                    ticks = 0;
                    return;
                }
                ModReloader.LOGGER.info("[MpRegistry] RESULT: PASS");
            } else {
                ModReloader.LOGGER.error("[MpRegistry] client ids differ from the server: {}",
                        differences);
                ModReloader.LOGGER.error("[MpRegistry] RESULT: FAIL (registry mapping was not synced)");
            }
        } catch (Throwable t) {
            fail("compare failed: " + t);
            return;
        }
        stage = Stage.DONE;
        ticks = 0;
    }

    private static void preparePlacement(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            fail("connection dropped before the placement action");
            return;
        }
        if (placeBlock == null || placeItem == null) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(placeModId, placeBlockPath);
            placeBlock = BuiltInRegistries.BLOCK.get(id);
            placeItem = BuiltInRegistries.ITEM.get(id);
            if (placeBlock == net.minecraft.world.level.block.Blocks.AIR
                    || placeItem == net.minecraft.world.item.Items.AIR) {
                fail("cannot find the hot-added block/item " + id);
                return;
            }
        }
        if (ticks > PREPARE_TIMEOUT_TICKS) {
            fail("server did not prepare the placement platform");
            return;
        }
        try {
            List<String> lines = Files.readAllLines(PLACE_MARKER);
            targetPos = parsePos(lines, "target");
            placePos = parsePos(lines, "place");
        } catch (Throwable t) {
            return;
        }
        int slot = findSlot(mc);
        if (slot < 0) {
            return;
        }
        mc.player.getInventory().selected = slot;
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        ModReloader.LOGGER.info(
                "[MpRegistry] prepared to place {} at {} on target {} (hotbar slot {})",
                placeBlock.builtInRegistryHolder().key().location(), placePos, targetPos, slot);
        stage = Stage.PLACE;
        ticks = 0;
    }

    private static void place(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || mc.level == null) {
            fail("connection dropped before useItemOn");
            return;
        }
        if (ticks < 5) {
            return;
        }
        sendUse(mc);
        attempts = 1;
        stage = Stage.VERIFY;
        ticks = 0;
    }

    private static void verify(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || mc.level == null) {
            fail("connection dropped during the placement verification");
            return;
        }
        if (mc.level.getBlockState(placePos).is(placeBlock)) {
            ModReloader.LOGGER.info(
                    "[MpRegistry] client hot-add placed {} at {} (local block state confirmed)",
                    placeBlock.builtInRegistryHolder().key().location(), placePos);
            try {
                Files.writeString(CLIENT_PLACED, "place " + placePos.getX() + " "
                        + placePos.getY() + " " + placePos.getZ() + "\n");
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[MpRegistry] cannot write {}", CLIENT_PLACED, t);
            }
            stage = Stage.PLAY;
            ticks = 0;
            return;
        }
        if (ticks > VERIFY_TIMEOUT_TICKS) {
            fail("the placed block never appeared on the client");
            return;
        }
        if (ticks % 20 == 0 && attempts < PLACE_RETRIES) {
            sendUse(mc);
            attempts++;
        }
    }

    private static void sendUse(Minecraft mc) {
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(targetPos), Direction.UP,
                targetPos, false);
        Object result = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
        ModReloader.LOGGER.info("[MpRegistry] useItemOn({}, {}) = {}", targetPos, Direction.UP, result);
    }

    private static void play(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || mc.level == null) {
            fail("connection dropped during the play window");
            return;
        }
        if (ticks < PLAY_TICKS) {
            return;
        }
        ModReloader.LOGGER.info(
                "[MpRegistry] still connected={}, playerAlive={}, level={} after {}s in world",
                true, mc.player.isAlive(), mc.level.dimension().location(), PLAY_TICKS / 20);
        ModReloader.LOGGER.info("[MpRegistry] RESULT: PASS");
        stage = Stage.DONE;
        ticks = 0;
    }

    private static int findSlot(Minecraft mc) {
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).is(placeItem)) {
                return i;
            }
        }
        return -1;
    }

    private static BlockPos parsePos(List<String> lines, String prefix) {
        for (String line : lines) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length == 4 && parts[0].equals(prefix)) {
                return new BlockPos(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]));
            }
        }
        throw new IllegalArgumentException("no " + prefix + " line in " + lines);
    }

    private static void fail(String message) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[MpRegistry] {}", message);
        ModReloader.LOGGER.error("[MpRegistry] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
