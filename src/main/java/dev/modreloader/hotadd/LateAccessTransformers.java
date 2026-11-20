package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.AccessTransformerRules;
import dev.modreloader.inspect.JbrSupport;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.modreloader.reflect.JvmAccess;

/**
 * Experimental late access transformers.
 *
 * <p>ATs are normally applied when a class loads, which is why hot-added mods
 * with ATs used to be refused. JBR's enhanced redefinition can change the
 * access flags (and remove {@code final}) of an already-loaded class, though:
 * a synthetic probe on JBR 21 accepted {@code private -> public} on a field and
 * a method, and a caller compiled against the public shape worked afterwards.
 *
 * <p>So the late path is: capture the target's <em>current</em> bytes (after
 * NeoForge/Mixin), rewrite only the access flags named by the AT, and
 * {@code redefineClasses} before the hot-added mod's classes are loaded.
 * This keeps every existing transformation intact and is reversible by
 * redefining the captured bytes back. Class-level final removal is the one
 * exception: JBR's redefine makes instances of such a class come back null
 * (measured with {@code NativeImage.read} and a synthetic final class), so
 * {@link #apply} refuses such a jar before changing anything.
 *
 * <p>Experimental and opt-in ({@code -Dmodreloader.lateAt=true}): it changes
 * loaded vanilla classes for the rest of the process, needs JBR, and only
 * covers access/final changes (which is what NeoForge ATs express). It is not
 * a replacement for a restart in the general case.
 */
public final class LateAccessTransformers {
    public record Result(boolean ok, int ruleCount, int targetCount, int applied, int skipped,
                         List<String> messages) {
        public String summary() {
            return ruleCount + " rule(s), " + targetCount + " target(s), " + applied
                    + " applied, " + skipped + " skipped";
        }
    }

    private LateAccessTransformers() {
    }

    /**
     * Late AT is part of the prepared environment: once the process is JBR with
     * enhanced redefinition, it is on by default. The old property remains as an
     * explicit override (dev runs, and {@code -Dmodreloader.lateAt=false} to opt
     * out); a blank/unset value must not make the "one prepare click" flow depend
     * on a second switch.
     */
    public static boolean enabled() {
        return lateAtEnabled(System.getProperty("modreloader.lateAt"),
                JbrSupport.enhancedRedefinition());
    }

    static boolean lateAtEnabled(String property, boolean enhancedJbr) {
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        return enhancedJbr;
    }

    public static Result apply(Path jar, Rollback rollback) {
        AccessTransformerRules parsed;
        try {
            parsed = AccessTransformerRules.read(jar);
        } catch (Throwable t) {
            return fail("cannot read " + AccessTransformerRules.ENTRY + ": " + t);
        }
        if (parsed.ruleCount() == 0) {
            return new Result(true, 0, 0, 0, 0, List.of());
        }
        if (!JbrSupport.available()) {
            return fail("the needed capability requires JBR 21 with enhanced redefinition ("
                    + JbrSupport.describe() + ")" + JbrSupport.hint());
        }
        Instrumentation instrumentation;
        try {
            instrumentation = JvmAccess.get();
        } catch (Throwable t) {
            return fail("cannot get Instrumentation: " + t);
        }

        Map<String, List<AccessTransformerRules.Rule>> byTarget = new LinkedHashMap<>();
        for (AccessTransformerRules.Rule rule : parsed.rules()) {
            byTarget.computeIfAbsent(rule.targetClass(), ignored -> new ArrayList<>()).add(rule);
        }

        // Refuse before mutating anything: a class-level `-f` rule on an
        // already-loaded final class cannot be emulated. JBR accepts the
        // redefine but every construction of that class then yields null
        // (measured with NativeImage.read and a synthetic final class), and a
        // following redefine of the same class crashed the JVM in
        // ConstantPoolCacheEntry::clear_entry. A restart applies the AT at load
        // time; pretending to support it would corrupt the process.
        List<String> blockers = classFinalBlockers(byTarget);
        if (!blockers.isEmpty()) {
            return fail("a class-level `-f` rule needs a class that is not final at load time: "
                    + String.join("; ", blockers)
                    + ". Removing final from a loaded class breaks constructing it on this JVM. "
                    + "Restart with the jar in mods/ to apply the AT at startup.");
        }

        int applied = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();
        // JBR's redefine/retransform invokes Mixin's agent, which would apply the
        // active configs to bytes that already contain them (MixinExtras throws
        // InjectionError, JBR reports ClassFormatError). Suspend it for every
        // target of this jar while we capture + redefine.
        try (MixinAgentGuard ignored = MixinAgentGuard.suspend(instrumentation, byTarget.keySet())) {
        if (!MixinAgentGuard.safeToRedefine()) {
            return fail("the Mixin agent guard is unavailable (" + MixinAgentGuard.detail()
                    + "); refusing late access transforms, which would be re-transformed by "
                    + "Mixin's agent");
        }
        for (Map.Entry<String, List<AccessTransformerRules.Rule>> entry : byTarget.entrySet()) {
            String target = entry.getKey();
            Class<?> type;
            try {
                type = Class.forName(target, false, LateAccessTransformers.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                skipped++;
                messages.add("skip " + target + ": not present on this side");
                continue;
            }
            if (!instrumentation.isModifiableClass(type)) {
                skipped++;
                messages.add("skip " + target + ": not modifiable");
                continue;
            }
            // Prefer what this runtime last installed (late AT / mixin redefine).
            // Otherwise capture the live bytes: the JVM starts a retransformation
            // from the bytes passed to the last defineClass/redefineClasses, and
            // with every transformer suppressed the recorder sees exactly that
            // definition. The class-file resource cannot be used here — it is the
            // pre-transform original, so redefining it from there silently drops
            // FML's load-time AT (measured with RenderStateShard$TextureStateShard:
            // NeoForge's `blur` field went back to private and rendering crashed
            // with IllegalAccessError right after architectury's late AT).
            byte[] original = ClassBytesCache.get(target);
            if (original == null) {
                try (AutoCloseable blanket = MixinAgentGuard.blanket()) {
                    original = ClassBytes.of(instrumentation, type);
                } catch (Throwable t) {
                    messages.add("note " + target + ": live byte capture failed ("
                            + t.getMessage() + "); falling back to the class resource");
                }
            }
            if (original == null) {
                original = readClassResource(target);
            }
            if (original == null) {
                skipped++;
                messages.add("skip " + target + ": bytes unavailable");
                continue;
            }
            Rewrite rewritten;
            try {
                rewritten = rewrite(original, target, entry.getValue());
            } catch (Throwable t) {
                return fail("cannot rewrite " + target + ": " + t.getMessage());
            }
            if (!rewritten.matchedAny()) {
                skipped++;
                messages.add("skip " + target + ": no rule matched (stale AT entry?)");
                continue;
            }
            try (AutoCloseable blanket = MixinAgentGuard.blanket()) {
                // The bytes are final for this define: no transformer may process
                // them again (JBR's redefine does not always pass a class identity,
                // so the name-based guard cannot identify the target here).
                instrumentation.redefineClasses(new ClassDefinition(type, rewritten.bytes()));
            } catch (Throwable t) {
                return fail("JBR refused " + target + ": " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
            ClassBytesCache.put(target, rewritten.bytes());
            byte[] captured = original;
            rollback.add("late AT " + target, () -> {
                try (AutoCloseable blanket = MixinAgentGuard.blanket()) {
                    instrumentation.redefineClasses(new ClassDefinition(type, captured));
                } catch (Throwable t) {
                    // The live class is still the valid widened shape; only the
                    // revert failed. Keeping it is safe (access widening is
                    // additive) and is strictly better than ROLLBACK_PARTIAL.
                    ModReloader.LOGGER.warn(
                            "[ModReloader] rollback: late AT of {} stays applied; reverting its "
                                    + "bytes was refused ({}). The widened flags are harmless.",
                            target, t.getClass().getSimpleName()
                                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
                    return;
                }
                ClassBytesCache.put(target, captured);
            });
            applied++;
            messages.add("applied " + target + " (" + rewritten.matched() + " rule(s))");
            rewritten.unmatched().forEach(message -> messages.add("  " + message));
            rewritten.notes().forEach(message -> messages.add("note " + message));
        }
        }
        return new Result(true, parsed.ruleCount(), byTarget.size(), applied, skipped,
                List.copyOf(messages));
    }

    private static byte[] readClassResource(String dotted) {
        String path = dotted.replace('.', '/') + ".class";
        try (java.io.InputStream in = LateAccessTransformers.class.getClassLoader()
                .getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (Exception e) {
            ModReloader.LOGGER.warn("[ModReloader] cannot read class resource {}", path, e);
            return null;
        }
    }

    /** Loaded, final classes whose AT removes the class's final flag. */
    private static List<String> classFinalBlockers(
            Map<String, List<AccessTransformerRules.Rule>> byTarget) {
        List<String> blockers = new ArrayList<>();
        for (Map.Entry<String, List<AccessTransformerRules.Rule>> entry : byTarget.entrySet()) {
            boolean wantsNonFinal = entry.getValue().stream()
                    .anyMatch(rule -> rule.classLevel() && rule.removeFinal());
            if (!wantsNonFinal) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(entry.getKey(), false,
                        LateAccessTransformers.class.getClassLoader());
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                continue;
            }
            if (java.lang.reflect.Modifier.isFinal(type.getModifiers())) {
                blockers.add(entry.getKey());
            }
        }
        return blockers;
    }

    record Rewrite(byte[] bytes, int matched, List<String> unmatched, List<String> notes) {
        boolean matchedAny() {
            return matched > 0;
        }
    }

    static Rewrite rewrite(byte[] original, String target,
                           List<AccessTransformerRules.Rule> rules) {
        ClassReader reader = new ClassReader(original);
        ClassNode node = new ClassNode();
        reader.accept(node, 0);
        boolean[] matched = new boolean[rules.size()];
        List<String> notes = new ArrayList<>();
        for (int index = 0; index < rules.size(); index++) {
            AccessTransformerRules.Rule rule = rules.get(index);
            if (rule.classLevel()) {
                // Widening the class itself is fine. Removing the class's final
                // flag is not: measured on JBR 21 (docs/features/late-at.md
                // §5.1), a redefine that only drops ACC_FINAL makes every
                // construction of the class come back null - `NativeImage.read`
                // returned null afterwards, and the whole sprite/font pipeline
                // crashed on the next resource reload. The flag only affects
                // subclassing, not access, so keep it. `apply` refuses up front
                // when the class is final, so reaching here means it was not.
                boolean wasFinal = (node.access & Opcodes.ACC_FINAL) != 0;
                node.access = widen(node.access, rule.accessFlag(), false);
                matched[index] = true;
                if (rule.removeFinal() && wasFinal) {
                    notes.add("kept final on class " + target + ": a JBR redefine that drops "
                            + "it breaks instances created afterwards (subclassing this class "
                            + "will still fail)");
                }
                continue;
            }
            if (rule.method()) {
                for (MethodNode method : node.methods) {
                    if (method.name.equals(rule.member()) && method.desc.equals(rule.descriptor())) {
                        method.access = widen(method.access, rule.accessFlag(), rule.removeFinal());
                        matched[index] = true;
                    }
                }
                continue;
            }
            for (FieldNode field : node.fields) {
                if (field.name.equals(rule.member())) {
                    field.access = widen(field.access, rule.accessFlag(), rule.removeFinal());
                    matched[index] = true;
                }
            }
        }
        int matchedCount = 0;
        List<String> unmatched = new ArrayList<>();
        for (int index = 0; index < matched.length; index++) {
            if (matched[index]) {
                matchedCount++;
                continue;
            }
            AccessTransformerRules.Rule rule = rules.get(index);
            // FML tolerates AT entries that no longer name a member (JourneyMap
            // ships a stale heightMap rule); the mod's own construction will fail
            // later if it actually needed it.
            unmatched.add("unmatched rule: " + rule.modifier() + " " + target + " "
                    + rule.member() + (rule.descriptor() == null ? "" : rule.descriptor()));
        }
        ClassWriter writer = new ClassWriter(reader, 0);
        node.accept(writer);
        return new Rewrite(writer.toByteArray(), matchedCount, List.copyOf(unmatched),
                List.copyOf(notes));
    }

    private static int widen(int access, int flags, boolean removeFinal) {
        if (flags != 0) {
            access &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            access |= flags;
        }
        if (removeFinal) {
            access &= ~Opcodes.ACC_FINAL;
        }
        return access;
    }

    private static Result fail(String message) {
        ModReloader.LOGGER.warn("[ModReloader] late AT refused: {}", message);
        return new Result(false, 0, 0, 0, 0, List.of(message));
    }
}
