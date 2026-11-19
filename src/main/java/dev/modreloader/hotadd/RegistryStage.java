package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.ReplayCatalog;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.registries.GameData;
import net.neoforged.neoforge.registries.RegisterEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Guifei-tier stage: lets a hot-added mod register content (blocks / items / …) into the
 * already-frozen built-in registries.
 *
 * <p>Shape of the operation — deliberately different from the pin/fei tiers:
 *
 * <ol>
 *   <li><b>begin</b>: {@code GameData.unfreezeData()} <em>before</em> the mod is
 *       constructed, because some mods call {@code Registry.register} directly in
 *       their constructor.</li>
 *   <li>construct the mod (its {@code DeferredRegister}s attach listeners).</li>
 *   <li><b>commit</b>: replay {@code RegisterEvent} per registry and per
 *       container, in {@code GameData.getRegistrationOrder()} order; clear the
 *       BlockState id map and re-freeze. The replay happens on the calling
 *       thread, the clear + freeze on the server thread — see the comment in
 *       {@link #commit} and {@link ServerThreadHop}.</li>
 * </ol>
 *
 * <p><b>Not rollback-able.</b> Registries have no public "remove" and deleting
 * entries would shift numeric ids (and break saves). If the commit already
 * happened, {@link #abort()} only logs; the caller must treat the runtime as
 * dirty and ask for a restart. Before the commit, abort clears the state id map
 * and re-freezes, which preserves the canonical BlockState ids.
 *
 * <p>Add-only built-in registry content is supported, including a mod's own
 * built-in registries ({@link CustomRegistries} replays {@code NewRegistryEvent}
 * after construction). Datapack registries (biome/dimension/enchantment/…),
 * {@code ModifyRegistriesEvent} and post-register events (block entity blocks,
 * spawn placements, attributes) are not replayed yet.
 */
public final class RegistryStage {
    private static final MethodHandle REGISTER_EVENT_CTOR = registerEventCtor();

    private static boolean active;
    private static boolean committed;
    private static Map<ResourceLocation, Integer> sizesBefore = Map.of();
    private static int stoneIdBefore = -1;
    private static int stateMapBefore = -1;

    private RegistryStage() {
    }

    public static boolean active() {
        return active;
    }

    /** Opens the unfreeze window and registers the abort step. */
    public static void begin(Rollback rollback) {
        if (active) {
            throw new IllegalStateException("a registry window is already open");
        }
        sizesBefore = sizes();
        stoneIdBefore = stoneId();
        stateMapBefore = Block.BLOCK_STATE_REGISTRY.size();
        GameData.unfreezeData();
        active = true;
        committed = false;
        rollback.add("registry", RegistryStage::abort);
        ModReloader.LOGGER.info(
                "[ModReloader] registry window open: blocks={} items={} states={} stoneId={}",
                sizesBefore.getOrDefault(id("block"), -1),
                sizesBefore.getOrDefault(id("item"), -1), stateMapBefore, stoneIdBefore);
    }

    /** Replays registration, rebuilds the BlockState id map and re-freezes. */
    public static void commit(List<ModContainer> containers) throws Throwable {
        if (!active) {
            throw new IllegalStateException("registry window is not open");
        }
        if (REGISTER_EVENT_CTOR == null) {
            throw new IllegalStateException("RegisterEvent constructor is unavailable");
        }
        int registries = 0;
        for (ResourceLocation name : GameData.getRegistrationOrder()) {
            ResourceKey<? extends Registry<?>> key = ResourceKey.createRegistryKey(name);
            Registry<?> registry = BuiltInRegistries.REGISTRY.get(name);
            if (registry == null) {
                continue;
            }
            for (ModContainer container : containers) {
                OneShotReplay.fire(container, ReplayCatalog.REGISTER_EVENT,
                        newRegisterEvent(key, registry));
            }
            registries++;
        }

        // NeoForge rebuilds BLOCKSTATE_TO_ID_MAP in the block registry's bake
        // callback, but IdMapper#add always appends: without clearing, every
        // existing BlockState would get a new id and chunks saved afterwards
        // would reference ids that do not exist after a restart.
        //
        // The clear-to-rebuild window is the only moment in this stage where the
        // block-state map is incomplete: between clear() and the bake's last
        // add(), getting a state id yields -1. Vanilla encodes chunks on the
        // server thread, so the section must run there. Doing it on the calling
        // thread (dev harness, GUI, inbox watcher - all off the server thread)
        // raced with chunk serialisation and wrote -1 into a palette, which the
        // client then failed to decode (status known issue 28). The RegisterEvent
        // replay above stays on the calling thread on purpose.
        int cleared = ServerThreadHop.call("registry freeze", () -> {
            int idsBefore = clearBlockStateIds();
            GameData.freezeData();
            return idsBefore;
        });
        committed = true;
        active = false;
        CustomRegistries.close();

        int stoneAfter = stoneId();
        int blocksAfter = BuiltInRegistries.BLOCK.size();
        int itemsAfter = BuiltInRegistries.ITEM.size();
        int statesAfter = Block.BLOCK_STATE_REGISTRY.size();
        boolean stable = stoneAfter == stoneIdBefore
                && blocksAfter >= sizesBefore.getOrDefault(id("block"), 0)
                && itemsAfter >= sizesBefore.getOrDefault(id("item"), 0);

        ModReloader.LOGGER.info(
                "[ModReloader] registry commit: {} registries replayed; blocks {} -> {}, items {} -> {}, "
                        + "states {} -> {} (cleared {} entries), stoneId {} -> {} ({})",
                registries,
                sizesBefore.getOrDefault(id("block"), -1), blocksAfter,
                sizesBefore.getOrDefault(id("item"), -1), itemsAfter,
                stateMapBefore, statesAfter, cleared,
                stoneIdBefore, stoneAfter, stable ? "stable" : "CHANGED");

        for (ModContainer container : containers) {
            ModReloader.LOGGER.info("[ModReloader] registered content for {}",
                    container.getModId());
        }
    }

    /** Rollback step: only safe before the commit; afterwards the runtime is dirty. */
    private static void abort() {
        if (!active) {
            if (committed) {
                ModReloader.LOGGER.error(
                        "[ModReloader] registry content was committed and cannot be rolled back; "
                                + "restart is required to get a clean state");
            }
            return;
        }
        active = false;
        try {
            // No content was committed, but construct may have registered some
            // entries directly. Re-freeze with a cleared state map so the
            // canonical ids survive; report growth as a dirty registry.
            // Same hazard as commit: the incomplete map must not be visible to
            // whoever encodes a chunk, so the clear + freeze runs on the server
            // thread.
            int cleared = ServerThreadHop.call("registry abort freeze", () -> {
                int idsBefore = clearBlockStateIds();
                GameData.freezeData();
                return idsBefore;
            });
            Map<ResourceLocation, Integer> after = sizes();
            List<String> grown = new ArrayList<>();
            sizesBefore.forEach((name, before) -> {
                int now = after.getOrDefault(name, before);
                if (now != before) {
                    grown.add(name + " " + before + "->" + now);
                }
            });
            if (grown.isEmpty()) {
                ModReloader.LOGGER.info(
                        "[ModReloader] registry window closed after failure; no entries were added "
                                + "(state map cleared {} entries, ids restored)", cleared);
            } else {
                ModReloader.LOGGER.error(
                        "[ModReloader] registry window closed after failure, but entries were already "
                                + "added: {}; the runtime is dirty and needs a restart", grown);
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.error(
                    "[ModReloader] cannot restore the registry freeze state; restart is required", t);
        }
    }

    private static RegisterEvent newRegisterEvent(ResourceKey<? extends Registry<?>> key,
                                                  Registry<?> registry) throws Throwable {
        return (RegisterEvent) REGISTER_EVENT_CTOR.invoke(key, registry);
    }

    private static MethodHandle registerEventCtor() {
        try {
            return UnsafeAccess.trusted().findConstructor(RegisterEvent.class,
                    MethodType.methodType(void.class, ResourceKey.class, Registry.class));
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] cannot resolve the RegisterEvent constructor", t);
            return null;
        }
    }

    /** Clears the BlockState id map; the next freeze rebuilds it in registry order. */
    private static int clearBlockStateIds() throws Throwable {
        Class<?> callbacks = Class.forName(
                "net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks");
        Object map = UnsafeAccess.getStatic(callbacks, "BLOCKSTATE_TO_ID_MAP");
        int size = Block.BLOCK_STATE_REGISTRY.size();
        MethodHandle clear = UnsafeAccess.trusted().findVirtual(
                map.getClass(), "clear", MethodType.methodType(void.class));
        clear.invoke(map);
        return size;
    }

    private static int stoneId() {
        return getId(BuiltInRegistries.BLOCK, net.minecraft.world.level.block.Blocks.STONE);
    }

    private static int getId(Registry<Block> registry, Block block) {
        try {
            BlockState state = block.defaultBlockState();
            return Block.BLOCK_STATE_REGISTRY.getId(state);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static Map<ResourceLocation, Integer> sizes() {
        Map<ResourceLocation, Integer> sizes = new LinkedHashMap<>();
        sizes.put(id("block"), BuiltInRegistries.BLOCK.size());
        sizes.put(id("item"), BuiltInRegistries.ITEM.size());
        return sizes;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.withDefaultNamespace(path);
    }
}
