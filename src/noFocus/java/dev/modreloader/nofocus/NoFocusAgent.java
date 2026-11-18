package dev.modreloader.nofocus;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev-only JVM agent: create the Minecraft window without taking input focus.
 *
 * <p>This is for automated harnesses. A GLFW window that is shown normally
 * activates the game and steals the user's foreground app. GLFW's
 * {@code GLFW_FOCUS_ON_SHOW} hint prevents that, but Minecraft does not expose
 * it. The agent rewrites {@code com.mojang.blaze3d.platform.Window.<init>} to
 * call {@code glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE)} immediately after
 * {@code glfwDefaultWindowHints()} (which would otherwise reset it).
 *
 * <p>Loaded only through {@code -javaagent:...} when {@code -PnoFocus=true};
 * it is not part of the mod jar and cannot affect normal play. The window is
 * still visible (screenshots still work); it simply does not become the active
 * application.
 */
public final class NoFocusAgent {
    /** GLFW_FOCUS_ON_SHOW from glfw3.h. */
    private static final int GLFW_FOCUS_ON_SHOW = 0x0002000C;
    private static final Set<String> PATCHED = ConcurrentHashMap.newKeySet();

    private NoFocusAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        if (!Boolean.getBoolean("modreloader.noFocus")) {
            return;
        }
        instrumentation.addTransformer(new Transformer());
        System.err.println("[no-focus] agent active: Window will be created without focus");
    }

    private static final class Transformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!"com/mojang/blaze3d/platform/Window".equals(className)) {
                return null;
            }
            if (!PATCHED.add(className)) {
                return null;
            }
            try {
                byte[] patched = instrument(classfileBuffer);
                System.err.println("[no-focus] patched " + className);
                return patched;
            } catch (Throwable t) {
                System.err.println("[no-focus] could not patch " + className + ": " + t);
                return null;
            }
        }
    }

    /** Visible for a unit test; inserts the hint after every glfwDefaultWindowHints call. */
    public static byte[] instrument(byte[] original) {
        ClassReader reader = new ClassReader(original);
        ClassWriter writer = new ClassWriter(reader, 0);
        ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"<init>".equals(name)) {
                    return delegate;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                                boolean isInterface) {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                        if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/glfw/GLFW".equals(owner)
                                && "glfwDefaultWindowHints".equals(name)
                                && "()V".equals(descriptor)) {
                            super.visitLdcInsn(GLFW_FOCUS_ON_SHOW);
                            super.visitInsn(Opcodes.ICONST_0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "org/lwjgl/glfw/GLFW", "glfwWindowHint", "(II)V", false);
                        }
                    }
                };
            }
        };
        reader.accept(visitor, 0);
        return writer.toByteArray();
    }
}
