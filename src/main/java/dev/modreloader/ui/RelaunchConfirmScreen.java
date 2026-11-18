package dev.modreloader.ui;

import dev.modreloader.ModReloader;
import dev.modreloader.client.PendingAdd;
import dev.modreloader.client.Relauncher;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Confirmation for "restart on JBR". The body says what the switch does, and —
 * when the retry is for an access-transformer jar — what enabling the
 * experimental late-AT path costs. The warning is not optional text: the flag
 * changes classes process-wide, so the user must see it before the button.
 */
public final class RelaunchConfirmScreen extends Screen {
    private final Screen parent;
    private final Path pendingJar;
    private final boolean enableLateAt;
    private MultiLineLabel body;

    public RelaunchConfirmScreen(Screen parent) {
        this(parent, null, false);
    }

    /**
     * @param pendingJar  jar to preselect after the relaunch (may be null)
     * @param enableLateAt pass {@code -Dmodreloader.lateAt=true} to the new session
     */
    public RelaunchConfirmScreen(Screen parent, Path pendingJar, boolean enableLateAt) {
        super(Component.translatable("modreloader.relaunch.jbr.title"));
        this.parent = parent;
        this.pendingJar = pendingJar;
        this.enableLateAt = enableLateAt;
    }

    @Override
    protected void init() {
        MutableComponent text = Component.translatable("modreloader.relaunch.jbr.body",
                String.valueOf(Relauncher.jbrExecutable().orElse(null))).copy();
        if (enableLateAt) {
            text.append(Component.literal("\n\n"))
                    .append(Component.translatable("modreloader.relaunch.jbr.late_at_warning"));
        }
        body = MultiLineLabel.create(font, width - 40, text);
        int y = height - 52;
        addRenderableWidget(Button.builder(Component.translatable("modreloader.relaunch.jbr.confirm"),
                pressed -> {
                    rememberPending();
                    Optional<String> error = Relauncher.saveAndRestart(minecraft,
                            "switch to JBR for this session",
                            Relauncher.jbrExecutable().orElse(Relauncher.executable()),
                            Relauncher.jbrFlags(enableLateAt));
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

    /**
     * The failure already recorded this jar as "failed"; leaving that fingerprint
     * in place would hide the jar from the next session's candidate list.
     */
    private void rememberPending() {
        if (minecraft == null || pendingJar == null) {
            return;
        }
        if (ModReloader.WATCHER_CONFIG != null) {
            ModReloader.WATCHER_CONFIG.clearFailed(pendingJar);
        }
        if (!PendingAdd.write(minecraft.gameDirectory.toPath(), pendingJar)) {
            ModReloader.LOGGER.warn("[ModReloader] cannot remember {} for the next session",
                    pendingJar);
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 24, 0xFFFFFF);
        body.renderCentered(guiGraphics, width / 2, 54, 10, 0xDDDDDD);
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
