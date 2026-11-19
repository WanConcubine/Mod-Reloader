package dev.modreloader.dev;

import dev.modreloader.ModReloader;

/**
 * Dev-only entry point for <em>dedicated servers</em>.
 *
 * <p>Kept apart from {@link DevEntry}: that one installs client tick listeners and
 * UI checks, so loading it on a dedicated server would fail on the first client
 * class it touches. This entry only wires harnesses that are safe server-side.
 */
public final class ServerDev {
    private ServerDev() {
    }

    public static void install() {
        String mpRegistryServer = System.getProperty("modreloader.mpRegistryServer", "").trim();
        if (!mpRegistryServer.isEmpty()) {
            MpRegistryServerCheck.start(mpRegistryServer);
            ModReloader.LOGGER.info("[ServerDev] mpRegistryServer harness installed");
        }
        String mpNoticeServer = System.getProperty("modreloader.mpNoticeServer", "").trim();
        if (!mpNoticeServer.isEmpty()) {
            MpNoticeServerCheck.start(mpNoticeServer);
            ModReloader.LOGGER.info("[ServerDev] mpNoticeServer harness installed");
        }
        String mpFeiServer = System.getProperty("modreloader.mpFeiServer", "").trim();
        if (!mpFeiServer.isEmpty()) {
            MpFeiServerCheck.start(mpFeiServer);
            ModReloader.LOGGER.info("[ServerDev] mpFeiServer harness installed");
        }
        String serverWhy = System.getProperty("modreloader.serverWhy", "").trim();
        if (!serverWhy.isEmpty()) {
            ServerWhyCheck.start(serverWhy);
            ModReloader.LOGGER.info("[ServerDev] serverWhy harness installed");
        }
        String serverRunState = System.getProperty("modreloader.serverRunState", "").trim();
        if (!serverRunState.isEmpty()) {
            RunStateCheck.startServer(serverRunState);
            ModReloader.LOGGER.info("[ServerDev] serverRunState harness installed");
        }
        String serverAddCommand = System.getProperty("modreloader.serverAddCommand", "").trim();
        if (!serverAddCommand.isEmpty()) {
            ServerAddCmdCheck.start(serverAddCommand);
            ModReloader.LOGGER.info("[ServerDev] serverAddCommand harness installed");
        }
    }
}
