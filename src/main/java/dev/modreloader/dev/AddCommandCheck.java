package dev.modreloader.dev;

import com.mojang.brigadier.CommandDispatcher;
import dev.modreloader.ModReloader;
import dev.modreloader.command.ReloaderCommands;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.commands.CommandSourceStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;

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
        OPEN_CREATE, WAIT_CREATE_SCREEN, WAIT_WORLD, RUN_COMMAND, VERIFY, DONE
    }

    private static final int TIMEOUT_TICKS = 20 * 300;
    private static final int WORLD_SETTLE_TICKS = 10;

    private static Stage stage = Stage.OPEN_CREATE;
    private static int ticks;
    private static int totalTicks;
    private static int worldTicks;
    private static boolean started;
    private static boolean pressedCreate;
    private static boolean failed;

    /** Mod the command is expected to add (or to reject when {@link #expectFailure}). */
    private static final String EXPECT_MOD_ID =
            System.getProperty("modreloader.verifyAddCommandModId", "easylan").trim();
    private static final boolean EXPECT_FAILURE =
            Boolean.getBoolean("modreloader.verifyAddCommandExpectFailure");

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
            case OPEN_CREATE -> openCreateScreen(mc);
            case WAIT_CREATE_SCREEN -> waitForCreateScreen(mc);
            case WAIT_WORLD -> waitForWorld(mc);
            case RUN_COMMAND -> runCommand(mc, jar);
            case VERIFY -> verify(mc, jar);
            case DONE -> stopWhenDone(mc);
        }
    }

    private static void openCreateScreen(Minecraft mc) {
        if (mc.getOverlay() != null) {
            if (ticks > 20 * 30) {
                fail("resource reload overlay did not clear");
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
        CommandDispatcher<CommandSourceStack> dispatcher = ReloaderCommands.dispatcher();
        if (dispatcher == null) {
            fail("client command dispatcher was never captured");
            return;
        }
        ModReloader.LOGGER.info("[AddCmdCheck] dispatcher has /modreloader={}",
                dispatcher.getRoot().getChild("modreloader") != null);
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
            stage = Stage.DONE;
            ticks = 0;
            return;
        }
        if (!loaded || !container) {
            fail("command did not load " + EXPECT_MOD_ID + " (isLoaded=" + loaded
                    + ", container=" + container + ")");
            return;
        }
        ModReloader.LOGGER.info("[AddCmdCheck] RESULT: PASS");
        stage = Stage.DONE;
        ticks = 0;
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
