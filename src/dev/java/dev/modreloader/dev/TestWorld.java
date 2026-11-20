package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.resources.language.I18n;

/**
 * Creates a world through the real create-world screen and reports when it is
 * playable.
 *
 * <p>Hot-add harnesses used to copy this state machine (open the screen, press
 * "Create New World", wait for the player, settle a few ticks) with slightly
 * different timeouts; this is the one implementation.
 */
public final class TestWorld {
    /** ready: world is playable; error: give up; otherwise keep ticking. */
    public record Result(boolean ready, String error) {
        static Result readyNow() {
            return new Result(true, null);
        }

        static Result waiting() {
            return new Result(false, null);
        }

        static Result failed(String message) {
            return new Result(false, message);
        }
    }

    private static final int SCREEN_TIMEOUT_TICKS = 20 * 120;
    private static final int LOAD_TIMEOUT_TICKS = 20 * 120;

    private final int settleTicks;
    private int ticks;
    private int worldTicks;
    private boolean opened;
    private boolean pressed;
    private boolean done;

    public TestWorld(int settleTicks) {
        this.settleTicks = Math.max(1, settleTicks);
    }

    public Result tick(Minecraft mc) {
        if (done) {
            return Result.readyNow();
        }
        if (mc.level != null && mc.player != null) {
            if (++worldTicks < settleTicks) {
                return Result.waiting();
            }
            done = true;
            ModReloader.LOGGER.info("[TestWorld] in world: {}", mc.level.dimension().location());
            return Result.readyNow();
        }
        worldTicks = 0;
        if (!opened) {
            if (mc.getOverlay() != null) {
                if (++ticks > SCREEN_TIMEOUT_TICKS) {
                    return Result.failed("resource reload overlay did not clear");
                }
                return Result.waiting();
            }
            try {
                CreateWorldScreen.openFresh(mc, mc.screen);
                opened = true;
                ticks = 0;
            } catch (Throwable t) {
                return Result.failed("CreateWorldScreen.openFresh failed: " + t);
            }
            return Result.waiting();
        }
        if (!pressed) {
            if (!(mc.screen instanceof CreateWorldScreen screen)) {
                if (++ticks > SCREEN_TIMEOUT_TICKS) {
                    return Result.failed("create-world screen did not appear; current screen="
                            + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
                }
                return Result.waiting();
            }
            AbstractButton button = button(screen);
            if (button == null) {
                if (++ticks > SCREEN_TIMEOUT_TICKS) {
                    return Result.failed("create-world button did not appear");
                }
                return Result.waiting();
            }
            pressed = true;
            ticks = 0;
            ModReloader.LOGGER.info("[TestWorld] pressing '{}'", button.getMessage().getString());
            button.onPress();
            return Result.waiting();
        }
        if (++ticks > LOAD_TIMEOUT_TICKS) {
            return Result.failed("world did not load; screen="
                    + (mc.screen == null ? "null" : mc.screen.getClass().getName()));
        }
        return Result.waiting();
    }

    private static AbstractButton button(CreateWorldScreen screen) {
        String label = I18n.get("selectWorld.create");
        for (var child : screen.children()) {
            if (child instanceof AbstractButton button
                    && label.equals(button.getMessage().getString())) {
                return button;
            }
        }
        return null;
    }
}