package dev.modreloader.server;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.PlatformHooks;
import dev.modreloader.hotadd.Rollback;
import net.minecraft.network.Connection;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;

import java.util.List;
import java.util.Set;

/**
 * Dedicated-server implementation of {@link PlatformHooks}: no client setup, no
 * resource packs, no client connection; commands go to the server dispatcher.
 */
public final class ServerPlatformHooks implements PlatformHooks {
    @Override
    public int keyMappingCount() {
        return -1;
    }

    @Override
    public void acceptClientLifecycle(ModContainer container) {
        // A dedicated server has no client lifecycle to replay.
    }

    @Override
    public void injectResourcePack(ModFile modFile, ModFileInfo fileInfo, Set<String> modIds,
                                   Rollback rollback) {
        ModReloader.LOGGER.info(
                "[ModReloader] server side: skipping resource pack injection for {}", modIds);
    }

    @Override
    public void replayCommands() {
        ServerCommands.replay();
    }

    @Override
    public List<Connection> extraConnections() {
        return List.of();
    }
}
