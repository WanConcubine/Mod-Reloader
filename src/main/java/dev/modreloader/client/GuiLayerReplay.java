package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.ReplayCatalog;
import dev.modreloader.hotadd.OneShotReplay;
import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.GuiLayerManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Replays {@code RegisterGuiLayersEvent}: HUD layers of a hot-added mod.
 *
 * <p>{@code RegisterGuiLayersEvent} is fired exactly once, from
 * {@code GuiLayerManager.initModdedLayers()} while {@code Gui} is constructed.
 * A hot-added mod misses it, so its HUD element (AppleSkin's hunger/saturation
 * overlays, a minimap, a mana bar) never draws — silently, with no error, which
 * is why the registration audit had it in the {@code silent} bucket.
 *
 * <p>The replay follows the merge strategy the other client replays use, with a
 * twist: the event's API orders layers <em>relative to an existing id</em>
 * ({@code registerBelow/Above(VanillaGuiLayers.HOTBAR, …)}), so replaying into
 * an empty list would throw "ordered against an unregistered layer". The replay
 * therefore runs against a <em>copy</em> of the live layer list and then swaps
 * the copy in. That is safe because the event can only insert new entries or
 * wrap/replace an existing one — never remove or reorder — and
 * {@code container.acceptEvent} posts to the hot-added mod's own bus, so other
 * loaded mods' listeners do not run a second time.
 *
 * <p>Thread: the layer list is read by the HUD every frame (the client thread).
 * A hot-add triggered from chat runs on the server thread, so in that case the
 * swap is scheduled on the client thread instead of racing the renderer.
 *
 * <p>Not replayed: {@code RegisterItemDecorationsEvent} and
 * {@code EntityRenderersEvent.RegisterLayerDefinitions} (both still gaps, see
 * the registration audit).
 */
public final class GuiLayerReplay {
    private GuiLayerReplay() {
    }

    /** Replays the event for {@code container}; never throws. */
    public static void replay(ModContainer container) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot replay GUI layers for {}: the Gui does not exist yet",
                    container.getModId());
            return;
        }
        List<GuiLayerManager.NamedLayer> live = liveLayers(minecraft, container.getModId());
        if (live == null) {
            return;
        }
        if (!minecraft.isSameThread()) {
            // Chat command: the add runs on the server thread while the HUD keeps
            // rendering. Hand the swap to the client thread rather than mutating a
            // list the renderer may be iterating.
            ModReloader.LOGGER.info(
                    "[ModReloader] GUI layers of {}: replay scheduled on the client thread",
                    container.getModId());
            minecraft.execute(() -> apply(container, live));
            return;
        }
        apply(container, live);
    }

    private static void apply(ModContainer container, List<GuiLayerManager.NamedLayer> live) {
        try {
            Set<ResourceLocation> before = new HashSet<>();
            for (GuiLayerManager.NamedLayer layer : live) {
                before.add(layer.name());
            }
            List<GuiLayerManager.NamedLayer> copy = new ArrayList<>(live);
            OneShotReplay.fire(container, ReplayCatalog.REGISTER_GUI_LAYERS,
                    new RegisterGuiLayersEvent(copy));

            List<ResourceLocation> added = new ArrayList<>();
            for (GuiLayerManager.NamedLayer layer : copy) {
                if (!before.contains(layer.name())) {
                    added.add(layer.name());
                }
            }
            if (added.isEmpty()) {
                ModReloader.LOGGER.info(
                        "[ModReloader] GUI layers of {}: the mod registered no new layer ({} total)",
                        container.getModId(), live.size());
                return;
            }
            // The event cannot remove or reorder: the copy is the live list with the
            // new layers spliced in at the requested positions.
            live.clear();
            live.addAll(copy);
            ModReloader.LOGGER.info(
                    "[ModReloader] GUI layers replayed for {}: {} new ({} -> {} total); new layers: {}",
                    container.getModId(), added.size(), before.size(), live.size(), added);
        } catch (Throwable t) {
            // Cosmetic and additive: never fail an otherwise good add for this, and
            // leave the live list untouched (the copy was discarded).
            ModReloader.LOGGER.warn("[ModReloader] GUI layer replay failed for {}",
                    container.getModId(), t);
        }
    }

    /** The live layer list of the running client, or {@code null} when it cannot be read. */
    private static List<GuiLayerManager.NamedLayer> liveLayers(Minecraft minecraft, String modId) {
        try {
            GuiLayerManager manager = UnsafeAccess.get(minecraft.gui, Gui.class, "layerManager");
            @SuppressWarnings("unchecked")
            List<GuiLayerManager.NamedLayer> layers = UnsafeAccess.get(
                    manager, GuiLayerManager.class, "layers");
            return layers;
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot read the GUI layer list; {}'s HUD layers are not replayed",
                    modId, t);
            return null;
        }
    }
}
