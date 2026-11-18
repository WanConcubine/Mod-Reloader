package dev.modreloader.ui;

import dev.modreloader.inspect.JbrSupport;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * In-game "how do I get JBR" guide. This is a fallback, not the intended path:
 * when a JBR is installed the failure screen offers "restart on JBR" directly.
 * Always names the restart fallback so nobody is stuck.
 */
public final class JbrGuideScreen extends Screen {
    static final String DOWNLOAD = "https://github.com/JetBrains/JetBrainsRuntime/releases";

    private final Screen parent;
    private MultiLineLabel body;

    public JbrGuideScreen(Screen parent) {
        super(Component.translatable("modreloader.jbr_guide.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Optional<java.nio.file.Path> install = JbrSupport.installation();
        Component text = Component.translatable("modreloader.jbr_guide.body",
                install.map(String::valueOf).orElse("—"));
        body = MultiLineLabel.create(font, width - 40, text);
        int y = height - 52;
        addRenderableWidget(Button.builder(Component.translatable("modreloader.jbr_guide.download"),
                        pressed -> Util.getPlatform().openUri(DOWNLOAD))
                .bounds(width / 2 - 105, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.close"),
                        pressed -> onClose())
                .bounds(width / 2 + 5, y, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        body.renderLeftAligned(guiGraphics, 20, 32, 10, 0xDDDDDD);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
