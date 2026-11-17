package dev.modreloader.ui;

import dev.modreloader.ModReloader;
import dev.modreloader.client.Relauncher;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Optional;

/**
 * Explicit confirmation before restarting on JBR.
 *
 * <p>This overrides the launcher's JVM choice for the current session, so it never
 * happens silently. The text also says that the setting does not persist: the next
 * launch still uses whatever the launcher is configured with.
 */
public final class RelaunchConfirmScreen extends Screen {
    private final Screen parent;
    private MultiLineLabel body;

    public RelaunchConfirmScreen(Screen parent) {
        super(Component.translatable("modreloader.relaunch.jbr.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        body = MultiLineLabel.create(font, width - 40,
                Component.translatable("modreloader.relaunch.jbr.body",
                        String.valueOf(Relauncher.jbrExecutable().orElse(null))));
        int y = height - 52;
        addRenderableWidget(Button.builder(Component.translatable("modreloader.relaunch.jbr.confirm"),
                pressed -> {
                    Optional<String> error = Relauncher.saveAndRestart(minecraft,
                            "switch to JBR for this session",
                            Relauncher.jbrExecutable().orElse(Relauncher.executable()),
                            Relauncher.jbrFlags());
                    error.ifPresent(message -> {
                        ModReloaderToasts.failure(Component.translatable(
                                "modreloader.relaunch.failed", message));
                        if (minecraft != null) {
                            minecraft.setScreen(parent);
                        }
                    });
                }).bounds(width / 2 - 105, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.cancel"),
                pressed -> onClose()).bounds(width / 2 + 5, y, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 40, 0xFFFFFF);
        body.renderCentered(guiGraphics, width / 2, 80, 12, 0xDDDDDD);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
