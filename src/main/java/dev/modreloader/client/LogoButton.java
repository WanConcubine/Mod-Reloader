package dev.modreloader.client;

import dev.modreloader.ModReloader;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Brand-logo button for the title/pause screens.
 *
 * <p>Drawn in Minecraft's GUI language: a beveled stone slot holding a grass
 * block, as pixel art at 1:1 so it stays crisp at every GUI scale. The hover
 * state uses a second texture; keyboard focus gets an outline. The tooltip and
 * narration still carry the name.
 */
public final class LogoButton extends AbstractButton {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ModReloader.MOD_ID, "textures/gui/modreloader_logo.png");
    private static final ResourceLocation TEXTURE_HOVERED = ResourceLocation.fromNamespaceAndPath(
            ModReloader.MOD_ID, "textures/gui/modreloader_logo_hovered.png");
    private static final int TEXTURE_SIZE = 20;

    private final Runnable action;

    public LogoButton(int x, int y, int size, Component tooltip, Runnable action) {
        super(x, y, size, size, tooltip);
        this.action = action;
        setTooltip(Tooltip.create(tooltip));
    }

    @Override
    public void onPress() {
        action.run();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    @Override
    protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        if (isFocused()) {
            // Keyboard focus needs its own marker: the hover texture only covers
            // the mouse case.
            guiGraphics.fill(getX() - 1, getY() - 1, getX() + width + 1, getY() + height + 1,
                    0xFFFFFFFF);
        }
        guiGraphics.blit(isHoveredOrFocused() ? TEXTURE_HOVERED : TEXTURE,
                getX(), getY(), 0f, 0f, width, height, TEXTURE_SIZE, TEXTURE_SIZE);
    }
}
