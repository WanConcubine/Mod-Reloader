package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in adapter for ModernUI ({@code modernui}): replays the renderer init that
 * its {@code MixinRenderSystem} only performs once, from
 * {@code RenderSystem.initRenderer} during startup.
 *
 * <p>Without it the mod's client-setup deferred task throws
 * {@code IllegalStateException: The render thread has not been initialized yet}
 * ({@code Core.sRenderThread == null}), the add rolls back, and the UI thread
 * ModernUI started while constructing keeps drawing with no recording context
 * ({@code UI recording context has not been created yet, or creation failed}) -
 * the process dies. The reconstruction is deliberately version-pinned: every
 * class/method is found by name, and anything missing is reported, not guessed.
 *
 * <p>Enabled with {@code -Dmodreloader.modernuiAdapter=true}. Everything runs on
 * the client (render) thread; classes are loaded through the game layer's loader
 * so the hot-added mod's own copy is used.
 */
final class ModernUIAdapter {
    static final String PROPERTY = "modreloader.modernuiAdapter";
    private static final String MOD_ID = "modernui";
    private static final String CORE = "icyllis.modernui.core.Core";
    private static final String CONTEXT_OPTIONS = "icyllis.arc3d.engine.ContextOptions";
    private static final String CLIENT = "icyllis.modernui.mc.ModernUIClient";

    private ModernUIAdapter() {
    }

    static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    }

    /**
     * Called right after the mod's {@code @Mod} constructor and before its config
     * load. On failure neither the add nor the game can continue safely (the mod's
     * own threads cannot be rolled back), so this restarts and then throws.
     */
    static void afterConstruct(String modId) {
        if (!MOD_ID.equals(modId) || !enabled() || !dev.modreloader.Platform.isClient()) {
            return;
        }
        String error = initialize();
        if (error == null) {
            return;
        }
        ModReloader.LOGGER.error("[ModReloader] modernui adapter failed: {}; its threads cannot be "
                + "rolled back, restarting instead of continuing", error);
        dev.modreloader.launch.Relauncher.saveAndRestartCurrentClient(
                        "modernui adapter failed: " + error)
                .ifPresent(reason -> ModReloader.LOGGER.error(
                        "[ModReloader] cannot relaunch after the modernui adapter failed: {}",
                        reason));
        throw new IllegalStateException("modernui adapter failed: " + error + "; restart required");
    }

    /**
     * @return {@code null} when the adapter does not apply or the init succeeded;
     *         otherwise why it failed (the caller must not roll back and continue)
     */
    static String initialize() {
        Object minecraft = minecraft();
        if (minecraft == null) {
            return "no client instance";
        }
        try {
            if (isSameThread(minecraft)) {
                run();
                return null;
            }
            // Chat-triggered adds run on the server thread; Core.initialize() and
            // Core.initOpenGL() require the render thread.
            CompletableFuture<String> result = new CompletableFuture<>();
            execute(minecraft, () -> {
                try {
                    run();
                    result.complete(null);
                } catch (Throwable t) {
                    ModReloader.LOGGER.error("[ModReloader] modernui adapter (render thread) failed", t);
                    result.complete(rootCause(t));
                }
            });
            return result.get(60, TimeUnit.SECONDS);
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] modernui adapter failed", t);
            return rootCause(t);
        }
    }

    /** {@code InvocationTargetException} hides the real failure; unwrap it. */
    private static String rootCause(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getName() + (root.getMessage() == null ? "" : ": " + root.getMessage());
    }

    private static void run() throws Throwable {
        Class<?> core = Class.forName(CORE);
        Method initialize = core.getMethod("initialize");
        initialize.invoke(null);

        Class<?> optionsType = Class.forName(CONTEXT_OPTIONS);
        Object options = optionsType.getConstructor().newInstance();
        Class<?> client = Class.forName(CLIENT);
        Method property = client.getMethod("getBootstrapProperty", String.class);
        copyBoolean(optionsType, options, "mUseStagingBuffers", property.invoke(null,
                "arc3d_context_useStagingBuffers"));
        copyBoolean(optionsType, options, "mAllowGLSPIRV", property.invoke(null,
                "arc3d_context_allowGLSPIRV"));
        Object workarounds = client.getMethod("getGpuDriverBugWorkarounds").invoke(null);
        Field field = optionsType.getField("mDriverBugWorkarounds");
        field.set(options, workarounds);

        Object ok = core.getMethod("initOpenGL", optionsType).invoke(null, options);
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("Core.initOpenGL returned false (GL caps rejected)");
        }
        // sImmediateContext now exists; initUiThread() creates the UI handler and the
        // sUiRecordingContext the text/GUI pipeline requires (Core.requireUiRecordingContext).
        core.getMethod("initUiThread").invoke(null);
        ModReloader.LOGGER.info("[ModReloader] modernui adapter: render-thread init replayed "
                + "(Core.initialize + Core.initOpenGL + Core.initUiThread)");
    }

    private static void copyBoolean(Class<?> type, Object options, String field, Object property)
            throws ReflectiveOperationException {
        if (property != null) {
            type.getField(field).setBoolean(options, Boolean.parseBoolean(String.valueOf(property)));
        }
    }

    private static Object minecraft() {
        try {
            return Class.forName("net.minecraft.client.Minecraft")
                    .getMethod("getInstance").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isSameThread(Object minecraft) throws ReflectiveOperationException {
        return (Boolean) minecraft.getClass().getMethod("isSameThread").invoke(minecraft);
    }

    private static void execute(Object minecraft, Runnable task) throws ReflectiveOperationException {
        minecraft.getClass().getMethod("execute", Runnable.class).invoke(minecraft, task);
    }
}