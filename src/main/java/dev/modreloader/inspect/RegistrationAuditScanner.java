package dev.modreloader.inspect;

import dev.modreloader.ModReloader;
import net.neoforged.fml.event.IModBusEvent;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The bytecode half of {@link RegistrationAudit}: reads a jar's class files,
 * resolves the NeoForge types they reference to {@link IModBusEvent}
 * implementations, and hands the names to the pure grading rules.
 *
 * <p>Split from the rules on purpose: the rules must be unit-testable in a JVM
 * that has neither ASM nor NeoForge on its classpath (this project's tests run
 * with JUnit only), while scanning needs both.
 */
public final class RegistrationAuditScanner {
    private static final int MAX_CLASS_BYTES = 4 * 1024 * 1024;

    public static RegistrationAudit.Report audit(Path rawJar, boolean clientSide) {
        Path jar = rawJar.toAbsolutePath().normalize();
        Set<String> referenced;
        try {
            if (!Files.isRegularFile(jar)) {
                return new RegistrationAudit.Report(List.of(), List.of());
            }
            referenced = referencedTypes(jar);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] registration audit cannot scan {}", jar, t);
            return new RegistrationAudit.Report(List.of(), List.of());
        }

        Set<String> events = new LinkedHashSet<>();
        Set<String> abstractBases = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        ClassLoader loader = RegistrationAudit.class.getClassLoader();
        for (String internal : referenced) {
            if (!internal.startsWith("net/neoforged/")) {
                continue;
            }
            String dotted = internal.replace('/', '.');
            // IModBusEvent implementations are all called *Event (including the
            // nested ones); this filter keeps Class.forName to a handful instead
            // of walking every NeoForge type the mod touches.
            if (!dotted.substring(dotted.lastIndexOf('.') + 1).contains("Event")) {
                continue;
            }
            try {
                Class<?> type = Class.forName(dotted, false, loader);
                if (!IModBusEvent.class.isAssignableFrom(type) || type.isInterface()) {
                    // IModBusEvent itself is the marker a mod's own event class
                    // implements; it is never registered against.
                    continue;
                }
                events.add(dotted);
                if (Modifier.isAbstract(type.getModifiers())) {
                    abstractBases.add(dotted);
                }
            } catch (Throwable t) {
                unresolved.add(dotted);
            }
        }
        return RegistrationAudit.classify(events, abstractBases, clientSide, unresolved);
    }

    /**
     * Every class / descriptor / instruction owner referenced by the jar's class
     * files, in internal form ({@code net/neoforged/.../Foo$Bar}). Class-level
     * and member-level references are both collected; debug info and stack-map
     * frames are skipped.
     */
    public static Set<String> referencedTypes(Path rawJar) throws Exception {
        Set<String> types = new LinkedHashSet<>();
        try (ZipFile zip = new ZipFile(rawJar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                if (entry.getSize() >= 0 && entry.getSize() > MAX_CLASS_BYTES) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry)) {
                    new ClassReader(in).accept(new TypeCollector(types),
                            ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                } catch (Throwable ignored) {
                    // One malformed class must not hide the rest of the jar.
                }
            }
        }
        return types;
    }


    private static final class TypeCollector extends ClassVisitor {
        private final Set<String> types;

        TypeCollector(Set<String> types) {
            super(Opcodes.ASM9);
            this.types = types;
        }

        @Override
        public void visit(int version, int access, String name, String signature,
                          String superName, String[] interfaces) {
            add(name);
            add(superName);
            if (interfaces != null) {
                for (String implemented : interfaces) {
                    add(implemented);
                }
            }
            addSignature(signature);
        }

        @Override
        public void visitNestHost(String nestHost) {
            add(nestHost);
        }

        @Override
        public void visitNestMember(String nestMember) {
            add(nestMember);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            add(permittedSubclass);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            // Only the inner class is a real reference. The attribute also names
            // the *outer* class for every nested type a class touches, so adding
            // it would report abstract parents (EntityRenderersEvent,
            // RegisterColorHandlersEvent) as if the mod listened to them.
            add(name);
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            add(owner);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(String name, String descriptor,
                                                           String signature) {
            addDescriptor(descriptor);
            addSignature(signature);
            return null;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor,
                                       String signature, Object value) {
            addDescriptor(descriptor);
            addSignature(signature);
            addConstant(value);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor,
                                         String signature, String[] exceptions) {
            addDescriptor(descriptor);
            addSignature(signature);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    add(exception);
                }
            }
            return new InstructionCollector(this);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            addDescriptor(descriptor);
            return new NestedAnnotationCollector(this);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            addDescriptor(descriptor);
            return new NestedAnnotationCollector(this);
        }

        void add(String internalName) {
            if (internalName == null || internalName.isEmpty()) {
                return;
            }
            // Array element types arrive as "[Lfoo;" and only appear in
            // descriptors; normalize so both forms land in the same set.
            int start = 0;
            while (start < internalName.length() && internalName.charAt(start) == '[') {
                start++;
            }
            if (start > 0) {
                if (start < internalName.length() && internalName.charAt(start) == 'L'
                        && internalName.endsWith(";")) {
                    types.add(internalName.substring(start + 1, internalName.length() - 1));
                }
                return;
            }
            types.add(internalName);
        }

        void addDescriptor(String descriptor) {
            if (descriptor == null || descriptor.isEmpty()) {
                return;
            }
            int index = 0;
            while ((index = descriptor.indexOf('L', index)) >= 0) {
                int end = descriptor.indexOf(';', index + 1);
                if (end < 0) {
                    return;
                }
                add(descriptor.substring(index + 1, end));
                index = end + 1;
            }
        }

        void addSignature(String signature) {
            if (signature == null || signature.isEmpty()) {
                return;
            }
            int index = 0;
            while (index < signature.length()) {
                if (signature.charAt(index++) != 'L') {
                    continue;
                }
                int start = index;
                while (index < signature.length()
                        && signature.charAt(index) != ';'
                        && signature.charAt(index) != '<') {
                    index++;
                }
                add(signature.substring(start, index));
                // On '<' the generic arguments follow and are scanned by the
                // outer loop, so a nested L...; is not lost.
            }
        }

        void addConstant(Object value) {
            if (value instanceof Type type) {
                if (type.getSort() == Type.METHOD) {
                    addDescriptor(type.getDescriptor());
                } else if (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY) {
                    addDescriptor(type.getDescriptor());
                }
            } else if (value instanceof Handle handle) {
                add(handle.getOwner());
                addDescriptor(handle.getDesc());
            } else if (value instanceof ConstantDynamic dynamic) {
                addDescriptor(dynamic.getDescriptor());
                addConstant(dynamic.getBootstrapMethod());
                for (int i = 0; i < dynamic.getBootstrapMethodArgumentCount(); i++) {
                    addConstant(dynamic.getBootstrapMethodArgument(i));
                }
            }
        }
    }

    private static final class InstructionCollector extends MethodVisitor {
        private final TypeCollector owner;

        InstructionCollector(TypeCollector owner) {
            super(Opcodes.ASM9);
            this.owner = owner;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            owner.add(type);
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
            this.owner.add(owner);
            this.owner.addDescriptor(descriptor);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String descriptor,
                                    boolean isInterface) {
            this.owner.add(owner);
            this.owner.addDescriptor(descriptor);
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor, Handle bootstrapMethod,
                                           Object... bootstrapMethodArguments) {
            owner.addDescriptor(descriptor);
            owner.addConstant(bootstrapMethod);
            for (Object argument : bootstrapMethodArguments) {
                owner.addConstant(argument);
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            owner.addConstant(value);
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
            owner.addDescriptor(descriptor);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            owner.add(type);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            owner.addDescriptor(descriptor);
            return new NestedAnnotationCollector(owner);
        }

        @Override
        public AnnotationVisitor visitParameterAnnotation(int parameter, String descriptor,
                                                          boolean visible) {
            owner.addDescriptor(descriptor);
            return new NestedAnnotationCollector(owner);
        }

        @Override
        public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath,
                                                     String descriptor, boolean visible) {
            owner.addDescriptor(descriptor);
            return new NestedAnnotationCollector(owner);
        }

        @Override
        public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath,
                                                         String descriptor, boolean visible) {
            owner.addDescriptor(descriptor);
            return new NestedAnnotationCollector(owner);
        }

        @Override
        public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath,
                                                              Label[] start, Label[] end,
                                                              int[] index, String descriptor,
                                                              boolean visible) {
            owner.addDescriptor(descriptor);
            return new NestedAnnotationCollector(owner);
        }
    }

    private static final class NestedAnnotationCollector extends AnnotationVisitor {
        private final TypeCollector owner;

        NestedAnnotationCollector(TypeCollector owner) {
            super(Opcodes.ASM9);
            this.owner = owner;
        }

        @Override
        public void visit(String name, Object value) {
            owner.addConstant(value);
        }

        @Override
        public void visitEnum(String name, String descriptor, String value) {
            owner.addDescriptor(descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String name, String descriptor) {
            owner.addDescriptor(descriptor);
            return this;
        }

        @Override
        public AnnotationVisitor visitArray(String name) {
            return this;
        }
    }
}
