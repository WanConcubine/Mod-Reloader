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

    private final Set<String> mine = new HashSet<>();
    private boolean armed;

    private MixinAgentGuard() {
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
            // Each Java agent gets its own InstrumentationImpl; the Mixin agent
            // registered its transformer through the instance MixinRuntime created,
            // not through ours (which has an empty transformer list).
            Instrumentation owner = mixinInstrumentation(instrumentation);
            List<String> seen = new ArrayList<>();
            int replaced = 0;
            for (String field : new String[]{
                    "mRetransfomableTransformerManager", "mTransformerManager"}) {
                Object manager = UnsafeAccess.get(owner, owner.getClass(), field);
                if (manager == null) {
                    continue;
                }
                Object[] infos = (Object[]) UnsafeAccess.get(manager, manager.getClass(),
                        "mTransformerList");
                if (infos == null) {
                    continue;
                }
                for (Object info : infos) {
                    if (info == null) {
                        continue;
                    }
                    Object transformer = UnsafeAccess.get(info, info.getClass(), "mTransformer");
                    if (!(transformer instanceof ClassFileTransformer delegate)) {
                        continue;
                    }
                    String name = delegate.getClass().getName();
                    seen.add(name + "@" + System.identityHashCode(delegate));
                    // Only the Mixin agent is wrapped. Other transformers (module
                    // tracker, dist cleaner) keep their position; a single redefine
                    // opts into blanket suppression instead, which covers them too
                    // without re-registering anything mid-load.
                    if (!name.equals(AGENT_TRANSFORMER)) {
                        continue;
                    }
                    if (owner.removeTransformer(delegate)) {
                        owner.addTransformer(new Guard(delegate), true);
                        replaced++;
                    }
                }
            }
            installed = replaced > 0;
            if (installed) {
                ModReloader.LOGGER.info(
                        "[ModReloader] mixin agent guard installed ({} transformer(s) wrapped; "
                                + "targets are suppressed only during redefine windows)", replaced);
            } else {
                ModReloader.LOGGER.info("[ModReloader] instrumentation transformers: {}", seen);
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot install the mixin agent guard", t);
        }
    }

    private static Instrumentation mixinInstrumentation(Instrumentation fallback) {
        try {
            Class<?> agent = Class.forName("org.spongepowered.tools.agent.MixinAgent", false,
                    MixinRuntimeApi.class.getClassLoader());
            Instrumentation mixin = UnsafeAccess.getStatic(agent, "instrumentation");
            return mixin == null ? fallback : mixin;
        } catch (Throwable t) {
            return fallback;
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