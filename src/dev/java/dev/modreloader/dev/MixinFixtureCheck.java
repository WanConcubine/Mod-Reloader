package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.inspect.JbrSupport;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Dev-only check for the non-JBR mixin path: hot-add a mod whose mixin targets a
 * class that is <em>not loaded yet</em>, and call that class afterwards.
 *
 * <p>Before 2026-11-16 the engine refused every mod with mixins unless the JVM had
 * JBR's enhanced redefinition, which is stricter than necessary: a mixin applied
 * while the target class is being defined needs no redefinition at all, and a
 * layout-preserving change to a loaded class works with plain hotswap. This check
 * pins the first half of that claim (the fixture's {@code Probe.value()} returns 1
 * without the mixin and 2 with it), so {@code -PnoJbr=true} is expected to PASS.
 *
 * <p>It also calls the fixture's static {@code @Invoker} interface, which is
 * loaded only after the add. That interface is rewritten by Mixin's accessor
 * coprocessor at its class definition; before 2026-11-21 a config prepared by hand
 * had no coprocessor listeners and the call hit Mixin's
 * {@code AssertionError("Mixin application failed!")} stub (issue 65).
 *
 * <p>{@code -PverifyMixinFixture=<fixture jar>}; build the jar with
 * {@code experiments/mixin-fixture/build.sh}.
 */
public final class MixinFixtureCheck {
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final int AFTER_ADD_TICKS = 20;
    private static final int TIMEOUT_TICKS = 20 * 180;

    private static Path jar;
    private static int ticks;
    private static int totalTicks;
    private static boolean added;
    private static boolean done;

    private MixinFixtureCheck() {
    }

    public static void start(Path target) {
        if (jar != null || done) {
            return;
        }
        jar = target.toAbsolutePath().normalize();
        ModReloader.LOGGER.info("[MixinFixtureCheck] hot-add {} ({})", jar, JbrSupport.describe());
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
        if (totalTicks < TITLE_SETTLE_TICKS) {
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
            ModReloader.LOGGER.info("[MixinFixtureCheck] hot-add result: ok={} stage={} kind={} msg={}",
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
        try {
            Class<?> probe = Class.forName("mixinfixture.Probe", true,
                    MixinFixtureCheck.class.getClassLoader());
            Method value = probe.getMethod("value");
            Object result = value.invoke(null);
            ModReloader.LOGGER.info("[MixinFixtureCheck] Probe.value() = {} ({}): expected 2",
                    result, JbrSupport.describe());
            if (!Integer.valueOf(2).equals(result)) {
                problems.add("Probe.value() = " + result
                        + "; the mixin did not apply (1 means the class loaded unmixed)");
            }
        } catch (ClassNotFoundException e) {
            problems.add("mixinfixture.Probe is not on the class path; the fixture jar was not added");
        } catch (Throwable t) {
            problems.add("Probe.value() threw " + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
        checkInvoker(problems);
        finish(problems.isEmpty(), problems);
    }

    /**
     * Loads the static {@code @Invoker} interface only after the add: Mixin must
     * rewrite it while it is defined. A stub here means the config was prepared
     * without Mixin's accessor coprocessor attached (issue 65).
     */
    private static void checkInvoker(List<String> problems) {
        try {
            Class<?> invoker = Class.forName("mixinfixture.mixin.accessor.AccessTargetInvoker", true,
                    MixinFixtureCheck.class.getClassLoader());
            Object result = invoker.getMethod("mixinfixture$hidden").invoke(null);
            ModReloader.LOGGER.info("[MixinFixtureCheck] AccessTargetInvoker.mixinfixture$hidden() = {}"
                    + ": expected reached-target", result);
            if (!"reached-target".equals(result)) {
                problems.add("AccessTargetInvoker.mixinfixture$hidden() = " + result
                        + "; the invoker interface was not rewritten");
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            problems.add("AccessTargetInvoker.mixinfixture$hidden() threw "
                    + cause.getClass().getSimpleName() + ": " + cause.getMessage()
                    + " (a static @Invoker interface kept its Mixin stub; issue 65)");
        }
    }

    private static void finish(boolean ok, List<String> problems) {
        done = true;
        if (ok) {
            ModReloader.LOGGER.info("[MixinFixtureCheck] RESULT: PASS - a mixin into a not-yet-loaded "
                    + "class applied without enhanced redefinition, and a static @Invoker interface "
                    + "loaded after the add was rewritten ({})", JbrSupport.describe());
        } else {
            for (String problem : problems) {
                ModReloader.LOGGER.error("[MixinFixtureCheck] problem: {}", problem);
            }
            ModReloader.LOGGER.error("[MixinFixtureCheck] RESULT: FAIL - {}", problems);
        }
    }
}
