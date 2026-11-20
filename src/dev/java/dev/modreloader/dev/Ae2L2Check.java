package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * L2 smoke for AE2: hot-add GuideME + AE2 at the title screen, enter a world,
 * then open the ME Terminal and the guide book.
 *
 * <p>L1 only proves the classes redefined; this exercises the client
 * registrations a player actually touches: menu screens, item models, textures,
 * and the mod's own GUI resources after the hot-add reload. Needs
 * {@code -PlateAt=true} (AE2 ships an access transformer) and
 * {@code -PverifyAe2L2=<guide>,<ae2>}.
 */
public final class Ae2L2Check {
    private enum Stage { WAIT_TITLE, ADD, WORLD, GIVE, OPEN_GUIDE, OPEN_WIRELESS, DONE }

    private static final int TIMEOUT_TICKS = 20 * 300;

    private static final TestWorld WORLD = new TestWorld(20);
    private static final List<Path> JARS = new ArrayList<>();
    private static Stage stage = Stage.WAIT_TITLE;
    private static int ticks;
    private static int totalTicks;
    private static boolean started;
    private static boolean failed;
    private static String terminalScreen = "?";
    private static String guideScreen = "?";

    private Ae2L2Check() {
    }

    public static void start(String jars) {
        if (started) {
            return;
        }
        for (String part : jars.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                JARS.add(Path.of(trimmed).toAbsolutePath().normalize());
            }
        }
        if (JARS.isEmpty()) {
            ModReloader.LOGGER.error("[Ae2L2] no jars given");
            return;
        }
        started = true;
        ModReloader.LOGGER.info("[Ae2L2] armed with {}", JARS);
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
            case WAIT_TITLE -> waitTitle(mc);
            case ADD -> add();
            case WORLD -> enterWorld(mc);
            case GIVE -> give(mc);
            case OPEN_GUIDE -> openGuide(mc);
            case OPEN_WIRELESS -> openWireless(mc);
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void waitTitle(Minecraft mc) {
        if (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null
                || ModReloader.WATCHER == null) {
            ticks = 0;
            return;
        }
        if (ticks < 40) {
            return;
        }
        stage = Stage.ADD;
        ticks = 0;
    }

    private static void add() {
        for (Path jar : JARS) {
            HotAddEngine.Result result = HotAddEngine.add(jar);
            ModReloader.LOGGER.info("[Ae2L2] hot-add {}: ok={} kind={} stage={} msg={}",
                    jar.getFileName(), result.ok(), result.kind(), result.stage(), result.message());
            if (!result.ok()) {
                fail("hot-add failed for " + jar.getFileName() + ": " + result.kind()
                        + " " + result.message());
                return;
            }
        }
        stage = Stage.WORLD;
        ticks = 0;
    }

    private static void enterWorld(Minecraft mc) {
        TestWorld.Result result = WORLD.tick(mc);
        if (result.error() != null) {
            fail(result.error());
            return;
        }
        if (!result.ready()) {
            return;
        }
        stage = Stage.GIVE;
        ticks = 0;
    }

    private static void give(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (server == null) {
            fail("no integrated server");
            return;
        }
        if (ticks == 1) {
            // The created world has cheats off, so /give would be refused; put the
            // items into the player's inventory on the server thread instead.
            server.execute(() -> {
                if (server.getPlayerList().getPlayers().isEmpty()) {
                    return;
                }
                ServerPlayer player = server.getPlayerList().getPlayers().get(0);
                player.getInventory().add(stack("ae2:guide"));
                player.getInventory().add(stack("ae2:wireless_terminal"));
                ModReloader.LOGGER.info("[Ae2L2] added ae2:guide and ae2:wireless_terminal to {}",
                        player.getGameProfile().getName());
            });
        }
        if (ticks < 40) {
            return;
        }
        if (mc.player == null) {
            fail("no client player after world load");
            return;
        }
        ModReloader.LOGGER.info("[Ae2L2] inventory slots 0/1: {}/{}",
                mc.player.getInventory().getItem(0), mc.player.getInventory().getItem(1));
        stage = Stage.OPEN_GUIDE;
        ticks = 0;
    }

    private static ItemStack stack(String id) {
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
        return new ItemStack(item);
    }

    private static void openGuide(Minecraft mc) {
        if (ticks == 1) {
            select(0);
            use(mc, "guide");
        }
        if (mc.screen != null && !(mc.screen instanceof TitleScreen)) {
            guideScreen = mc.screen.getClass().getName();
            ModReloader.LOGGER.info("[Ae2L2] guide screen opened: {}", guideScreen);
            close(mc);
            stage = Stage.OPEN_WIRELESS;
            ticks = 0;
            return;
        }
        if (ticks > 100) {
            fail("guide did not open a screen");
        }
    }

    /** Informational: an unlinked wireless terminal may refuse to open; the guide is the assertion. */
    private static void openWireless(Minecraft mc) {
        if (ticks == 1) {
            select(1);
            use(mc, "wireless terminal");
        }
        if (mc.screen != null && !(mc.screen instanceof TitleScreen)) {
            terminalScreen = mc.screen.getClass().getName();
            ModReloader.LOGGER.info("[Ae2L2] wireless terminal screen opened: {}", terminalScreen);
            close(mc);
            ModReloader.LOGGER.info("[Ae2L2] RESULT: PASS (guide={}, wireless={})",
                    guideScreen, terminalScreen);
            stage = Stage.DONE;
            ticks = 0;
            return;
        }
        if (ticks > 60) {
            ModReloader.LOGGER.info(
                    "[Ae2L2] wireless terminal opened no screen (expected when unlinked); "
                            + "guide is the L2 assertion");
            ModReloader.LOGGER.info("[Ae2L2] RESULT: PASS (guide={}, wireless=none)",
                    guideScreen);
            stage = Stage.DONE;
            ticks = 0;
        }
    }

    private static void select(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.getInventory().selected = slot;
        }
    }

    private static void use(Minecraft mc, String what) {
        if (mc.player == null || mc.gameMode == null) {
            fail("no player/game mode to use the " + what);
            return;
        }
        ModReloader.LOGGER.info("[Ae2L2] using {} (slot {})", what,
                mc.player.getInventory().selected);
        mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
    }

    private static void close(Minecraft mc) {
        if (mc.player != null) {
            mc.player.closeContainer();
        }
        mc.setScreen(null);
    }

    private static void stopWhenDone(Minecraft mc) {
        if (ticks >= 20) {
            started = false;
            ModReloader.LOGGER.info("[Ae2L2] stopping client");
            mc.stop();
        }
    }

    private static void fail(String reason) {
        if (failed) {
            return;
        }
        failed = true;
        ModReloader.LOGGER.error("[Ae2L2] FAILED: {}", reason);
        ModReloader.LOGGER.error("[Ae2L2] RESULT: FAIL");
        stage = Stage.DONE;
        ticks = 0;
    }
}