package dev.modreloader.hotadd;

import dev.modreloader.inspect.ReplayCatalog;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import dev.modreloader.reflect.UnsafeAccess;

/**
 * Constructs a hot-added mod and replays the client lifecycle events it missed.
 *
 * <p>A0 deliberately stops at client setup + key mappings. Registry events are
 * out of scope (registry content is handled by {@link RegistryStage}).
 */
public final class FmlEvents {
    private FmlEvents() {
    }

    public static void constructAndInit(ModContainer container) throws Throwable {
        construct(container);
        finish(container);
    }

    /**
     * Constructs the mod and fires {@code FMLConstructModEvent}. Split from
     * {@link #finish(ModContainer)} so a registry-content hot-add can replay registry events
     * between construction and common setup (that is the normal startup order).
     */
    public static void construct(ModContainer container) throws Throwable {
        MethodHandle construct = UnsafeAccess.trusted().findVirtual(
                ModContainer.class, "constructMod", MethodType.methodType(void.class));

        ModLoadingContext context = ModLoadingContext.get();
        context.setActiveContainer(container);
        try {
            construct.invoke(container);
            // registerConfig() only records the spec; FML normally loads it in the
            // startup sequence, so a hot-added mod would throw on the first read.
            ModConfigs.load(container);
        } finally {
            context.setActiveContainer(null);
        }

        DeferredWorkQueue constructQueue = queue(container, "construct");
        OneShotReplay.fire(container, ReplayCatalog.FML_CONSTRUCT,
                new FMLConstructModEvent(container, constructQueue));
        constructQueue.runTasks();
    }

    /** Common setup, client setup/key mappings and load-complete. */
    public static void finish(ModContainer container) throws Throwable {
        finishAll(List.of(container));
    }

    /**
     * Runs the post-construct lifecycle for several containers in startup order.
     *
     * <p>All {@code FMLCommonSetupEvent} listeners run before any enqueued task
     * does. That matters for nested mods: XaeroLib registers packet handlers in
     * its own synchronized common-setup listener and freezes the registry in an
     * enqueued task; running the nested container's queue first (the old
     * one-container-at-a-time loop) froze the registry before Xaero's World Map
     * could register its own handlers.
     */
    public static void finishAll(List<ModContainer> containers) throws Throwable {
        commonSetupAll(containers);
        sidedSetupAll(containers);
    }

    /** {@code FMLCommonSetupEvent} for every container, then their queued tasks. */
    public static void commonSetupAll(List<ModContainer> containers) throws Throwable {
        List<DeferredWorkQueue> commonQueues = new ArrayList<>();
        for (ModContainer container : containers) {
            DeferredWorkQueue commonQueue = queue(container, "common");
            OneShotReplay.fire(container, ReplayCatalog.FML_COMMON_SETUP,
                    new FMLCommonSetupEvent(container, commonQueue));
            commonQueues.add(commonQueue);
        }
        for (DeferredWorkQueue commonQueue : commonQueues) {
            commonQueue.runTasks();
        }
    }

    /**
     * Side-specific setup ({@code FMLClientSetupEvent} on a client) and
     * {@code FMLLoadCompleteEvent}.
     *
     * <p>Split from {@link #commonSetupAll} because of issue 53: at startup the
     * client tasks run inside the initial resource reload, where the live
     * {@code ResourceManager} already contains every mod pack. The engine starts
     * the hot-add reload before calling this, so the same is true here.
     */
    public static void sidedSetupAll(List<ModContainer> containers) throws Throwable {
        for (ModContainer container : containers) {
            // Client-only events: a dedicated server must not replay these, so the
            // side hook decides instead of the engine.
            PlatformHooks.get().acceptClientLifecycle(container);

            DeferredWorkQueue completeQueue = queue(container, "complete");
            OneShotReplay.fire(container, ReplayCatalog.FML_LOAD_COMPLETE,
                    new FMLLoadCompleteEvent(container, completeQueue));
            completeQueue.runTasks();
        }
    }

    private static DeferredWorkQueue queue(ModContainer container, String phase) {
        return new DeferredWorkQueue("modreloader-" + container.getModId() + "-" + phase);
    }
}
