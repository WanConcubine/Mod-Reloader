package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.PlatformHooks;
import dev.modreloader.hotadd.Rollback;
import dev.modreloader.server.ServerCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.network.Connection;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import java.util.List;
import java.util.Set;

/**
 * Client implementation of {@link PlatformHooks}: key mappings, client setup,
 * resource packs, the client half of live connections, and command replay for
 * the integrated server.
 */
public final class ClientPlatformHooks implements PlatformHooks {
    @Override
    public int keyMappingCount() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? 0 : minecraft.options.keyMappings.length;
    }

    @Override
    public void acceptClientLifecycle(ModContainer container) {
        DeferredWorkQueue queue = new DeferredWorkQueue(
                "modreloader-" + container.getModId() + "-client");
        container.acceptEvent(new FMLClientSetupEvent(container, queue));
        queue.runTasks();
        container.acceptEvent(new RegisterKeyMappingsEvent(Minecraft.getInstance().options));
    }

    @Override
    public void injectResourcePack(ModFile modFile, ModFileInfo fileInfo, Set<String> modIds,
                                   Rollback rollback) {
        ClientResourcePacks.inject(modFile, fileInfo, modIds, rollback);
    }

    @Override
    public void replayCommands() {
        if (!ServerCommands.available()) {
            // Title screen / no world yet: there is no integrated server to
            // register into. Quiet on purpose — this is the normal hot-add case.
            ModReloader.LOGGER.info(
                    "[ModReloader] no integrated server yet; commands of the hot-added mod are not registered");
            return;
        }
        ServerCommands.replay();
    }

    @Override
    public List<Connection> extraConnections() {
        return ClientConnections.current();
    }

    @Override
    public void afterRegistryContent() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null || minecraft.player == null) {
            // At the title screen the tabs are built later, when the player opens
            // the creative inventory; nothing is stale yet.
            return;
        }
        net.neoforged.neoforge.common.CreativeModeTabRegistry.sortTabs();
        CreativeModeTab.ItemDisplayParameters parameters =
                new CreativeModeTab.ItemDisplayParameters(
                        minecraft.level.enabledFeatures(),
                        minecraft.player.hasPermissions(2),
                        minecraft.level.registryAccess());
        int rebuilt = 0;
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            tab.buildContents(parameters);
            rebuilt++;
        }
        ModReloader.LOGGER.info(
                "[ModReloader] rebuilt {} creative tab(s) after registry content", rebuilt);
    }
}
