package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Stops Mixin's agent from re-transforming the bytes we already mixed.
 *
 * <p>{@code Instrumentation.redefineClasses} on JBR invokes the registered
 * transformers; Mixin's agent then calls {@code transformClassBytes} on the
 * already-mixed class, applying the active configs a second time. For a plain
 * method mixin that can be harmless, but a MixinExtras {@code @WrapOperation}
 * fails its injection check on the second pass and the agent returns its error
 * bytecode, which surfaces as {@code ClassFormatError: null}.
 *
 * <p>During the redefine window we remove the agent's transformer and add a
 * delegating one: it returns {@code null} (no transform) only when the JVM is
 * redefining one of our targets, and delegates everything else — including
 * normal class loads on other threads — to the original transformer. The
 * original is put back afterwards.
 */
final class MixinAgentGuard implements AutoCloseable {
    private static final String AGENT_TRANSFORMER =
            "org.spongepowered.tools.agent.MixinAgent$Transformer";

    private final Instrumentation owner;
    private final ClassFileTransformer original;
    private final ClassFileTransformer wrapper;

    private MixinAgentGuard(Instrumentation owner, ClassFileTransformer original,
                            ClassFileTransformer wrapper) {
        this.owner = owner;
        this.original = original;
        this.wrapper = wrapper;
    }

    /** Never throws; returns a no-op guard when the agent layout is unknown. */
    static MixinAgentGuard suspend(Instrumentation instrumentation, Set<String> dottedTargets) {
        try {
            // Each Java agent gets its own InstrumentationImpl; the Mixin agent
            // registered its transformer through the instance MixinRuntime created,
            // not through ours (which has an empty transformer list).
            Instrumentation owner = mixinInstrumentation(instrumentation);
            List<String> seen = new ArrayList<>();
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
                    seen.add(delegate.getClass().getName());
                    if (!delegate.getClass().getName().equals(AGENT_TRANSFORMER)) {
                        continue;
                    }
                    if (!owner.removeTransformer(delegate)) {
                        ModReloader.LOGGER.warn(
                                "[ModReloader] mixin agent transformer was not registered; not guarding");
                        return new MixinAgentGuard(instrumentation, null, null);
                    }
                    ClassFileTransformer guard = new Guard(delegate, dottedTargets);
                    owner.addTransformer(guard, true);
                    ModReloader.LOGGER.info(
                            "[ModReloader] mixin agent suspended for redefine of {}", dottedTargets);
                    return new MixinAgentGuard(owner, delegate, guard);
                }
            }
            ModReloader.LOGGER.info("[ModReloader] instrumentation transformers: {}", seen);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot suspend the mixin agent transformer", t);
        }
        return new MixinAgentGuard(instrumentation, null, null);
    }

    @Override
    public void close() {
        if (original == null) {
            return;
        }
        try {
            owner.removeTransformer(wrapper);
            owner.addTransformer(original, true);
            ModReloader.LOGGER.info("[ModReloader] mixin agent restored");
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot restore the mixin agent transformer", t);
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

    private record Guard(ClassFileTransformer delegate, Set<String> dottedTargets)
            implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> redefined,
                                ProtectionDomain domain, byte[] bytes) {
            if (redefined != null && className != null
                    && dottedTargets.contains(className.replace('/', '.'))) {
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
