package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;

import java.util.Map;

/**
 * The monitor Mixin's ModLauncher plugin uses around {@code handlesClass} /
 * {@code processClass} ({@code MixinLaunchPluginLegacy.processors}).
 *
 * <p>Normal class loading takes it <em>before</em> Mixin's transformer lock
 * (A -&gt; B). A direct {@code transformClassBytes} call takes B first, and Mixin
 * loads classes while parsing injectors; that class load re-enters ModLauncher
 * and asks for A -&gt; deadlock with a worldgen/server thread that holds A and
 * waits for B (measured as issue 54, 2026-11-19:
 * {@code MixinLaunchPluginLegacy.handlesClass} vs {@code MixinTransformer.transformClass}).
 *
 * <p>Taking A first restores the A -&gt; B order; class loads inside the transform
 * re-enter the monitor on the same thread.
 */
public final class LaunchPluginMonitor {
    private static volatile Object monitor;
    private static volatile boolean resolved;

    private LaunchPluginMonitor() {
    }

    static Object get() {
        if (!resolved) {
            synchronized (LaunchPluginMonitor.class) {
                if (!resolved) {
                    monitor = resolve();
                    resolved = true;
                    if (monitor == null) {
                        ModReloader.LOGGER.warn(
                                "[ModReloader] cannot locate Mixin's launch-plugin monitor; a direct "
                                        + "mixin transform can deadlock with a concurrent class load");
                    } else {
                        ModReloader.LOGGER.info(
                                "[ModReloader] Mixin launch-plugin monitor resolved; direct "
                                        + "transforms take it before Mixin's transformer lock");
                    }
                }
            }
        }
        return monitor;
    }

    private static Object resolve() {
        try {
            Object handler = launchPluginHandler();
            Object monitor = mixinProcessors(handler);
            if (monitor != null) {
                return monitor;
            }
            // Fallback: the TransformingClassLoader cached by the launcher.
            Object loader = System.getProperties().get("transforming_class_loader");
            if (loader == null) {
                ClassLoader candidate = net.minecraft.client.Minecraft.class.getClassLoader();
                while (candidate != null && !candidate.getClass().getName()
                        .equals("cpw.mods.modlauncher.TransformingClassLoader")) {
                    candidate = candidate.getParent();
                }
                loader = candidate;
            }
            if (loader != null) {
                Object transformer = UnsafeAccess.get(loader, loader.getClass(), "classTransformer");
                Object viaTransformer = UnsafeAccess.get(transformer, transformer.getClass(), "pluginHandler");
                monitor = mixinProcessors(viaTransformer);
            }
            return monitor;
        } catch (Throwable t) {
            ModReloader.LOGGER.warn(
                    "[ModReloader] mixin launch-plugin monitor lookup failed", t);
        }
        return null;
    }

    /** {@code cpw.mods.modlauncher.Launcher.INSTANCE.launchPlugins}. */
    private static Object launchPluginHandler() {
        Class<?> launcherClass = null;
        ClassLoader[] loaders = {
                ClassLoader.getSystemClassLoader(),
                Thread.currentThread().getContextClassLoader(),
                LaunchPluginMonitor.class.getClassLoader()
        };
        for (ClassLoader loader : loaders) {
            try {
                launcherClass = Class.forName("cpw.mods.modlauncher.Launcher", false, loader);
                break;
            } catch (Throwable ignored) {
                // try the next loader
            }
        }
        if (launcherClass == null) {
            return null;
        }
        try {
            Object launcher = UnsafeAccess.getStatic(launcherClass, "INSTANCE");
            if (launcher == null) {
                return null;
            }
            return UnsafeAccess.get(launcher, launcher.getClass(), "launchPlugins");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object mixinProcessors(Object handler) {
        if (handler == null) {
            return null;
        }
        Map<?, ?> plugins = UnsafeAccess.get(handler, handler.getClass(), "plugins");
        for (Object plugin : plugins.values()) {
            try {
                // The registered service is MixinLaunchPlugin, a subclass of the
                // legacy plugin that declares the monitored "processors" list.
                ClassLoader loader = plugin.getClass().getClassLoader();
                Class<?> legacy = Class.forName(
                        "org.spongepowered.asm.launch.MixinLaunchPluginLegacy", false, loader);
                if (legacy.isInstance(plugin)) {
                    return UnsafeAccess.get(plugin, legacy, "processors");
                }
            } catch (Throwable ignored) {
                // not the Mixin plugin
            }
        }
        return null;
    }
}