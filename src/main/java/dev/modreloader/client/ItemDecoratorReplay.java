package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.ReplayCatalog;
import dev.modreloader.hotadd.OneShotReplay;
import dev.modreloader.hotadd.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.client.ItemDecoratorHandler;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replays {@code RegisterItemDecorationsEvent}: the overlay a hot-added mod draws
 * on an item icon (durability bars, a charge level, a corner marker).
 *
 * <p>NeoForge fires the event once from {@code ItemDecoratorHandler.init()} and
 * bakes the result into a static, immutable {@code DECORATOR_LOOKUP}
 * ({@code Item -> ItemDecoratorHandler}, whose own list of decorators is
 * immutable too). Replaying the event into the live map is therefore impossible
 * and replaying into an empty one would lose every already-loaded mod's
 * decorations — so the replay reads the live lookup, copies each decorator list,
 * lets the hot-added mod append to the copies, and swaps in a rebuilt lookup.
 *
 * <p>{@code container.acceptEvent} posts to the new mod's own bus, so other
 * loaded mods are not registered twice (their decorators come from the copy).
 *
 * <p>Thread: the lookup is read while item icons render (client thread). A
 * hot-add from chat runs on the server thread, so the swap is scheduled on the
 * client thread rather than racing the renderer.
 */
public final class ItemDecoratorReplay {
    private static MethodHandle handlerCtor;

    private ItemDecoratorReplay() {
    }

    /** Replays the event for {@code container}; never throws. */
    public static void replay(ModContainer container) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot replay item decorations for {}: no client",
                    container.getModId());
            return;
        }
        if (!minecraft.isSameThread()) {
            ModReloader.LOGGER.info(
                    "[ModReloader] item decorations of {}: replay scheduled on the client thread",
                    container.getModId());
            minecraft.execute(() -> apply(container));
            return;
        }
        apply(container);
    }

    @SuppressWarnings("unchecked")
    private static void apply(ModContainer container) {
        try {
            Map<Item, ItemDecoratorHandler> live =
                    UnsafeAccess.getStatic(ItemDecoratorHandler.class, "DECORATOR_LOOKUP");
            // Copy: the event appends to per-item lists, so the copies must not be
            // the immutable lists inside the live handlers.
            Map<Item, List<IItemDecorator>> decorators = new HashMap<>();
            int before = 0;
            for (Map.Entry<Item, ItemDecoratorHandler> entry : live.entrySet()) {
                List<IItemDecorator> list = UnsafeAccess.get(
                        entry.getValue(), ItemDecoratorHandler.class, "itemDecorators");
                decorators.put(entry.getKey(), new ArrayList<>(list));
                before += list.size();
            }

            OneShotReplay.fire(container, ReplayCatalog.REGISTER_ITEM_DECORATIONS,
                    new RegisterItemDecorationsEvent(decorators));

            int after = 0;
            for (List<IItemDecorator> list : decorators.values()) {
                after += list.size();
            }
            if (after == before) {
                ModReloader.LOGGER.info(
                        "[ModReloader] item decorations of {}: the mod registered none ({} total)",
                        container.getModId(), before);
                return;
            }
            Map<Item, ItemDecoratorHandler> rebuilt = new HashMap<>();
            for (Map.Entry<Item, List<IItemDecorator>> entry : decorators.entrySet()) {
                rebuilt.put(entry.getKey(), newHandler(entry.getValue()));
            }
            UnsafeAccess.setStatic(ItemDecoratorHandler.class, "DECORATOR_LOOKUP",
                    com.google.common.collect.ImmutableMap.copyOf(rebuilt));
            ModReloader.LOGGER.info(
                    "[ModReloader] item decorations replayed for {}: {} new decorator(s) "
                            + "({} -> {} decorated item(s), {} -> {} decorator(s) total)",
                    container.getModId(), after - before, live.size(), rebuilt.size(), before, after);
        } catch (Throwable t) {
            // Cosmetic and additive: never fail an otherwise good add for this.
            ModReloader.LOGGER.warn("[ModReloader] item decoration replay failed for {}",
                    container.getModId(), t);
        }
    }

    /** {@code new ItemDecoratorHandler(List)} — private, so a trusted lookup. */
    private static ItemDecoratorHandler newHandler(List<IItemDecorator> decorators) throws Throwable {
        if (handlerCtor == null) {
            handlerCtor = UnsafeAccess.trusted().findConstructor(ItemDecoratorHandler.class,
                    MethodType.methodType(void.class, List.class));
        }
        return (ItemDecoratorHandler) handlerCtor.invoke(decorators);
    }
}
