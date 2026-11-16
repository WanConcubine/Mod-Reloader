package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Dev-only check for capability provider replay.
 *
 * <p>NeoForge fires {@code RegisterCapabilitiesEvent} once at start-up. A hot-added
 * mod misses it, so its item handlers / fluid tanks / energy providers never exist:
 * the barrel accepts nothing, the crucible has no tank, machines cannot be piped.
 * The engine now replays the event for the new container; this check proves it by
 * placing the mod's blocks and looking the capabilities up exactly like a player
 * interaction would.
 *
 * <p>Deliberately adds at the <b>title screen</b> and only then creates a world:
 * the in-world path can race the block-state id rebake against chunk serialisation
 * (observed once as {@code No value with id -1}), which is unrelated to
 * capabilities and would make this check flaky.
 *
 * <p>{@code -PverifyCapabilities=<jar>}, mod id
 * {@code -PverifyCapabilitiesModId} (default {@code exnihilosatellitium}).
 */
public final class CapabilityCheck {
    private enum Stage {
        WAIT_TITLE, ADD, WAIT_ADDED, OPEN_CREATE, WAIT_CREATE_SCREEN, WAIT_WORLD, QUERY, DONE
    }

    private static final int TITLE_SETTLE_TICKS = 100;
    private static final int TIMEOUT_TICKS = 20 * 240;

    private static final String MOD_ID = System.getProperty(
            "modreloader.verifyCapabilitiesModId", "exnihilosatellitium").trim();

    private static Path jar;
    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean pressedCreate;
    private static boolean failed;
    private static CompletableFuture<Query> queryFuture;

    private CapabilityCheck() {
    }

    public static void start(Path candidate) {
        if (started) {
            return;
        }
        jar = candidate.toAbsolutePath().normalize();
        started = true;
        ModReloader.LOGGER.info("[CapabilityCheck] jar={} modId={}", jar, MOD_ID);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            fail("timeout at " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WAIT_TITLE -> {
                if (ticks < TITLE_SETTLE_TICKS || mc.getOverlay() != null) {
                    return;
                }
                stage = Stage.ADD;
                ticks = 0;
            }
            case ADD -> {
                HotAddEngine.Result result = HotAddEngine.add(jar);
                ModReloader.LOGGER.info("[CapabilityCheck] title-screen add: ok={} kind={} msg={}",
                        result.ok(), result.kind(), result.message());
                if (!result.ok()) {
                    fail("hot-add at the title screen failed: " + result.kind() + " " + result.message());
                    return;
                }
                stage = Stage.WAIT_ADDED;
                ticks = 0;
            }
            case WAIT_ADDED -> {
                if (ticks < 40) {
                    return;
                }
                stage = Stage.OPEN_CREATE;
                ticks = 0;
            }
            case OPEN_CREATE -> {
                if (mc.getOverlay() != null) {
                    return;
                }
                try {
                    CreateWorldScreen.openFresh(mc, mc.screen);
                } catch (Throwable t) {
                    fail("cannot open the create-world screen: " + t);
                    return;
                }
                stage = Stage.WAIT_CREATE_SCREEN;
                ticks = 0;
            }
            case WAIT_CREATE_SCREEN -> {
                if (!(mc.screen instanceof CreateWorldScreen screen)) {
                    if (ticks > 20 * 30) {
                        fail("the create-world screen never appeared");
                    }
                    return;
                }
                if (pressedCreate) {
                    return;
                }
                AbstractButton button = findButton(screen, "selectWorld.create");
                if (button == null) {
                    return;
                }
                pressedCreate = true;
                button.onPress();
                stage = Stage.WAIT_WORLD;
                ticks = 0;
            }
            case WAIT_WORLD -> {
                if (mc.level == null || mc.player == null) {
                    if (ticks > 20 * 180) {
                        fail("the world never loaded");
                    }
                    return;
                }
                stage = Stage.QUERY;
                ticks = 0;
            }
            case QUERY -> {
                if (ticks < 40) {
                    return;
                }
                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) {
                    fail("no integrated server");
                    return;
                }
                // Place and query on the server thread: setBlockAndUpdate off-thread
                // leaves the block without its block entity (and therefore without
                // capability providers).
                if (queryFuture == null) {
                    queryFuture = new CompletableFuture<>();
                    server.execute(() -> {
                        try {
                            queryFuture.complete(query(server));
                        } catch (Throwable t) {
                            queryFuture.completeExceptionally(t);
                        }
                    });
                    return;
                }
                if (!queryFuture.isDone()) {
                    return;
                }
                try {
                    Query query = queryFuture.join();
                    query.log();
                    if (!query.missing().isEmpty()) {
                        fail("capability providers missing after the hot-add: " + query.missing()
                                + " (RegisterCapabilitiesEvent replay did not run or failed)");
                        return;
                    }
                } catch (Throwable t) {
                    fail("capability query threw " + t);
                    return;
                }
                ModReloader.LOGGER.info("[CapabilityCheck] RESULT: PASS");
                stage = Stage.DONE;
                ticks = 0;
            }
            case DONE -> {
                if (ticks >= 20) {
                    started = false;
                    Minecraft.getInstance().stop();
                }
            }
        }
    }

    private record Query(List<String> results, List<String> missing) {
        void log() {
            ModReloader.LOGGER.info("[CapabilityCheck] providers: {}", results);
        }
    }

    private static Query query(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no server player"));
        BlockPos base = player.blockPosition();
        List<String> results = new ArrayList<>();
        List<String> missing = new ArrayList<>();

        BlockPos barrel = place(level, base.offset(-5, 0, 0), MOD_ID + ":barrel");
        BlockPos crucible = place(level, base.offset(-5, 0, 3), MOD_ID + ":crucible");
        BlockPos machine = place(level, base.offset(-8, 0, 3), MOD_ID + ":electric_sieve");

        check(results, missing, "barrel.item", Capabilities.ItemHandler.BLOCK, level, barrel);
        check(results, missing, "barrel.fluid", Capabilities.FluidHandler.BLOCK, level, barrel);
        check(results, missing, "crucible.fluid", Capabilities.FluidHandler.BLOCK, level, crucible);
        check(results, missing, "machine.item", Capabilities.ItemHandler.BLOCK, level, machine);
        check(results, missing, "machine.energy", Capabilities.EnergyStorage.BLOCK, level, machine);
        return new Query(List.copyOf(results), List.copyOf(missing));
    }

    private static <T, C> void check(List<String> results, List<String> missing, String name,
                                     BlockCapability<T, C> capability,
                                     ServerLevel level, BlockPos pos) {
        if (pos == null) {
            results.add(name + "=skipped");
            return;
        }
        try {
            T handler = capability.getCapability(level, pos, null, null, null);
            boolean present = handler != null;
            if (present) {
                results.add(name + "=present");
            } else {
                var state = level.getBlockState(pos);
                var blockEntity = level.getBlockEntity(pos);
                results.add(name + "=MISSING [block=" + BuiltInRegistries.BLOCK.getKey(state.getBlock())
                        + ", be=" + (blockEntity == null ? "null"
                        : blockEntity.getClass().getSimpleName()) + "]");
                missing.add(name);
            }
        } catch (Throwable t) {
            results.add(name + "=threw:" + t.getClass().getSimpleName());
            missing.add(name + " (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static BlockPos place(ServerLevel level, BlockPos pos, String blockId) {
        ResourceLocation key = ResourceLocation.tryParse(blockId);
        Block block = key == null ? null : BuiltInRegistries.BLOCK.get(key);
        if (block == null || block == Blocks.AIR) {
            ModReloader.LOGGER.info("[CapabilityCheck] block {} not registered; skipped", blockId);
            return null;
        }
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        return pos;
    }

    private static AbstractButton findButton(Screen screen, String key) {
        String label = net.minecraft.client.resources.language.I18n.get(key);
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button && label.equals(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }

    private static void fail(String message) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[CapabilityCheck] FAILED: {}", message);
        ModReloader.LOGGER.error("[CapabilityCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
