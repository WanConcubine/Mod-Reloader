package dev.modreloader.hotadd;

import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

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
        } finally {
            context.setActiveContainer(null);
        }

        DeferredWorkQueue constructQueue = queue(container, "construct");
        container.acceptEvent(new FMLConstructModEvent(container, constructQueue));
        constructQueue.runTasks();
    }

    /** Common setup, client setup/key mappings and load-complete. */
    public static void finish(ModContainer container) throws Throwable {
        DeferredWorkQueue commonQueue = queue(container, "common");
        container.acceptEvent(new FMLCommonSetupEvent(container, commonQueue));
        commonQueue.runTasks();

        // Client-only events: a dedicated server must not replay these, so the
        // side hook decides instead of the engine.
        PlatformHooks.get().acceptClientLifecycle(container);

        DeferredWorkQueue completeQueue = queue(container, "complete");
        container.acceptEvent(new FMLLoadCompleteEvent(container, completeQueue));
        completeQueue.runTasks();
    }

    private static DeferredWorkQueue queue(ModContainer container, String phase) {
        return new DeferredWorkQueue("modreloader-" + container.getModId() + "-" + phase);
    }
}
