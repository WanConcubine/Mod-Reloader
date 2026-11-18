package dev.modreloader.client;

import com.google.common.collect.ImmutableList;
import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.UnsafeAccess;
import net.minecraft.client.RecipeBookCategories;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.RecipeBookManager;
import net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Replays {@code RegisterRecipeBookCategoriesEvent} for a hot-added mod.
 *
 * <p>NeoForge's {@code RecipeBookManager.init()} fires this once at startup with
 * three fresh maps, then copies them into static maps. A mod like Farmer's Delight
 * registers its own recipe-book category and a finder; missing it makes the
 * category absent (or crashes when the book is opened), which is why the
 * registration audit used to refuse the add before touching anything.
 *
 * <p>The live maps are {@code HashMap}s (the aggregate view is only an
 * {@code unmodifiableMap} wrapper), so a targeted replay against copies and a
 * putAll merge is safe. Copies start from the current entries, so handlers that
 * extend an existing aggregate category see what is already there.
 */
public final class RecipeBookCategoryReplay {
    private RecipeBookCategoryReplay() {
    }

    @SuppressWarnings("unchecked")
    public static void replay(ModContainer container) {
        try {
            Map<RecipeBookCategories, List<RecipeBookCategories>> aggregate =
                    UnsafeAccess.getStatic(RecipeBookManager.class, "AGGREGATE_CATEGORIES");
            Map<RecipeBookType, List<RecipeBookCategories>> types =
                    UnsafeAccess.getStatic(RecipeBookManager.class, "TYPE_CATEGORIES");
            Map<RecipeType<?>, Function<RecipeHolder<?>, RecipeBookCategories>> lookups =
                    UnsafeAccess.getStatic(RecipeBookManager.class, "RECIPE_CATEGORY_LOOKUPS");
            if (aggregate == null || types == null || lookups == null) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] recipe book categories not replayed: maps unavailable");
                return;
            }

            Map<RecipeBookCategories, ImmutableList<RecipeBookCategories>> freshAggregate =
                    new HashMap<>();
            aggregate.forEach((category, children) ->
                    freshAggregate.put(category, ImmutableList.copyOf(children)));
            Map<RecipeBookType, ImmutableList<RecipeBookCategories>> freshTypes = new HashMap<>();
            types.forEach((type, children) ->
                    freshTypes.put(type, ImmutableList.copyOf(children)));
            Map<RecipeType<?>, Function<RecipeHolder<?>, RecipeBookCategories>> freshLookups =
                    new HashMap<>(lookups);

            int before = aggregate.size() + types.size() + lookups.size();
            container.acceptEvent(new RegisterRecipeBookCategoriesEvent(
                    freshAggregate, freshTypes, freshLookups));
            aggregate.putAll(freshAggregate);
            types.putAll(freshTypes);
            lookups.putAll(freshLookups);
            int added = aggregate.size() + types.size() + lookups.size() - before;
            ModReloader.LOGGER.info(
                    "[ModReloader] recipe book categories replayed: {} new, {} aggregate / {} type / {} lookup",
                    added, aggregate.size(), types.size(), lookups.size());
        } catch (Throwable t) {
            // Missing categories affect only the recipe book UI; never fail the add.
            ModReloader.LOGGER.warn("[ModReloader] recipe book category replay failed", t);
        }
    }
}
