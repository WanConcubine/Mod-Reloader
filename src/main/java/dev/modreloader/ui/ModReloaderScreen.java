package dev.modreloader.ui;

import dev.modreloader.ModReloader;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Prompt for newly detected jars. It is intentionally simple: a short list of
 * candidates plus Load/Later/Ignore. T4/T5 never get an enabled Load button.
 */
public final class ModReloaderScreen extends Screen {
    private static final int MAX_ROWS = 7;

    private final List<ModsFolderWatcher.Candidate> candidates;
    private final List<Button> candidateButtons = new ArrayList<>();
    private int selected;
    private Button loadButton;

    public ModReloaderScreen(List<ModsFolderWatcher.Candidate> candidates) {
        super(Component.translatable("modreloader.gui.new_mods.title"));
        this.candidates = new ArrayList<>(candidates);
    }

    @Override
    protected void init() {
        candidateButtons.clear();
        int top = 44;
        int rows = Math.min(candidates.size(), MAX_ROWS);
        for (int i = 0; i < rows; i++) {
            final int index = i;
            Button button = Button.builder(label(i), pressed -> {
                selected = index;
                refresh();
            }).bounds(width / 2 - 160, top + i * 22, 320, 20).build();
            candidateButtons.add(button);
            addRenderableWidget(button);
        }

        int bottom = height - 52;
        loadButton = addRenderableWidget(Button.builder(
                        Component.translatable("modreloader.gui.load"), pressed -> loadSelected())
                .bounds(width / 2 - 160, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.later"),
                        pressed -> onClose())
                .bounds(width / 2 - 55, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.ignore"),
                        pressed -> ignoreSelected())
                .bounds(width / 2 + 50, bottom, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.close"),
                        pressed -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
        refresh();
    }

    private Component label(int index) {
        var candidate = candidates.get(index);
        String prefix = index == selected ? "▶ " : "  ";
        return Component.literal(prefix + "[" + candidate.inspection().tier() + "] "
                + candidate.path().getFileName());
    }

    private void refresh() {
        for (int i = 0; i < candidateButtons.size(); i++) {
            candidateButtons.get(i).setMessage(label(i));
        }
        if (loadButton != null) {
            boolean canLoad = !candidates.isEmpty() && selected >= 0
                    && selected < candidates.size()
                    && candidates.get(selected).inspection().canHotLoad();
            loadButton.active = canLoad;
            loadButton.setMessage(Component.translatable(canLoad
                    ? "modreloader.gui.load" : "modreloader.gui.load_disabled"));
        }
    }

    private void loadSelected() {
        if (selected >= 0 && selected < candidates.size()) {
            LoadController.load(candidates.get(selected));
        }
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
        onClose();
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 16, 0xFFFFFF);

        if (candidates.isEmpty()) {
            guiGraphics.drawCenteredString(font,
                    Component.translatable("modreloader.gui.new_mods.empty"),
                    width / 2, height / 2, 0xAAAAAA);
            return;
        }
        if (candidates.size() > MAX_ROWS) {
            guiGraphics.drawCenteredString(font,
                    Component.translatable("modreloader.gui.new_mods.more", candidates.size() - MAX_ROWS),
                    width / 2, 44 + MAX_ROWS * 22 + 2, 0xAAAAAA);
        }

        var candidate = candidates.get(Math.max(0, Math.min(selected, candidates.size() - 1)));
        MutableComponent text = ModReloaderI18n.summary(candidate.inspection()).copy();
        text.append(Component.literal("\n")).append(ModReloaderI18n.blockReason(candidate.inspection()));
        MultiLineLabel label = MultiLineLabel.create(font, width - 60, text);
        label.renderLeftAligned(guiGraphics, 30,
                44 + Math.min(candidates.size(), MAX_ROWS) * 22 + 18, 11, 0xCCCCCC);
    }

    /** Dev-only hook for the watcher GUI check. */
    public void loadFirstForTest() {
        if (!candidates.isEmpty()) {
            LoadController.load(candidates.get(0));
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(null);
        }
    }
}
