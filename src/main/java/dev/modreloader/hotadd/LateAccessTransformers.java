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
 * redefining the captured bytes back.
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

    public static boolean enabled() {
        return Boolean.getBoolean("modreloader.lateAt");
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
                    + JbrSupport.describe() + ")");
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

        int applied = 0;
        int skipped = 0;
        List<String> messages = new ArrayList<>();
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
            byte[] original;
            try {
                original = ClassBytes.of(instrumentation, type);
            } catch (Throwable t) {
                return fail("cannot capture bytes of " + target + ": " + t);
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
            try {
                instrumentation.redefineClasses(new ClassDefinition(type, rewritten.bytes()));
            } catch (Throwable t) {
                return fail("JBR refused " + target + ": " + t.getClass().getSimpleName()
                        + ": " + t.getMessage());
            }
            rollback.add("late AT " + target, () -> instrumentation.redefineClasses(
                    new ClassDefinition(type, original)));
            applied++;
            messages.add("applied " + target + " (" + rewritten.matched() + " rule(s))");
            rewritten.unmatched().forEach(message -> messages.add("  " + message));
        }
        return new Result(true, parsed.ruleCount(), byTarget.size(), applied, skipped,
                List.copyOf(messages));
    }

    record Rewrite(byte[] bytes, int matched, List<String> unmatched) {
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
        for (int index = 0; index < rules.size(); index++) {
            AccessTransformerRules.Rule rule = rules.get(index);
            if (rule.classLevel()) {
                node.access = widen(node.access, rule);
                matched[index] = true;
                continue;
            }
            if (rule.method()) {
                for (MethodNode method : node.methods) {
                    if (method.name.equals(rule.member()) && method.desc.equals(rule.descriptor())) {
                        method.access = widen(method.access, rule);
                        matched[index] = true;
                    }
                }
                continue;
            }
            for (FieldNode field : node.fields) {
                if (field.name.equals(rule.member())) {
                    field.access = widen(field.access, rule);
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
        return new Rewrite(writer.toByteArray(), matchedCount, List.copyOf(unmatched));
    }

    private static int widen(int access, AccessTransformerRules.Rule rule) {
        int flags = rule.accessFlag();
        if (flags != 0) {
            access &= ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE);
            access |= flags;
        }
        if (rule.removeFinal()) {
            access &= ~Opcodes.ACC_FINAL;
        }
        return access;
    }

    private static Result fail(String message) {
        ModReloader.LOGGER.warn("[ModReloader] late AT refused: {}", message);
        return new Result(false, 0, 0, 0, 0, List.of(message));
    }
}
