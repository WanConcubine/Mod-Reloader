package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.modreloader.reflect.UnsafeAccess;
import dev.modreloader.inspect.MixinRuntimeApi;
import dev.modreloader.reflect.AgentTransformers;

/**
 * Stops Mixin's agent from re-transforming the bytes we already mixed.
 *
 * <p>{@code Instrumentation.redefineClasses} on JBR invokes the registered
 * transformers; Mixin's agent then calls {@code transformClassBytes} on the
 * already-mixed class, applying the active configs a second time. A MixinExtras
 * {@code @WrapOperation} fails its injection check on the second pass and the
 * agent returns its error bytecode, which surfaces as {@code ClassFormatError: null}.
 *
 * <p>All registered transformers are wrapped <b>once</b> and stay wrapped for the
 * rest of the process. Each wrapper delegates to its original unless the class is
 * in a live suppression set ({@link #suspend} adds names, {@link #close()} removes
 * them) or a single redefine call opted into blanket suppression
 * ({@link #blanket()}). Removing and re-adding transformers per stage left the
 * wrapper behind when late AT and the mixin stage ran back to back; and the mixin
 * agent turned out not to be the only path that re-applies injections, so a
 * redefine now suppresses every transformer for that call.
 *
 * <p>Never throws: when the agent layout is unknown it logs the transformer list
 * and behaves as a no-op (the old {@code ClassFormatError} comes back).
 */
final class MixinAgentGuard implements AutoCloseable {
    private static final String AGENT_TRANSFORMER =
            "org.spongepowered.tools.agent.MixinAgent$Transformer";

    private static final Object LOCK = new Object();
    private static final Set<String> SUPPRESSED = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.atomic.AtomicInteger BLANKET =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final AtomicBoolean INSTALL_ATTEMPTED = new AtomicBoolean();
    private static volatile boolean installed;
    private static volatile boolean agentPresent;
    private static volatile String detail = "not probed";

    private final Set<String> mine = new HashSet<>();
    private boolean armed;

    private MixinAgentGuard() {
    }

    /**
     * True when a redefine is safe: no Mixin agent on this side, or its
     * transformer was wrapped. Only meaningful after the first {@link #suspend}.
     */
    static boolean safeToRedefine() {
        return !agentPresent || installed;
    }

    /** Why the guard was/was not installed; goes into the refusal message. */
    static String detail() {
        return detail;
    }

    /** Adds the targets to the suppression window; never throws. */
    static MixinAgentGuard suspend(Instrumentation instrumentation, Set<String> dottedTargets) {
        MixinAgentGuard guard = new MixinAgentGuard();
        guard.mine.addAll(dottedTargets);
        synchronized (LOCK) {
            if (INSTALL_ATTEMPTED.compareAndSet(false, true)) {
                install(instrumentation);
            }
            if (installed) {
                SUPPRESSED.addAll(dottedTargets);
                guard.armed = true;
            }
        }
        return guard;
    }

    /**
     * Suppresses every transformer for the duration of one redefine call. The
     * bytes passed to {@code redefineClasses} are already final, so letting any
     * transformer process them again is wrong. Keep the window as short as the
     * call itself: other threads loading classes meanwhile would also skip their
     * transforms.
     */
    static AutoCloseable blanket() {
        BLANKET.incrementAndGet();
        return BLANKET::decrementAndGet;
    }

    @Override
    public void close() {
        if (!armed) {
            return;
        }
        synchronized (LOCK) {
            SUPPRESSED.removeAll(mine);
            mine.clear();
            armed = false;
        }
    }

    private static void install(Instrumentation instrumentation) {
        try {
            AgentTransformers.Scan scan = AgentTransformers.scan(
                    MixinRuntimeApi.class.getClassLoader(), instrumentation);
            // Only an *active* Mixin agent can re-transform bytes. With no
            // MixinRuntime the agent class ships with Mixin but has no transformer
            // registered (measured on a dedicated server 2026-11-21), so treating
            // "class present" as unsafe refused every late AT there.
            boolean mixinRuntime = net.neoforged.fml.ModList.get() != null
                    && net.neoforged.fml.ModList.get().isLoaded("mr");
            agentPresent = !scan.found().isEmpty() || (scan.agentPresent() && mixinRuntime);
            detail = scan.detail();
            if (!scan.found().isEmpty()) {
                detail = scan.detail();
            } else if (scan.agentPresent() && !mixinRuntime) {
                detail = "MixinAgent has no transformer and MixinRuntime is absent; "
                        + "nothing can re-transform on redefine";
            }
            int replaced = 0;
            for (AgentTransformers.Found found : scan.found()) {
                if (found.instrumentation().removeTransformer(found.transformer())) {
                    found.instrumentation().addTransformer(new Guard(found.transformer()), true);
                    replaced++;
                }
            }
            installed = replaced > 0;
            if (installed) {
                ModReloader.LOGGER.info(
                        "[ModReloader] mixin agent guard installed ({} transformer(s) wrapped; "
                                + "targets are suppressed only during redefine windows)", replaced);
            } else if (!scan.found().isEmpty()) {
                detail = "MixinAgent transformer found but could not be wrapped";
                ModReloader.LOGGER.warn("[ModReloader] {}", detail);
            } else {
                ModReloader.LOGGER.info("[ModReloader] mixin agent guard not installed: {}", detail);
            }
        } catch (Throwable t) {
            detail = "guard installation failed: " + t.getClass().getSimpleName();
            ModReloader.LOGGER.warn("[ModReloader] cannot install the mixin agent guard", t);
        }
    }

    private record Guard(ClassFileTransformer delegate) implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                ProtectionDomain domain, byte[] bytes) {
            // A redefine call can opt into blanket suppression: JBR's enhanced
            // redefine has been seen to call transformers without a usable class
            // identity, so name matching is not enough there.
            if (BLANKET.get() > 0) {
                return null;
            }
            // Suppress by name inside the window. JBR's redefine path is not
            // guaranteed to pass classBeingRedefined (and has been seen to pass a
            // null internal name), so requiring either would let the agent
            // double-transform the class we are redefining.
            String name = className != null ? className.replace('/', '.')
                    : redefined == null ? null : redefined.getName();
            if (name != null && SUPPRESSED.contains(name)) {
                if (LOGGED.add(name)) {
                    ModReloader.LOGGER.info(
                            "[ModReloader] mixin agent suppressed for {} (beingRedefined={})",
                            name, redefined != null);
                }
                return null;
            }
            try {
                return delegate.transform(loader, className, redefined, domain, bytes);
            } catch (Throwable t) {
                ModReloader.LOGGER.warn("[ModReloader] delegated mixin transform failed for {}",
                        className, t);
                return null;
            }
        }
    }
}
