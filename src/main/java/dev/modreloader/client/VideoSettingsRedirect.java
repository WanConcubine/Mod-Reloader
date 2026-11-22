package dev.modreloader.client;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.MixinProbe;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fallback for a hot-added mod that ships a mixin replacing the vanilla options
 * screen (Embeddium/Sodium style) but whose mixin was not applied, e.g. when a
 * mixin config plugin skips itself. The mod's config screen is already
 * registered through {@link IConfigScreenFactory}; point the vanilla "Video
 * Settings" button at it so the user still reaches the mod's options.
 *
 * <p>Only mods that declare a mixin targeting {@code OptionsScreen} or
 * {@code VideoSettingsScreen} AND register a config screen are redirected, so
 * ordinary hot-added mods keep their own config entries and the vanilla screen.
 */
final class VideoSettingsRedirect {
    private static final Map<String, ModContainer> CONFIG_SCREENS = new LinkedHashMap<>();
    private static boolean installed;

    private VideoSettingsRedirect() {
    }

    static void consider(Path jar, List<ModContainer> containers) {
        if (!MixinProbe.declaresOptionsScreenTarget(jar)) {
            return;
        }
        for (ModContainer container : containers) {
            if (container.getCustomExtension(IConfigScreenFactory.class).isEmpty()) {
                continue;
            }
            CONFIG_SCREENS.put(container.getModId(), container);
            ModReloader.LOGGER.info(
                    "[ModReloader] {} declares an options-screen mixin; vanilla Video Settings "
                            + "will open its config screen", container.getModId());
        }
        if (CONFIG_SCREENS.isEmpty()) {
            return;
        }
        install();
    }

    private static synchronized void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(ScreenEvent.Opening.class,
                VideoSettingsRedirect::onOpening);
    }

    private static void onOpening(ScreenEvent.Opening event) {
        if (!(event.getNewScreen() instanceof VideoSettingsScreen)) {
            return;
        }
        for (ModContainer container : CONFIG_SCREENS.values()) {
            Screen replacement;
            try {
                replacement = container.getCustomExtension(IConfigScreenFactory.class)
                        .map(factory -> factory.createScreen(container, event.getCurrentScreen()))
                        .orElse(null);
            } catch (Throwable t) {
                ModReloader.LOGGER.warn(
                        "[ModReloader] cannot build the config screen of {}; leaving vanilla "
                                + "Video Settings in place", container.getModId(), t);
                continue;
            }
            if (replacement == null) {
                continue;
            }
            event.setNewScreen(replacement);
            ModReloader.LOGGER.info(
                    "[ModReloader] redirected vanilla Video Settings to the config screen of {}",
                    container.getModId());
            return;
        }
    }
}