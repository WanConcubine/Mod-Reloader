package dev.modreloader.clientfixture;

import net.minecraft.client.RecipeBookCategories;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.RecipeBookType;
import net.minecraft.world.item.Items;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.event.RegisterItemDecorationsEvent;
import net.neoforged.neoforge.client.event.RegisterRecipeBookCategoriesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Client-side fixture for the startup-only client registration events.
 *
 * <p>The runtime replays a hand-written list of one-shot mod-bus events. Until
 * 2026-11-16 there was no fixture for that list: the dogfood mod happens not to
 * register a HUD layer or an item decoration, so a regression in those replays
 * would silently pass every harness. This mod registers one of each:
 *
 * <ul>
 *   <li>a GUI layer ({@code clientfixture:hud_marker}) above all vanilla layers;</li>
 *   <li>an item decoration on {@code minecraft:diamond}.</li>
 * </ul>
 *
 * <p>Used by {@code -PverifyAddCommand=<jar> -PverifyAddCommandModId=clientfixture
 * -PverifyGuiLayers=true -PverifyItemDecorators=true} (see
 * {@code docs/features/client-registration-audit.md}).
 */
@Mod(ClientFixtureMod.MOD_ID)
public final class ClientFixtureMod {
    public static final String MOD_ID = "clientfixture";
    public static final ResourceLocation HUD_LAYER =
            ResourceLocation.fromNamespaceAndPath(MOD_ID, "hud_marker");
    private static final Logger LOGGER = LoggerFactory.getLogger("ClientFixture");

    public ClientFixtureMod(IEventBus modBus) {
        modBus.addListener(ClientFixtureMod::onRegisterGuiLayers);
        modBus.addListener(ClientFixtureMod::onRegisterItemDecorations);
        modBus.addListener(ClientFixtureMod::onRegisterRecipeBookCategories);
        LOGGER.info("[ClientFixture] constructed; listeners attached");
    }

    private static void onRegisterGuiLayers(RegisterGuiLayersEvent event) {
        event.registerAboveAll(HUD_LAYER, (graphics, partialTick) ->
                graphics.fill(0, 0, 2, 2, 0x80FF00FF));
        LOGGER.info("[ClientFixture] registered GUI layer {}", HUD_LAYER);
    }

    private static void onRegisterRecipeBookCategories(RegisterRecipeBookCategoriesEvent event) {
        // A new RecipeBookType entry is the cheapest observable registration: the
        // map starts empty for vanilla types, so the replay must report +1.
        event.registerBookCategories(RecipeBookType.CRAFTING,
                List.of(RecipeBookCategories.CRAFTING_MISC));
        LOGGER.info("[ClientFixture] registered recipe book categories for {}",
                RecipeBookType.CRAFTING);
    }

    private static void onRegisterItemDecorations(RegisterItemDecorationsEvent event) {
        event.register(Items.DIAMOND, (graphics, font, stack, xOffset, yOffset) -> {
            graphics.fill(xOffset + 8, yOffset + 8, xOffset + 10, yOffset + 10, 0x8000FF00);
            return true;
        });
        LOGGER.info("[ClientFixture] registered an item decoration for minecraft:diamond");
    }
}
