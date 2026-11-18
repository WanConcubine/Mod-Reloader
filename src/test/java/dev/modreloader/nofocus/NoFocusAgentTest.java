package dev.modreloader.nofocus;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoFocusAgentTest {
    @Test
    void insertsFocusHintRightAfterDefaultHints() {
        byte[] original = syntheticWindow();
        byte[] patched = NoFocusAgent.instrument(original);

        assertFalse(mentionsFocusHint(original), "original must not contain the hint");
        assertTrue(mentionsFocusHint(patched), "patched class must set GLFW_FOCUS_ON_SHOW");
    }

    private static byte[] syntheticWindow() {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "demo/Window", null,
                "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/glfw/GLFW",
                "glfwDefaultWindowHints", "()V", false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static boolean mentionsFocusHint(byte[] bytes) {
        boolean[] found = {false};
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        if (opcode == Opcodes.INVOKESTATIC
                                && "org/lwjgl/glfw/GLFW".equals(owner)
                                && "glfwWindowHint".equals(name)
                                && "(II)V".equals(descriptor)) {
                            found[0] = true;
                        }
                    }

                    @Override
                    public void visitLdcInsn(Object value) {
                        if (value instanceof Integer number && number == 0x0002000C) {
                            found[0] = true;
                        }
                    }
                };
            }
        }, 0);
        return found[0];
    }
}
