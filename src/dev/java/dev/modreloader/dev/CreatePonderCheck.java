package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only check for issue 65 with the real Create jar.
 *
 * <p>Ponder builds its render types in {@code PonderRenderTypes.<clinit>} by calling
 * the static {@code @Invoker} {@code RenderTypeAccessor.catnip$create}. That
 * interface is a loadable mixin which Mixin rewrites only when the interface class
 * is defined, so a hot-add that prepares the config without Mixin's coprocessor
 * listeners leaves the {@code AssertionError("Mixin application failed!")} stub and
 * the first render in a hot-added Create crashes.
 *
 * <p>This check hot-adds Create at the title screen, then forces the same class
 * initialisation with reflection. It does not need a world; entering a world is
 * still the owner's manual verification.
 *
 * <p>{@code -PverifyCreatePonder=<create jar>}
 */
public final class CreatePonderCheck {
    private static final String PONDER_RENDER_TYPES = "net.createmod.catnip.render.PonderRenderTypes";
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final int AFTER_ADD_TICKS = 20;
    private static final int TIMEOUT_TICKS = 20 * 300;

    private static Path jar;
    private static int ticks;
    private static int totalTicks;
    private static boolean added;
    private static boolean done;

    private CreatePonderCheck() {
    }

    public static void start(Path target) {
        if (jar != null || done) {
            return;
        }
        jar = target.toAbsolutePath().normalize();
        ModReloader.LOGGER.info("[CreatePonderCheck] armed: jar={}", jar.getFileName());
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (done) {
            return;
        }
        totalTicks++;
        if (totalTicks > TIMEOUT_TICKS) {
            finish(false, List.of("timeout"));
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (totalTicks < TITLE_SETTLE_TICKS || mc.getOverlay() != null) {
            return;
        }
        if (!added) {
            added = true;
            HotAddEngine.Result result;
            try {
                result = HotAddEngine.add(jar);
            } catch (Throwable t) {
                finish(false, List.of("hot-add threw " + t.getClass().getSimpleName() + ": "
                        + t.getMessage()));
                return;
            }
            ModReloader.LOGGER.info("[CreatePonderCheck] hot-add result: ok={} stage={} kind={} msg={}",
                    result.ok(), result.stage(), result.kind(), result.message());
            if (!result.ok()) {
                finish(false, List.of("hot-add failed at " + result.stage() + "/" + result.kind()
                        + ": " + result.message()));
                return;
            }
            ticks = 0;
            return;
        }
        if (++ticks < AFTER_ADD_TICKS) {
            return;
        }
        check();
    }

    private static void check() {
        List<String> problems = new ArrayList<>();
        Class<?> ponder;
        try {
            ponder = Class.forName(PONDER_RENDER_TYPES, true,
                    CreatePonderCheck.class.getClassLoader());
        } catch (Throwable t) {
            Throwable cause = t instanceof ExceptionInInitializerError && t.getCause() != null
                    ? t.getCause() : t;
            problems.add(PONDER_RENDER_TYPES + " failed to initialise: "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            finish(false, problems);
            return;
        }
        for (String method : List.of("fluid", "outlineSolid")) {
            try {
                Method mine = ponder.getMethod(method);
                Object value = mine.invoke(null);
                ModReloader.LOGGER.info("[CreatePonderCheck] {}.{}() = {}", PONDER_RENDER_TYPES,
                        method, value);
                if (value == null) {
                    problems.add(PONDER_RENDER_TYPES + "." + method + "() returned null");
                }
            } catch (Throwable t) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                problems.add(PONDER_RENDER_TYPES + "." + method + "() threw "
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
        finish(problems.isEmpty(), problems);
    }

    private static void finish(boolean ok, List<String> problems) {
        done = true;
        if (ok) {
            ModReloader.LOGGER.info("[CreatePonderCheck] RESULT: PASS - Ponder's static @Invoker "
                    + "interface was rewritten and its render types initialise after a hot-add");
        } else {
            for (String problem : problems) {
                ModReloader.LOGGER.error("[CreatePonderCheck] problem: {}", problem);
            }
            ModReloader.LOGGER.error("[CreatePonderCheck] RESULT: FAIL - {}", problems);
        }
        Minecraft.getInstance().stop();
    }
}