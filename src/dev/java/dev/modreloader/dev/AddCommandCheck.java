package dev.modreloader.dev;

import com.mojang.brigadier.CommandDispatcher;
import dev.modreloader.ModReloader;
import dev.modreloader.command.ReloaderCommands;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.UnsafeAccess;
import dev.modreloader.ui.ModReloaderScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dev-only end-to-end check for the in-world {@code /modreloader add} command.
 *
 * <p>The command dispatcher only exists once the client is connected, which is
 * why this used to be a manual step. This harness creates a world through the
 * real create-world screen, then executes the command against the captured
 * client dispatcher. It deliberately skips the chat packet round-trip: the goal
 * is the command path (registration → parsing → {@code HotAddEngine}), not
 * Minecraft's chat UI.
 *
 * <p>Enabled with {@code -PverifyAddCommand=<jar>}.
 */
public final class AddCommandCheck {
    private enum Stage {
        OPEN_CREATE, WAIT_CREATE_SCREEN, WAIT_WORLD, RUN_COMMAND, VERIFY,
        WATCH_COPY, WATCH_WAIT, WATCH_VERIFY, DONE
    }

    private static final int TIMEOUT_TICKS = 20 * 300;
    private static final int WORLD_SETTLE_TICKS = 10;

    private static Stage stage = Stage.OPEN_CREATE;
    private static int ticks;
    private static int totalTicks;
    private static int worldTicks;
    private static boolean started;
    private static boolean pressedCreate;
    private static boolean titleAdded;
    private static boolean failed;

    /** Mod the command is expected to add (or to reject when {@link #expectFailure}). */
    private static final String EXPECT_MOD_ID =
            System.getProperty("modreloader.verifyAddCommandModId", "easylan").trim();
    private static final boolean EXPECT_FAILURE =
            Boolean.getBoolean("modreloader.verifyAddCommandExpectFailure");

    /**
     * Optional second phase: drop this jar into the inbox while already in world
     * and assert the toast-only path (no prompt screen).
     */
    private static final String WATCH_JAR =
            System.getProperty("modreloader.verifyAddCommandWatchJar", "").trim();

    /**
     * Optional datapack phase ({@code -PverifyDatapack=true}): proves the mod's
     * {@code data/} is live in the running world, not merely committed to the
     * registry — recipes, loot tables and tags are read back from the server.
     */
    private static final boolean VERIFY_DATAPACK = Boolean.getBoolean("modreloader.verifyDatapack");

    /**
     * Title-screen variant ({@code -PverifyDatapackAtTitle=true}): hot-add before
     * a world exists, exactly like the main-menu prompt / inbox watcher does.
     * The data pack must then be applied by the deferred world-start hook, not by
     * the in-world path.
     */
    private static final boolean TITLE_ADD = Boolean.getBoolean("modreloader.verifyDatapackAtTitle");

    /** Recipes in the mod namespace measured before the add; -1 when not measured. */
    private static long recipesBefore = -1;

    private AddCommandCheck() {
    }

    public static void start(Path jar) {
        if (started) {
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[AddCmdCheck] started; will run /modreloader add {}", jar);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick(jar));
    }

    private static void tick(Path jar) {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            fail("timeout after " + TIMEOUT_TICKS / 20 + "s at stage " + stage);
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        switch (stage) {
            case OPEN_CREATE -> openCreateScreen(mc, jar);
            case WAIT_CREATE_SCREEN -> waitForCreateScreen(mc);
            case WAIT_WORLD -> waitForWorld(mc);
            case RUN_COMMAND -> runCommand(mc, jar);
            case VERIFY -> verify(mc, jar);
            case WATCH_COPY -> watchCopy(mc);
            case WATCH_WAIT -> watchWait(mc);
            case WATCH_VERIFY -> watchVerify(mc);
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void openCreateScreen(Minecraft mc, Path jar) {
        if (mc.getOverlay() != null) {
            if (ticks > 20 * 30) {
                fail("resource reload overlay did not clear");
            }
            return;
        }
        if (TITLE_ADD && !titleAdded) {
            titleAdded = true;
            // Same path as the main-menu prompt: no server exists yet, so the
            // data pack has to be deferred to the world start.
            ModReloader.LOGGER.info("[AddCmdCheck] title-screen add: {}", jar);
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[AddCmdCheck] title-screen add result: {}", result.message());
            recipesBefore = 0;
            if (!result.ok()) {
                fail("title-screen add failed: " + result.message());
            }
            // Wait at least one tick so the resource reload overlay installed by
            // the add is visible to the check below.
            return;
        }
        if (TITLE_ADD && mc.getOverlay() != null) {
            // The add starts a client resource reload; creating a world while its
            // overlay is up deadlocks Minecraft.doWorldLoad's wait loop. A human
            // cannot click through the overlay either, so waiting is faithful.
            if (ticks > 20 * 120) {
                fail("resource reload overlay did not clear after the title-screen add");
            }
            return;
        }
        try {
            CreateWorldScreen.openFresh(mc, mc.screen);
            stage = Stage.WAIT_CREATE_SCREEN;
            ticks = 0;
        } catch (Throwable t) {
            fail("CreateWorldScreen.openFresh failed: " + t);
        }
    }

    private static void waitForCreateScreen(Minecraft mc) {
        if (!(mc.screen instanceof CreateWorldScreen screen)) {
            if (ticks > 20 * 30) {
                fail("create-world screen did not appear; current screen="
                        + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
            }
            return;
        }
        if (pressedCreate) {
            return;
        }
        AbstractButton button = findButton(screen, "selectWorld.create");
        if (button == null) {
            return;
        }
        pressedCreate = true;
        ModReloader.LOGGER.info("[AddCmdCheck] pressing '{}'", button.getMessage().getString());
        button.onPress();
        stage = Stage.WAIT_WORLD;
        ticks = 0;
        worldTicks = 0;
    }

    private static void waitForWorld(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            worldTicks = 0;
            if (ticks > 20 * 120) {
                fail("world did not load; screen="
                        + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
            }
            return;
        }
        worldTicks++;
        if (worldTicks < WORLD_SETTLE_TICKS) {
            return;
        }
        ModReloader.LOGGER.info("[AddCmdCheck] in world: {}", mc.level.dimension().location());
        stage = Stage.RUN_COMMAND;
        ticks = 0;
    }

    private static void runCommand(Minecraft mc, Path jar) {
        if (TITLE_ADD) {
            // The mod was added at the title screen; re-adding would be rejected
            // as ALREADY_LOADED. Go straight to the verification.
            ModReloader.LOGGER.info("[AddCmdCheck] title-screen mode: /modreloader add is skipped");
            stage = Stage.VERIFY;
            ticks = 0;
            return;
        }
        CommandDispatcher<CommandSourceStack> dispatcher = ReloaderCommands.dispatcher();
        if (dispatcher == null) {
            fail("client command dispatcher was never captured");
            return;
        }
        ModReloader.LOGGER.info("[AddCmdCheck] dispatcher has /modreloader={}",
                dispatcher.getRoot().getChild("modreloader") != null);
        if (VERIFY_DATAPACK) {
            MinecraftServer server = mc.getSingleplayerServer();
            recipesBefore = server == null ? -1 : recipesInNamespace(server, EXPECT_MOD_ID);
            ModReloader.LOGGER.info("[AddCmdCheck] datapack baseline before add: "
                    + "recipes in {} = {} (a fresh world has none)", EXPECT_MOD_ID, recipesBefore);
        }
        String path = jar.toAbsolutePath().normalize().toString();
        String command = "modreloader add " + (path.contains(" ") ? "\"" + path + "\"" : path);
        try {
            CommandSourceStack source = mc.player.createCommandSourceStack();
            int result = dispatcher.execute(command, source);
            ModReloader.LOGGER.info("[AddCmdCheck] /{} returned {}", command, result);
        } catch (Throwable t) {
            fail("executing /" + command + " threw " + t);
            return;
        }
        stage = Stage.VERIFY;
        ticks = 0;
    }

    private static void verify(Minecraft mc, Path jar) {
        if (ticks < 20) {
            return;
        }
        // The command reports through chat + log; the state we assert is ModList.
        boolean loaded = ModList.get().isLoaded(EXPECT_MOD_ID);
        boolean container = ModList.get().getModContainerById(EXPECT_MOD_ID).isPresent();
        ModReloader.LOGGER.info("[AddCmdCheck] ModList {}: isLoaded={}, container={}",
                EXPECT_MOD_ID, loaded, container);
        if (EXPECT_FAILURE) {
            if (loaded || container) {
                fail("expected " + EXPECT_MOD_ID + " to be rejected, but isLoaded=" + loaded
                        + ", container=" + container);
                return;
            }
            ModReloader.LOGGER.info("[AddCmdCheck] rejection confirmed; see the hot-add error above");
            ModReloader.LOGGER.info("[AddCmdCheck] RESULT: PASS");
            advanceAfterVerify();
            return;
        }
        if (!loaded || !container) {
            fail("command did not load " + EXPECT_MOD_ID + " (isLoaded=" + loaded
                    + ", container=" + container + ")");
            return;
        }
        if (VERIFY_DATAPACK && !verifyDatapack(mc)) {
            return;
        }
        ModReloader.LOGGER.info("[AddCmdCheck] RESULT: PASS");
        advanceAfterVerify();
    }

    /**
     * Reads the mod's data back from the running server: recipe manager, loot
     * table registry, block tags, plus two behavioural checks (a crafting grid
     * actually assembles an Ex Nihilo item; the block's loot table drops it).
     */
    private static boolean verifyDatapack(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("datapack check: the integrated server is gone");
            return false;
        }
        long recipes = recipesInNamespace(server, EXPECT_MOD_ID);
        boolean crucibleRecipe = server.getRecipeManager().byKey(id("wooden_crucible")).isPresent();
        ModReloader.LOGGER.info("[AddCmdCheck] datapack recipes: {} -> {} in namespace {}; "
                        + "{}:wooden_crucible={}",
                recipesBefore, recipes, EXPECT_MOD_ID, EXPECT_MOD_ID, crucibleRecipe);
        if (recipes <= Math.max(0, recipesBefore)) {
            fail("the data pack added no recipes for " + EXPECT_MOD_ID
                    + " (before=" + recipesBefore + ", after=" + recipes + ")");
            return false;
        }
        if (!crucibleRecipe) {
            fail(EXPECT_MOD_ID + ":wooden_crucible recipe is not in the recipe manager");
            return false;
        }

        long lootTables = server.reloadableRegistries().getKeys(Registries.LOOT_TABLE).stream()
                .filter(key -> EXPECT_MOD_ID.equals(key.getNamespace()))
                .count();
        boolean crucibleLoot = server.reloadableRegistries().getKeys(Registries.LOOT_TABLE)
                .contains(id("blocks/wooden_crucible"));
        ModReloader.LOGGER.info("[AddCmdCheck] datapack loot tables: {} in namespace {}; "
                        + "{}:blocks/wooden_crucible={}",
                lootTables, EXPECT_MOD_ID, EXPECT_MOD_ID, crucibleLoot);
        if (!crucibleLoot) {
            fail(EXPECT_MOD_ID + ":blocks/wooden_crucible loot table is not loaded");
            return false;
        }

        TagKey<Block> tagKey = TagKey.create(Registries.BLOCK, id("wooden_crucibles"));
        var tag = server.reloadableRegistries().get()
                .registryOrThrow(Registries.BLOCK)
                .getTag(tagKey);
        boolean tagLoaded = tag.map(set -> set.size() > 0).orElse(false);
        ModReloader.LOGGER.info("[AddCmdCheck] datapack block tag #{}: present={}, entries={}",
                tagKey.location(), tag.isPresent(), tag.map(set -> set.size()).orElse(0));
        if (!tagLoaded) {
            fail("block tag #" + tagKey.location() + " is not loaded");
            return false;
        }

        boolean crafted = craftsWoodenCrucible(server);
        if (!crafted) {
            fail("a real 3x3 grid of oak logs + oak slab does not assemble "
                    + EXPECT_MOD_ID + ":wooden_crucible");
            return false;
        }

        List<ItemStack> drops = woodenCrucibleDrops(server, mc.player.blockPosition());
        boolean dropsItem = drops.stream().anyMatch(stack ->
                id("wooden_crucible").equals(BuiltInRegistries.ITEM.getKey(stack.getItem())));
        ModReloader.LOGGER.info("[AddCmdCheck] datapack loot drop: {} -> {}", EXPECT_MOD_ID + ":wooden_crucible",
                drops.stream().map(stack -> BuiltInRegistries.ITEM.getKey(stack.getItem())
                        + " x" + stack.getCount()).toList());
        if (!dropsItem) {
            fail("the loot table of " + EXPECT_MOD_ID + ":wooden_crucible dropped nothing"
                    + " (drops=" + drops.size() + "); the table was not applied");
            return false;
        }

        ModReloader.LOGGER.info("[AddCmdCheck] datapack RESULT: PASS "
                + "(recipes/loot tables/tags/crafting/drops)");
        return true;
    }

    private static boolean craftsWoodenCrucible(MinecraftServer server) {
        List<ItemStack> grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        for (int index : new int[]{0, 2, 3, 5, 6, 8}) {
            grid.set(index, new ItemStack(Items.OAK_LOG));
        }
        grid.set(7, new ItemStack(Items.OAK_SLAB));
        CraftingInput input = CraftingInput.of(3, 3, grid);
        var match = server.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, server.overworld());
        ModReloader.LOGGER.info("[AddCmdCheck] datapack craft: 6x oak_log + oak_slab -> {}",
                match.map(holder -> holder.id() + " = "
                                + holder.value().getResultItem(server.registryAccess()).getItem())
                        .orElse("no recipe matched"));
        return match.isPresent() && id("wooden_crucible").equals(match.get().id());
    }

    private static List<ItemStack> woodenCrucibleDrops(MinecraftServer server, BlockPos near) {
        ServerLevel level = server.overworld();
        Block block = BuiltInRegistries.BLOCK.get(id("wooden_crucible"));
        if (block == Blocks.AIR) {
            return List.of();
        }
        BlockState state = block.defaultBlockState();
        // Reading the drop does not touch the world: Block.getDrops resolves the
        // block's loot table through server.reloadableRegistries().
        return Block.getDrops(state, level, near, null, null, new ItemStack(Items.IRON_PICKAXE));
    }

    private static long recipesInNamespace(MinecraftServer server, String namespace) {
        return server.getRecipeManager().getRecipeIds()
                .filter(key -> namespace.equals(key.getNamespace()))
                .count();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(EXPECT_MOD_ID, path);
    }

    private static void advanceAfterVerify() {
        ticks = 0;
        if (Boolean.getBoolean("modreloader.verifyGameplay")) {
            // Hand the client over to the gameplay phase; it drives the server
            // itself and stops the client when done.
            GameplayCheck.start(EXPECT_MOD_ID);
            started = false;
            return;
        }
        stage = WATCH_JAR.isEmpty() ? Stage.DONE : Stage.WATCH_COPY;
    }

    /** Drops a jar into the inbox while already in world; the watcher must toast, not pop a screen. */
    private static void watchCopy(Minecraft mc) {
        if (ticks < 20) {
            return;
        }
        try {
            Path inbox = ModReloader.WATCHER == null ? null : ModReloader.WATCHER.inboxDir();
            if (inbox == null) {
                fail("watcher not started; cannot test the in-world path");
                return;
            }
            Files.createDirectories(inbox);
            Path src = Path.of(WATCH_JAR);
            Path dst = inbox.resolve("worldtoast-" + src.getFileName());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            ModReloader.LOGGER.info("[AddCmdCheck] copied {} into inbox as {}",
                    src.getFileName(), dst.getFileName());
            ModReloader.LOGGER.info("[AddCmdCheck] toastOnDetect={}, openPromptInMainMenu={}",
                    ModReloader.WATCHER_CONFIG.toastOnDetect,
                    ModReloader.WATCHER_CONFIG.openPromptInMainMenu);
        } catch (Throwable t) {
            fail("copy into inbox failed: " + t);
            return;
        }
        stage = Stage.WATCH_WAIT;
        ticks = 0;
    }

    private static void watchWait(Minecraft mc) {
        int count = detectedToasts(mc);
        if (count < 0) {
            fail("cannot read the toast queue; refusing to guess");
            return;
        }
        if (count > 0) {
            ModReloader.LOGGER.info("[AddCmdCheck] detection toast queued/visible: {}", count);
            stage = Stage.WATCH_VERIFY;
            ticks = 0;
            return;
        }
        if (ticks > 20 * 45) {
            fail("no detection toast within 45s after dropping the jar into inbox");
        }
    }

    private static void watchVerify(Minecraft mc) {
        boolean popup = mc.screen instanceof ModReloaderScreen;
        ModReloader.LOGGER.info("[AddCmdCheck] in-world detection: screen={}, promptOpened={}",
                mc.screen == null ? "null" : mc.screen.getClass().getSimpleName(), popup);
        if (popup) {
            fail("prompt screen opened in world; expected toast only");
            return;
        }
        ModReloader.LOGGER.info("[AddCmdCheck] in-world RESULT: PASS (toast only, no prompt)");
        stage = Stage.DONE;
        ticks = 0;
    }

    /**
     * Counts our detection toasts in {@code visible} + {@code queued}.
     *
     * @return count, or -1 when the queue cannot be inspected (so the caller can
     *         fail instead of silently passing)
     */
    private static int detectedToasts(Minecraft mc) {
        String expected = I18n.get("modreloader.toast.detected.title");
        try {
            Object component = mc.getToasts();
            int count = 0;
            Object visible = UnsafeAccess.get(component, component.getClass(), "visible");
            if (visible instanceof Iterable<?> instances) {
                for (Object instance : instances) {
                    // ToastInstance is package-private: getToast() is public but its
                    // declaring class is not, so module access denies reflection.
                    Object toast = UnsafeAccess.get(instance, instance.getClass(), "toast");
                    if (isDetectionToast(toast, expected)) {
                        count++;
                    }
                }
            }
            Object queued = UnsafeAccess.get(component, component.getClass(), "queued");
            if (queued instanceof Iterable<?> toasts) {
                for (Object toast : toasts) {
                    if (isDetectionToast(toast, expected)) {
                        count++;
                    }
                }
            }
            return count;
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[AddCmdCheck] cannot inspect toast queue", t);
            return -1;
        }
    }

    private static boolean isDetectionToast(Object toast, String expectedTitle) {
        if (!(toast instanceof SystemToast systemToast)) {
            return false;
        }
        try {
            Object title = UnsafeAccess.get(systemToast, SystemToast.class, "title");
            return title instanceof Component component && expectedTitle.equals(component.getString());
        } catch (Throwable t) {
            return false;
        }
    }

    private static void stopWhenDone(Minecraft mc) {
        if (ticks >= 20) {
            started = false;
            ModReloader.LOGGER.info("[AddCmdCheck] stopping client");
            mc.stop();
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[AddCmdCheck] FAILED: {}", reason);
        ModReloader.LOGGER.error("[AddCmdCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }

    private static AbstractButton findButton(Screen screen, String translationKey) {
        String label = I18n.get(translationKey);
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button && label.equals(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }
}
