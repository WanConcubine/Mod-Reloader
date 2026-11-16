package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.CreativeModeTabRegistry;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Dev-only dogfood <em>gameplay</em> check for a hot-added content mod.
 *
 * <p>The datapack check proves the data is loaded; this one proves the loaded
 * data actually drives gameplay. It runs after the in-world add and drives real
 * server-side paths (not just registries):
 *
 * <ol>
 *   <li>the mod's own diagnostic command ({@code /exnihilo tables}) — every
 *       machine table must be non-empty;</li>
 *   <li><b>hammer</b>: break cobblestone with an iron hammer — the drop must be
 *       gravel, i.e. {@code BlockDropsEvent} replaced the vanilla drop;</li>
 *   <li><b>sieve</b>: place a sieve, install an iron mesh, insert dirt (only
 *       registered sieve inputs are accepted) and let it work — pebbles must
 *       drop (dirt's 100% entry);</li>
 *   <li><b>crafting</b>: a real 3×3 grid must assemble {@code iron_hammer}.</li>
 * </ol>
 *
 * <p>Sieve work is time-based ({@code WORK_MAX=100}, {@code WORK_PER_TICK=2}), so
 * the harness starts a server-side click chain (keepUsing every tick) and only
 * asserts after the sieve had time to finish. The client thread is never blocked
 * waiting on the server to tick.
 *
 * <p>Enabled with {@code -PverifyGameplay=true} together with
 * {@code -PverifyAddCommand=<ex nihilo jar>}; the mod id is
 * {@code -PverifyGameplayModId} (default {@code exnihilosatellitium}).
 */
public final class GameplayCheck {
    private enum Stage { SETUP, WAIT, VERIFY, CREATIVE, DONE }

    private static final int SIEVE_WORK_TICKS = 100;
    private static final int WAIT_TICKS = SIEVE_WORK_TICKS + 40;
    private static final int SIEVE_CLICK_EVERY_TICKS = 3;
    private static final int TIMEOUT_TICKS = 20 * 120;

    /** -PverifyCreative: switch to creative and render the creative inventory. */
    private static final boolean VERIFY_CREATIVE = Boolean.getBoolean("modreloader.verifyCreative");
    private static boolean creativeScreenOpened;
    private static boolean itemModelsChecked;

    /** -PverifyGameplayKeepOpen: leave the world running for manual play. */
    private static final boolean KEEP_OPEN = Boolean.getBoolean("modreloader.verifyGameplayKeepOpen");

    private static final String MOD_ID = System.getProperty(
            "modreloader.verifyGameplayModId", "exnihilosatellitium").trim();

    /** "/exnihilo tables" summary fragments we must see non-zero. */
    private static final Pattern[] TABLE_COUNTS = {
            Pattern.compile("sieve (\\d+) inputs"),
            Pattern.compile("hammer (\\d+)"),
            Pattern.compile("crook (\\d+)"),
            Pattern.compile("barrel (\\d+)"),
            Pattern.compile("compost (\\d+)"),
            Pattern.compile("crucible (\\d+) melt"),
            Pattern.compile("crucible \\d+ melt / (\\d+) heat"),
    };

    private static Stage stage = Stage.SETUP;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean failed;
    private static String modId = MOD_ID;

    private static BlockPos hammerPos;
    private static BlockPos sievePos;
    private static CompletableFuture<Setup> setupFuture;
    private static CompletableFuture<Verify> verifyFuture;

    private record Setup(String tables, int commandRc, List<String> hammerDrops,
                         boolean sieveInsertAccepted, String craft, int modRecipes,
                         boolean ironHammerRecipeLoaded, String craftDetail) {
    }

    private record Verify(List<String> sieveDrops, boolean pebbleInInventory) {
    }

    private GameplayCheck() {
    }

    /** Called by {@link AddCommandCheck} after the mod was hot-added in world. */
    public static void start(String expectedModId) {
        if (started) {
            return;
        }
        started = true;
        modId = expectedModId == null || expectedModId.isBlank() ? MOD_ID : expectedModId;
        ModReloader.LOGGER.info("[GameplayCheck] started for {}", modId);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            fail("timeout at stage " + stage);
            return;
        }
        MinecraftServer server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
        if (server == null) {
            fail("the integrated server is gone");
            return;
        }
        switch (stage) {
            case SETUP -> setup(server);
            case WAIT -> waitStage();
            case VERIFY -> verify(server);
            case CREATIVE -> creativeStage();
            case DONE -> stopWhenDone();
        }
    }

    // ---------------------------------------------------------------- setup

    private static void setup(MinecraftServer server) {
        if (setupFuture == null) {
            if (!checkMenuScreens()) {
                return;
            }
            if (!checkClientRegistrations()) {
                return;
            }
            setupFuture = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    setupFuture.complete(serverSetup(server));
                } catch (Throwable t) {
                    setupFuture.completeExceptionally(t);
                }
            });
            return;
        }
        if (!setupFuture.isDone()) {
            return;
        }
        Setup result = await(setupFuture);
        ModReloader.LOGGER.info("[GameplayCheck] /exnihilo tables (rc={}): {}",
                result.commandRc(), result.tables());
        ModReloader.LOGGER.info("[GameplayCheck] hammer cobblestone -> {}", result.hammerDrops());
        ModReloader.LOGGER.info("[GameplayCheck] sieve insert(minecraft:dirt) accepted={}",
                result.sieveInsertAccepted());
        ModReloader.LOGGER.info("[GameplayCheck] craft 2x iron_ingot + 2x stick -> {} "
                        + "(recipes in {}: {}, iron_hammer loaded={}, {})",
                result.craft(), modId, result.modRecipes(),
                result.ironHammerRecipeLoaded(), result.craftDetail());

        if (result.commandRc() != 1) {
            fail("/exnihilo tables returned " + result.commandRc() + " (command missing or failed)");
            return;
        }
        if (result.tables().isBlank()) {
            fail("/exnihilo tables printed nothing (the mod's reload listener never ran)");
            return;
        }
        // Every machine table must be non-empty: this is the data-driven half.
        Matcher sieveMatcher = Pattern.compile("sieve (\\d+) inputs").matcher(result.tables());
        if (!sieveMatcher.find() || Integer.parseInt(sieveMatcher.group(1)) <= 0) {
            fail("sieve table is empty: " + result.tables());
            return;
        }
        for (Pattern pattern : TABLE_COUNTS) {
            Matcher matcher = pattern.matcher(result.tables());
            if (!matcher.find() || Integer.parseInt(matcher.group(1)) <= 0) {
                fail("a machine table is empty (" + pattern.pattern() + "): " + result.tables());
                return;
            }
        }
        if (!hasItem(result.hammerDrops(), id("minecraft:gravel"))) {
            fail("hammering cobblestone did not drop gravel (drops=" + result.hammerDrops()
                    + "); BlockDropsEvent replacement did not run");
            return;
        }
        if (!result.sieveInsertAccepted()) {
            fail("the sieve rejected minecraft:dirt; the sieve registry does not know it");
            return;
        }
        if (!result.ironHammerRecipeLoaded()) {
            fail("the iron_hammer recipe is not in the recipe manager at all (" + result.craftDetail() + ")");
            return;
        }
        if (!id(modId + ":iron_hammer").equals(result.craft())) {
            fail("the iron_hammer recipe did not match a real grid (got " + result.craft()
                    + "; " + result.craftDetail() + ")");
            return;
        }
        stage = Stage.WAIT;
        ticks = 0;
    }

    private static Setup serverSetup(MinecraftServer server) {
        ServerLevel level = server.overworld();
        ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("no server player in world"));
        BlockPos base = player.blockPosition();
        // Keep the test blocks a few blocks away so item pickups do not eat the
        // drops before we read them.
        hammerPos = base.offset(5, 0, 0);
        sievePos = base.offset(5, 0, 3);

        // 1) The mod's own diagnostics.
        List<String> messages = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                messages.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        CommandSourceStack stack = new CommandSourceStack(sink,
                Vec3.atCenterOf(base), Vec2.ZERO, level, 4, "gameplay",
                Component.literal("gameplay"), server, player);
        int commandRc;
        try {
            commandRc = server.getCommands().getDispatcher().execute("exnihilo tables", stack);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            throw new IllegalStateException("/exnihilo is not registered: " + e.getMessage(), e);
        }
        String tables = String.join(" | ", messages);

        // 2) Hammer: real block break with the hammer in hand.
        level.setBlockAndUpdate(hammerPos, Blocks.COBBLESTONE.defaultBlockState());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(item(modId + ":iron_hammer")));
        boolean destroyed = player.gameMode.destroyBlock(hammerPos);
        if (!destroyed) {
            throw new IllegalStateException("destroyBlock returned false at " + hammerPos);
        }
        List<String> hammerDrops = describeItems(level, hammerPos, 3.0D);

        // 3) Sieve: place, install mesh, insert dirt, then let the BE work.
        Block sieve = block(modId + ":sieve");
        level.setBlockAndUpdate(sievePos, sieve.defaultBlockState());
        BlockState sieveState = level.getBlockState(sievePos);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(sievePos), Direction.UP, sievePos, false);
        ItemStack mesh = new ItemStack(item(modId + ":iron_mesh"));
        sieveState.useItemOn(mesh, level, player, InteractionHand.MAIN_HAND, hit);
        ItemStack dirt = new ItemStack(Items.DIRT, 1);
        ItemInteractionResult insert = sieveState.useItemOn(dirt, level, player, InteractionHand.MAIN_HAND, hit);
        boolean insertAccepted = insert.consumesAction();

        // 4) Crafting: the iron hammer recipe (iron ingot + c:rods/wooden = stick).
        List<ItemStack> grid = new ArrayList<>(Collections.nCopies(9, ItemStack.EMPTY));
        // pattern: [" x ", " yx", "y  "] -> x=(1,0),(2,1); y=(1,1),(0,2)
        grid.set(1, new ItemStack(Items.IRON_INGOT));
        grid.set(5, new ItemStack(Items.IRON_INGOT));
        grid.set(4, new ItemStack(Items.STICK));
        grid.set(6, new ItemStack(Items.STICK));
        String craft = server.getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, CraftingInput.of(3, 3, grid), level)
                .map(holder -> holder.id().toString())
                .orElse("none");
        List<String> modRecipes = server.getRecipeManager().getRecipeIds()
                .filter(key -> modId.equals(key.getNamespace())).map(ResourceLocation::toString).toList();
        ResourceLocation hammerRecipe = ResourceLocation.fromNamespaceAndPath(modId, "iron_hammer");
        boolean hammerRecipeLoaded = server.getRecipeManager().byKey(hammerRecipe).isPresent();
        String craftDetail = "byKey(" + hammerRecipe + ")=" + hammerRecipeLoaded
                + ", item=" + BuiltInRegistries.ITEM.getKey(item(modId + ":iron_hammer"))
                + ", recipeIdsContain=" + modRecipes.contains(hammerRecipe.toString())
                + ", total=" + modRecipes.size()
                + ", grid=[1,5]=iron_ingot,[4,6]=stick";

        return new Setup(tables, commandRc, hammerDrops, insertAccepted, craft,
                modRecipes.size(), hammerRecipeLoaded, craftDetail);
    }

    /**
     * Machine GUIs: every menu type of the hot-added mod must have a screen
     * factory. NeoForge registers them once at startup, so without the replay in
     * {@code ClientPlatformHooks} the client logs
     * "Failed to create screen for menu type" and the GUI never opens.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean checkMenuScreens() {
        int total = 0;
        List<String> missing = new ArrayList<>();
        for (var holder : BuiltInRegistries.MENU.holders().toList()) {
            ResourceLocation key = holder.unwrapKey()
                    .map(resourceKey -> resourceKey.location()).orElse(null);
            if (key == null || !modId.equals(key.getNamespace())) {
                continue;
            }
            total++;
            if (MenuScreens.getScreenFactory((net.minecraft.world.inventory.MenuType) holder.value())
                    .isEmpty()) {
                missing.add(key.toString());
            }
        }
        ModReloader.LOGGER.info("[GameplayCheck] menu screens: {} type(s) in {}, missing={}",
                total, modId, missing);
        if (total > 0 && !missing.isEmpty()) {
            fail("the hot-added mod's GUIs have no screen factory: " + missing);
            return false;
        }
        return true;
    }

    /**
     * Item icons: every item of the hot-added mod must resolve to a real baked
     * model (not ModelManager's missing model). A missing model is the purple
     * cube players see in the creative inventory.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean checkItemModels() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        net.minecraft.client.resources.model.BakedModel missing = mc.getModelManager().getMissingModel();
        int items = 0;
        List<String> missingModels = new ArrayList<>();
        for (var holder : BuiltInRegistries.ITEM.holders().toList()) {
            ResourceLocation key = holder.unwrapKey()
                    .map(resourceKey -> resourceKey.location()).orElse(null);
            if (key == null || !modId.equals(key.getNamespace())) {
                continue;
            }
            items++;
            net.minecraft.client.resources.model.BakedModel model = mc.getItemRenderer()
                    .getModel(new ItemStack((net.minecraft.world.item.Item) holder.value()),
                            null, null, 0);
            if (model == missing) {
                missingModels.add(key.toString());
            }
        }
        ModReloader.LOGGER.info("[GameplayCheck] item models: {}/{} resolved, missing={}",
                items - missingModels.size(), items,
                missingModels.size() > 8 ? missingModels.subList(0, 8) + "..." : missingModels);
        if (!missingModels.isEmpty()) {
            fail(missingModels.size() + "/" + items + " item models are the missing model: "
                    + missingModels.subList(0, Math.min(5, missingModels.size())));
            return false;
        }
        return true;
    }

    /**
     * Client-side registrations that are replayed by
     * {@code client/ClientRegistrationReplay}: block entity renderers must be
     * instantiated for the mod's block entities, item tints must exist, and the
     * mod's fluid types must have client extensions.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean checkClientRegistrations() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();

        Object renderersObject = dev.modreloader.hotadd.UnsafeAccess.get(
                mc.getBlockEntityRenderDispatcher(),
                net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher.class,
                "renderers");
        java.util.Set<Object> rendered = renderersObject instanceof java.util.Map<?, ?> map
                ? new java.util.HashSet<>(map.keySet())
                : java.util.Set.of();
        List<String> missingRenderers = new ArrayList<>();
        int beTypes = 0;
        for (var holder : BuiltInRegistries.BLOCK_ENTITY_TYPE.holders().toList()) {
            ResourceLocation key = holder.unwrapKey()
                    .map(resourceKey -> resourceKey.location()).orElse(null);
            if (key == null || !modId.equals(key.getNamespace())) {
                continue;
            }
            beTypes++;
            if (!rendered.contains(holder.value())) {
                missingRenderers.add(key.toString());
            }
        }

        Object itemColorsObject = dev.modreloader.hotadd.UnsafeAccess.get(
                mc.getItemColors(), net.minecraft.client.color.item.ItemColors.class, "itemColors");
        boolean hasBucketTint = itemColorsObject instanceof java.util.Map<?, ?> map
                && map.containsKey(item(modId + ":porcelain_bucket"));
        int tint = mc.getItemColors().getColor(new ItemStack(item(modId + ":porcelain_bucket")), 1);

        Object extensionsObject = dev.modreloader.hotadd.UnsafeAccess.getStatic(
                net.neoforged.neoforge.client.extensions.common.ClientExtensionsManager.class,
                "FLUID_TYPE_EXTENSIONS");
        int fluids = 0;
        if (extensionsObject instanceof java.util.Map<?, ?> map) {
            for (Object fluidType : map.keySet()) {
                ResourceLocation key = net.neoforged.neoforge.registries.NeoForgeRegistries.FLUID_TYPES
                        .getKey((net.neoforged.neoforge.fluids.FluidType) fluidType);
                if (key != null && modId.equals(key.getNamespace())) {
                    fluids++;
                }
            }
        }

        ModReloader.LOGGER.info(
                "[GameplayCheck] client registrations: beRenderers {}/{} (missing={}), "
                        + "porcelainBucketTintRegistered={} (tint={}), fluidExtensions={}",
                beTypes - missingRenderers.size(), beTypes, missingRenderers,
                hasBucketTint, tint, fluids);
        if (beTypes > 0 && !missingRenderers.isEmpty()) {
            fail("block entity renderers missing for " + missingRenderers);
            return false;
        }
        if (!hasBucketTint) {
            fail("no item color handler registered for " + modId + ":porcelain_bucket");
            return false;
        }
        if (fluids == 0) {
            fail("no client extensions for " + modId + " fluid types");
            return false;
        }
        return true;
    }

    // --------------------------------------------------------------- verify

    private static void waitStage() {
        if (ticks % SIEVE_CLICK_EVERY_TICKS == 0) {
            MinecraftServer server = net.minecraft.client.Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                // Keep the sieve "held" (HOLD_TICKS=8): one click every few ticks
                // lets gameTime advance, so the 100 work units accumulate.
                server.execute(GameplayCheck::clickSieveOnce);
            }
        }
        if (ticks < WAIT_TICKS) {
            return;
        }
        stage = Stage.VERIFY;
        ticks = 0;
    }

    /** Runs on the server thread; one right-click with an empty hand. */
    private static void clickSieveOnce() {
        try {
            MinecraftServer server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null || sievePos == null) {
                return;
            }
            ServerLevel level = server.overworld();
            ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (player == null) {
                return;
            }
            BlockState state = level.getBlockState(sievePos);
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(sievePos), Direction.UP, sievePos, false);
            state.useItemOn(ItemStack.EMPTY, level, player, InteractionHand.MAIN_HAND, hit);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[GameplayCheck] sieve click failed", t);
        }
    }

    private static void verify(MinecraftServer server) {
        if (!itemModelsChecked) {
            itemModelsChecked = true;
            if (!checkItemModels()) {
                return;
            }
        }
        if (verifyFuture == null) {
            verifyFuture = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    ServerLevel level = server.overworld();
                    ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst()
                            .orElseThrow(() -> new IllegalStateException("no server player"));
                    List<String> drops = describeItems(level, sievePos, 2.5D);
                    boolean inInventory = player.getInventory().items.stream()
                            .filter(stack -> !stack.isEmpty())
                            .anyMatch(stack -> itemId(stack).equals(id(modId + ":cobblestone_pebble")));
                    verifyFuture.complete(new Verify(drops, inInventory));
                } catch (Throwable t) {
                    verifyFuture.completeExceptionally(t);
                }
            });
            return;
        }
        if (!verifyFuture.isDone()) {
            return;
        }
        Verify result = await(verifyFuture);
        ModReloader.LOGGER.info("[GameplayCheck] sieve after {} ticks: ground={}, pebbleInInventory={}",
                SIEVE_WORK_TICKS + WAIT_TICKS, result.sieveDrops(), result.pebbleInInventory());
        boolean pebbles = result.pebbleInInventory()
                || hasItem(result.sieveDrops(), id(modId + ":cobblestone_pebble"));
        if (!pebbles) {
            fail("sifting dirt produced no cobblestone_pebble (drops=" + result.sieveDrops() + ")");
            return;
        }
        ModReloader.LOGGER.info("[GameplayCheck] manual follow-up (not automated): "
                + "barrel / crucible / compost / mesh wear / machine GUIs");
        ModReloader.LOGGER.info("[GameplayCheck] RESULT: PASS");
        stage = VERIFY_CREATIVE ? Stage.CREATIVE : Stage.DONE;
        ticks = 0;
    }

    /**
     * Opens the creative inventory. This is the regression check for the
     * DEFAULT_TABS duplication: with 8 default tabs the screen maps tabs to
     * columns 7/8 and {@code renderTabButton} overflows its 7 sprites, killing
     * the client. Runs on the client thread so the real render path executes.
     */
    private static void creativeStage() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("creative check: the integrated server is gone");
            return;
        }
        if (ticks == 1) {
            server.execute(() -> {
                ServerPlayer player = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
                if (player != null) {
                    player.setGameMode(GameType.CREATIVE);
                }
            });
            return;
        }
        if (mc.player == null) {
            fail("creative check: no client player");
            return;
        }
        if (!mc.player.isCreative()) {
            if (ticks > 100) {
                fail("creative check: the player never became creative");
            }
            return;
        }
        if (!creativeScreenOpened) {
            int defaults = CreativeModeTabRegistry.getDefaultTabs().size();
            ModReloader.LOGGER.info(
                    "[GameplayCheck] creative screen: all={} defaults={} sorted={}",
                    CreativeModeTabs.allTabs().size(), defaults,
                    CreativeModeTabRegistry.getSortedCreativeModeTabs().size());
            if (defaults != 4) {
                fail("NeoForge's default creative tab list was duplicated (defaults=" + defaults
                        + ", expected 4); the creative screen would overflow its tab sprites");
                return;
            }
            try {
                mc.setScreen(new CreativeModeInventoryScreen(
                        mc.player, mc.level.enabledFeatures(), true));
            } catch (Throwable t) {
                fail("opening the creative inventory threw " + t);
                return;
            }
            creativeScreenOpened = true;
            ticks = 0;
            return;
        }
        if (ticks < 40) {
            return;
        }
        ModReloader.LOGGER.info(
                "[GameplayCheck] creative inventory rendered 40 ticks without crashing");
        mc.setScreen(null);
        ModReloader.LOGGER.info("[GameplayCheck] creative RESULT: PASS");
        stage = Stage.DONE;
        ticks = 0;
    }

    // --------------------------------------------------------------- helpers

    private static List<String> describeItems(ServerLevel level, BlockPos pos, double radius) {
        AABB area = new AABB(pos).inflate(radius);
        return level.getEntitiesOfClass(ItemEntity.class, area).stream()
                .map(entity -> itemId(entity.getItem()) + " x" + entity.getItem().getCount())
                .toList();
    }

    /** Drop descriptions are "&lt;id&gt; x&lt;count&gt;"; match the id, not the whole string. */
    private static boolean hasItem(List<String> described, String id) {
        return described.stream().anyMatch(entry -> entry.equals(id) || entry.startsWith(id + " x"));
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static Item item(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        Item item = location == null ? null : BuiltInRegistries.ITEM.get(location);
        if (item == null || item == Items.AIR) {
            throw new IllegalStateException("missing item " + id);
        }
        return item;
    }

    private static Block block(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        Block block = location == null ? null : BuiltInRegistries.BLOCK.get(location);
        if (block == null || block == Blocks.AIR) {
            throw new IllegalStateException("missing block " + id);
        }
        return block;
    }

    private static String id(String value) {
        return ResourceLocation.parse(value).toString();
    }

    private static <T> T await(CompletableFuture<T> future) {
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the server thread", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("server-side gameplay step failed: " + cause, cause);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("server-side gameplay step timed out", e);
        }
    }

    private static void stopWhenDone() {
        if (ticks < 20) {
            return;
        }
        started = false;
        if (KEEP_OPEN) {
            ModReloader.LOGGER.info(
                    "[GameplayCheck] leaving the client open for manual play "
                            + "(sieve at {})", sievePos);
            return;
        }
        ModReloader.LOGGER.info("[GameplayCheck] stopping client");
        net.minecraft.client.Minecraft.getInstance().stop();
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[GameplayCheck] FAILED: {}", reason);
        ModReloader.LOGGER.error("[GameplayCheck] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}
