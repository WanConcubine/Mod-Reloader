package dev.modreloader.ui;

import dev.modreloader.ModReloader;
import dev.modreloader.diagnose.FailureAnalyzer;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Single-flight bridge between the prompt UI and {@link HotAddEngine}. */
public final class LoadController {
    private static boolean loading;

    private LoadController() {
    }

    public static boolean isLoading() {
        return loading;
    }

    public static void load(ModsFolderWatcher.Candidate candidate) {
        Minecraft minecraft = Minecraft.getInstance();
        if (loading) {
            ModReloaderToasts.info(Component.translatable("modreloader.toast.loading.title"),
                    Component.translatable("modreloader.toast.loading.message"));
            return;
        }
        var inspection = candidate.inspection();
        if (!inspection.canHotLoad()) {
            minecraft.setScreen(new LoadFailureScreen(null, candidate,
                    FailureAnalyzer.preflight(inspection)));
            return;
        }

        loading = true;
        try {
            ModReloader.LOGGER.info("[ModReloader] UI loading {}", candidate.path());
            minecraft.setScreen(null);
            HotAddEngine.Result result = HotAddEngine.add(candidate.path());
            if (result.ok()) {
                ModReloaderToasts.success(Component.literal(candidate.path().getFileName().toString()));
                ModReloader.LOGGER.info("[ModReloader] UI load succeeded: {}", result.message());
            } else {
                // A file that vanished between detection and Load cannot be retried.
                if (result.kind() != HotAddEngine.FailureKind.MISSING_FILE
                        && ModReloader.WATCHER_CONFIG != null) {
                    ModReloader.WATCHER_CONFIG.markFailed(candidate.path(), candidate.size(),
                            candidate.mtime(), result.kind().name(), result.message());
                }
                ModReloaderToasts.failure(Component.literal(candidate.path().getFileName().toString()));
                minecraft.setScreen(new LoadFailureScreen(null, candidate,
                        FailureAnalyzer.analyze(result, inspection)));
            }
        } finally {
            loading = false;
        }
    }
}
