package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;

import java.util.Set;

/**
 * Replays the start-up work Sodium does through mixins that cannot run again.
 *
 * <p>Sodium's {@code EntrypointMixin} initializes its config system at the head of
 * the game's main method, and its {@code MinecraftMixin} does the late config
 * registration plus a core-shader check in {@code Minecraft}'s constructor. A
 * hot-add redefines those classes but never re-executes those points, so
 * {@code Minecraft.runTick -> preRender -> SodiumClientMod.options()} throws
 * "Config not yet available" (measured 2026-11-20).
 *
 * <p>This is a version-pinned compatibility adapter, not a generic mechanism: every
 * step is optional, and a missing class/method only logs (the add stays J2). The
 * spike is opt-in ({@code -Dmodreloader.sodiumBootstrap=true}) and currently still
 * hits an uninitialized {@code SpriteContents$Ticker.parent} on old sprites, so it
 * is not a fix yet.
 */
final class SodiumBootstrap {
    private static final String SODIUM = "sodium";

    private SodiumBootstrap() {
    }

    static void afterClientStartup(Set<String> ids) {
        if (!ids.contains(SODIUM) || !Platform.isClient()) {
            return;
        }
        if (!Boolean.parseBoolean(System.getProperty("modreloader.sodiumBootstrap", "false"))) {
            ModReloader.LOGGER.info("[ModReloader] Sodium bootstrap disabled by property");
            return;
        }
        ClassLoader loader = gameLoader();
        if (loader == null) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] Sodium bootstrap skipped: cannot find the game-layer loader");
            return;
        }
        String version = ModList.get().getModContainerById(SODIUM)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("UNKNOWN");

        step("SodiumClientMod.onInitialization", () -> {
            Class<?> client = Class.forName("net.caffeinemc.mods.sodium.client.SodiumClientMod",
                    false, loader);
            client.getMethod("onInitialization", String.class).invoke(null, version);
        });
        step("ConfigLoaderForge.collectConfigEntryPoints", () -> invokeStatic(loader,
                "net.caffeinemc.mods.sodium.neoforge.config.ConfigLoaderForge",
                "collectConfigEntryPoints"));
        step("ConfigManager.registerConfigsEarly", () -> invokeStatic(loader,
                "net.caffeinemc.mods.sodium.client.config.ConfigManager", "registerConfigsEarly"));
        step("ConfigManager.registerConfigsLate", () -> invokeStatic(loader,
                "net.caffeinemc.mods.sodium.client.config.ConfigManager", "registerConfigsLate"));
        step("ResourcePackScanner.checkIfCoreShaderLoaded", () -> {
            Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft", false, loader);
            Object mc = minecraft.getMethod("getInstance").invoke(null);
            Object resourceManager = minecraft.getMethod("getResourceManager").invoke(mc);
            Class<?> scanner = Class.forName(
                    "net.caffeinemc.mods.sodium.client.checks.ResourcePackScanner", false, loader);
            Class<?> managerType = Class.forName(
                    "net.minecraft.server.packs.resources.ResourceManager", false, loader);
            scanner.getMethod("checkIfCoreShaderLoaded", managerType).invoke(null, resourceManager);
        });
    }

    private static ClassLoader gameLoader() {
        try {
            ClassLoader loader = FMLLoader.getGameLayer().findLoader(SODIUM);
            if (loader != null) {
                return loader;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        ClassLoader own = SodiumBootstrap.class.getClassLoader();
        return own;
    }

    private static void invokeStatic(ClassLoader loader, String type, String method)
            throws ReflectiveOperationException {
        Class.forName(type, false, loader).getMethod(method).invoke(null);
    }

    private interface Step {
        void run() throws Throwable;
    }

    private static void step(String name, Step step) {
        try {
            step.run();
            ModReloader.LOGGER.info("[ModReloader] Sodium bootstrap: {}", name);
        } catch (ClassNotFoundException e) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] Sodium bootstrap: {} is gone (version mismatch?)", name);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] Sodium bootstrap: {} failed", name, t);
        }
    }
}