package dev.modreloader.server;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.DatapackStage;
import dev.modreloader.hotadd.PlatformHooks;
import dev.modreloader.hotadd.Rollback;
import net.minecraft.network.Connection;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.util.List;
import java.util.Map;
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

    @Override
    public void afterRegistryContent() {
        // No creative tabs on a dedicated server.
    }

    @Override
    public List<ServerPlayer> connectedPlayers() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? List.of() : List.copyOf(server.getPlayerList().getPlayers());
    }

    @Override
    public void requestRegistrySnapshot() {
        // The server owns the mapping; there is nobody to ask.
    }

    @Override
    public void acceptServerModNotice(List<dev.modreloader.hotadd.ServerModNoticePayload.Notice> notices) {
        // Notices travel server → client only.
        ModReloader.LOGGER.warn("[ModReloader] ignored {} server mod notice(s) from a client",
                notices.size());
    }

    @Override
    public void acceptModNoticeAck(String playerName, List<String> knownModIds) {
        ModReloader.LOGGER.info(
                "[ModReloader] {} has {} of the hot-added mod id(s) this runtime noticed; "
                        + "anything missing must be added on their client too",
                playerName, knownModIds);
    }

    @Override
    public void acceptRegistrySnapshot(Map<ResourceLocation, RegistrySnapshot> snapshots) {
        // A dedicated server has no registries to update from a client: the client
        // never sends this, and accepting it would let a client rewrite ids.
        ModReloader.LOGGER.warn(
                "[ModReloader] ignored a registry snapshot from a client ({} registries)",
                snapshots.size());
    }

    @Override
    public DatapackStage.Result reloadDataPacks(IModFileInfo fileInfo, Set<String> modIds) {
        return DatapackStage.apply(ServerLifecycleHooks.getCurrentServer(), fileInfo, modIds);
    }
}
