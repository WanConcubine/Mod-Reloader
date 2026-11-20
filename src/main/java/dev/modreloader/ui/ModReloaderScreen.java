package dev.modreloader.ui;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.RunState;
import dev.modreloader.inspect.JbrSupport;
import dev.modreloader.inspect.ModInspection;
import dev.modreloader.watch.ModWatcherConfig;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.modreloader.i18n.ModReloaderI18n;

/**
 * Pending mods: newly detected jars plus the ignored ones, which can be restored.
 *
 * <p>Layout rules: one aligned action row at the bottom (primary actions), one
 * secondary row for the view switch. No button duplicates another: closing is the
 * only way to dismiss, so there is no separate "later" that did the same thing,
 * and the Load button keeps its label when it is not usable — the reason is shown
 * as text instead of being turned into a fake button.
 */
public final class ModReloaderScreen extends Screen {
    private static final int MAX_ROWS = 7;
    private static final int ROW_HEIGHT = 22;
    private static final int BUTTON_WIDTH = 100;
    private static final int GAP = 8;
    private static final int TOP = 44;

    private final List<ModsFolderWatcher.Candidate> candidates;
    private final List<Button> rowButtons = new ArrayList<>();
    private Button loadButton;
    private Button ignoreButton;
    private Button restoreButton;
    private int selected;
    private boolean showingIgnored;

    public ModReloaderScreen(List<ModsFolderWatcher.Candidate> candidates) {
        this(candidates, null);
    }

    /**
     * @param preselect jar to select on open — the one a managed relaunch was
     *                  retrying, so the user does not have to find it again
     */
    public ModReloaderScreen(List<ModsFolderWatcher.Candidate> candidates, Path preselect) {
        super(Component.translatable("modreloader.gui.new_mods.title"));
        this.candidates = new ArrayList<>(candidates);
        this.selected = indexOf(preselect);
    }

    private int indexOf(Path preselect) {
        if (candidates.isEmpty()) {
            return -1;
        }
        if (preselect != null) {
            Path wanted = preselect.toAbsolutePath().normalize();
            for (int i = 0; i < candidates.size(); i++) {
                if (candidates.get(i).path().toAbsolutePath().normalize().equals(wanted)) {
                    return i;
                }
            }
        }
        return 0;
    }

    private List<ModWatcherConfig.IgnoredEntry> ignoredEntries() {
        return ModReloader.WATCHER_CONFIG == null
                ? List.of()
                : ModReloader.WATCHER_CONFIG.ignoredEntries();
    }

    @Override
    protected void init() {
        rowButtons.clear();
        int rows = showingIgnored
                ? Math.min(ignoredEntries().size(), MAX_ROWS)
                : Math.min(candidates.size(), MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            final int index = i;
            Button button = Button.builder(rowLabel(i), pressed -> {
                selected = index;
                refresh();
            }).bounds(width / 2 - 160, TOP + i * ROW_HEIGHT, 320, 20).build();
            rowButtons.add(button);
            addRenderableWidget(button);
        }

        // Primary row: identical geometry in every view so nothing jumps around.
        int y = height - 56;
        int x = width / 2 - (BUTTON_WIDTH * 3 + GAP * 2) / 2;
        if (showingIgnored) {
            restoreButton = addRenderableWidget(Button.builder(
                            Component.translatable("modreloader.gui.restore"), pressed -> restoreSelected())
                    .bounds(x, y, BUTTON_WIDTH, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.close"),
                            pressed -> onClose())
                    .bounds(x + BUTTON_WIDTH + GAP, y, BUTTON_WIDTH, 20).build());
        } else if (candidates.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.close"),
                            pressed -> onClose())
                    .bounds(width / 2 - BUTTON_WIDTH / 2, y, BUTTON_WIDTH, 20).build());
        } else {
            loadButton = addRenderableWidget(Button.builder(
                            Component.translatable("modreloader.gui.load"), pressed -> loadSelected())
                    .bounds(x, y, BUTTON_WIDTH, 20).build());
            ignoreButton = addRenderableWidget(Button.builder(
                            Component.translatable("modreloader.gui.ignore"), pressed -> ignoreSelected())
                    .bounds(x + BUTTON_WIDTH + GAP, y, BUTTON_WIDTH, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.close"),
                            pressed -> onClose())
                    .bounds(x + (BUTTON_WIDTH + GAP) * 2, y, BUTTON_WIDTH, 20).build());
        }

        // Secondary row: the view switch, plus the JBR guide when this screen is
        // exactly where the player is blocked (AT/layout mod, no usable JBR).
        boolean jbrHelp = !showingIgnored && !JbrSupport.available()
                && candidates.stream().anyMatch(ModReloaderScreen::needsJbr);
        if (jbrHelp) {
            int total = 140 + 6 + 140;
            int left = Math.max(4, width / 2 - total / 2);
            addRenderableWidget(Button.builder(Component.translatable(
                            "modreloader.gui.ignored_list", ignoredEntries().size()),
                            pressed -> toggleIgnored())
                    .bounds(left, height - 30, 140, 20).build());
            addRenderableWidget(Button.builder(Component.translatable("modreloader.jbr_setup.short"),
                            pressed -> {
                                if (minecraft != null) {
                                    Path pending = selected >= 0 && selected < candidates.size()
                                            ? candidates.get(selected).path() : null;
                                    minecraft.setScreen(new JbrSetupScreen(this, pending));
                                }
                            })
                    .bounds(left + 146, height - 30, 140, 20).build());
        } else {
            boolean needsPrep = !showingIgnored
                    && JbrSupport.status() != JbrSupport.Status.READY;
            if (needsPrep) {
                int total = 150 + 6 + 150;
                int left = Math.max(4, width / 2 - total / 2);
                addRenderableWidget(Button.builder(Component.translatable(
                                "modreloader.gui.ignored_list", ignoredEntries().size()),
                                pressed -> toggleIgnored())
                        .bounds(left, height - 30, 150, 20).build());
                addRenderableWidget(Button.builder(
                                Component.translatable("modreloader.jbr_setup.short"),
                                pressed -> {
                                    if (minecraft != null) {
                                        Path pending = selected >= 0 && selected < candidates.size()
                                                ? candidates.get(selected).path() : null;
                                        minecraft.setScreen(new JbrSetupScreen(this, pending));
                                    }
                                })
                        .bounds(left + 156, height - 30, 150, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.translatable(showingIgnored
                                        ? "modreloader.gui.ignored_back"
                                        : "modreloader.gui.ignored_list", ignoredEntries().size()),
                                pressed -> toggleIgnored())
                        .bounds(width / 2 - 80, height - 30, 160, 20).build());
            }
        }
        refresh();
    }

    /** A candidate whose add would need JBR's enhanced redefinition. */
    private static boolean needsJbr(ModsFolderWatcher.Candidate candidate) {
        ModInspection inspection = candidate.inspection();
        if (inspection.accessTransformers()) {
            return true;
        }
        return inspection.tier() == ModInspection.Tier.FEI
                || (inspection.tier() == ModInspection.Tier.GUIFEI && inspection.mixins());
    }

    private Component rowLabel(int index) {
        String prefix = index == selected ? "▶ " : "  ";
        if (showingIgnored) {
            List<ModWatcherConfig.IgnoredEntry> ignored = ignoredEntries();
            if (index >= ignored.size()) {
                return Component.literal("");
            }
            return Component.literal(prefix + Path.of(ignored.get(index).path).getFileName());
        }
        var candidate = candidates.get(index);
        return Component.literal(prefix + "[")
                .append(ModReloaderI18n.tier(candidate.inspection().tier()))
                .append(Component.literal("] " + candidate.path().getFileName()));
    }

    private void toggleIgnored() {
        showingIgnored = !showingIgnored;
        int rows = showingIgnored ? ignoredEntries().size() : candidates.size();
        selected = rows == 0 ? -1 : 0;
        rebuildWidgets();
    }

    private void refresh() {
        for (int i = 0; i < rowButtons.size(); i++) {
            rowButtons.get(i).setMessage(rowLabel(i));
        }
        if (loadButton != null) {
            loadButton.active = selected >= 0 && selected < candidates.size()
                    && candidates.get(selected).inspection()
                            .canHotLoad(registryContentHotAdd(), lateAccessTransformers());
        }
        if (ignoreButton != null) {
            ignoreButton.active = selected >= 0 && selected < candidates.size();
        }
        if (restoreButton != null) {
            restoreButton.active = selected >= 0 && selected < ignoredEntries().size();
        }
    }

    private static boolean registryContentHotAdd() {
        return dev.modreloader.capability.Capabilities.available(
                dev.modreloader.capability.Capabilities.Capability.REGISTRY_ADAPTATION);
    }

    /**
     * Advanced/pack setups only: the experimental late-AT switch. Players are
     * never asked to set it; the packaged runtime decides.
     */
    private static boolean lateAccessTransformers() {
        return dev.modreloader.hotadd.LateAccessTransformers.enabled();
    }

    private void loadSelected() {
        if (selected >= 0 && selected < candidates.size()) {
            LoadController.load(candidates.get(selected));
        }
    }

    private void restoreSelected() {
        List<ModWatcherConfig.IgnoredEntry> ignored = ignoredEntries();
        if (selected < 0 || selected >= ignored.size() || ModReloader.WATCHER_CONFIG == null) {
            return;
        }
        ModWatcherConfig.IgnoredEntry entry = ignored.get(selected);
        if (ModReloader.WATCHER_CONFIG.unignore(Path.of(entry.path), entry.size, entry.mtime)) {
            ModReloaderToasts.info(Component.translatable("modreloader.toast.restored.title"),
                    Component.translatable("modreloader.toast.restored.message",
                            Path.of(entry.path).getFileName().toString()));
        }
        showingIgnored = false;
        selected = candidates.isEmpty() ? -1 : 0;
        rebuildWidgets();
    }

    private void ignoreSelected() {
        if (selected < 0 || selected >= candidates.size()) {
            return;
        }
        var candidate = candidates.get(selected);
        if (ModReloader.WATCHER_CONFIG != null) {
            ModReloader.WATCHER_CONFIG.ignore(candidate.path(), candidate.size(), candidate.mtime());
        }
        ModReloaderToasts.info(Component.translatable("modreloader.toast.ignored.title"),
                Component.translatable("modreloader.toast.ignored.message",
                        candidate.path().getFileName().toString()));
        candidates.remove(selected);
        selected = candidates.isEmpty() ? -1 : Math.min(selected, candidates.size() - 1);
        rebuildWidgets();
    }

    /**
     * Top-left environment badge. "Been asked to restart on JBR although the
     * launcher selected JBR" is the confusing case: this shows whether the
     * runtime sees a JBR at all and whether the enhanced-redefinition flag is on.
     */
    private void renderJbrBadge(GuiGraphics guiGraphics) {
        String key;
        int color;
        switch (JbrSupport.status()) {
            case READY -> {
                key = "modreloader.gui.jbr.ready";
                color = 0x55FF55;
            }
            case JBR_NO_ENHANCED -> {
                key = "modreloader.gui.jbr.no_flag";
                color = 0xFFAA00;
            }
            default -> {
                key = "modreloader.gui.jbr.missing";
                color = 0xFF5555;
            }
        }
        guiGraphics.drawString(font, Component.translatable(key), 6, 6, color, true);
    }

    /** A dirty runtime looks healthy until the next add; say so while the menu is open. */
    private void renderDirtyBadge(GuiGraphics guiGraphics) {
        if (!RunState.isDirty()) {
            return;
        }
        guiGraphics.drawString(font,
                Component.translatable("modreloader.gui.dirty",
                        Component.translatable(RunState.reason().key())),
                6, 32, 0xFF5555, true);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        renderJbrBadge(guiGraphics);
        renderDirtyBadge(guiGraphics);
        String vendor = System.getProperty("java.vendor", "?");
        guiGraphics.drawCenteredString(font, Component.translatable("modreloader.gui.env",
                        vendor,
                        dev.modreloader.inspect.JbrSupport.available() ? "on" : "off"),
                width / 2, height - 12, 0x808080);

        if (showingIgnored) {
            renderIgnored(guiGraphics);
            return;
        }
        if (candidates.isEmpty()) {
            guiGraphics.drawCenteredString(font,
                    Component.translatable("modreloader.gui.new_mods.empty"),
                    width / 2, height / 2 - 10, 0xAAAAAA);
            MultiLineLabel hint = MultiLineLabel.create(font, width - 80,
                    Component.translatable("modreloader.gui.empty_hint"));
            hint.renderCentered(guiGraphics, width / 2, height / 2 + 6, 11, 0x808080);
            return;
        }
        if (candidates.size() > MAX_ROWS) {
            guiGraphics.drawCenteredString(font,
                    Component.translatable("modreloader.gui.new_mods.more", candidates.size() - MAX_ROWS),
                    width / 2, TOP + MAX_ROWS * ROW_HEIGHT + 2, 0xAAAAAA);
        }
        if (selected >= 0 && selected < candidates.size()) {
            var candidate = candidates.get(selected);
            MutableComponent text = ModReloaderI18n.summary(candidate.inspection()).copy();
            text.append(Component.literal("\n"))
                    .append(ModReloaderI18n.blockReason(candidate.inspection(),
                            registryContentHotAdd(), lateAccessTransformers()));
            MultiLineLabel label = MultiLineLabel.create(font, width - 60, text);
            label.renderLeftAligned(guiGraphics, 30,
                    TOP + Math.min(candidates.size(), MAX_ROWS) * ROW_HEIGHT + 14, 11, 0xCCCCCC);
        }
    }

    private void renderIgnored(GuiGraphics guiGraphics) {
        List<ModWatcherConfig.IgnoredEntry> ignored = ignoredEntries();
        if (ignored.isEmpty()) {
            guiGraphics.drawCenteredString(font,
                    Component.translatable("modreloader.gui.ignored_empty"),
                    width / 2, height / 2, 0xAAAAAA);
            return;
        }
        if (ignored.size() > MAX_ROWS) {
            guiGraphics.drawCenteredString(font,
                    Component.translatable("modreloader.gui.new_mods.more", ignored.size() - MAX_ROWS),
                    width / 2, TOP + MAX_ROWS * ROW_HEIGHT + 2, 0xAAAAAA);
        }
        MultiLineLabel hint = MultiLineLabel.create(font, width - 60,
                Component.translatable("modreloader.gui.ignored_hint"));
        hint.renderLeftAligned(guiGraphics, 30,
                TOP + Math.min(ignored.size(), MAX_ROWS) * ROW_HEIGHT + 14, 11, 0xCCCCCC);
    }

    /** Dev-only hook for the watcher GUI check. */
    public void loadFirstForTest() {
        if (!candidates.isEmpty()) {
            LoadController.load(candidates.get(0));
        }
    }

    /** Dev-only hook: current view is the ignored list. */
    public boolean isShowingIgnored() {
        return showingIgnored;
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
