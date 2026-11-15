package dev.modreloader.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;

import java.util.List;

/** Client-only hook so {@code ActiveConnections} stays server-safe. */
public final class ClientConnections {
    private ClientConnections() {
    }

    public static List<Connection> current() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) {
            return List.of();
        }
        ClientPacketListener listener = minecraft.getConnection();
        if (listener == null) {
            return List.of();
        }
        Connection connection = listener.getConnection();
        return connection == null ? List.of() : List.of(connection);
    }
}
