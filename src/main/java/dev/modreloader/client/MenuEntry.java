package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.ui.ModReloaderScreen;
import dev.modreloader.watch.ModsFolderWatcher;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.List;

/**
 * Persistent entry point for the pending-mods screen.
 *
 * <p>Without this, the only ways in were the automatic title-screen prompt and
 * {@code /modreloader gui} (which needs a world). After "Ignore" the jar would
 * be unreachable unless the user edited {@code watcher.json} by hand.
 */
public final class MenuEntry {
    private static final int WIDTH = 110;
    private static final int HEIGHT = 20;

    private MenuEntry() {
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Init.Post.class, MenuEntry::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        Screen screen = event.getScreen();
        if (!(screen instanceof TitleScreen) && !(screen instanceof PauseScreen)) {
            return;
        }
        int x = 4;
        int y = screen.height - HEIGHT - 4;
        event.addListener(Button.builder(
                        Component.translatable("modreloader.gui.entry"),
                        button -> open())
                .bounds(x, y, WIDTH, HEIGHT)
                .build());
        ModReloader.LOGGER.debug("[ModReloader] menu entry added to {}",
                screen.getClass().getSimpleName());
    }

    private static void open() {
        List<ModsFolderWatcher.Candidate> candidates = ModReloader.WATCHER == null
                ? List.of()
                : ModReloader.WATCHER.scanNow();
        Minecraft.getInstance().setScreen(new ModReloaderScreen(candidates));
    }
}
