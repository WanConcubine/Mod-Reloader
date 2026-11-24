package dev.modreloader.ui;

import dev.modreloader.inspect.JbrSupport;
import dev.modreloader.launch.JbrProvisioner;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * In-game manual JBR guide. Automatic provisioning failed or the player chose
 * not to download: this page names the exact target directory, how to verify the
 * layout, the platform URL, and how to get back to the live re-check/restart.
 */
public final class JbrGuideScreen extends Screen {
    private final Screen parent;
    private ScrollableText body;
    private String status = "";

    public JbrGuideScreen(Screen parent) {
        super(Component.translatable("modreloader.jbr_guide.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        Optional<Path> install = JbrSupport.installation();
        Optional<JbrProvisioner.Asset> asset = JbrProvisioner.asset();
        Path gameDir = minecraft == null ? Path.of(".") : minecraft.gameDirectory.toPath();
        String target = JbrProvisioner.installDir(gameDir).toString();
        String url = asset.map(JbrProvisioner::downloadUrl)
                .orElse(JbrProvisioner.releasePage());
        body = new ScrollableText(MultiLineLabel.create(font, width - 40,
                Component.translatable("modreloader.jbr_guide.body", url, target,
                        install.map(String::valueOf).orElse("—"))),
                width, 20, 32, height - 82, 10, false);

        int y = height - 56;
        addRenderableWidget(Button.builder(Component.translatable("modreloader.jbr_guide.copy"),
                        pressed -> copy(url))
                .bounds(width / 2 - 155, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.jbr_guide.download"),
                        pressed -> Util.getPlatform().openUri(JbrProvisioner.releasePage()))
                .bounds(width / 2 + 5, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.jbr_guide.open_dir"),
                        pressed -> {
                            if (minecraft != null) {
                                Util.getPlatform().openFile(gameDir.toFile());
                            }
                        })
                .bounds(width / 2 - 155, y + 24, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.close"),
                        pressed -> onClose())
                .bounds(width / 2 + 5, y + 24, 150, 20).build());
    }

    private void copy(String url) {
        if (minecraft != null) {
            minecraft.keyboardHandler.setClipboard(url);
            status = Component.translatable("modreloader.jbr_guide.copied").getString();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        body.render(guiGraphics);
        if (!status.isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.literal(status),
                    width / 2, height - 72, 0x88FF88);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return (body != null && body.handleScroll(scrollY))
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}