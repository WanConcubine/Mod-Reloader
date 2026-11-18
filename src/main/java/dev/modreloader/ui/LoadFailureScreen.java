package dev.modreloader.ui;

import dev.modreloader.ModReloader;
import dev.modreloader.client.PendingAdd;
import dev.modreloader.client.Relauncher;
import dev.modreloader.diagnose.FailureAnalyzer;
import dev.modreloader.inspect.JbrSupport;
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
                .append(Component.literal("\n\n")).append(report.suggestion());
        // A JBR retry is attempted when the failure is one JBR can fix (AT or a
        // layout change); late AT is enabled so an AT jar in the new session works.
        boolean lateAtRetry = candidate != null && candidate.inspection().accessTransformers();
        if (lateAtRetry && Relauncher.jbrRelaunchAvailable()) {
            body.append(Component.literal("\n\n"))
                    .append(Component.translatable("modreloader.gui.failure.jbr_late_at_hint"));
        }
        body.append(Component.literal("\n\n"))
                .append(Component.translatable("modreloader.gui.details_hint"));
        lines = MultiLineLabel.create(font, width - 40, body);

        int y = height - 52;
        boolean canRelaunch = Relauncher.canRelaunch();
        boolean restartOffered = report.restartRecommended() && canRelaunch;
        boolean jbrFound = JbrSupport.installation().isPresent();
        // A JBR relaunch needs the launcher's command line; some launchers do not
        // expose it. Then the useful thing is the manual steps, not a dead button.
        boolean jbrOffered = Relauncher.jbrRelaunchAvailable() && canRelaunch;
        boolean guideOffered = !JbrSupport.available() && report.jbrHelps()
                && (!jbrFound || !canRelaunch);
        int restartWidth = 150;
        int jbrWidth = jbrOffered ? 120 : 140;
        int secondaryY = y - 24;
        int secondaryGap = 6;
        if (restartOffered && (jbrOffered || guideOffered)) {
            int total = restartWidth + secondaryGap + jbrWidth;
            if (total + 16 <= width) {
                int left = width / 2 - total / 2;
                addRestartButton(left, secondaryY, restartWidth);
                if (jbrOffered) {
                    addJbrButton(left + restartWidth + secondaryGap, secondaryY, jbrWidth);
                } else {
                    addJbrGuideButton(left + restartWidth + secondaryGap, secondaryY, jbrWidth);
                }
            } else {
                addRestartButton(width / 2 - restartWidth / 2, y - 46, restartWidth);
                if (jbrOffered) {
                    addJbrButton(width / 2 - jbrWidth / 2, secondaryY, jbrWidth);
                } else {
                    addJbrGuideButton(width / 2 - jbrWidth / 2, secondaryY, jbrWidth);
                }
            }
        } else if (restartOffered) {
            addRestartButton(width / 2 - restartWidth / 2, secondaryY, restartWidth);
        } else if (jbrOffered) {
            addJbrButton(width / 2 - jbrWidth / 2, secondaryY, jbrWidth);
        } else if (guideOffered) {
            addJbrGuideButton(width / 2 - jbrWidth / 2, secondaryY, jbrWidth);
        }
        if (!canRelaunch && (report.restartRecommended() || (jbrFound && report.jbrHelps()))) {
            lines = MultiLineLabel.create(font, width - 40, body.copy()
                    .append(Component.literal("\n\n"))
                    .append(Component.translatable("modreloader.relaunch.unavailable",
                            String.valueOf(Relauncher.blocker()))));
        }
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

    private void addRestartButton(int x, int y, int w) {
        addRenderableWidget(Button.builder(
                Component.translatable("modreloader.gui.failure.save_restart"), pressed -> {
                    rememberPending();
                    var error = Relauncher.saveAndRestart(minecraft, report.title().getString());
                    error.ifPresent(message -> ModReloaderToasts.failure(
                            Component.translatable("modreloader.relaunch.failed", message)));
                }).bounds(x, y, w, 20).build());
    }

    /** Keep the retry visible after the restart (failed fingerprints would hide it). */
    private void addJbrButton(int x, int y, int w) {
        // One product action, one label: prepare the enhanced environment (install
        // the runtime if needed, then restart with the full flag set). The setup
        // screen reuses an already installed JBR and enables late AT.
        addRenderableWidget(Button.builder(
                Component.translatable("modreloader.jbr_setup.short"), pressed -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new JbrSetupScreen(this,
                                candidate == null ? null : candidate.path()));
                    }
                }).bounds(x, y, w, 20).build());
    }

    private void addJbrGuideButton(int x, int y, int w) {
        // No JBR yet: offer the automated install (download, verify, restart).
        // The manual guide is a secondary link inside that screen.
        addRenderableWidget(Button.builder(
                Component.translatable("modreloader.jbr_setup.short"), pressed -> {
                    if (minecraft != null) {
                        minecraft.setScreen(new JbrSetupScreen(this,
                                candidate == null ? null : candidate.path()));
                    }
                }).bounds(x, y, w, 20).build());
    }

    private void rememberPending() {
        if (minecraft == null || candidate == null) {
            return;
        }
        if (ModReloader.WATCHER_CONFIG != null) {
            ModReloader.WATCHER_CONFIG.clearFailed(candidate.path());
        }
        if (!PendingAdd.write(minecraft.gameDirectory.toPath(), candidate.path())) {
            ModReloader.LOGGER.warn("[ModReloader] cannot remember {} for the next session",
                    candidate.path());
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }
}
