package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.reflect.UnsafeAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.CreativeTabsScreenPage;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only check for the sequence that was never automated:
 * <b>hot-add at the title screen → create a world → switch to creative → open the
 * creative inventory → the hot-added mod's own tab must be there, listed and
 * renderable</b>.
 *
 * <p>Why this is not the same as {@code -PverifyGameplay -PverifyCreative}: that
 * path adds the mod from inside a running world, where
 * {@code afterRegistryContent()} has a level and rebuilds every tab's contents
 * immediately. At the title screen there is no integrated server yet; the tabs
 * are only sorted and the content cache is invalidated, and the tab contents are
 * rebuilt later by the creative screen. That difference is exactly what a player
 * sees as "hot-loaded from the main menu, but the mod's creative tab is gone".
 *
 * <p>{@code -PverifyCreativeAtTitle=<jar>}, mod id
 * {@code -PverifyCreativeAtTitleModId} (default {@code exnihilosatellitium}).
 */
public final class CreativeTitleCheck {
    private enum Stage {
        WAIT_TITLE, WARM_CREATE_WORLD, WARM_PREPARE_CREATIVE, WARM_OPEN_SCREEN, WARM_RENDER,
        WARM_QUIT, WARM_WAIT_TITLE, ADD, WAIT_ADDED, WORLD,
        PREPARE_CREATIVE, OPEN_SCREEN, VERIFY, SELECTED, DONE
    }

    private static final int TITLE_SETTLE_TICKS = 100;
    private static final int TIMEOUT_TICKS = 20 * 240;

    private static final String MOD_ID = System.getProperty(
            "modreloader.verifyCreativeAtTitleModId", "exnihilosatellitium").trim();

    /**
     * -PverifyCreativeAtTitleWarm: first enter a world and open the creative
     * inventory once (so vanilla's cached ItemDisplayParameters is populated),
     * then go back to the title screen, hot-add, and enter a world again. This is
     * the sequence that was flagged as never measured.
     */
    private static final boolean WARM = Boolean.getBoolean("modreloader.verifyCreativeAtTitleWarm");

    /**
     * -PverifyCreativeAtTitleInstalled: the mod is already in mods/ (normal
     * startup). Skip the add entirely and only verify the tab — the control case
     * for "is this hot-add specific or does a normal install show it too".
     */
    private static final boolean INSTALLED =
            Boolean.getBoolean("modreloader.verifyCreativeAtTitleInstalled");

    private static Path jar;
    private static final TestWorld WARM_RUNNER = new TestWorld(1);
    private static final TestWorld WORLD_RUNNER = new TestWorld(1);
    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean creativeSet;
    private static boolean warmed;
    private static boolean warmCreativeSet;
    private static boolean pauseOpened;
    private static CreativeModeTab ownTab;

    private CreativeTitleCheck() {
    }

    public static void start(Path candidate) {
        if (started) {
            return;
        }
        jar = candidate.toAbsolutePath().normalize();
        started = true;
        ModReloader.LOGGER.info("[CreativeTitle] jar={} modId={}", jar, MOD_ID);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            fail("timeout at " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case WAIT_TITLE -> {
                if (ticks < TITLE_SETTLE_TICKS || mc.getOverlay() != null) {
                    return;
                }
                if (WARM) {
                    stage = Stage.WARM_CREATE_WORLD;
                } else if (INSTALLED) {
                    stage = Stage.WORLD;
                } else {
                    stage = Stage.ADD;
                }
                ticks = 0;
            }
            case WARM_CREATE_WORLD -> {
                TestWorld.Result result = WARM_RUNNER.tick(mc);
                if (result.error() != null) {
                    fail("warm-up: " + result.error());
                    return;
                }
                if (!result.ready()) {
                    return;
                }
                stage = Stage.WARM_PREPARE_CREATIVE;
                ticks = 0;
            }
            case WARM_PREPARE_CREATIVE -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) {
                    fail("warm-up: no integrated server");
                    return;
                }
                if (!warmCreativeSet) {
                    warmCreativeSet = true;
                    server.execute(() -> {
                        ServerPlayer player = server.getPlayerList().getPlayers().stream()
                                .findFirst().orElse(null);
                        if (player != null) {
                            player.setGameMode(GameType.CREATIVE);
                        }
                    });
                    return;
                }
                if (mc.player == null || !mc.player.isCreative()) {
                    if (ticks > 100) {
                        fail("warm-up: the player never became creative");
                    }
                    return;
                }
                stage = Stage.WARM_OPEN_SCREEN;
                ticks = 0;
            }
            case WARM_OPEN_SCREEN -> {
                try {
                    mc.setScreen(new CreativeModeInventoryScreen(
                            mc.player, mc.level.enabledFeatures(), true));
                } catch (Throwable t) {
                    fail("warm-up: opening the creative inventory threw " + t);
                    return;
                }
                stage = Stage.WARM_RENDER;
                ticks = 0;
            }
            case WARM_RENDER -> {
                if (ticks < 40) {
                    return;
                }
                ModReloader.LOGGER.info(
                        "[CreativeTitle] warm-up: creative inventory rendered; "
                                + "CACHED_PARAMETERS is now populated");
                stage = Stage.WARM_QUIT;
                ticks = 0;
            }
            case WARM_QUIT -> {
                // Exactly the player path: pause screen -> "Save and Quit to Title".
                if (!pauseOpened) {
                    pauseOpened = true;
                    mc.setScreen(new PauseScreen(true));
                    return;
                }
                AbstractButton quit = findButton(mc.screen, "menu.returnToMenu");
                if (quit == null) {
                    if (ticks > 100) {
                        fail("warm-up: the pause screen has no 'menu.returnToMenu' button (screen="
                                + (mc.screen == null ? "null" : mc.screen.getClass().getSimpleName()) + ")");
                    }
                    return;
                }
                quit.onPress();
                stage = Stage.WARM_WAIT_TITLE;
                ticks = 0;
            }
            case WARM_WAIT_TITLE -> {
                if (mc.level != null || mc.player != null) {
                    if (ticks > 20 * 60) {
                        fail("warm-up: still in a world after disconnect");
                    }
                    return;
                }
                warmed = true;
                ModReloader.LOGGER.info(
                        "[CreativeTitle] warm-up done (title screen, level={}, server={}); "
                                + "hot-adding now",
                        mc.level, ServerLifecycleHooks.getCurrentServer() == null ? "null" : "present");
                stage = Stage.ADD;
                ticks = 0;
            }
            case ADD -> {
                if (WARM) {
                    ModReloader.LOGGER.info(
                            "[CreativeTitle] at add: sortedTabs={} modTabsInSorted={} server={}",
                            CreativeModeTabRegistry.getSortedCreativeModeTabs().size(),
                            countSortedModTabs(),
                            ServerLifecycleHooks.getCurrentServer() == null ? "null" : "present");
                }
                HotAddEngine.Result result = HotAddEngine.add(jar);
                ModReloader.LOGGER.info("[CreativeTitle] title-screen add: ok={} stage={} kind={} msg={}",
                        result.ok(), result.stage(), result.kind(), result.message());
                if (!result.ok()) {
                    fail("hot-add at the title screen failed: " + result.kind() + " " + result.message());
                    return;
                }
                stage = Stage.WAIT_ADDED;
                ticks = 0;
            }
            case WAIT_ADDED -> {
                if (ticks < 40) {
                    return;
                }
                stage = Stage.WORLD;
                ticks = 0;
            }
            case WORLD -> {
                TestWorld.Result result = WORLD_RUNNER.tick(mc);
                if (result.error() != null) {
                    fail(result.error());
                    return;
                }
                if (!result.ready()) {
                    return;
                }
                stage = Stage.PREPARE_CREATIVE;
                ticks = 0;
            }
            case PREPARE_CREATIVE -> {
                MinecraftServer server = mc.getSingleplayerServer();
                if (server == null) {
                    fail("no integrated server after world load");
                    return;
                }
                if (!creativeSet) {
                    creativeSet = true;
                    server.execute(() -> {
                        ServerPlayer player = server.getPlayerList().getPlayers().stream()
                                .findFirst().orElse(null);
                        if (player != null) {
                            player.setGameMode(GameType.CREATIVE);
                        }
                    });
                    return;
                }
                if (mc.player == null || !mc.player.isCreative()) {
                    if (ticks > 100) {
                        fail("the player never became creative");
                    }
                    return;
                }
                stage = Stage.OPEN_SCREEN;
                ticks = 0;
            }
            case OPEN_SCREEN -> {
                try {
                    mc.setScreen(new CreativeModeInventoryScreen(
                            mc.player, mc.level.enabledFeatures(), true));
                } catch (Throwable t) {
                    fail("opening the creative inventory threw " + t);
                    return;
                }
                stage = Stage.VERIFY;
                ticks = 0;
            }
            case VERIFY -> {
                if (ticks < 40) {
                    return;
                }
                if (!verify(mc)) {
                    return;
                }
                stage = Stage.SELECTED;
                ticks = 0;
            }
            case SELECTED -> {
                if (ticks < 40) {
                    return;
                }
                if (!finishVerification(mc)) {
                    return;
                }
                stage = Stage.DONE;
            }
            case DONE -> {
                if (ticks >= 20) {
                    ModReloader.LOGGER.info("[CreativeTitle] RESULT: PASS");
                    started = false;
                    Minecraft.getInstance().stop();
                }
            }
        }
    }

    /** Looks for the mod's own tab, in the registry and in the screen's pages. */
    private static boolean verify(Minecraft mc) {
        List<String> tabNames = new ArrayList<>();
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            tabNames.add(tab.getDisplayName().getString());
        }
        ModReloader.LOGGER.info("[CreativeTitle] registry tabs ({}): {}", tabNames.size(), tabNames);

        List<CreativeModeTab> modTabs = new ArrayList<>();
        for (CreativeModeTab tab : CreativeModeTabs.allTabs()) {
            ResourceLocation key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (key != null && key.getNamespace().equals(MOD_ID)) {
                modTabs.add(tab);
            }
        }
        if (modTabs.isEmpty()) {
            fail("no creative tab with namespace '" + MOD_ID + "' is registered after a title-screen "
                    + "hot-add; the registry tabs are " + tabNames);
            return false;
        }
        CreativeModeTab best = null;
        int bestModItems = -1;
        for (CreativeModeTab tab : modTabs) {
            int count = countModItems(tab);
            ModReloader.LOGGER.info("[CreativeTitle] own tab candidate: {} items={} modItems={}",
                    BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab), tab.getDisplayItems().size(), count);
            if (count > bestModItems) {
                bestModItems = count;
                best = tab;
            }
        }
        if (best == null || bestModItems <= 0) {
            fail("the mod's creative tab is registered but lists no item from '" + MOD_ID
                    + "' after a title-screen hot-add; tab contents were not rebuilt");
            return false;
        }
        List<CreativeTabsScreenPage> pages = UnsafeAccess.get(
                mc.screen, CreativeModeInventoryScreen.class, "pages");
        int pageIndex = -1;
        if (pages != null) {
            for (int i = 0; i < pages.size(); i++) {
                if (pages.get(i).getVisibleTabs().contains(best)) {
                    pageIndex = i;
                    break;
                }
            }
        }
        if (pageIndex < 0) {
            fail("the mod's tab " + BuiltInRegistries.CREATIVE_MODE_TAB.getKey(best)
                    + " is not in any page of the creative screen (pages="
                    + (pages == null ? "null" : pages.size())
                    + "); no button would be drawn for it. Visible tabs: " + describePages(pages));
            return false;
        }
        ModReloader.LOGGER.info("[CreativeTitle] own tab {} is on page {}/{}",
                BuiltInRegistries.CREATIVE_MODE_TAB.getKey(best), pageIndex + 1, pages.size());
        // What a player actually does: the screen opens on page 0 and the mod's tab
        // may be on a later page. Press the real ">" page button until it is reachable.
        CreativeModeInventoryScreen screen = (CreativeModeInventoryScreen) mc.screen;
        CreativeTabsScreenPage current = screen.getCurrentPage();
        if (!current.getVisibleTabs().contains(best)) {
            ModReloader.LOGGER.info("[CreativeTitle] page 1 does not show the mod's tab ({}); "
                            + "pressing the page button",
                    pageDescriptions(null, current));
            boolean reached = false;
            for (int attempt = 0; attempt < pages.size() + 1; attempt++) {
                AbstractButton next = findPageButton(mc.screen, ">");
                if (next == null) {
                    fail("the creative screen has " + pages.size()
                            + " page(s) but no '>' button, so the mod's tab on page "
                            + (pageIndex + 1) + " cannot be reached");
                    return false;
                }
                next.onPress();
                CreativeTabsScreenPage now = screen.getCurrentPage();
                if (now != null && now.getVisibleTabs().contains(best)) {
                    reached = true;
                    break;
                }
            }
            if (!reached) {
                fail("pressing '>' never reached the page with the mod's tab (current page: "
                        + pageDescriptions(null, screen.getCurrentPage()) + ")");
                return false;
            }
            ModReloader.LOGGER.info("[CreativeTitle] mod's tab is now on the current page: {}",
                    pageDescriptions(null, screen.getCurrentPage()));
        }
        try {
            MethodHandle select = UnsafeAccess.trusted().findVirtual(
                    CreativeModeInventoryScreen.class, "selectTab",
                    java.lang.invoke.MethodType.methodType(void.class, CreativeModeTab.class));
            select.invoke(mc.screen, best);
        } catch (Throwable t) {
            fail("selecting the mod's tab threw " + t);
            return false;
        }
        ownTab = best;
        ModReloader.LOGGER.info("[CreativeTitle] selected own tab {} ('{}', {} items)",
                BuiltInRegistries.CREATIVE_MODE_TAB.getKey(best),
                best.getDisplayName().getString(), best.getDisplayItems().size());
        return true;
    }

    private static boolean finishVerification(Minecraft mc) {
        CreativeModeTab selected = UnsafeAccess.getStatic(
                CreativeModeInventoryScreen.class, "selectedTab");
        if (selected != ownTab) {
            fail("the creative screen did not switch to the mod's tab (selected="
                    + (selected == null ? "null" : BuiltInRegistries.CREATIVE_MODE_TAB.getKey(selected))
                    + ")");
            return false;
        }
        int menuItems = mc.screen instanceof CreativeModeInventoryScreen screen
                ? (int) screen.getMenu().getItems().stream().filter(stack -> !stack.isEmpty()).count()
                : -1;
        if (menuItems <= 0) {
            fail("the mod's tab shows no item in the menu (" + menuItems + ")");
            return false;
        }
        ModReloader.LOGGER.info(
                "[CreativeTitle] own tab rendered 40 ticks: {} tab items, {} in the menu",
                ownTab.getDisplayItems().size(), menuItems);
        return true;
    }

    private static int countModItems(CreativeModeTab tab) {
        int count = 0;
        for (ItemStack stack : tab.getDisplayItems()) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key != null && key.getNamespace().equals(MOD_ID)) {
                count++;
            }
        }
        return count;
    }

    private static String describePages(List<CreativeTabsScreenPage> pages) {
        if (pages == null) {
            return "null";
        }
        List<String> out = new ArrayList<>();
        for (CreativeTabsScreenPage page : pages) {
            List<String> names = new ArrayList<>();
            for (CreativeModeTab tab : page.getVisibleTabs()) {
                names.add(String.valueOf(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab)));
            }
            out.add(names.toString());
        }
        return out.toString();
    }

    private static int countSortedModTabs() {
        int count = 0;
        for (CreativeModeTab tab : CreativeModeTabRegistry.getSortedCreativeModeTabs()) {
            ResourceLocation key = BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab);
            if (key != null && key.getNamespace().equals(MOD_ID)) {
                count++;
            }
        }
        return count;
    }

    /** Tabs of a page, as registry keys (for log lines). */
    private static List<String> pageDescriptions(java.util.List<CreativeTabsScreenPage> pages,
                                                 CreativeTabsScreenPage page) {
        if (page == null) {
            return List.of("<null page>");
        }
        List<String> names = new ArrayList<>();
        for (CreativeModeTab tab : page.getVisibleTabs()) {
            names.add(String.valueOf(BuiltInRegistries.CREATIVE_MODE_TAB.getKey(tab)));
        }
        return names;
    }

    /** The ">" / "<" page buttons are literal Components, not i18n keys. */
    private static AbstractButton findPageButton(Screen screen, String label) {
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button && label.equals(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }

    private static AbstractButton findButton(Screen screen, String key) {
        String label = net.minecraft.client.resources.language.I18n.get(key);
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button && label.equals(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }

    private static void fail(String message) {
        ModReloader.LOGGER.error("[CreativeTitle] FAIL: {}", message);
        ModReloader.LOGGER.info("[CreativeTitle] RESULT: FAIL");
        started = false;
        Minecraft.getInstance().stop();
    }
}
