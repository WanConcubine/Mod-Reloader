package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dev-only server half of the late-join check.
 *
 * <p>The server hot-adds the jar <em>before any player connects</em>, publishes a
 * registry dump, then waits for a client that has the same jar loaded at startup
 * (restart path). Once that client joins, the server prepares a small platform,
 * teleports the player there, gives them the hot-added block item, and waits for
 * the client's real {@code useItemOn} to place it. The final PASS is authoritative
 * (the block exists on the server); the client also checks its local copy.
 *
 * <p>{@code -PmpLateJoinServer=<jar>|<modId>[|<blockPath>]}. The harness stops the
 * server by itself after the placement (or a failure), so a command pair is enough.
 */
public final class MpLateJoinServerCheck {
    static final Path READY = Path.of("mp-latejoin-ready.txt");
    static final Path DUMP = Path.of("mp-latejoin-server.txt");
    static final Path PLACE = Path.of("mp-latejoin-place.txt");
    static final Path FAILED = Path.of("mp-latejoin-server-fail.txt");

    private static final int START_DELAY_TICKS = 20;
    private static final int TIMEOUT_TICKS = 20 * 300;
    private static final int PLAYER_GRACE_TICKS = 20 * 5;
    private static final int PLACEMENT_TIMEOUT_TICKS = 20 * 60;
    private static final int STOP_AFTER_PLACEMENT_TICKS = 20 * 20;

    private static Path jar;
    private static String modId;
    private static String blockPath;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static BlockPos targetPos;
    private static BlockPos placePos;
    private static Block block;
    private static boolean armed;
    private static boolean added;
    private static boolean serverUp;
    private static boolean resultLogged;
    private static boolean prepared;
    private static boolean placementSeen;
    private static int ticks;
    private static int grace;
    private static int placementTicks;
    private static int stopTicks;

    private MpLateJoinServerCheck() {
    }

    public static void start(String spec) {
        if (armed) {
            return;
        }
        String[] parts = spec.split("\\|");
        String rawJar = parts.length > 0 ? parts[0].trim() : spec.trim();
        modId = parts.length > 1 ? parts[1].trim() : "";
        blockPath = parts.length > 2 ? parts[2].trim() : "test_block";
        jar = Path.of(rawJar).toAbsolutePath().normalize();
        armed = true;
        try {
            Files.deleteIfExists(READY);
            Files.deleteIfExists(DUMP);
            Files.deleteIfExists(PLACE);
            Files.deleteIfExists(FAILED);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpLateJoin] cannot clear old markers", t);
        }
        ModReloader.LOGGER.info(
                "[MpLateJoin] server side armed: jar={} modId={} block={}:{}",
                jar, modId, modId, blockPath);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            server = event.getServer();
            serverUp = true;
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!armed || !serverUp || server == null) {
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            fail("TIMEOUT waiting for a late client");
            return;
        }
        if (!added) {
            if (ticks < START_DELAY_TICKS) {
                return;
            }
            add();
            return;
        }
        if (!resultLogged) {
            if (server.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            if (++grace < PLAYER_GRACE_TICKS) {
                return;
            }
            resultLogged = true;
            boolean loaded = modId.isEmpty() || ModList.get().isLoaded(modId);
            int players = server.getPlayerList().getPlayers().size();
            ModReloader.LOGGER.info(
                    "[MpLateJoin] late client joined; players={}, isLoaded={}, dumpLines={}; "
                            + "preparing the placement action",
                    players, loaded, dumpLineCount());
            if (!loaded) {
                fail("late client joined but the hot-added mod is not loaded");
                return;
            }
            preparePlacement();
            return;
        }
        if (!prepared) {
            preparePlacement();
            return;
        }
        if (!placementSeen) {
            if (++placementTicks > PLACEMENT_TIMEOUT_TICKS) {
                fail("the late client never placed " + modId + ":" + blockPath);
                return;
            }
            BlockState state = level.getBlockState(placePos);
            if (state.is(block)) {
                placementSeen = true;
                ModReloader.LOGGER.info(
                        "[MpLateJoin] client placed {} at {} (server block state confirmed)",
                        state.getBlock().builtInRegistryHolder().key().location(), placePos);
                ModReloader.LOGGER.info("[MpLateJoin] RESULT: PASS");
            }
            return;
        }
        if (++stopTicks >= STOP_AFTER_PLACEMENT_TICKS) {
            armed = false;
            ModReloader.LOGGER.info("[MpLateJoin] stopping the server after the late-join L2 check");
            server.halt(false);
        }
    }

    private static void add() {
        added = true;
        ModReloader.LOGGER.info(
                "[MpLateJoin] no players online; hot-adding {} before any client connects",
                jar.getFileName());
        HotAddEngine.Result result = HotAddEngine.add(jar);
        boolean loaded = modId.isEmpty() || ModList.get().isLoaded(modId);
        ModReloader.LOGGER.info("[MpLateJoin] server hot-add: ok={} kind={} msg={} isLoaded={}",
                result.ok(), result.kind(), result.message(), loaded);
        if (!result.ok() || !loaded) {
            fail("server hot-add failed before the late client connected");
            return;
        }
        try {
            List<String> lines = RegistryDump.lines();
            Files.write(DUMP, lines);
            Files.writeString(READY, (modId.isEmpty() ? "?" : modId) + "\n"
                    + jar.getFileName() + "\n" + lines.size() + "\n");
            ModReloader.LOGGER.info(
                    "[MpLateJoin] wrote {} ({} lines) and {}; waiting for a late-joining client",
                    DUMP, lines.size(), READY);
        } catch (Throwable t) {
            fail("cannot write " + DUMP + ": " + t);
        }
    }

    private static void preparePlacement() {
        if (prepared) {
            return;
        }
        if (server.getPlayerList().getPlayers().isEmpty()) {
            fail("the client left before the placement action could start");
            return;
        }
        try {
            ServerLevel lvl = server.overworld();
            BlockPos base = new BlockPos(0, 120, 0);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    lvl.setBlockAndUpdate(new BlockPos(base.getX() + dx, base.getY(),
                            base.getZ() + dz), Blocks.STONE.defaultBlockState());
                }
            }
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    for (int dy = 1; dy <= 3; dy++) {
                        lvl.setBlockAndUpdate(new BlockPos(base.getX() + dx,
                                base.getY() + dy, base.getZ() + dz), Blocks.AIR.defaultBlockState());
                    }
                }
            }
            targetPos = new BlockPos(1, 120, 0);
            placePos = new BlockPos(1, 121, 0);
            lvl.setBlockAndUpdate(targetPos, Blocks.STONE.defaultBlockState());
            lvl.setBlockAndUpdate(placePos, Blocks.AIR.defaultBlockState());

            ResourceLocation blockId = ResourceLocation.fromNamespaceAndPath(modId, blockPath);
            block = BuiltInRegistries.BLOCK.get(blockId);
            Item item = BuiltInRegistries.ITEM.get(blockId);
            if (block == Blocks.AIR || item == Items.AIR) {
                fail("cannot find the hot-added block/item " + blockId);
                return;
            }
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            player.setGameMode(GameType.CREATIVE);
            player.teleportTo(lvl, 0.5, 121.0, 0.5, 0f, 0f);
            player.getInventory().add(new ItemStack(item));
            player.containerMenu.broadcastChanges();
            Files.writeString(PLACE, "target " + targetPos.getX() + " " + targetPos.getY() + " "
                    + targetPos.getZ() + "\nplace " + placePos.getX() + " " + placePos.getY() + " "
                    + placePos.getZ() + "\n");
            level = lvl;
            prepared = true;
            ModReloader.LOGGER.info(
                    "[MpLateJoin] placed the player at {} and gave {}; target={}, place={}; "
                            + "waiting for the client's useItemOn",
                    player.position(), blockId, targetPos, placePos);
        } catch (Throwable t) {
            fail("cannot prepare the placement action: " + t);
        }
    }

    private static int dumpLineCount() {
        try {
            return Files.readAllLines(DUMP).size();
        } catch (Throwable t) {
            return -1;
        }
    }

    private static void fail(String message) {
        armed = false;
        ModReloader.LOGGER.error("[MpLateJoin] {}", message);
        ModReloader.LOGGER.error("[MpLateJoin] RESULT: FAIL");
        try {
            Files.writeString(FAILED, message + "\n");
        } catch (Throwable ignored) {
            // The log already has the failure; the marker is only for the shell.
        }
        if (server != null) {
            server.halt(false);
        }
    }
}