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
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only server half of the multiplayer registry-sync check.
 *
 * <p>Waits for a client to be connected <em>and</em> to have written
 * {@code mp-client-ready.txt} (i.e. it already hot-added its copy), then hot-adds
 * the jars in its own order and writes {@code mp-registry-server.txt} with the
 * authoritative id mapping. The client compares against that file.
 *
 * <p>{@code -PmpRegistryServer=<jar>[,<jar>...]}. The server keeps running: the
 * point is a live connection, so this check cannot stop the JVM itself.
 */
public final class MpRegistryServerCheck {
    private static final Path READY = Path.of("mp-client-ready.txt");
    private static final Path DUMP = Path.of("mp-registry-server.txt");
    static final Path PLACE = Path.of("mp-registry-place.txt");
    private static final int TIMEOUT_TICKS = 20 * 300;
    /** Grace after the client says it is ready, so its resource reload settles first. */
    private static final int GRACE_TICKS = 20 * 3;
    private static final int PLACEMENT_TIMEOUT_TICKS = 20 * 60;
    private static final int STOP_AFTER_PLACEMENT_TICKS = 20 * 20;

    private static final List<Path> JARS = new ArrayList<>();
    private static String placeModId;
    private static String placeBlockPath;
    private static boolean started;
    private static boolean serverUp;
    private static boolean hotAdded;
    private static boolean prepared;
    private static boolean placementSeen;
    private static int ticks;
    private static int grace;
    private static int placementTicks;
    private static int stopTicks;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static BlockPos targetPos;
    private static BlockPos placePos;
    private static Block block;

    private MpRegistryServerCheck() {
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
            Files.deleteIfExists(DUMP);
            Files.deleteIfExists(PLACE);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[MpRegistry] cannot clear old markers", t);
        }
        ModReloader.LOGGER.info("[MpRegistry] server side armed; jars (in this order)={}; place={}",
                JARS, place.isEmpty() ? "off" : place);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> {
            server = event.getServer();
            serverUp = true;
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started || !serverUp || server == null) {
            return;
        }
        if (++ticks > TIMEOUT_TICKS) {
            if (placeModId != null) {
                failPlacement("TIMEOUT waiting for the placement action");
            } else {
                ModReloader.LOGGER.error("[MpRegistry] TIMEOUT: no ready client after {}s",
                        TIMEOUT_TICKS / 20);
                started = false;
            }
            return;
        }
        if (!hotAdded) {
            int players = server.getPlayerList().getPlayers().size();
            if (players == 0) {
                return;
            }
            if (!Files.isRegularFile(READY)) {
                if (ticks % 100 == 0) {
                    ModReloader.LOGGER.info("[MpRegistry] {} player(s) connected; waiting for {}",
                            players, READY);
                }
                return;
            }
            if (++grace < GRACE_TICKS) {
                return;
            }
            hotAdd();
            return;
        }
        if (placeModId == null) {
            return; // registry-only mode: started was cleared after the dump
        }
        if (!prepared) {
            preparePlacement();
            return;
        }
        if (!placementSeen) {
            if (++placementTicks > PLACEMENT_TIMEOUT_TICKS) {
                failPlacement("the client never placed " + placeModId + ":" + placeBlockPath);
                return;
            }
            BlockState state = level.getBlockState(placePos);
            if (state.is(block)) {
                placementSeen = true;
                ModReloader.LOGGER.info(
                        "[MpRegistry] client placed {} at {} (server block state confirmed)",
                        state.getBlock().builtInRegistryHolder().key().location(), placePos);
                ModReloader.LOGGER.info("[MpRegistry] RESULT: PASS");
            }
            return;
        }
        if (++stopTicks >= STOP_AFTER_PLACEMENT_TICKS) {
            started = false;
            ModReloader.LOGGER.info("[MpRegistry] stopping the server after the client-hot-add L2 check");
            server.halt(false);
        }
    }

    private static void hotAdd() {
        hotAdded = true;
        ModReloader.LOGGER.info("[MpRegistry] client is ready; hot-adding {} in server order", JARS);
        for (Path jar : JARS) {
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[MpRegistry] server hot-add {}: ok={} kind={} msg={}",
                    jar.getFileName(), result.ok(), result.kind(), result.message());
            if (!result.ok()) {
                ModReloader.LOGGER.error("[MpRegistry] RESULT: FAIL (server hot-add failed)");
                if (placeModId != null) {
                    server.halt(false);
                }
                started = false;
                return;
            }
        }
        try {
            Files.write(DUMP, RegistryDump.lines());
            ModReloader.LOGGER.info("[MpRegistry] wrote {} (blocks={}, items={})", DUMP,
                    net.minecraft.core.registries.BuiltInRegistries.BLOCK.size(),
                    net.minecraft.core.registries.BuiltInRegistries.ITEM.size());
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[MpRegistry] cannot write {}", DUMP, t);
            if (placeModId != null) {
                failPlacement("cannot write " + DUMP + ": " + t);
            }
        }
        if (placeModId == null) {
            started = false;
        } else {
            ModReloader.LOGGER.info("[MpRegistry] registry sync done; preparing the L2 placement");
        }
    }

    private static void preparePlacement() {
        if (server.getPlayerList().getPlayers().isEmpty()) {
            failPlacement("the client left before the placement action could start");
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

            ResourceLocation blockId = ResourceLocation.fromNamespaceAndPath(placeModId,
                    placeBlockPath);
            block = BuiltInRegistries.BLOCK.get(blockId);
            Item item = BuiltInRegistries.ITEM.get(blockId);
            if (block == Blocks.AIR || item == Items.AIR) {
                failPlacement("cannot find the hot-added block/item " + blockId);
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
                    "[MpRegistry] placed the player at {} and gave {}; target={}, place={}; "
                            + "waiting for the client's useItemOn",
                    player.position(), blockId, targetPos, placePos);
        } catch (Throwable t) {
            failPlacement("cannot prepare the placement action: " + t);
        }
    }

    private static void failPlacement(String message) {
        started = false;
        ModReloader.LOGGER.error("[MpRegistry] {}", message);
        ModReloader.LOGGER.error("[MpRegistry] RESULT: FAIL");
        if (server != null) {
            server.halt(false);
        }
    }
}
