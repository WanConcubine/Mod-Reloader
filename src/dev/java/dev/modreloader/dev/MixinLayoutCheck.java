package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.HotAddEngine;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.nio.file.Path;
import java.util.Arrays;

/**
 * Dev-only check for the "layout change on an already-loaded class" boundary.
 *
 * <p>An interface/accessor mixin (e.g. Searchables' {@code AccessEditBox}) adds an
 * interface to its target. JBR's enhanced redefinition can add fields and methods
 * but cannot change a loaded class's superclass or interfaces, so this only fails
 * when the target is <em>already loaded</em> — the same add at the title screen
 * succeeds, because the target is loaded for the first time with the mixin in
 * place.
 *
 * <p>The runtime must therefore refuse this <em>before</em> mutating anything.
 * This harness loads the target first, then hot-adds the jar and asserts:
 * the add fails with a layout explanation, nothing was rolled back (nothing was
 * changed), and the target still does not implement the mixin's interface.
 *
 * <p>{@code -PverifyMixinLayout=<jar> -PverifyMixinLayoutTarget=<class name>}
 */
public final class MixinLayoutCheck {
    private static final int SETTLE_TICKS = 100;

    private static Path jar;
    private static String target;
    private static boolean started;
    private static int ticks;

    private MixinLayoutCheck() {
    }

    public static void start(String jarPath, String targetClass) {
        if (started) {
            return;
        }
        jar = Path.of(jarPath).toAbsolutePath().normalize();
        target = targetClass;
        started = true;
        ModReloader.LOGGER.info("[MixinLayout] armed: jar={} target={}", jar.getFileName(), target);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (!started) {
            return;
        }
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        if (ticks < SETTLE_TICKS || mc.getOverlay() != null) {
            return;
        }
        started = false;
        run();
    }

    private static void run() {
        boolean ok = true;
        Class<?> loaded;
        try {
            loaded = Class.forName(target, false, MixinLayoutCheck.class.getClassLoader());
            ModReloader.LOGGER.info("[MixinLayout] pre-loaded {} (interfaces={})", target,
                    Arrays.stream(loaded.getInterfaces()).map(Class::getName).toList());
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[MixinLayout] cannot load {} first", target, t);
            ModReloader.LOGGER.error("[MixinLayout] RESULT: FAIL");
            return;
        }

        HotAddEngine.Result result = HotAddEngine.add(jar);
        ModReloader.LOGGER.info("[MixinLayout] add result: ok={} stage={} kind={} msg={}",
                result.ok(), result.stage(), result.kind(), result.message());

        boolean jbr = dev.modreloader.inspect.JbrSupport.available();
        if (jbr) {
            // Measured 2026-11-16: JBR accepts an added interface on a loaded class
            // (EditBox via Searchables, AbstractWidget via LSP), so this must work.
            ok &= expect(result.ok(), "with JBR the add should succeed: " + result.message());
            ok &= expect(result.message().contains("redefined"),
                    "the target should have been redefined: " + result.message());
            ok &= expect(Arrays.stream(loaded.getInterfaces())
                            .anyMatch(type -> type.getName().contains("searchables")),
                    "the interface mixin should be visible on the class: "
                            + Arrays.stream(loaded.getInterfaces()).map(Class::getName).toList());
        } else {
            // Without enhanced redefinition the same change is impossible, and it must
            // be refused before anything is mutated.
            ok &= expect(!result.ok(), "without JBR the add must be refused");
            ok &= expect(result.message().contains("JBR"), "the message should name JBR: "
                    + result.message());
            ok &= expect(!Arrays.stream(loaded.getInterfaces())
                            .anyMatch(type -> type.getName().contains("searchables")),
                    "the target must be unchanged after the refusal");
        }

        ModReloader.LOGGER.info("[MixinLayout] RESULT: {}", ok ? "PASS" : "FAIL");
        Minecraft.getInstance().stop();
    }

    private static boolean expect(boolean condition, String failure) {
        if (!condition) {
            ModReloader.LOGGER.error("[MixinLayout] FAIL: {}", failure);
        }
        return condition;
    }
}
