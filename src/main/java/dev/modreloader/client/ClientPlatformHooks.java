package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.DatapackStage;
import dev.modreloader.hotadd.PlatformHooks;
import dev.modreloader.hotadd.ModNoticeAckPayload;
import dev.modreloader.hotadd.RegistrySyncRequestPayload;
import dev.modreloader.hotadd.ServerModNoticePayload;
import net.neoforged.fml.ModList;
import dev.modreloader.hotadd.Rollback;
import dev.modreloader.ui.ModReloaderToasts;
import dev.modreloader.hotadd.UnsafeAccess;
import dev.modreloader.server.ServerCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceProvider;
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
import net.neoforged.neoforge.registries.RegistryManager;
import net.neoforged.neoforge.registries.RegistrySnapshot;
import net.neoforged.neoforgespi.language.IModFileInfo;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client implementation of {@link PlatformHooks}: key mappings, client setup,
 * the client-side registrations NeoForge only performs at startup (menu screens,
 * renderers, color handlers, client extensions), resource packs, the client half
 * of live connections, and command replay for the integrated server.
 */
public final class ClientPlatformHooks implements PlatformHooks {
    /** Reload listeners added by the current add; reset on every {@link #acceptClientLifecycle}. */
    private int lastReloadListeners;
    /** Whether the current add requested a resource reload (its shader pass fires the event). */
    private boolean lastReloadRequested;
    private static MethodHandle reloadShaders;

    @Override
    public void rebuildShaderInstances() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return;
        }
        try {
            if (reloadShaders == null) {
                // Package-private in 1.21.1; it closes the old programs and builds
                // every ShaderInstance through the (mixin-patched) constructor.
                reloadShaders = UnsafeAccess.trusted().findVirtual(
                        GameRenderer.class, "reloadShaders",
                        MethodType.methodType(void.class, ResourceProvider.class));
            }
            reloadShaders.invoke(minecraft.gameRenderer, minecraft.getResourceManager());
            ModReloader.LOGGER.info(
                    "[ModReloader] shader instances recreated after the mixin redefine");
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot recreate shader instances; rendering "
                    + "may crash until the game restarts", t);
        }
    }

    @Override
    public int keyMappingCount() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? 0 : minecraft.options.keyMappings.length;
    }

    @Override
    public void acceptClientLifecycle(ModContainer container) {
        DeferredWorkQueue queue = new DeferredWorkQueue(
                "modreloader-" + container.getModId() + "-client");
        lastReloadRequested = false;
        // Some mods call ItemBlockRenderTypes.setRenderLayer from client setup (or
        // a deferred task). NeoForge guards that with ClientModLoader.isLoading();
        // re-open the flag for the replay window, like the other internal toggles.
        boolean wasLoading = net.neoforged.neoforge.client.loading.ClientModLoader.isLoading();
        if (!wasLoading) {
            UnsafeAccess.setStaticBoolean(
                    net.neoforged.neoforge.client.loading.ClientModLoader.class, "loading", true);
        }
        try {
            container.acceptEvent(new FMLClientSetupEvent(container, queue));
            queue.runTasks();
        } finally {
            if (!wasLoading) {
                UnsafeAccess.setStaticBoolean(
                        net.neoforged.neoforge.client.loading.ClientModLoader.class, "loading", false);
            }
        }
        container.acceptEvent(new RegisterKeyMappingsEvent(Minecraft.getInstance().options));
        replayMenuScreens(container);
        // Kept for the resource-pack stage: a newly registered reload listener only
        // runs if a reload happens, so the pack stage may not skip the reload then.
        lastReloadListeners = ClientRegistrationReplay.replay(container);
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
    public void afterClientLifecycles(List<ModContainer> containers) {
        ClientRegistrationReplay.replayStaged(containers);
    }

    @Override
    public boolean injectResourcePacks(java.util.List<ModFileInfo> fileInfos, Set<String> modIds,
                                       Rollback rollback) {
        lastReloadRequested = ClientResourcePacks.injectAll(
                fileInfos, modIds, rollback, lastReloadListeners);
        return lastReloadRequested;
    }

    @Override
    public void afterHotAdd(java.util.List<ModContainer> containers) {
        if (lastReloadRequested) {
            // GameRenderer.reloadShaders runs as part of that reload and fires
            // RegisterShadersEvent for every container already in ModList, which
            // includes this hot-added one. Replaying it again would construct a
            // second ShaderInstance for the same name, put it over the live one
            // and leak the first.
            ModReloader.LOGGER.info(
                    "[ModReloader] shader replay skipped: the resource reload already ran "
                            + "RegisterShadersEvent for the hot-added mod");
            return;
        }
        ClientRegistrationReplay.enqueueShaderReplay(containers);
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
        if (minecraft == null) {
            return;
        }
        // NeoForge's sortTabs() appends HOTBAR/SEARCH/OP_BLOCKS/INVENTORY to
        // DEFAULT_TABS on every call without clearing it, so a second call (which
        // a hot-add needs to page the new tab) duplicates them 4 -> 8. The creative
        // screen maps the duplicates to columns 7/8 and overflows its 7 tab
        // sprites: CreativeModeInventoryScreen.renderTabButton -> AIOOBE.
        resetCreativeDefaultTabs();
        net.neoforged.neoforge.common.CreativeModeTabRegistry.sortTabs();

        if (minecraft.level == null || minecraft.player == null) {
            // Title-screen hot-add. The tab list is now correct, but the tab
            // *contents* are built by vanilla on demand - and `tryRebuildTabContents`
            // short-circuits while its cached ItemDisplayParameters still "match"
            // (enabledFeatures / permissions / registry access). A registry that grew
            // is invisible to that comparison, so the new items would never appear in
            // the creative inventory after entering the world. Dropping the cache
            // makes the first creative screen rebuild with real parameters.
            // Measured 2026-11-16: with the cache already absent (a session that has
            // never opened the creative inventory) vanilla's own first rebuild is
            // enough and this call changes nothing. It is kept as a guard for the
            // sequence that has NOT been measured: creative opened earlier in the
            // session (cache present) -> back to the title screen -> hot-add -> enter
            // the world, where `needsUpdate` would compare unchanged parameters and
            // skip the rebuild.
            invalidateCreativeTabCache();
            ModReloader.LOGGER.info(
                    "[ModReloader] creative tabs sorted at the title screen; contents will be rebuilt "
                            + "on the first creative inventory (cache invalidated)");
            return;
        }

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
     * Drops vanilla's cached {@code ItemDisplayParameters} so the next
     * {@code CreativeModeTabs.tryRebuildTabContents} actually rebuilds.
     *
     * <p>The cache compares enabled features, permissions and registry access -
     * none of which change when a registry grows, so without this the items of a
     * mod hot-added at the title screen never enter any tab.
     */
    private static void invalidateCreativeTabCache() {
        try {
            UnsafeAccess.setStatic(CreativeModeTabs.class, "CACHED_PARAMETERS", null);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] cannot invalidate the creative tab cache; the creative inventory "
                            + "may be missing the hot-added items until it rebuilds", t);
        }
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

    /**
     * Sends the snapshot through the integrated server, if one is running.
     *
     * <p>In single-player the host shares {@code BuiltInRegistries} with the
     * integrated server, so the host's own (memory) connection must be skipped —
     * NeoForge skips it during login sync for the same reason. LAN guests on the
     * same integrated server do need it, and they are the reason this is not a
     * no-op here.
     */
    @Override
    public List<ServerPlayer> connectedPlayers() {
        Minecraft minecraft = Minecraft.getInstance();
        MinecraftServer server = minecraft == null ? null : minecraft.getSingleplayerServer();
        if (server == null) {
            return List.of();
        }
        return server.getPlayerList().getPlayers().stream()
                .filter(player -> !player.connection.getConnection().isMemoryConnection())
                .toList();
    }

    /**
     * Applies the server's authoritative id mapping. This is the same call the
     * login-phase handler makes ({@code ClientPayloadHandler}), so the unfreeze /
     * clear / id-remap / freeze cycle and the block-state id rebuild that follows
     * from the bake callbacks are the ones NeoForge itself relies on.
     *
     * <p>If the client does not know every entry the server has, the result would
     * be a partial mapping — chunk sections and item stacks would decode to the
     * wrong entries. In that case the client is put back on its login snapshot and
     * the player is told to install the mod locally; it is not kicked, because a
     * mid-game disconnect is worse than staying in a consistent (old) state.
     */
    @Override
    public void requestRegistrySnapshot() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            ModReloader.LOGGER.info(
                    "[ModReloader] no server connection; the local registry mapping stays authoritative");
            return;
        }
        if (minecraft.getSingleplayerServer() != null) {
            // Single-player / LAN host: our own integrated server shares the
            // registries, so asking it would be asking ourselves.
            return;
        }
        minecraft.getConnection().send(RegistrySyncRequestPayload.INSTANCE);
        ModReloader.LOGGER.info("[ModReloader] asked the server for its registry mapping");
    }

    /**
     * A hot-add happened on the server. Say what it was and what the player can do:
     * a client that does not have the jar cannot see the content, and may even be
     * disconnected by a payload it cannot decode — the useful information is
     * "which mod, which version, same jar".
     */
    @Override
    public void acceptServerModNotice(List<ServerModNoticePayload.Notice> notices) {
        dev.modreloader.hotadd.ModNotice.recordReceived();
        Minecraft minecraft = Minecraft.getInstance();
        List<String> known = new ArrayList<>();
        for (ServerModNoticePayload.Notice notice : notices) {
            boolean present = ModList.get() != null && ModList.get().isLoaded(notice.modId());
            if (present) {
                known.add(notice.modId());
            }
            Component line = present
                    ? Component.translatable("modreloader.notice.present",
                            notice.displayName(), notice.version())
                    : Component.translatable("modreloader.notice.missing",
                            notice.displayName(), notice.version(), notice.jarName(), notice.hash());
            ModReloader.LOGGER.info("[ModReloader] server hot-added {} {} (jar {}, sha256:{}…, tier {}, "
                            + "registry content {}); this client {} it",
                    notice.modId(), notice.version(), notice.jarName(), notice.hash(),
                    notice.tier().isEmpty() ? "?" : notice.tier(), notice.registryContent(),
                    present ? "has" : "does NOT have");
            if (minecraft != null && minecraft.player != null) {
                minecraft.player.displayClientMessage(line, false);
            }
            ModReloaderToasts.info(
                    Component.translatable("modreloader.notice.toast.title"), line);
        }
        if (minecraft != null && minecraft.getConnection() != null) {
            minecraft.getConnection().send(new ModNoticeAckPayload(List.copyOf(known)));
        }
    }

    @Override
    public void acceptModNoticeAck(String playerName, List<String> knownModIds) {
        // Clients answer notices; a client receiving an ack is a protocol bug.
        ModReloader.LOGGER.warn("[ModReloader] ignored a mod-notice ack from {}", playerName);
    }

    @Override
    public void acceptRegistrySnapshot(Map<ResourceLocation, RegistrySnapshot> snapshots) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            // Both sides must hold the same entries: applying a snapshot that is a
            // subset leaves the client's extra entries without any id (-1), which
            // desyncs chunk sections just as badly as the opposite case.
            List<String> mismatch = keySetMismatch(snapshots);
            if (!mismatch.isEmpty()) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] refusing the server registry snapshot: the two sides hold "
                                + "different entries ({} of {} registries differ); hot-add the same "
                                + "jar on both sides first. Client state was not modified.",
                        mismatch.stream().limit(5).toList(), mismatch.size());
                message(minecraft, "modreloader.sync.mismatched");
                return;
            }
            Set<ResourceKey<?>> missing = RegistryManager.applySnapshot(snapshots, false);
            if (!missing.isEmpty()) {
                RegistryManager.revertToFrozen();
                ModReloader.LOGGER.warn(
                        "[ModReloader] server registry snapshot has {} entries this client does not "
                                + "know ({}); reverted to the login snapshot. Install the mod on the "
                                + "client and hot-add it there too.", missing.size(),
                        missing.stream().limit(8).map(Object::toString).toList());
                message(minecraft, "modreloader.sync.missing");
                return;
            }
            ModReloader.LOGGER.info("[ModReloader] applied registry snapshot from the server: "
                            + "{} registries, {} entries", snapshots.size(),
                    snapshots.values().stream().mapToInt(snapshot -> snapshot.getIds().size()).sum());
            // Ids changed under the running game: the creative tabs cache items by
            // identity but the display parameters went stale, and this is the same
            // refresh a local guifei-tier commit performs.
            afterRegistryContent();
            message(minecraft, "modreloader.sync.applied");
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] cannot apply the server registry snapshot", t);
            try {
                RegistryManager.revertToFrozen();
            } catch (Throwable revert) {
                ModReloader.LOGGER.error("[ModReloader] cannot revert to the login snapshot", revert);
            }
            message(minecraft, "modreloader.sync.failed");
        }
    }

    /**
     * Per-registry comparison of the client's key set against the snapshot's.
     *
     * @return human-readable differences (empty when they match)
     */
    private static List<String> keySetMismatch(Map<ResourceLocation, RegistrySnapshot> snapshots) {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<ResourceLocation, RegistrySnapshot> entry : snapshots.entrySet()) {
            ResourceLocation name = entry.getKey();
            net.minecraft.core.Registry<?> registry = BuiltInRegistries.REGISTRY.get(name);
            if (registry == null) {
                // A registry this client does not have at all, e.g. one a
                // server-side mod created; applying is pointless.
                if (!entry.getValue().getIds().isEmpty()) {
                    problems.add(name + ": registry unknown to this client");
                }
                continue;
            }
            // Registry#keySet returns names (not ResourceKeys) in 1.21.
            Set<ResourceLocation> local = new java.util.HashSet<>(registry.keySet());
            Set<ResourceLocation> theirs = new java.util.HashSet<>(entry.getValue().getIds().values());
            Set<ResourceLocation> onlyClient = new java.util.HashSet<>(local);
            onlyClient.removeAll(theirs);
            Set<ResourceLocation> onlyServer = new java.util.HashSet<>(theirs);
            onlyServer.removeAll(local);
            if (!onlyClient.isEmpty()) {
                problems.add(name + ": client has " + onlyClient.size() + " entries the server does "
                        + "not (" + onlyClient.stream().limit(3).toList() + ")");
            }
            if (!onlyServer.isEmpty()) {
                problems.add(name + ": server has " + onlyServer.size() + " entries the client does "
                        + "not (" + onlyServer.stream().limit(3).toList() + ")");
            }
        }
        return problems;
    }

    private static void message(Minecraft minecraft, String key) {
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(key), false);
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
