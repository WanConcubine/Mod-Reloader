package dev.modreloader.ui;

import dev.modreloader.ModReloader;
import dev.modreloader.diagnose.FailureAnalyzer;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Path;

/** Structured failure view: reason, suggestion, rollback detail, copy/open-log. */
public final class LoadFailureScreen extends Screen {
    private final Screen parent;
    private final ModsFolderWatcher.Candidate candidate;
    private final FailureAnalyzer.Report report;
    private MultiLineLabel lines;

    public LoadFailureScreen(Screen parent, ModsFolderWatcher.Candidate candidate,
                             FailureAnalyzer.Report report) {
        super(Component.translatable("modreloader.gui.failure.title"));
        this.parent = parent;
        this.candidate = candidate;
        this.report = report;
    }

    @Override
    protected void init() {
        MutableComponent body = report.summary().copy()
                .append(Component.literal("\n\n")).append(report.suggestion())
                .append(Component.literal("\n\n"))
                .append(Component.translatable("modreloader.gui.details_hint"));
        lines = MultiLineLabel.create(font, width - 40, body);

        int y = height - 52;
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.failure.copy"), pressed -> {
            if (minecraft != null) {
                minecraft.keyboardHandler.setClipboard(report.clipboardText());
                ModReloaderToasts.info(Component.translatable("modreloader.toast.copied.title"),
                        Component.translatable("modreloader.toast.copied.message"));
            }
        }).bounds(width / 2 - 160, y, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.failure.open_log"), pressed -> {
            if (minecraft != null) {
                Path logs = minecraft.gameDirectory.toPath().resolve("logs");
                Util.getPlatform().openPath(logs);
            }
        }).bounds(width / 2 - 55, y, 100, 20).build());
        if (candidate != null) {
            addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.failure.retry"), pressed -> {
                if (ModReloader.WATCHER_CONFIG != null) {
                    ModReloader.WATCHER_CONFIG.clearFailed(candidate.path());
                }
                if (minecraft != null) {
                    minecraft.setScreen(null);
                    LoadController.load(candidate);
                }
            }).bounds(width / 2 + 50, y, 100, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.close"), pressed -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);
        if (lines != null) {
            lines.renderLeftAligned(guiGraphics, 20, 40, 12, 0xDDDDDD);
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
