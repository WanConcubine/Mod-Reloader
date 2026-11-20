package dev.modreloader.reflect;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only discovery of the Mixin agent's transformer, the one that re-applies
 * injections to bytes that were already mixed (issue 40).
 *
 * <p>The agent keeps its own {@link Instrumentation} instance; the one our
 * {@link JvmAccess} agent creates has an empty transformer list. So the lookup is:
 * static {@code MixinAgent.instrumentation} first, and the caller's instrumentation
 * only as a fallback.
 *
 * <p>This lives in {@code reflect} because both the redefine paths
 * ({@code hotadd}) and the capability report ({@code capability}) need it, and it
 * is pure JVM introspection.
 */
public final class AgentTransformers {
    public static final String MIXIN_AGENT = "org.spongepowered.tools.agent.MixinAgent";
    public static final String MIXIN_TRANSFORMER = MIXIN_AGENT + "$Transformer";

    /** One registered Mixin transformer and the instrumentation that owns it. */
    public record Found(Instrumentation instrumentation, ClassFileTransformer transformer) {
    }

    /**
     * @param agentPresent false when the Mixin agent class is not on this side at
     *                     all - then there is nothing to guard
     * @param found        entries a guard could wrap
     * @param detail       human sentence for logs and refusal messages
     */
    public record Scan(boolean agentPresent, List<Found> found, String detail) {
        /** True when a redefine is safe: no agent, or its transformer is wrapped. */
        public boolean safeAfterGuard(boolean guardInstalled) {
            return !agentPresent || guardInstalled;
        }
    }

    private AgentTransformers() {
    }

    public static Scan scan(ClassLoader mixinLoader, Instrumentation fallback) {
        Instrumentation owner = null;
        boolean present = false;
        String detail;
        if (mixinLoader == null) {
            detail = "no class loader for the Mixin agent";
        } else {
            try {
                Class<?> agent = Class.forName(MIXIN_AGENT, false, mixinLoader);
                present = true;
                Object agentInstrumentation = UnsafeAccess.getStatic(agent, "instrumentation");
                if (agentInstrumentation instanceof Instrumentation instrumentation) {
                    owner = instrumentation;
                }
                detail = owner == null
                        ? "MixinAgent is present but its Instrumentation is not readable"
                        : null;
            } catch (ClassNotFoundException e) {
                detail = "MixinAgent is not on this side (nothing to guard)";
            } catch (Throwable t) {
                present = true;
                detail = "MixinAgent lookup failed: " + t.getClass().getSimpleName();
            }
        }
        if (owner == null) {
            owner = fallback;
        }
        if (owner == null) {
            return new Scan(present, List.of(), detail);
        }

        List<Found> found = new ArrayList<>();
        boolean sawList = false;
        for (String field : new String[]{"mRetransfomableTransformerManager", "mTransformerManager"}) {
            try {
                Object manager = UnsafeAccess.get(owner, owner.getClass(), field);
                if (manager == null) {
                    continue;
                }
                Object[] infos = (Object[]) UnsafeAccess.get(manager, manager.getClass(),
                        "mTransformerList");
                if (infos == null) {
                    continue;
                }
                sawList = true;
                for (Object info : infos) {
                    if (info == null) {
                        continue;
                    }
                    Object transformer = UnsafeAccess.get(info, info.getClass(), "mTransformer");
                    if (transformer instanceof ClassFileTransformer delegate
                            && delegate.getClass().getName().equals(MIXIN_TRANSFORMER)) {
                        found.add(new Found(owner, delegate));
                    }
                }
            } catch (Throwable ignored) {
                // try the other manager field
            }
        }
        if (!found.isEmpty()) {
            detail = "MixinAgent transformer found (" + found.size() + ")";
        } else if (present && sawList) {
            detail = "MixinAgent is present but no MixinAgent$Transformer is registered";
        } else if (present) {
            detail = "MixinAgent is present but the instrumentation transformer list is unreadable";
        }
        return new Scan(present, List.copyOf(found), detail);
    }
}