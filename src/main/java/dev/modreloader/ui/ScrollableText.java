package dev.modreloader.ui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.util.Mth;

/**
 * Draws a MultiLineLabel inside a fixed viewport and follows the mouse wheel.
 *
 * <p>The JBR consent/guide pages carry long bodies (URLs, SHA-512, install
 * paths). At the default GUI scale a 854x480 window is only 240 GUI units tall,
 * so an unclipped body used to paint straight over the footer buttons. Clipping
 * plus a scrollbar keeps the buttons reachable at every scale.
 */
final class ScrollableText {
    private static final int COLOR = 0xDDDDDD;

    private final MultiLineLabel label;
    private final int width;
    private final int anchorX;
    private final int top;
    private final int bottom;
    private final int lineHeight;
    private final boolean centered;
    private int scroll;

    ScrollableText(MultiLineLabel label, int width, int anchorX, int top, int bottom,
                   int lineHeight, boolean centered) {
        this.label = label;
        this.width = width;
        this.anchorX = anchorX;
        this.top = top;
        this.bottom = Math.max(top + lineHeight, bottom);
        this.lineHeight = lineHeight;
        this.centered = centered;
    }

    boolean handleScroll(double notches) {
        int max = maxScroll();
        if (max <= 0) {
            return false;
        }
        scroll = Mth.clamp(scroll - (int) Math.round(notches * lineHeight * 3.0), 0, max);
        return true;
    }

    void render(GuiGraphics graphics) {
        graphics.enableScissor(0, top, width, bottom);
        int y = top - scroll;
        if (centered) {
            label.renderCentered(graphics, anchorX, y, lineHeight, COLOR);
        } else {
            label.renderLeftAligned(graphics, anchorX, y, lineHeight, COLOR);
        }
        graphics.disableScissor();

        int max = maxScroll();
        if (max <= 0) {
            return;
        }
        int viewport = bottom - top;
        int trackX = width - 6;
        int thumb = Math.max(8, viewport * viewport / (viewport + max));
        int thumbY = top + (viewport - thumb) * scroll / max;
        graphics.fill(trackX, top, trackX + 2, bottom, 0x30FFFFFF);
        graphics.fill(trackX, thumbY, trackX + 2, thumbY + thumb, 0xFFBBBBBB);
    }

    private int maxScroll() {
        return Math.max(0, label.getLineCount() * lineHeight - (bottom - top));
    }
}