package dev.modreloader.hotadd;

import dev.modreloader.inspect.AccessTransformerRules;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The late-AT rewrite is pure ASM: it must change access/final flags and leave
 * the rest of the class alone. Kept game-free so it runs on every build.
 */
class LateAccessTransformersTest {
    @Test
    void widensFieldAndMethodAndRemovesFinal() {
        AccessTransformerRules rules = AccessTransformerRules.parse("""
                public-f demo.Target secret # field
                public demo.Target hidden()Ljava/lang/String; # method
                """);
        List<AccessTransformerRules.Rule> list = rules.rules();

        LateAccessTransformers.Rewrite rewrite = LateAccessTransformers.rewrite(
                synthetic(Opcodes.ACC_PUBLIC), "demo.Target", list);
        assertTrue(rewrite.matchedAny());
        assertEquals(2, rewrite.matched());

        int[] flags = readFlags(rewrite.bytes());
        assertTrue((flags[0] & Opcodes.ACC_PUBLIC) != 0, "field must be public");
        assertTrue((flags[0] & Opcodes.ACC_FINAL) == 0, "field must lose final");
        assertTrue((flags[1] & Opcodes.ACC_PUBLIC) != 0, "method must be public");
    }

    @Test
    void classLevelRuleWidensButKeepsClassFinal() {
        AccessTransformerRules rules = AccessTransformerRules.parse("""
                public-f demo.Target
                """);
        LateAccessTransformers.Rewrite rewrite = LateAccessTransformers.rewrite(
                synthetic(Opcodes.ACC_FINAL), "demo.Target", rules.rules());

        assertEquals(1, rewrite.matched());
        int access = new ClassReader(rewrite.bytes()).getAccess();
        assertTrue((access & Opcodes.ACC_PUBLIC) != 0, "class must become public");
        assertTrue((access & Opcodes.ACC_FINAL) != 0,
                "class final must stay: JBR redefine drops instances when it is removed");
        assertEquals(1, rewrite.notes().size());
    }

    @Test
    void classLevelRuleOnNonFinalClassNeedsNoNote() {
        AccessTransformerRules rules = AccessTransformerRules.parse("""
                public-f demo.Target
                """);
        LateAccessTransformers.Rewrite rewrite = LateAccessTransformers.rewrite(
                synthetic(Opcodes.ACC_PUBLIC), "demo.Target", rules.rules());

        assertEquals(1, rewrite.matched());
        assertEquals(0, rewrite.notes().size(), "nothing to keep when the class is not final");
    }

    private static byte[] synthetic(int classAccess) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, classAccess, "demo/Target", null,
                "java/lang/Object", null);
        FieldVisitor field = writer.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL, "secret", "I", null, null);
        field.visitEnd();
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "hidden", "()Ljava/lang/String;", null, null);
        method.visitCode();
        method.visitLdcInsn("hidden");
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static int[] readFlags(byte[] bytes) {
        int[] flags = new int[2];
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor,
                                           String signature, Object value) {
                if ("secret".equals(name)) {
                    flags[0] = access;
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                if ("hidden".equals(name)) {
                    flags[1] = access;
                }
                return null;
            }
        }, 0);
        return flags;
    }
}
