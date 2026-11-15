package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.client.ClientConnections;
import net.minecraft.network.Connection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.List;

/**
 * Every live connection of this process: the integrated/dedicated server's
 * players plus, on a client, the connection to the server.
 *
 * <p>Both sides live in the same runtime, so both copies of a connection's
 * negotiated {@code PayloadSetup} can be patched — which is what makes a
 * late-registered payload usable.
 */
public final class ActiveConnections {
    private ActiveConnections() {
    }

    public static List<Connection> all() {
        List<Connection> connections = new ArrayList<>();
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    if (player.connection != null && player.connection.getConnection() != null) {
                        connections.add(player.connection.getConnection());
                    }
                }
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot list server connections", t);
        }
        if (Platform.isClient()) {
            try {
                connections.addAll(ClientConnections.current());
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] cannot list the client connection", t);
            }
        }
        return connections;
    }
}
