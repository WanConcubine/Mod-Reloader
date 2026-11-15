package dev.modreloader.hotadd;

import dev.modreloader.Platform;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.lifecycle.FMLConstructModEvent;
import net.neoforged.fml.event.lifecycle.FMLLoadCompleteEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;

/**
 * Constructs a hot-added mod and replays the client lifecycle events it missed.
 *
 * <p>A0 deliberately stops at client setup + key mappings. Registry events are
 * out of scope (T4 content mods are not supported yet).
 */
public final class FmlEvents {
    private FmlEvents() {
    }

    /**
     * Probes whether the mod wants custom network payloads.
     *
     * <p>NeoForge forbids registering payloads after the start-up registration
     * phase ({@code NetworkRegistry.register} throws), so a hot-added mod that
     * needs them can never work. Replaying the event here turns that into a
     * precise error instead of a mod that loads but cannot talk to clients.
     *
     * @return the failure message, or {@code null} when the mod registers no payloads
     */
    public static String probeNetworkPayloads(ModContainer container) {
        try {
            container.acceptEvent(new RegisterPayloadHandlersEvent());
            return null;
        } catch (UnsupportedOperationException e) {
            return e.getMessage();
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    public static void constructAndInit(ModContainer container) throws Throwable {
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

        DeferredWorkQueue commonQueue = queue(container, "common");
        container.acceptEvent(new FMLCommonSetupEvent(container, commonQueue));
        commonQueue.runTasks();

        // Client-only events: a dedicated server must not replay these.
        if (Platform.isClient()) {
            DeferredWorkQueue clientQueue = queue(container, "client");
            container.acceptEvent(new FMLClientSetupEvent(container, clientQueue));
            clientQueue.runTasks();

            container.acceptEvent(new RegisterKeyMappingsEvent(Minecraft.getInstance().options));
        }

        DeferredWorkQueue completeQueue = queue(container, "complete");
        container.acceptEvent(new FMLLoadCompleteEvent(container, completeQueue));
        completeQueue.runTasks();
    }

    private static DeferredWorkQueue queue(ModContainer container, String phase) {
        return new DeferredWorkQueue("modreloader-" + container.getModId() + "-" + phase);
    }
}
