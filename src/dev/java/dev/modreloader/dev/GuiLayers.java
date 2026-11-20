package dev.modreloader.dev;

import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.gui.GuiLayerManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only view of the client's live HUD layer list, for the GUI-layer harness.
 *
 * <p>The list lives in {@code Gui#layerManager} (NeoForge's {@code GuiLayerManager})
 * as a private final field, so it is read through {@link UnsafeAccess} — the same
 * way the runtime itself reaches it in {@code client/GuiLayerReplay}. Dev-only.
 */
public final class GuiLayers {
    private GuiLayers() {
    }

    /** Layer ids in render order (bottom to top); empty when it cannot be read. */
    public static List<ResourceLocation> ids() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (GuiLayerManager.NamedLayer layer : layers()) {
            ids.add(layer.name());
        }
        return ids;
    }

    public static int count() {
        return layers().size();
    }

    /** How many layers belong to {@code namespace}. */
    public static int countInNamespace(String namespace) {
        int count = 0;
        for (GuiLayerManager.NamedLayer layer : layers()) {
            if (namespace.equals(layer.name().getNamespace())) {
                count++;
            }
        }
        return count;
    }

    /** True when {@code expected} appears in {@code actual} in the same relative order. */
    public static boolean containsInOrder(List<ResourceLocation> actual,
                                         List<ResourceLocation> expected) {
        int index = 0;
        for (ResourceLocation id : actual) {
            if (index < expected.size() && expected.get(index).equals(id)) {
                index++;
            }
        }
        return index == expected.size();
    }

    public static List<ResourceLocation> added(List<ResourceLocation> before,
                                               List<ResourceLocation> after) {
        List<ResourceLocation> added = new ArrayList<>();
        for (ResourceLocation id : after) {
            if (!before.contains(id)) {
                added.add(id);
            }
        }
        return added;
    }

    private static List<GuiLayerManager.NamedLayer> layers() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gui == null) {
            return List.of();
        }
        try {
            GuiLayerManager manager = UnsafeAccess.get(minecraft.gui, Gui.class, "layerManager");
            @SuppressWarnings("unchecked")
            List<GuiLayerManager.NamedLayer> layers = UnsafeAccess.get(
                    manager, GuiLayerManager.class, "layers");
            return List.copyOf(layers);
        } catch (Throwable t) {
            return List.of();
        }
    }
}
