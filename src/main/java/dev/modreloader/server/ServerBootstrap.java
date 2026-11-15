package dev.modreloader.server;

import dev.modreloader.ModReloader;
import dev.modreloader.command.ReloaderCommands;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.MixinProbe;
import dev.modreloader.watch.ModsFolderWatcher;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

import java.nio.file.Path;
import java.util.List;

/**
 * Server-side wiring: console commands and the triggers the client obtains from
 * its tick loop. A dedicated server has no UI, so detections are reported to the
 * log and acted on through {@code /modreloader add}.
 */
public final class ServerBootstrap {
    private static boolean installed;
    private static boolean started;

    private ServerBootstrap() {
    }

    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(ReloaderCommands::registerServer);
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> onServerStarted());
    }

    private static void onServerStarted() {
        if (started) {
            return;
        }
        started = true;
        ModReloader.startWatcher(ServerBootstrap::onCandidates);

        String autoAdd = System.getProperty("modreloader.autoAdd", "").trim();
        if (!autoAdd.isEmpty()) {
            HotAddEngine.Result result = HotAddEngine.add(Path.of(autoAdd));
            ModReloader.LOGGER.info("[ModReloader] autoAdd result: {}", result.message());
        }

        String probe = System.getProperty("modreloader.probe", "").trim();
        if (!probe.isEmpty()) {
            MixinProbe.probe(Path.of(probe)).forEach(ModReloader.LOGGER::info);
        }
    }

    private static void onCandidates(List<ModsFolderWatcher.Candidate> candidates) {
        for (ModsFolderWatcher.Candidate candidate : candidates) {
            ModReloader.LOGGER.info(
                    "[ModReloader] new mod detected on server: {} ({}); run /modreloader add {} "
                            + "in the console",
                    candidate.path().getFileName(), candidate.inspection().tier(), candidate.path());
        }
    }
}
