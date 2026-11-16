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
        String serverAddCommand = System.getProperty("modreloader.serverAddCommand", "").trim();
        if (!serverAddCommand.isEmpty()) {
            ServerAddCmdCheck.start(serverAddCommand);
            ModReloader.LOGGER.info("[ServerDev] serverAddCommand harness installed");
        }
    }
}
