package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.DatapackStage;
import dev.modreloader.hotadd.PlatformHooks;
import dev.modreloader.hotadd.Rollback;
import dev.modreloader.hotadd.UnsafeAccess;
import dev.modreloader.server.ServerCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.network.Connection;
import net.neoforged.fml.DeferredWorkQueue;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.moddiscovery.ModFile;
import net.neoforged.fml.loading.moddiscovery.ModFileInfo;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        replayMenuScreens(container);
    }

    /**
     * Menu screens are registered once at startup by {@code MenuScreens.init()}
     * through {@code RegisterMenuScreensEvent}. A hot-added mod's listener never
     * ran, so opening one of its GUIs logs
     * {@code Failed to create screen for menu type: ...} and nothing opens.
     *
     * <p>The real {@code SCREENS} map throws on duplicate registrations, and the
     * event is global, so replay it into a throwaway map (other mods re-register
     * harmlessly) and merge only the entries that are genuinely new.
     */
    @SuppressWarnings("unchecked")
    private static void replayMenuScreens(ModContainer container) {
        try {
            Map<MenuType<?>, MenuScreens.ScreenConstructor<?, ?>> fresh = new HashMap<>();
            container.acceptEvent(new RegisterMenuScreensEvent(fresh));
            Map<MenuType<?>, MenuScreens.ScreenConstructor<?, ?>> screens =
                    UnsafeAccess.getStatic(MenuScreens.class, "SCREENS");
            int added = 0;
            for (Map.Entry<MenuType<?>, MenuScreens.ScreenConstructor<?, ?>> entry : fresh.entrySet()) {
                if (screens.putIfAbsent(entry.getKey(), entry.getValue()) == null) {
                    added++;
                }
            }
            ModReloader.LOGGER.info("[ModReloader] menu screens replayed: {} new, {} total",
                    added, screens.size());
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] menu screen replay failed", t);
        }
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
        // NeoForge's sortTabs() appends HOTBAR/SEARCH/OP_BLOCKS/INVENTORY to
        // DEFAULT_TABS on every call without clearing it, so a second call (which
        // a hot-add needs to page the new tab) duplicates them 4 -> 8. The creative
        // screen maps the duplicates to columns 7/8 and overflows its 7 tab
        // sprites: CreativeModeInventoryScreen.renderTabButton -> AIOOBE.
        resetCreativeDefaultTabs();
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
        // The creative screen pages tabs 10 at a time and NeoForge maps the
        // always-visible tabs with "+5"; >10 tabs is exactly where the screen
        // has been seen to overflow its 7 tab sprites. Log the inputs.
        ModReloader.LOGGER.info(
                "[ModReloader] creative tabs: all={} defaults={} sorted={} ids={}",
                CreativeModeTabs.allTabs().size(),
                net.neoforged.neoforge.common.CreativeModeTabRegistry.getDefaultTabs().size(),
                net.neoforged.neoforge.common.CreativeModeTabRegistry.getSortedCreativeModeTabs().size(),
                CreativeModeTabs.allTabs().stream()
                        .map(tab -> tab.getDisplayName().getString()
                                + (tab.isAlignedRight() ? "/R" : "/L"))
                        .toList());
    }

    /**
     * Empties NeoForge's {@code DEFAULT_TABS} so {@code sortTabs()} is idempotent.
     * Only the 4 tabs it re-adds itself should be in there; a previous call in the
     * same session leaves duplicates that break the creative screen's tab paging.
     */
    private static void resetCreativeDefaultTabs() {
        try {
            Object defaults = UnsafeAccess.getStatic(
                    net.neoforged.neoforge.common.CreativeModeTabRegistry.class, "DEFAULT_TABS");
            UnsafeAccess.trusted()
                    .findVirtual(defaults.getClass(), "clear",
                            java.lang.invoke.MethodType.methodType(void.class))
                    .invoke(defaults);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot reset NeoForge's default creative tab list", t);
        }
    }

    @Override
    public DatapackStage.Result reloadDataPacks(IModFileInfo fileInfo, Set<String> modIds) {
        Minecraft minecraft = Minecraft.getInstance();
        // A client only has a server while a world is open. At the title screen
        // this is null and DatapackStage defers the pack to the next world start.
        return DatapackStage.apply(minecraft == null ? null : minecraft.getSingleplayerServer(),
                fileInfo, modIds);
    }
}
