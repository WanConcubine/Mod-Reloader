package dev.modreloader.t4fixture;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * T4 fixture: registers one block + one BlockItem through DeferredRegister.
 *
 * <p>Common setup reads the block back from the built-in registries; that only
 * works if the registry window (unfreeze → RegisterEvent → clear state ids →
 * freeze) ran before common setup.
 *
 * <p>Optional self-test ({@code -Pt4fixtureSelftest}):
 * <ul>
 *   <li>write mode: place the block at (0,100,0), save, log, halt.</li>
 *   <li>read mode ({@code -Pt4fixtureSelftestRead}): read that position back
 *       after a restart and log PASS/FAIL.</li>
 * </ul>
 */
@Mod(T4FixtureMod.MOD_ID)
public final class T4FixtureMod {
    public static final String MOD_ID = "t4fixture";
    private static final Logger LOGGER = LoggerFactory.getLogger("T4Fixture");
    private static final BlockPos TEST_POS = new BlockPos(0, 100, 0);
    private static int ticks;
    private static boolean testDone;

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MOD_ID);

    public static final DeferredBlock<Block> TEST_BLOCK = BLOCKS.registerSimpleBlock(
            "test_block", BlockBehaviour.Properties.of().strength(2.0F));
    public static final DeferredItem<BlockItem> TEST_ITEM = ITEMS.registerSimpleBlockItem(
            TEST_BLOCK, new Item.Properties());

    public T4FixtureMod(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        modBus.addListener(T4FixtureMod::onCommonSetup);
        NeoForge.EVENT_BUS.addListener(T4FixtureMod::onServerTick);
        LOGGER.info("[T4Fixture] constructed");
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        Block block = TEST_BLOCK.get();
        LOGGER.info("[T4Fixture] common setup: block={} entryId={} stateId={} item={} itemId={}",
                BuiltInRegistries.BLOCK.getKey(block),
                BuiltInRegistries.BLOCK.getId(block),
                Block.BLOCK_STATE_REGISTRY.getId(block.defaultBlockState()),
                BuiltInRegistries.ITEM.getKey(TEST_ITEM.get()),
                BuiltInRegistries.ITEM.getId(TEST_ITEM.get()));
    }

    private static void onServerTick(ServerTickEvent.Post event) {
        if (testDone || !Boolean.getBoolean("t4fixture.selftest")) {
            return;
        }
        if (++ticks < 100) {
            return;
        }
        testDone = true;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerLevel level = server.overworld();
        boolean read = Boolean.getBoolean("t4fixture.selftest.read");
        if (!read) {
            level.setBlockAndUpdate(TEST_POS, TEST_BLOCK.get().defaultBlockState());
            BlockState state = level.getBlockState(TEST_POS);
            LOGGER.info("[T4Fixture] placed {} at {}; readback={} stateId={}",
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()), TEST_POS,
                    state.is(TEST_BLOCK.get()),
                    Block.BLOCK_STATE_REGISTRY.getId(state));
            server.saveEverything(true, true, true);
            LOGGER.info("[T4Fixture] world saved; RESULT: PLACED");
        } else {
            BlockState state = level.getBlockState(TEST_POS);
            boolean pass = state.is(TEST_BLOCK.get());
            LOGGER.info("[T4Fixture] restart readback {}: expected={} actual={} isT4Block={} RESULT: {}",
                    TEST_POS, BuiltInRegistries.BLOCK.getKey(TEST_BLOCK.get()),
                    BuiltInRegistries.BLOCK.getKey(state.getBlock()), pass, pass ? "PASS" : "FAIL");
        }
        Thread stopper = new Thread(() -> {
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            server.halt(false);
        }, "T4FixtureStopper");
        stopper.setDaemon(true);
        stopper.start();
    }
}
