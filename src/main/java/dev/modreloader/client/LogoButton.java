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
 * <p>Renders the mod's icon instead of text (the tooltip and narration still
 * carry the name), and sits in a corner that vanilla screens leave empty.
 */
public final class LogoButton extends AbstractButton {
    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(
            ModReloader.MOD_ID, "textures/gui/modreloader_logo.png");
    private static final int TEXTURE_SIZE = 64;

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
        if (isHoveredOrFocused()) {
            guiGraphics.fill(getX() - 2, getY() - 2, getX() + width + 2, getY() + height + 2,
                    0x40FFFFFF);
        }
        // blit() samples 1:1 and does not scale, so draw the 64x64 source through a
        // pose transform instead of sampling its top-left corner.
        var pose = guiGraphics.pose();
        pose.pushPose();
        pose.translate(getX(), getY(), 0f);
        pose.scale((float) width / TEXTURE_SIZE, (float) height / TEXTURE_SIZE, 1f);
        guiGraphics.blit(TEXTURE, 0, 0, 0f, 0f, TEXTURE_SIZE, TEXTURE_SIZE,
                TEXTURE_SIZE, TEXTURE_SIZE);
        pose.popPose();
    }
}
