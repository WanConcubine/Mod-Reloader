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
public final class ModernUIAdapter {
    static final String PROPERTY = "modreloader.modernuiAdapter";
    private static final String MOD_ID = "modernui";
    private static final String CORE = "icyllis.modernui.core.Core";
    private static final String CONTEXT_OPTIONS = "icyllis.arc3d.engine.ContextOptions";
    private static final String CLIENT = "icyllis.modernui.mc.ModernUIClient";
    /** Every live Font Minecraft keeps: ChatScreen's EditBox uses fontFilterFishy. */
    private static final String[] FONT_FIELDS = {"font", "fontFilterFishy"};

    private ModernUIAdapter() {
    }

    public static boolean enabled() {
        return Boolean.parseBoolean(System.getProperty(PROPERTY, "false"));
    }

    /**
     * Called before the mod's {@code @Mod} constructor: ModernUI starts its own UI
     * thread there, and that thread creates the recording context from whatever GPU
     * device exists at that moment. The immediate context therefore has to be in
     * place first. Returns {@code null} when the adapter does not apply or succeeded;
     * otherwise why it failed (caller: clean failure, nothing is half-initialized yet).
     */
    static String beforeConstruct(java.util.Set<String> ids) {
        if (ids == null || !ids.contains(MOD_ID) || !enabled()
                || !dev.modreloader.Platform.isClient()) {
            return null;
        }
        return initialize();
    }

    /**
     * After the constructor: its {@code Font} mixin adds the instance field
     * {@code modernUI_MC$textRenderer}, initialized only from Font's constructor.
     * The {@code Minecraft.font} object predates the mixin, so its field is null and
     * the first drawn string (a toast) NPEs. Backfill it from the same source the
     * mixin uses ({@code TextLayoutEngine.getInstance().getTextRenderer()}).
     */
    static String afterConstruct(java.util.Set<String> ids) {
        if (ids == null || !ids.contains(MOD_ID) || !enabled()
                || !dev.modreloader.Platform.isClient()) {
            return null;
        }
        try {
            Class<?> engineType = Class.forName("icyllis.modernui.mc.text.TextLayoutEngine");
            Object engine = engineType.getMethod("getInstance").invoke(null);
            Object renderer = engine.getClass().getMethod("getTextRenderer").invoke(engine);
            if (renderer == null) {
                return "TextLayoutEngine.getTextRenderer() returned null";
            }
            Object minecraft = minecraft();
            if (minecraft == null) {
                return "no client instance";
            }
            Class<?> fontType = Class.forName("net.minecraft.client.gui.Font");
            boolean any = false;
            for (String name : FONT_FIELDS) {
                Object font = minecraft.getClass().getField(name).get(minecraft);
                if (font == null) {
                    continue;
                }
                dev.modreloader.reflect.UnsafeAccess.set(font, fontType,
                        "modernUI_MC$textRenderer", renderer);
                any = true;
                ModReloader.LOGGER.info("[ModReloader] modernui adapter: backfilled "
                        + "Font.modernUI_MC$textRenderer on Minecraft.{} (id={})",
                        name, System.identityHashCode(font));
            }
            return any ? null : "no live Font instance to backfill";
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] modernui adapter (after construct) failed", t);
            return rootCause(t);
        }
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

    /**
     * Safety net for screens that open later (ChatScreen's EditBox): if the live
     * Font lost/never had the mixin field, fill it from the same source the mixin
     * uses. Cheap: one field read; logs only when it actually writes.
     */
    public static void ensureFontBackfilled() {
        if (!enabled() || !dev.modreloader.Platform.isClient()
                || !net.neoforged.fml.ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        try {
            Object minecraft = minecraft();
            if (minecraft == null) {
                return;
            }
            Class<?> fontType = Class.forName("net.minecraft.client.gui.Font");
            Object renderer = null;
            for (String name : FONT_FIELDS) {
                Object font = minecraft.getClass().getField(name).get(minecraft);
                if (font == null) {
                    continue;
                }
                Object current = dev.modreloader.reflect.UnsafeAccess.get(font, fontType,
                        "modernUI_MC$textRenderer");
                if (current != null) {
                    continue;
                }
                if (renderer == null) {
                    Class<?> engineType = Class.forName("icyllis.modernui.mc.text.TextLayoutEngine");
                    Object engine = engineType.getMethod("getInstance").invoke(null);
                    renderer = engine.getClass().getMethod("getTextRenderer").invoke(engine);
                    if (renderer == null) {
                        return;
                    }
                }
                dev.modreloader.reflect.UnsafeAccess.set(font, fontType,
                        "modernUI_MC$textRenderer", renderer);
                ModReloader.LOGGER.info("[ModReloader] modernui adapter: re-backfilled "
                        + "Minecraft.{} Font field (was null, id={})",
                        name, System.identityHashCode(font));
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.debug("[ModReloader] modernui font re-backfill failed", t);
        }
    }

    private static void run() throws Throwable {
        Class<?> core = Class.forName(CORE);
        core.getMethod("initialize").invoke(null);

        Class<?> optionsType = Class.forName(CONTEXT_OPTIONS);
        Object options = optionsType.getConstructor().newInstance();
        // Bootstrap properties are optional tuning; reading them must not pull in
        // ModernUIClient's static state before its @Mod constructor ran.
        try {
            Class<?> client = Class.forName(CLIENT);
            Method property = client.getMethod("getBootstrapProperty", String.class);
            copyBoolean(optionsType, options, "mUseStagingBuffers", property.invoke(null,
                    "arc3d_context_useStagingBuffers"));
            copyBoolean(optionsType, options, "mAllowGLSPIRV", property.invoke(null,
                    "arc3d_context_allowGLSPIRV"));
            Object workarounds = client.getMethod("getGpuDriverBugWorkarounds").invoke(null);
            optionsType.getField("mDriverBugWorkarounds").set(options, workarounds);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] modernui adapter: bootstrap properties "
                    + "unavailable, using ContextOptions defaults", t);
        }

        Object ok = core.getMethod("initOpenGL", optionsType).invoke(null, options);
        if (!Boolean.TRUE.equals(ok)) {
            throw new IllegalStateException("Core.initOpenGL returned false (GL caps rejected)");
        }
        // initUiThread() belongs to ModernUI's own UI thread (started by the @Mod
        // constructor); calling it here would bind sUiThread to the render thread and
        // make that thread skip creating the recording context.
        ModReloader.LOGGER.info("[ModReloader] modernui adapter: Core.initialize + Core.initOpenGL "
                + "replayed before the constructor (UI thread will create the recording context)");
    }

    /**
     * After the resource reload / shader rebuild: ModernUI's config reload runs
     * {@code TextRenderType.toggleSDFShaders} during construct, before its shader
     * JSONs were registered, so the "current" SDF shaders stay null and the first
     * drawn string NPEs in {@code RenderType.draw}. preloadShaders() re-reads the
     * now-registered instances.
     */
    static String afterClientStartup(java.util.Set<String> ids) {
        if (ids == null || !ids.contains(MOD_ID) || !enabled()
                || !dev.modreloader.Platform.isClient()) {
            return null;
        }
        try {
            Class<?> type = Class.forName("icyllis.modernui.mc.text.TextRenderType");
            type.getMethod("preloadShaders").invoke(null);
            ModReloader.LOGGER.info("[ModReloader] modernui adapter: TextRenderType.preloadShaders() "
                    + "replayed after the shader rebuild");
            ensureFontBackfilled();
            return null;
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] modernui adapter (shaders) failed", t);
            return rootCause(t);
        }
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