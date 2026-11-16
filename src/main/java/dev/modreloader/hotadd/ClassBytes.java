package dev.modreloader.hotadd;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Reads the current class-file bytes of an already-loaded class.
 *
 * <p>Needed before a mixin is applied to a loaded target: the bytes are what the
 * rollback hands back to {@code redefineClasses}. {@code Instrumentation} has no
 * "give me the bytes" call, so we register a pass-through transformer that only
 * records the byte array the JVM already passes to every transformer, then
 * retransform the one class. Returning {@code null} means "no transformation", so
 * the class is left exactly as it was.
 *
 * <p>This used to live in MixinRuntime's {@code InstUtils}. It is plain JVM API
 * (nothing mod-loading specific), and owning it removes a compile-time dependency
 * whose jar is not shipped with the repository.
 */
public final class ClassBytes {
    private ClassBytes() {
    }

    /**
     * @return the bytes the JVM hands to transformers for {@code type}, or
     *         {@code null} if the class is not retransformable
     */
    public static byte[] of(Instrumentation instrumentation, Class<?> type) {
        if (!instrumentation.isModifiableClass(type)
                || !instrumentation.isRetransformClassesSupported()) {
            return null;
        }
        byte[][] captured = new byte[1][];
        ClassFileTransformer recorder = new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className, Class<?> beingRedefined,
                                    ProtectionDomain domain, byte[] bytes) {
                if (type.equals(beingRedefined)) {
                    captured[0] = bytes;
                }
                return null;
            }
        };
        instrumentation.addTransformer(recorder, true);
        try {
            instrumentation.retransformClasses(type);
        } catch (Throwable t) {
            throw new IllegalStateException("cannot read class bytes for " + type.getName(), t);
        } finally {
            instrumentation.removeTransformer(recorder);
        }
        return captured[0];
    }
}
