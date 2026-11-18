package dev.modreloader.dev;

import dev.modreloader.ModReloader;
import dev.modreloader.hotadd.JvmAccess;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Isolates JBR's enhanced redefinition from Mixin: for each class name, change
 * the class bytes with plain ASM (identical / add field / add method / add marker
 * interface), redefine it, restore the original, and log the verdict.
 *
 * <p>If plain ASM-added members hit the same {@code ClassFormatError} as Mixin,
 * the limit is in JBR/HotSpot, not in Mixin's output. Dev-only:
 * {@code -PverifyRedefineProbe=<class>[,<class>...]}.
 */
public final class RedefineProbeCheck {
    private static final int TITLE_SETTLE_TICKS = 100;
    private static final String PROBE_FIELD = "modreloader$probe";
    private static final String PROBE_METHOD = "modreloader$probe";
    private static final String MARKER = "java/io/Serializable";

    private static List<String> classes = List.of();
    private static int ticks;
    private static boolean done;

    private RedefineProbeCheck() {
    }

    public static void start(String csv) {
        List<String> parsed = new ArrayList<>();
        for (String part : csv.split(",")) {
            String raw = part.trim();
            if (!raw.isEmpty()) {
                parsed.add(raw);
            }
        }
        classes = List.copyOf(parsed);
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> tick());
    }

    private static void tick() {
        if (done || ++ticks < TITLE_SETTLE_TICKS) {
            return;
        }
        done = true;
        Minecraft minecraft = Minecraft.getInstance();
        Path gameDir = minecraft.gameDirectory.toPath();
        List<String> out = new ArrayList<>();
        out.add("JVM=" + System.getProperty("java.vendor") + " "
                + System.getProperty("java.version"));
        try {
            Instrumentation instrumentation = JvmAccess.get();
            for (String name : classes) {
                probe(instrumentation, name, out);
            }
            probeFinalRemoval(instrumentation, out);
        } catch (Throwable t) {
            out.add("FAIL setup: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        write(gameDir, out);
        minecraft.stop();
    }

    private static void probe(Instrumentation instrumentation, String name, List<String> out) {
        try {
            Class<?> type = Class.forName(name, false, Minecraft.class.getClassLoader());
            byte[] original = readClass(type);
            if (original == null) {
                out.add(name + " SKIP: no class bytes");
                return;
            }
            run(instrumentation, type, original, original, name, "identical", out);
            run(instrumentation, type, addField(original, Opcodes.ACC_PUBLIC, "public"),
                    original, name, "add-field-public", out);
            run(instrumentation, type, addField(original, Opcodes.ACC_PRIVATE, "private"),
                    original, name, "add-field-private", out);
            run(instrumentation, type, addField(original,
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "static"),
                    original, name, "add-field-static", out);
            run(instrumentation, type, addField(original,
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "private-final"),
                    original, name, "add-field-private-final", out);
            run(instrumentation, type, addMethod(original, Opcodes.ACC_PUBLIC, "public"),
                    original, name, "add-method-public", out);
            run(instrumentation, type, addMethod(original, Opcodes.ACC_PRIVATE, "private"),
                    original, name, "add-method-private", out);
            run(instrumentation, type, addMarker(original), original, name, "add-interface", out);
            run(instrumentation, type, addIndy(original), original, name, "add-invokedynamic", out);
            run(instrumentation, type, addSourceDebug(original), original, name,
                    "add-source-debug", out);
            run(instrumentation, type, addMethod(original,
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "private-static"),
                    original, name, "add-method-private-static", out);
            run(instrumentation, type, addMethod(original,
                            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                            "private-static-synthetic"),
                    original, name, "add-method-synthetic", out);
            run(instrumentation, type, addAnnotatedMethod(original), original, name,
                    "add-method-annotated", out);
            run(instrumentation, type, addTriple(original), original, name, "add-three-methods", out);
        } catch (Throwable t) {
            out.add(name + " ERROR: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    /** Does JBR's redefine actually drop {@code final} from a loaded method? */
    private static void probeFinalRemoval(Instrumentation instrumentation, List<String> out) {
        try {
            Class<?> screen = Class.forName("net.minecraft.client.gui.screens.Screen", false,
                    Minecraft.class.getClassLoader());
            byte[] original = readClass(screen);
            ClassNode node = read(original);
            MethodNode target = null;
            for (MethodNode method : node.methods) {
                if (method.name.equals("init")
                        && method.desc.equals("(Lnet/minecraft/client/Minecraft;II)V")) {
                    target = method;
                    break;
                }
            }
            if (target == null) {
                out.add("Screen.init remove-final SKIP: method not found");
                return;
            }
            boolean before = (target.access & Opcodes.ACC_FINAL) != 0;
            target.access &= ~Opcodes.ACC_FINAL;
            instrumentation.redefineClasses(new ClassDefinition(screen, write(node)));
            Method reflected = screen.getDeclaredMethod("init", Minecraft.class, int.class, int.class);
            boolean after = Modifier.isFinal(reflected.getModifiers());
            out.add("Screen.init remove-final before=" + before + " after=" + after);
            instrumentation.redefineClasses(new ClassDefinition(screen, original));
            out.add("Screen.init remove-final restore OK");
        } catch (Throwable t) {
            out.add("Screen.init remove-final FAIL: " + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
    }

    private static void run(Instrumentation instrumentation, Class<?> type, byte[] bytes,
                            byte[] original, String name, String label, List<String> out) {
        if (bytes == null) {
            out.add(name + " " + label + " BUILD-FAILED");
            return;
        }
        boolean applied = false;
        try {
            instrumentation.redefineClasses(new ClassDefinition(type, bytes));
            applied = true;
            out.add(name + " " + label + " OK");
        } catch (Throwable t) {
            out.add(name + " " + label + " FAIL: " + t.getClass().getSimpleName() + ": "
                    + t.getMessage());
        }
        if (applied && bytes != original) {
            try {
                instrumentation.redefineClasses(new ClassDefinition(type, original));
                out.add(name + " " + label + " restore OK");
            } catch (Throwable t) {
                out.add(name + " " + label + " restore FAIL: " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
        }
    }

    private static byte[] addField(byte[] original, int access, String label) {
        try {
            ClassNode node = read(original);
            node.fields.add(new FieldNode(access, PROBE_FIELD + "$" + label, "I", null, null));
            return write(node);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] addMethod(byte[] original, int access, String label) {
        try {
            ClassNode node = read(original);
            MethodNode method = new MethodNode(access, PROBE_METHOD + "$" + label, "()I", null, null);
            method.instructions.add(new InsnNode(Opcodes.ICONST_0));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 1;
            method.maxLocals = 1;
            node.methods.add(method);
            return write(node);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Adds a method that uses {@code invokedynamic} (a lambda), which also adds a
     * {@code BootstrapMethods} attribute. MixinExtras' WrapOperation injects exactly
     * this shape into the target; if JBR refuses it, that is the ClassFormatError.
     */
    private static byte[] addIndy(byte[] original) {
        try {
            ClassNode node = read(original);
            MethodNode impl = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                    "modreloader$probeImpl", "()Ljava/lang/String;", null, null);
            impl.instructions.add(new LdcInsnNode("x"));
            impl.instructions.add(new InsnNode(Opcodes.ARETURN));
            impl.maxStack = 1;
            impl.maxLocals = 0;
            node.methods.add(impl);

            MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    "modreloader$probeIndy", "()Ljava/lang/String;", null, null);
            method.instructions.add(new InvokeDynamicInsnNode("get",
                    "()Ljava/util/function/Supplier;",
                    new Handle(Opcodes.H_INVOKESTATIC, "java/lang/invoke/LambdaMetafactory",
                            "metafactory",
                            "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                                    + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;"
                                    + "Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)"
                                    + "Ljava/lang/invoke/CallSite;",
                            false),
                    Type.getType("()Ljava/lang/Object;"),
                    new Handle(Opcodes.H_INVOKESTATIC, node.name, "modreloader$probeImpl",
                            "()Ljava/lang/String;", false),
                    Type.getType("()Ljava/lang/String;")));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    "java/util/function/Supplier", "get", "()Ljava/lang/Object;", true));
            method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
            method.instructions.add(new InsnNode(Opcodes.ARETURN));
            method.maxStack = 1;
            method.maxLocals = 0;
            node.methods.add(method);
            return write(node);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] addSourceDebug(byte[] original) {
        try {
            ClassNode node = read(original);
            node.sourceDebug = "SMAP\nProbe.java\nMixin\n*S Mixin\n*F\n"
                    + "+ 1 Probe.java\nProbe.java\n*E\n";
            return write(node);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] addAnnotatedMethod(byte[] original) {
        try {
            ClassNode node = read(original);
            MethodNode method = probeMethod(Opcodes.ACC_PUBLIC, "annotated");
            method.visibleAnnotations = new java.util.ArrayList<>();
            method.visibleAnnotations.add(new AnnotationNode("Ljava/lang/Deprecated;"));
            node.methods.add(method);
            return write(node);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] addTriple(byte[] original) {
        try {
            ClassNode node = read(original);
            node.methods.add(probeMethod(Opcodes.ACC_PUBLIC, "triple-public-a"));
            node.methods.add(probeMethod(Opcodes.ACC_PUBLIC, "triple-public-b"));
            node.methods.add(probeMethod(
                    Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
                    "triple-private-static-synthetic"));
            return write(node);
        } catch (Throwable t) {
            return null;
        }
    }

    private static MethodNode probeMethod(int access, String label) {
        MethodNode method = new MethodNode(access, PROBE_METHOD + "$" + label, "()I", null, null);
        method.instructions.add(new InsnNode(Opcodes.ICONST_0));
        method.instructions.add(new InsnNode(Opcodes.IRETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
        return method;
    }

    private static byte[] addMarker(byte[] original) {
        try {
            ClassNode node = read(original);
            if (!node.interfaces.contains(MARKER)) {
                node.interfaces.add(MARKER);
            }
            return write(node);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ClassNode read(byte[] bytes) {
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static byte[] write(ClassNode node) {
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static byte[] readClass(Class<?> type) {
        String path = "/" + type.getName().replace('.', '/') + ".class";
        try (var in = type.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            return null;
        }
    }

    private static void write(Path gameDir, List<String> lines) {
        try {
            Path file = gameDir.resolve("modreloader").resolve("redefine-probe.txt");
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (Exception e) {
            ModReloader.LOGGER.warn("[RedefineProbe] cannot write result", e);
        }
        for (String line : lines) {
            ModReloader.LOGGER.info("[RedefineProbe] {}", line);
        }
    }
}
