package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.ReplayCatalog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.CapabilityHooks;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

import net.neoforged.bus.api.IEventBus;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Replays {@code RegisterCapabilitiesEvent} for a hot-added mod.
 *
 * <p>NeoForge fires this event once at start-up (after the registry events, before
 * common setup). A hot-added mod missed it, so its capability providers (item
 * handlers for barrels/machines, fluid tanks, energy storage) are never registered
 * and the content silently does not work: items cannot be inserted, buckets do not
 * fill, pipes see nothing. Measured on the dogfood (Ex Nihilo 1.5.0): the barrel /
 * crucible / machine providers exist in the mod's listener but were absent after a
 * hot-add until this replay was added.
 *
 * <p>Two details make the replay safe:
 *
 * <ul>
 *   <li>The event is dispatched through {@link ModContainer#acceptEvent}, so only
 *       the new container's listeners run — already-loaded mods do not register
 *       their providers a second time.</li>
 *   <li>{@code CapabilityHooks.initFinished} is true by now (the real event fired
 *       long ago), which makes {@code setProxyable} throw. It is flipped back to
 *       false for the duration of the replay and restored afterwards, so a mod that
 *       marks its own capability proxyable registers it exactly like at start-up.
 *       Providers themselves are add-only lists, so nothing is overwritten.</li>
 * </ul>
 *
 * <p>Never fails the add: capabilities are additive, and by the time this runs the
 * registry content may already be committed. A failure is logged and the content
 * stays usable but capability-less.
 */
public final class CapabilityReplay {
    private CapabilityReplay() {
    }

    /** {@code {entries for this mod, total entries}} in a block capability's provider map. */
    private static int[] providerCounts(BlockCapability<?, ?> capability, String modId) {
        try {
            Object raw = UnsafeAccess.get(capability, BlockCapability.class, "providers");
            if (!(raw instanceof java.util.Map<?, ?> map)) {
                return new int[]{-1, -1};
            }
            int ours = 0;
            for (Object key : map.keySet()) {
                if (key instanceof Block block) {
                    var id = BuiltInRegistries.BLOCK.getKey(block);
                    if (id != null && id.getNamespace().equals(modId)) {
                        ours++;
                    }
                }
            }
            return new int[]{ours, map.size()};
        } catch (Throwable t) {
            return new int[]{-1, -1};
        }
    }

    /**
     * @return true when the event was dispatched without throwing
     */
    public static boolean replay(ModContainer container) {
        if (Boolean.getBoolean("modreloader.skipCapabilityReplay")) {
            ModReloader.LOGGER.info(
                    "[ModReloader] capability replay skipped by property for {}",
                    container.getModId());
            return false;
        }
        boolean previous = true;
        boolean flipped = false;
        try {
            previous = UnsafeAccess.getStaticBoolean(CapabilityHooks.class, "initFinished");
            UnsafeAccess.setStaticBoolean(CapabilityHooks.class, "initFinished", false);
            flipped = true;
            MethodHandle constructor = UnsafeAccess.trusted().findConstructor(
                    RegisterCapabilitiesEvent.class, MethodType.methodType(void.class));
            RegisterCapabilitiesEvent event = (RegisterCapabilitiesEvent) constructor.invoke();
            // Probe: prove the dispatch reached the container's bus even when the mod
            // has no listener (otherwise "replayed" would be a silent no-op).
            IEventBus bus = container.getEventBus();
            AtomicInteger fired = new AtomicInteger();
            Consumer<RegisterCapabilitiesEvent> probe = dispatched -> fired.incrementAndGet();
            if (bus != null) {
                bus.addListener(RegisterCapabilitiesEvent.class, probe);
            }
            try {
                OneShotReplay.fire(container, ReplayCatalog.REGISTER_CAPABILITIES, event);
            } finally {
                if (bus != null) {
                    bus.unregister(probe);
                }
            }
            String modId = container.getModId();
            int[] item = providerCounts(Capabilities.ItemHandler.BLOCK, modId);
            int[] fluid = providerCounts(Capabilities.FluidHandler.BLOCK, modId);
            int[] energy = providerCounts(Capabilities.EnergyStorage.BLOCK, modId);
            ModReloader.LOGGER.info(
                    "[ModReloader] capability providers replayed for {}: item {}/{}, fluid {}/{}, "
                            + "energy {}/{} block(s) for this mod/total; dispatch reached {} listener(s)",
                    modId, item[0], item[1], fluid[0], fluid[1], energy[0], energy[1], fired.get());
            return true;
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot replay RegisterCapabilitiesEvent for {}; its blocks/items "
                            + "may lack capability providers (item handlers, fluid tanks, energy)",
                    container.getModId(), t);
            return false;
        } finally {
            if (flipped) {
                try {
                    UnsafeAccess.setStaticBoolean(CapabilityHooks.class, "initFinished", previous);
                } catch (Throwable ignored) {
                    // The guard is only about setProxyable; leave the framework as-is.
                }
            }
        }
    }
}
