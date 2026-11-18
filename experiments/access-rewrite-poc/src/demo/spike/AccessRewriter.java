package demo.spike;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rewrites the <em>caller</em> (a hot-added mod class) instead of the target.
 *
 * <p>Every access that an access transformer would have made legal is replaced by
 * a call to a generated private static accessor with the <em>same stack shape</em>
 * ({@code GETFIELD C.f:Desc} becomes {@code INVOKESTATIC C.access$0(C)Desc},
 * {@code NEW; DUP; args; INVOKESPECIAL <init>} becomes
 * {@code args; INVOKESTATIC C.access$N(args)C}). Keeping the shape means the
 * existing StackMapTable stays valid and no boxing is introduced; the accessor
 * itself calls a cached MethodHandle with {@code invokeExact}.
 *
 * <p>Deliberately not handled (see README): type references to package-private
 * classes, super calls, {@code invokedynamic} member references, class hierarchy
 * changes. They must be detected and refused, not guessed.
 */
public final class AccessRewriter implements Opcodes {
    public enum Kind { FIELD, STATIC_FIELD, METHOD, STATIC_METHOD, CONSTRUCTOR }

    public record Rule(String owner, String name, String desc, Kind kind) {
    }

    /** A shape the rewriter refuses to guess about. */
    public static final class Unsupported extends RuntimeException {
        public Unsupported(String message) {
            super(message);
        }
    }

    private static final class Accessor {
        final String fieldName;
        final String methodName;
        final String accessorDesc;
        final String owner;
        final String memberName;
        final String memberDesc;
        final String factory;

        Accessor(String fieldName, String methodName, String accessorDesc, String owner,
                 String memberName, String memberDesc, String factory) {
            this.fieldName = fieldName;
            this.methodName = methodName;
            this.accessorDesc = accessorDesc;
            this.owner = owner;
            this.memberName = memberName;
            this.memberDesc = memberDesc;
            this.factory = factory;
        }
    }

    private AccessRewriter() {
    }

    public static byte[] rewrite(byte[] original, List<Rule> rules) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        Map<String, Accessor> accessors = new LinkedHashMap<>();
        int[] counter = {0};

        for (MethodNode method : node.methods) {
            for (AbstractInsnNode insn : snapshot(method)) {
                if (insn instanceof FieldInsnNode field) {
                    rewriteField(node, method, field, rules, accessors, counter);
                } else if (insn instanceof MethodInsnNode call) {
                    rewriteCall(node, method, call, rules, accessors, counter);
                }
            }
        }

        for (Accessor accessor : accessors.values()) {
            install(node, accessor);
        }

        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void rewriteField(ClassNode node, MethodNode method, FieldInsnNode field,
                                     List<Rule> rules, Map<String, Accessor> accessors, int[] counter) {
        boolean isStatic = field.getOpcode() == GETSTATIC || field.getOpcode() == PUTSTATIC;
        boolean get = field.getOpcode() == GETFIELD || field.getOpcode() == GETSTATIC;
        Rule rule = findRule(rules, field.owner, field.name, field.desc);
        if (rule == null || rule.kind() != (isStatic ? Kind.STATIC_FIELD : Kind.FIELD)) {
            return;
        }
        String accessorDesc = isStatic
                ? (get ? "()" + field.desc : "(" + field.desc + ")V")
                : (get ? "(L" + field.owner + ";)" + field.desc
                       : "(L" + field.owner + ";" + field.desc + ")V");
        String factory = isStatic
                ? (get ? "staticFieldGetter" : "staticFieldSetter")
                : (get ? "fieldGetter" : "fieldSetter");
        Accessor accessor = accessor(accessors, counter, field.owner, field.name, field.desc,
                accessorDesc, factory);
        method.instructions.set(field,
                new MethodInsnNode(INVOKESTATIC, node.name, accessor.methodName, accessorDesc, false));
    }

    private static void rewriteCall(ClassNode node, MethodNode method, MethodInsnNode call,
                                    List<Rule> rules, Map<String, Accessor> accessors, int[] counter) {
        if ("<init>".equals(call.name)) {
            rewriteConstructor(node, method, call, rules, accessors, counter);
            return;
        }
        Rule rule = findRule(rules, call.owner, call.name, call.desc);
        if (rule == null) {
            return;
        }
        boolean isStatic = call.getOpcode() == INVOKESTATIC;
        if (rule.kind() != (isStatic ? Kind.STATIC_METHOD : Kind.METHOD)) {
            return;
        }
        String args = argsPart(call.desc);
        String ret = returnPart(call.desc);
        String accessorDesc = isStatic ? call.desc
                : "(" + "L" + call.owner + ";" + args.substring(1) + ret;
        Accessor accessor = accessor(accessors, counter, call.owner, call.name, call.desc,
                accessorDesc, isStatic ? "staticMethod" : "virtualMethod");
        method.instructions.set(call,
                new MethodInsnNode(INVOKESTATIC, node.name, accessor.methodName, accessorDesc, false));
    }

    private static void rewriteConstructor(ClassNode node, MethodNode method, MethodInsnNode call,
                                           List<Rule> rules, Map<String, Accessor> accessors,
                                           int[] counter) {
        Rule rule = findRule(rules, call.owner, "<init>", call.desc);
        if (rule == null || rule.kind() != Kind.CONSTRUCTOR) {
            return;
        }
        // javac emits NEW; DUP; <args>; INVOKESPECIAL, so the DUP is *before* the
        // argument instructions. Walk back to it, with a conservative stop on
        // anything that could consume the uninitialized reference. A production
        // version needs a real analyzer here (arguments may contain calls that
        // allocate again); the spike only needs the straight-line shape.
        AbstractInsnNode dup = previousReal(call);
        int guard = 0;
        while (dup != null && dup.getOpcode() != DUP) {
            if (dup instanceof MethodInsnNode || dup instanceof TypeInsnNode) {
                break;
            }
            dup = previousReal(dup);
            if (++guard > 128) {
                dup = null;
            }
        }
        AbstractInsnNode allocation = dup == null ? null : previousReal(dup);
        if (dup == null || dup.getOpcode() != DUP
                || !(allocation instanceof TypeInsnNode created)
                || created.getOpcode() != NEW
                || !created.desc.equals(call.owner)) {
            throw new Unsupported("NEW/DUP/INVOKESPECIAL pattern not found for "
                    + call.owner + call.desc + " in " + node.name);
        }
        String accessorDesc = argsPart(call.desc) + "L" + call.owner + ";";
        Accessor accessor = accessor(accessors, counter, call.owner, "<init>", call.desc,
                accessorDesc, "constructor");
        method.instructions.remove(dup);
        method.instructions.remove(allocation);
        method.instructions.set(call,
                new MethodInsnNode(INVOKESTATIC, node.name, accessor.methodName, accessorDesc, false));
    }

    private static void install(ClassNode node, Accessor accessor) {
        node.fields.add(new FieldNode(ACC_PRIVATE | ACC_STATIC | ACC_FINAL | ACC_SYNTHETIC,
                accessor.fieldName, "Ljava/lang/invoke/MethodHandle;", null, null));

        MethodNode method = new MethodNode(ACC_PRIVATE | ACC_STATIC | ACC_SYNTHETIC,
                accessor.methodName, accessor.accessorDesc, null, null);
        method.instructions.add(new FieldInsnNode(GETSTATIC, node.name, accessor.fieldName,
                "Ljava/lang/invoke/MethodHandle;"));
        int slot = 0;
        for (Type parameter : Type.getArgumentTypes(accessor.accessorDesc)) {
            method.instructions.add(new VarInsnNode(parameter.getOpcode(ILOAD), slot));
            slot += parameter.getSize();
        }
        method.instructions.add(new MethodInsnNode(INVOKEVIRTUAL,
                "java/lang/invoke/MethodHandle", "invokeExact", accessor.accessorDesc, false));
        method.instructions.add(new InsnNode(
                Type.getReturnType(accessor.accessorDesc).getOpcode(IRETURN)));
        node.methods.add(method);

        InsnList init = new InsnList();
        init.add(new LdcInsnNode(Type.getObjectType(accessor.owner)));
        init.add(new LdcInsnNode(accessor.memberName));
        init.add(new LdcInsnNode(accessor.memberDesc));
        init.add(new MethodInsnNode(INVOKESTATIC, "demo/spike/Access", accessor.factory,
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/invoke/MethodHandle;",
                false));
        init.add(new FieldInsnNode(PUTSTATIC, node.name, accessor.fieldName,
                "Ljava/lang/invoke/MethodHandle;"));
        classInitializer(node).instructions.insert(init);
    }

    private static MethodNode classInitializer(ClassNode node) {
        for (MethodNode method : node.methods) {
            if ("<clinit>".equals(method.name)) {
                return method;
            }
        }
        MethodNode created = new MethodNode(ACC_STATIC, "<clinit>", "()V", null, null);
        created.instructions.add(new InsnNode(RETURN));
        node.methods.add(created);
        return created;
    }

    private static Accessor accessor(Map<String, Accessor> accessors, int[] counter,
                                     String owner, String memberName, String memberDesc,
                                     String accessorDesc, String factory) {
        String key = factory + ":" + owner + "." + memberName + memberDesc + "->" + accessorDesc;
        Accessor existing = accessors.get(key);
        if (existing != null) {
            return existing;
        }
        Accessor created = new Accessor("mh$" + counter[0], "access$" + counter[0],
                accessorDesc, owner, memberName, memberDesc, factory);
        counter[0]++;
        accessors.put(key, created);
        return created;
    }

    private static Rule findRule(List<Rule> rules, String owner, String name, String desc) {
        for (Rule rule : rules) {
            if (rule.owner().equals(owner) && rule.name().equals(name) && rule.desc().equals(desc)) {
                return rule;
            }
        }
        return null;
    }

    private static List<AbstractInsnNode> snapshot(MethodNode method) {
        List<AbstractInsnNode> nodes = new ArrayList<>();
        for (AbstractInsnNode node = method.instructions.getFirst(); node != null; node = node.getNext()) {
            nodes.add(node);
        }
        return nodes;
    }

    /** Previous node that is a real instruction (skips labels, line numbers, frames). */
    private static AbstractInsnNode previousReal(AbstractInsnNode node) {
        AbstractInsnNode previous = node.getPrevious();
        while (previous != null && previous.getOpcode() < 0) {
            previous = previous.getPrevious();
        }
        return previous;
    }

    private static String argsPart(String desc) {
        return desc.substring(0, desc.indexOf(')') + 1);
    }

    private static String returnPart(String desc) {
        return desc.substring(desc.indexOf(')') + 1);
    }
}
