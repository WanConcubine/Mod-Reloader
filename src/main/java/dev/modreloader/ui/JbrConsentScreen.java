package dev.modreloader.ui;

import dev.modreloader.inspect.JbrSupport;
import dev.modreloader.launch.JbrProvisioner;
import dev.modreloader.launch.Relauncher;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Explicit consent gate before the runtime downloads / unpacks / relaunches.
 *
 * <p>The auto-provision path is intentionally powerful (network download, file
 * extraction, process restart). Showing the exact URL and pinned SHA-512 before
 * anything happens makes the behaviour auditable by the player and by store
 * reviewers; nothing is downloaded until "allow" is pressed.
 */
public final class JbrConsentScreen extends Screen {
    private final Screen parent;
    private ScrollableText body;
    private String status;
    private boolean downloadable;

    public JbrConsentScreen(Screen parent) {
        super(Component.translatable("modreloader.jbr_consent.title"));
        this.parent = parent;
        this.status = "";
    }

    @Override
    protected void init() {
        Optional<Path> existing = JbrSupport.installation();
        boolean reuse = existing.isPresent() && runnable(existing.get());
        Optional<JbrProvisioner.Asset> asset = JbrProvisioner.asset();
        downloadable = !reuse && asset.isPresent();

        Component text;
        if (reuse) {
            text = Component.translatable("modreloader.jbr_consent.existing",
                    existing.get().toString(), JbrProvisioner.releasePage());
        } else if (asset.isPresent()) {
            String target = minecraft == null ? "jbr-21.0.11"
                    : JbrProvisioner.installDir(minecraft.gameDirectory.toPath()).toString();
            text = Component.translatable("modreloader.jbr_consent.body",
                    JbrProvisioner.releasePage(), JbrProvisioner.downloadUrl(asset.get()),
                    asset.get().sha512(), target);
        } else {
            text = Component.translatable("modreloader.jbr_consent.no_asset",
                    JbrProvisioner.releasePage());
        }
        body = new ScrollableText(MultiLineLabel.create(font, width - 40, text),
                width, width / 2, 34, height - 106, 10, true);

        int y = height - 76;
        Button copyPage = Button.builder(Component.translatable("modreloader.jbr_consent.copy"),
                        pressed -> copyPage())
                .bounds(width / 2 - 165, y, 100, 20).build();
        addRenderableWidget(copyPage);
        Button copyAsset = Button.builder(Component.translatable("modreloader.jbr_consent.copy_asset"),
                        pressed -> copyAsset(asset))
                .bounds(width / 2 - 55, y, 100, 20).build();
        copyAsset.active = !reuse && asset.isPresent();
        addRenderableWidget(copyAsset);
        addRenderableWidget(Button.builder(Component.translatable("modreloader.jbr_consent.open"),
                        pressed -> Util.getPlatform().openUri(JbrProvisioner.releasePage()))
                .bounds(width / 2 + 55, y, 100, 20).build());

        Button allow = Button.builder(Component.translatable("modreloader.jbr_consent.allow"),
                        pressed -> allow())
                .bounds(width / 2 - 155, y + 24, 150, 20).build();
        allow.active = reuse || downloadable;
        addRenderableWidget(allow);
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.cancel"),
                        pressed -> onClose())
                .bounds(width / 2 + 5, y + 24, 150, 20).build());
    }

    private static boolean runnable(Path home) {
        Path java = home.resolve("bin").resolve(Relauncher.isWindows() ? "java.exe" : "java");
        return Relauncher.isRunnable(java);
    }

    private void copyPage() {
        if (minecraft == null) {
            return;
        }
        minecraft.keyboardHandler.setClipboard(JbrProvisioner.releasePage());
        status = Component.translatable("modreloader.jbr_consent.copied").getString();
    }

    private void copyAsset(Optional<JbrProvisioner.Asset> asset) {
        if (minecraft == null || asset.isEmpty()) {
            return;
        }
        minecraft.keyboardHandler.setClipboard(JbrProvisioner.downloadUrl(asset.get()));
        status = Component.translatable("modreloader.jbr_consent.copied_asset").getString();
    }

    private void allow() {
        if (parent instanceof JbrSetupScreen setup) {
            setup.startAfterConsent();
        } else {
            onClose();
        }
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 18, 0xFFFFFF);
        body.render(guiGraphics);
        if (!status.isEmpty()) {
            guiGraphics.drawCenteredString(font, Component.literal(status),
                    width / 2, height - 100, 0x88FF88);
        }
        if (!downloadable && !JbrSupport.installation().isPresent()) {
            guiGraphics.drawCenteredString(font,
                    Component.translatable("modreloader.jbr_consent.manual_hint"),
                    width / 2, height - 100, 0xFFAA55);
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