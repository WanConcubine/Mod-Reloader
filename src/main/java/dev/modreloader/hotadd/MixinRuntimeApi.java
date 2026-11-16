package dev.modreloader.hotadd;

import org.spongepowered.asm.mixin.extensibility.IMixinConfig;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

/**
 * The five MixinRuntime (modId {@code mr}) capabilities this runtime uses, behind
 * reflection.
 *
 * <p>Note what is deliberately <em>not</em> used: MixinRuntime's
 * {@code MixinTransformerUtils.transform(Class)}. It captures the bytes by
 * retransforming the class and then redefines it, so calling it mutates the live
 * class — measured 2026-11-16: an interface mixin that we had just refused still
 * ended up on {@code EditBox} while our own report said {@code redefined=[]}.
 * The engine therefore does its own byte-level transform
 * ({@code IMixinTransformer#transformClassBytes}) and calls
 * {@code redefineClasses} itself.
 *
 * <p>Why reflection: MixinRuntime is a third-party optional dependency whose jar
 * is not distributed with this repository. Compiling against it left a fresh
 * clone unable to build at all (24 symbol errors) for a dependency the runtime
 * treats as optional. Resolving it here at call time removes the compile-time
 * link; {@link #probe()} keeps the drift check explicit so a changed MixinRuntime
 * still produces a readable failure instead of a {@code NoSuchMethodError}
 * halfway through mixin application.
 *
 * <p>Parameter types below are Mixin's own ({@link IMixinConfig},
 * {@link IMixinTransformer}), not MixinRuntime's — both live in the game layer, so
 * they are the same {@code Class} objects MixinRuntime declares.
 */
public final class MixinRuntimeApi {
    private static final String MOD_CLASS = "cn.ksmcbrigade.mr.MixinRuntimeMod";
    private static final String CONFIG_UTILS = "cn.ksmcbrigade.mr.utils.mixin.MixinConfigUtils";
    private static final String PROCESSOR_UTILS = "cn.ksmcbrigade.mr.utils.mixin.MixinProcessorUtils";
    private static final String TRANSFORMER_UTILS = "cn.ksmcbrigade.mr.utils.mixin.MixinTransformerUtils";

    /** Resolved handles; {@code null} until the first call succeeds. */
    private static volatile Handles handles;
    private static volatile boolean readable;

    private record Handles(MethodHandle onSelect, MethodHandle prepare, MethodHandle getProcessor,
                           MethodHandle getConfigs, MethodHandle getPendingConfigs,
                           MethodHandle setConfigs, MethodHandle setPendingConfigs,
                           MethodHandle addIntoProcessor, MethodHandle getTransformer) {
    }

    private MixinRuntimeApi() {
    }

    /**
     * Resolves every handle and returns one readable entry per missing piece;
     * empty means the reflected surface still matches. Never throws.
     */
    public static List<String> probe() {
        List<String> missing = new ArrayList<>();
        try {
            resolve();
        } catch (ClassNotFoundException e) {
            missing.add("MixinRuntime class missing: " + e.getMessage());
        } catch (NoSuchMethodException e) {
            missing.add("MixinRuntime method missing: " + e.getMessage());
        } catch (Throwable t) {
            missing.add("MixinRuntime API: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        return missing;
    }

    public static void onSelect(IMixinConfig config) {
        invoke(handles().onSelect(), config);
    }

    public static void prepare(IMixinConfig config, IMixinTransformer transformer) {
        invoke(handles().prepare(), config, transformer);
    }

    public static Object getProcessor(IMixinTransformer transformer) {
        return invoke(handles().getProcessor(), transformer);
    }

    public static List<Object> getConfigs(Object processor) {
        return list(invoke(handles().getConfigs(), processor));
    }

    public static List<Object> getPendingConfigs(Object processor) {
        return list(invoke(handles().getPendingConfigs(), processor));
    }

    public static void setConfigs(Object processor, List<Object> configs) {
        invoke(handles().setConfigs(), processor, configs);
    }

    public static void setPendingConfigs(Object processor, List<Object> configs) {
        invoke(handles().setPendingConfigs(), processor, configs);
    }

    public static void addIntoProcessor(Object processor, IMixinConfig config) {
        invoke(handles().addIntoProcessor(), processor, config);
    }

    public static IMixinTransformer getTransformer() {
        return (IMixinTransformer) invoke(handles().getTransformer());
    }

    private static Handles handles() {
        Handles current = handles;
        if (current == null) {
            try {
                current = resolve();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "MixinRuntime (mr) does not expose the API this runtime needs: " + e, e);
            }
        }
        // Only on the call path: the start-up capability probe must not attach the
        // agent just to read a module edge.
        ensureReadable();
        return current;
    }

    private static synchronized Handles resolve() throws ClassNotFoundException, NoSuchMethodException {
        Handles current = handles;
        if (current != null) {
            return current;
        }
        ClassLoader loader = MixinRuntimeApi.class.getClassLoader();
        Class<?> modClass = Class.forName(MOD_CLASS, false, loader);
        Class<?> configUtils = Class.forName(CONFIG_UTILS, false, loader);
        Class<?> processorUtils = Class.forName(PROCESSOR_UTILS, false, loader);
        Class<?> transformerUtils = Class.forName(TRANSFORMER_UTILS, false, loader);

        Class<?> configType = IMixinConfig.class;
        Class<?> transformerType = IMixinTransformer.class;
        Handles resolved = new Handles(
                staticMethod(configUtils, "onSelect",
                        MethodType.methodType(void.class, configType)),
                staticMethod(configUtils, "prepare",
                        MethodType.methodType(void.class, configType, transformerType)),
                staticMethod(processorUtils, "getProcessor",
                        MethodType.methodType(Object.class, transformerType)),
                staticMethod(processorUtils, "getConfigs",
                        MethodType.methodType(List.class, Object.class)),
                staticMethod(processorUtils, "getPendingConfigs",
                        MethodType.methodType(List.class, Object.class)),
                staticMethod(processorUtils, "setConfigs",
                        MethodType.methodType(void.class, Object.class, List.class)),
                staticMethod(processorUtils, "setPendingConfigs",
                        MethodType.methodType(void.class, Object.class, List.class)),
                staticMethod(processorUtils, "addIntoProcessor",
                        MethodType.methodType(void.class, Object.class, configType)),
                staticMethod(transformerUtils, "getTransformer",
                        MethodType.methodType(transformerType)));
        handles = resolved;
        return resolved;
    }

    /**
     * Adds a read edge from our module to MixinRuntime's, once.
     *
     * <p>The trusted lookup does not need it, but anything that touches
     * MixinRuntime classes directly (or a future non-reflective call) does, and
     * this is exactly the edge the engine used to add before every mixin step.
     */
    private static void ensureReadable() {
        if (readable) {
            return;
        }
        synchronized (MixinRuntimeApi.class) {
            if (readable) {
                return;
            }
            readable = true;
            try {
                Module ours = MixinRuntimeApi.class.getModule();
                Module mr = Class.forName(MOD_CLASS, false, MixinRuntimeApi.class.getClassLoader())
                        .getModule();
                if (ours.isNamed() && mr.isNamed() && ours != mr) {
                    JvmAccess.get().redefineModule(ours, java.util.Set.of(mr), java.util.Map.of(),
                            java.util.Map.of(), java.util.Set.of(), java.util.Map.of());
                }
            } catch (Throwable t) {
                // Not fatal: the resolved handles do not depend on this edge.
                dev.modreloader.ModReloader.LOGGER.warn(
                        "[ModReloader] cannot add a module read edge to MixinRuntime", t);
            }
        }
    }

    private static MethodHandle staticMethod(Class<?> owner, String name, MethodType type)
            throws NoSuchMethodException {
        try {
            return UnsafeAccess.trusted().findStatic(owner, name, type);
        } catch (IllegalAccessException e) {
            throw new NoSuchMethodException(owner.getName() + "#" + name + " (" + e + ")");
        }
    }

    private static Object invoke(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (Throwable t) {
            throw new IllegalStateException("MixinRuntime call failed: " + t, t);
        }
    }

    private static List<Object> list(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Object> cast = (List<Object>) list;
            return cast;
        }
        throw new IllegalStateException("expected a List from MixinRuntime, got " + value.getClass());
    }
}
