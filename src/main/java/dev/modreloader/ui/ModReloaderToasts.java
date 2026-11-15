package dev.modreloader.ui;

import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Small, non-blocking notifications for detections and load results. */
public final class ModReloaderToasts {
    private static final SystemToast.SystemToastId ID = new SystemToast.SystemToastId(5000);

    private ModReloaderToasts() {
    }

    public static void detected(List<ModsFolderWatcher.Candidate> candidates) {
        if (candidates.isEmpty()) {
            return;
        }
        Component message = candidates.size() == 1
                ? Component.translatable("modreloader.toast.detected.one",
                candidates.get(0).path().getFileName().toString())
                : Component.translatable("modreloader.toast.detected.many", candidates.size());
        add(Component.translatable("modreloader.toast.detected.title"), message);
    }

    public static void success(Component name) {
        add(Component.translatable("modreloader.toast.loaded.title"),
                Component.translatable("modreloader.toast.loaded.message", name));
    }

    public static void failure(Component name) {
        add(Component.translatable("modreloader.toast.failed.title"),
                Component.translatable("modreloader.toast.failed.message", name));
    }

    public static void info(Component title, Component message) {
        add(title, message);
    }

    private static void add(Component title, Component message) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return;
        }
        SystemToast.add(minecraft.getToasts(), ID, title, message);
    }
}
