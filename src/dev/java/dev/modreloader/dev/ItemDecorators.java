package dev.modreloader.dev;

import dev.modreloader.hotadd.UnsafeAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.client.IItemDecorator;
import net.neoforged.neoforge.client.ItemDecoratorHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only view of NeoForge's baked item-decorator lookup, for the harness.
 *
 * <p>{@code ItemDecoratorHandler.DECORATOR_LOOKUP} is a private static
 * {@code ImmutableMap<Item, ItemDecoratorHandler>}, and each handler keeps its
 * decorators in a private {@code itemDecorators} list. Both are read through
 * {@link UnsafeAccess}, the same way the runtime rewrites them in
 * {@code client/ItemDecoratorReplay}. Dev-only.
 */
public final class ItemDecorators {
    private ItemDecorators() {
    }

    /** Items that have at least one decorator, with their item id. */
    public static List<ResourceLocation> decoratedItems() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Item item : lookup().keySet()) {
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                ids.add(id);
            }
        }
        return ids;
    }

    /** Number of decorators registered for {@code item} (0 when none). */
    public static int countFor(Item item) {
        ItemDecoratorHandler handler = lookup().get(item);
        return handler == null ? 0 : decorators(handler).size();
    }

    /** Total decorators across all items. */
    public static int total() {
        int total = 0;
        for (ItemDecoratorHandler handler : lookup().values()) {
            total += decorators(handler).size();
        }
        return total;
    }

    private static Map<Item, ItemDecoratorHandler> lookup() {
        try {
            return UnsafeAccess.getStatic(ItemDecoratorHandler.class, "DECORATOR_LOOKUP");
        } catch (Throwable t) {
            return Map.of();
        }
    }

    private static List<IItemDecorator> decorators(ItemDecoratorHandler handler) {
        try {
            return UnsafeAccess.get(handler, ItemDecoratorHandler.class, "itemDecorators");
        } catch (Throwable t) {
            return List.of();
        }
    }
}
