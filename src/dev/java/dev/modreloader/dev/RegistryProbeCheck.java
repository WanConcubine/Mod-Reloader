package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.registries.BaseMappedRegistry;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;

/**
 * Read-only guifei-tier research probe ({@code -PverifyRegistryProbe=true}).
 *
 * <p>Reports the facts a registry adaptation would depend on: registry sizes and
 * freeze state, whether the per-world {@code RegistryAccess} is the same object as
 * {@code BuiltInRegistries}, how BlockState ids are assigned, which callbacks run
 * on freeze, and whether {@code RegisterEvent} can be constructed for a replay.
 *
 * <p>It never writes to a registry and never posts an event.
 */
public final class RegistryProbeCheck {
    private static int ticks;
    private static boolean done;

    private RegistryProbeCheck() {
    }

    public static void start() {
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (done) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.getSingleplayerServer() == null) {
            return;
        }
        if (++ticks < 20) {
            return;
        }
        done = true;
        try {
            probe(minecraft);
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[RegistryProbe] failed", t);
        }
        // Deliberately does not stop the client: run it together with a harness
        // that does (e.g. -PverifyAddCommand), so both observations share one run.
    }

    @SuppressWarnings("unchecked")
    private static void probe(Minecraft minecraft) {
        MinecraftServer server = minecraft.getSingleplayerServer();
        Registry<Block> blocks = BuiltInRegistries.BLOCK;

        log("block registry: size={} frozen={}", blocks.size(), frozen(blocks));
        log("item registry: size={} frozen={}", BuiltInRegistries.ITEM.size(),
                frozen(BuiltInRegistries.ITEM));
        log("registryAccess.BLOCK == BuiltInRegistries.BLOCK: {}",
                server.registryAccess().registryOrThrow(Registries.BLOCK) == blocks);
        log("registryAccess.ITEM == BuiltInRegistries.ITEM: {}",
                server.registryAccess().registryOrThrow(Registries.ITEM) == BuiltInRegistries.ITEM);
        log("registry layers: {}", server.registryAccess().registries().count());
        log("registry-of-registries size: {}", BuiltInRegistries.REGISTRY.size());

        log("blockstate id map: size={} sameAsGameData={} stone={} dirt={} grass={}",
                Block.BLOCK_STATE_REGISTRY.size(),
                GameData.getBlockStateIDMap() == Block.BLOCK_STATE_REGISTRY,
                stateId(Blocks.STONE.defaultBlockState()),
                stateId(Blocks.DIRT.defaultBlockState()),
                stateId(Blocks.GRASS_BLOCK.defaultBlockState()));

        Set<net.minecraft.resources.ResourceLocation> order = GameData.getRegistrationOrder();
        log("registration order: {} registries; first={}", order.size(),
                order.stream().limit(5).toList());
        log("register-event ctor: {}", describeRegisterCtor());
        log("unfreeze method: {}", describeUnfreeze());

        log("callbacks on BLOCK: add={} bake={} clear={}",
                callbacks(blocks, "addCallbacks"), callbacks(blocks, "bakeCallbacks"),
                callbacks(blocks, "clearCallbacks"));
        log("callbacks on ITEM: add={}", callbacks(BuiltInRegistries.ITEM, "addCallbacks"));
        log("callbacks on ATTRIBUTE: bake={}",
                callbacks(BuiltInRegistries.ATTRIBUTE, "bakeCallbacks"));

        log("block tags: {} names; stone has {}", blocks.getTagNames().count(),
                Blocks.STONE.builtInRegistryHolder().tags().count());
        log("creative tabs: sorted={} all={}",
                CreativeModeTabRegistry.getSortedCreativeModeTabs().size(),
                net.minecraft.world.item.CreativeModeTabs.allTabs().size());

        if (Boolean.getBoolean("modreloader.verifyRegistryRefreeze")) {
            refreeze(blocks);
        }
    }

    /**
     * Controlled experiment: unfreeze and re-freeze every built-in registry
     * without registering anything. Verifies the mechanics a registry
     * adaptation would rely on (bake callbacks, id stability) without adding
     * content. Only run on a throwaway dev world.
     */
    private static void refreeze(Registry<Block> blocks) {
        int stoneBefore = stateId(Blocks.STONE.defaultBlockState());
        int grassBefore = stateId(Blocks.GRASS_BLOCK.defaultBlockState());
        int statesBefore = Block.BLOCK_STATE_REGISTRY.size();
        int blocksBefore = blocks.size();
        log("refreeze: before blocks={} states={} frozen={} stone={} grass={}",
                blocksBefore, statesBefore, frozen(blocks), stoneBefore, grassBefore);

        long started = System.nanoTime();
        try {
            GameData.unfreezeData();
            log("refreeze: unfrozen; block frozen={}", frozen(blocks));
            GameData.freezeData();
        } catch (Throwable t) {
            log("refreeze: FAILED after {} ms: {}", (System.nanoTime() - started) / 1_000_000,
                    t.getClass().getSimpleName() + ": " + t.getMessage());
            return;
        }
        long millis = (System.nanoTime() - started) / 1_000_000;

        int stoneAfter = stateId(Blocks.STONE.defaultBlockState());
        int grassAfter = stateId(Blocks.GRASS_BLOCK.defaultBlockState());
        log("refreeze: after {} ms; blocks={} states={} frozen={} stone={} grass={} stable={}",
                millis, blocks.size(), Block.BLOCK_STATE_REGISTRY.size(), frozen(blocks),
                stoneAfter, grassAfter,
                blocks.size() == blocksBefore && Block.BLOCK_STATE_REGISTRY.size() == statesBefore
                        && stoneAfter == stoneBefore && grassAfter == grassBefore);
    }

    private static int stateId(BlockState state) {
        try {
            return Block.BLOCK_STATE_REGISTRY.getId(state);
        } catch (Throwable t) {
            return Integer.MIN_VALUE;
        }
    }

    private static boolean frozen(Registry<?> registry) {
        try {
            return UnsafeAccess.getBoolean(registry, MappedRegistry.class, "frozen");
        } catch (Throwable t) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private static int callbacks(Registry<?> registry, String field) {
        try {
            Object value = UnsafeAccess.get(registry, BaseMappedRegistry.class, field);
            return value instanceof List<?> list ? list.size() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String describeUnfreeze() {
        try {
            return MappedRegistry.class.getMethod("unfreeze").toString();
        } catch (Throwable t) {
            return "missing (" + t.getClass().getSimpleName() + ")";
        }
    }

    private static String describeRegisterCtor() {
        for (Constructor<?> constructor : RegisterEvent.class.getDeclaredConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2 && parameters[0] == net.minecraft.resources.ResourceKey.class
                    && parameters[1] == Registry.class) {
                return Modifier.toString(constructor.getModifiers()) + " " + constructor;
            }
        }
        return "not found";
    }

    private static void log(String format, Object... args) {
        ModReloader.LOGGER.info("[RegistryProbe] " + format, args);
    }
}
