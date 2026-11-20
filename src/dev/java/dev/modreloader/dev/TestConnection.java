package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/**
 * One way to join a dedicated server from a harness.
 *
 * <p>The multiplayer harnesses (payload, registry sync, fei-tier, notice) each
 * carried their own copy of "parse address → start connecting", with only the
 * log tag and profile name differing.
 */
public final class TestConnection {
    private TestConnection() {
    }

    /**
     * @return false when the connection could not be started (already logged)
     */
    public static boolean connect(Minecraft mc, String tag, String address, String profileName) {
        try {
            ServerData data = new ServerData(profileName, address, ServerData.Type.OTHER);
            ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address), data,
                    false, null);
            ModReloader.LOGGER.info("[{}] connecting to {}", tag, address);
            return true;
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[{}] connect failed", tag, t);
            return false;
        }
    }

    public static boolean connected(Minecraft mc) {
        return mc.getConnection() != null && mc.player != null;
    }
}