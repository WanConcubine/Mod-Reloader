package dev.modreloader.ui;

import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.client.JbrProvisioner;
import dev.modreloader.client.PendingAdd;
import dev.modreloader.client.Relauncher;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One-click JBR provisioning: download (official, SHA-512 verified), unpack next
 * to the game directory, then relaunch on it with the required JVM flags.
 *
 * <p>This is the "player does nothing technical" path. The manual guide is only
 * a secondary link for users who already have a runtime or cannot download.
 */
public final class JbrSetupScreen extends Screen {
    private final Screen parent;
    private final Path pendingJar;
    private volatile String status;
    private volatile String error;
    private volatile boolean running;
    private volatile boolean closed;
    private MultiLineLabel body;

    public JbrSetupScreen(Screen parent, Path pendingJar) {
        super(Component.translatable("modreloader.jbr_setup.title"));
        this.parent = parent;
        this.pendingJar = pendingJar;
        this.status = Component.translatable("modreloader.jbr_setup.idle").getString();
    }

    @Override
    protected void init() {
        String target = minecraft == null ? "jbr-21.0.11"
                : JbrProvisioner.installDir(minecraft.gameDirectory.toPath()).toString();
        body = MultiLineLabel.create(font, width - 40,
                Component.translatable("modreloader.jbr_setup.body", target));
        int y = height - 52;
        addRenderableWidget(Button.builder(Component.translatable("modreloader.jbr_setup.download"),
                        pressed -> start())
                .bounds(width / 2 - 160, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.jbr_setup.manual"),
                        pressed -> {
                            if (minecraft != null) {
                                minecraft.setScreen(new JbrGuideScreen(this));
                            }
                        })
                .bounds(width / 2 + 5, y, 150, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("modreloader.gui.close"),
                        pressed -> onClose())
                .bounds(width / 2 - 50, height - 28, 100, 20).build());
    }

    private void start() {
        if (running || minecraft == null) {
            return;
        }
        running = true;
        error = null;
        // One action, three states: a usable JBR (the running one included) is
        // reused directly, only a missing runtime is downloaded. The player should
        // not have to know where it came from.
        Optional<Path> existing = dev.modreloader.inspect.JbrSupport.installation();
        if (existing.isPresent()) {
            Path java = existing.get().resolve("bin").resolve(
                    Relauncher.isWindows() ? "java.exe" : "java");
            if (Relauncher.isRunnable(java)) {
                status = Component.translatable("modreloader.jbr_setup.found",
                        existing.get().toString()).getString();
                relaunch(java);
                return;
            }
        }
        status = Component.translatable("modreloader.jbr_setup.downloading").getString();
        Path gameDir = minecraft.gameDirectory.toPath();
        Thread worker = new Thread(() -> {
            try {
                Path home = JbrProvisioner.install(gameDir, message -> status = message);
                Path java = home.resolve("bin").resolve(
                        Relauncher.isWindows() ? "java.exe" : "java");
                if (!Relauncher.isRunnable(java)) {
                    throw new IllegalStateException("JBR 缺少 bin/java：" + java);
                }
                status = Component.translatable("modreloader.jbr_setup.ready").getString();
                Platform.runOnMainThread(() -> relaunch(java));
            } catch (Throwable t) {
                error = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                ModReloader.LOGGER.error("[ModReloader] JBR provisioning failed", t);
                running = false;
            }
        }, "modreloader-jbr-setup");
        worker.setDaemon(true);
        worker.start();
    }

    private void relaunch(Path java) {
        MinecraftAccess minecraft = new MinecraftAccess();
        if (closed || minecraft.get() == null) {
            running = false;
            return;
        }
        rememberPending(minecraft.get());
        Optional<String> failure = Relauncher.saveAndRestart(minecraft.get(),
                "provisioned JBR", java, Relauncher.jbrFlags(true));
        failure.ifPresent(message -> {
            running = false;
            error = message;
        });
    }

    private void rememberPending(net.minecraft.client.Minecraft minecraft) {
        if (pendingJar == null) {
            return;
        }
        if (ModReloader.WATCHER_CONFIG != null) {
            ModReloader.WATCHER_CONFIG.clearFailed(pendingJar);
        }
        PendingAdd.write(minecraft.gameDirectory.toPath(), pendingJar);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(font, title, width / 2, 24, 0xFFFFFF);
        body.renderCentered(guiGraphics, width / 2, 54, 10, 0xDDDDDD);
        int y = height - 76;
        if (error != null) {
            guiGraphics.drawWordWrap(font,
                    Component.translatable("modreloader.jbr_setup.failed", error),
                    20, y, width - 40, 0xFF8888);
        } else {
            guiGraphics.drawCenteredString(font, Component.literal(status), width / 2, y, 0xAAAAAA);
        }
    }

    @Override
    public void onClose() {
        closed = true;
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    /** Small indirection so the worker callback reads the current instance safely. */
    private static final class MinecraftAccess {
        net.minecraft.client.Minecraft get() {
            return net.minecraft.client.Minecraft.getInstance();
        }
    }
}
