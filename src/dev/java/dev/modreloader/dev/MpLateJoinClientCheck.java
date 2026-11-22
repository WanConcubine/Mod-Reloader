package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.ModNotice;
import net.minecraft.client.Minecraft;
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
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dev-only client half of the late-join check.
 *
 * <p>The jar must already be in {@code run/mods} so FML loads it at client
 * startup (the restart path). The server hot-added it before this client
 * connected, so the only way the client can see the same ids is NeoForge's
 * normal login registry sync. After the id comparison the client performs a real
 * {@code useItemOn} with the hot-added block; the server confirms the block state.
 *
 * <p>{@code -PverifyMpLateJoin=<jar>|<modId>[|<blockPath>]}.
 */
public final class MpLateJoinClientCheck {
    private enum Stage { WAIT_TITLE, CONNECT, WAIT_JOIN, SETTLE, COMPARE, PREPARE, PLACE, VERIFY, PLAY, DONE }

    private static final Path SERVER_DUMP = MpLateJoinServerCheck.DUMP;
    private static final Path PLACE_MARKER = MpLateJoinServerCheck.PLACE;
    private static final Path CLIENT_DUMP = Path.of("mp-latejoin-client.txt");
    private static final Path CLIENT_PLACED = Path.of("mp-latejoin-client-placed.txt");
    private static final int TIMEOUT_TICKS = 20 * 300;
    private static final int SETTLE_TICKS = 20 * 8;
    private static final int PREPARE_TIMEOUT_TICKS = 20 * 30;
    private static final int PLACE_RETRIES = 3;
    private static final int VERIFY_TIMEOUT_TICKS = 20 * 10;
    private static final int PLAY_TICKS = 20 * 10;

    private static String modId;
    private static String blockPath;
    private static Block block;
    private static Item item;
    private static BlockPos targetPos;
    private static BlockPos placePos;
    private static int attempts;

    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;

    private MpLateJoinClientCheck() {
    }

    public static void start(String spec) {
        if (started) {
            return;
        }
        String[] parts = spec.split("\\|");
        modId = parts.length > 1 ? parts[1].trim() : "";
        blockPath = parts.length > 2 ? parts[2].trim() : "test_block";
        started = true;
        try {
            Files.deleteIfExists(CLIENT_DUMP);
            Files.deleteIfExists(CLIENT_PLACED);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpLateJoin] cannot clear old client markers", t);
        }
        ModReloader.LOGGER.info(
                "[MpLateJoin] client side armed: modId={} block={}:{} (jar must already be in run/mods)",
                modId, modId, blockPath);
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
            case WAIT_TITLE -> {
                if (ticks >= 100 && mc.getOverlay() == null) {
                    stage = Stage.CONNECT;
                    ticks = 0;
                }
            }
            case CONNECT -> connect(mc);
            case WAIT_JOIN -> waitJoin(mc);
            case SETTLE -> {
                if (ticks >= SETTLE_TICKS) {
                    stage = Stage.COMPARE;
                    ticks = 0;
                }
            }
            case COMPARE -> compare(mc);
            case PREPARE -> preparePlacement(mc);
            case PLACE -> place(mc);
            case VERIFY -> verify(mc);
            case PLAY -> play(mc);
            case DONE -> {
                if (ticks >= 40) {
                    ModReloader.LOGGER.info("[MpLateJoin] stopping client");
                    started = false;
                    mc.stop();
                }
            }
        }
    }

    private static void connect(Minecraft mc) {
        if (mc.getOverlay() != null) {
            if (ticks > 20 * 60) {
                fail("resource reload overlay did not clear");
            }
            return;
        }
        String address = System.getProperty("modreloader.mpLateJoinAddress", "127.0.0.1:25565");
        if (!TestConnection.connect(mc, "MpLateJoin", address, "mp-latejoin")) {
            fail("connect failed");
            return;
        }
        stage = Stage.WAIT_JOIN;
        ticks = 0;
    }

    private static void waitJoin(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            return;
        }
        boolean loaded = modId.isEmpty() || ModList.get().isLoaded(modId);
        ModReloader.LOGGER.info(
                "[MpLateJoin] joined; isLoaded={} (modId={}), noticeCount={} (late joins are not notified)",
                loaded, modId, ModNotice.receivedCount());
        ModReloader.LOGGER.info("[MpLateJoin] settling for {}s to let the login registry sync finish",
                SETTLE_TICKS / 20);
        stage = Stage.SETTLE;
        ticks = 0;
    }

    private static void compare(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            fail("connection dropped before the registry comparison");
            return;
        }
        if (modId != null && !modId.isEmpty() && !ModList.get().isLoaded(modId)) {
            fail("mod " + modId + " is not loaded on the client");
            return;
        }
        List<String> serverLines;
        try {
            serverLines = Files.readAllLines(SERVER_DUMP);
        } catch (Throwable t) {
            fail("cannot read " + SERVER_DUMP + ": " + t);
            return;
        }
        List<String> clientLines = RegistryDump.lines();
        try {
            Files.write(CLIENT_DUMP, clientLines);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpLateJoin] cannot write {}", CLIENT_DUMP, t);
        }
        List<String> differences = RegistryDump.differences(serverLines, clientLines);
        if (!differences.isEmpty()) {
            ModReloader.LOGGER.error("[MpLateJoin] registry mismatch ({} server lines, {} client lines):",
                    serverLines.size(), clientLines.size());
            differences.forEach(line -> ModReloader.LOGGER.error("[MpLateJoin]   {}", line));
            fail("registry ids differ from the server after a late join");
            return;
        }
        ModReloader.LOGGER.info("[MpLateJoin] ids match the server exactly ({} lines, blocks={}, items={})",
                clientLines.size(),
                net.minecraft.core.registries.BuiltInRegistries.BLOCK.size(),
                net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
        stage = Stage.PREPARE;
        ticks = 0;
    }

    private static void preparePlacement(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null) {
            fail("connection dropped before the placement action");
            return;
        }
        if (block == null || item == null) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(modId, blockPath);
            block = BuiltInRegistries.BLOCK.get(id);
            item = BuiltInRegistries.ITEM.get(id);
            if (block == net.minecraft.world.level.block.Blocks.AIR
                    || item == net.minecraft.world.item.Items.AIR) {
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
            return; // marker not written yet
        }
        int slot = findSlot(mc);
        if (slot < 0) {
            return; // the server has not given the item yet
        }
        mc.player.getInventory().selected = slot;
        mc.player.connection.send(new ServerboundSetCarriedItemPacket(slot));
        ModReloader.LOGGER.info(
                "[MpLateJoin] prepared to place {} at {} on target {} (hotbar slot {})",
                block.builtInRegistryHolder().key().location(), placePos, targetPos, slot);
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
        if (mc.level.getBlockState(placePos).is(block)) {
            ModReloader.LOGGER.info(
                    "[MpLateJoin] client placed {} at {} (local block state confirmed)",
                    block.builtInRegistryHolder().key().location(), placePos);
            try {
                Files.writeString(CLIENT_PLACED, "place " + placePos.getX() + " "
                        + placePos.getY() + " " + placePos.getZ() + "\n");
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[MpLateJoin] cannot write {}", CLIENT_PLACED, t);
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
        ModReloader.LOGGER.info("[MpLateJoin] useItemOn({}, {}) = {}", targetPos, Direction.UP, result);
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
                "[MpLateJoin] still connected={}, playerAlive={}, level={} after {}s in world",
                true, mc.player.isAlive(), mc.level.dimension().location(), PLAY_TICKS / 20);
        ModReloader.LOGGER.info("[MpLateJoin] RESULT: PASS");
        stage = Stage.DONE;
        ticks = 0;
    }

    private static int findSlot(Minecraft mc) {
        for (int i = 0; i < mc.player.getInventory().getContainerSize(); i++) {
            if (mc.player.getInventory().getItem(i).is(item)) {
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
        ModReloader.LOGGER.error("[MpLateJoin] {}", message);
        ModReloader.LOGGER.error("[MpLateJoin] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}