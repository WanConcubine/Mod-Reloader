package dev.modreloader.inspect;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The access-transformer rules of a mod jar, parsed from
 * {@code META-INF/accesstransformer.cfg}.
 *
 * <p>Why this exists: an AT is applied by FML when a class <em>loads</em>, so a
 * hot-added mod's ATs never take effect and a mod that depends on one fails at
 * construction. The gate that refuses such mods is conservative on purpose, but
 * it says nothing about <em>what</em> the ATs are for. Measured on the corpus
 * (see {@code docs/features/hotadd-compatibility-matrix.md} §8): some jars only
 * widen datagen classes (`net.minecraft.data.recipes.RecipeProvider`), which a
 * playing client never loads — for those the AT is not a real runtime boundary.
 * This class makes that composition visible in the refusal and in the
 * {@code -PverifyAtSpike} measurement, instead of the user having to unzip the
 * jar.
 *
 * <p>Pure: no game classes, no bytecode. Unit-tested in
 * {@code AccessTransformerRulesTest}.
 */
public final class AccessTransformerRules {
    public static final String ENTRY = "META-INF/accesstransformer.cfg";

    /** target class name (dotted) -> number of rules that mention it. */
    private final Map<String, Integer> rulesPerTarget;
    private final int ruleCount;

    private AccessTransformerRules(Map<String, Integer> rulesPerTarget, int ruleCount) {
        this.rulesPerTarget = Collections.unmodifiableMap(rulesPerTarget);
        this.ruleCount = ruleCount;
    }

    public static AccessTransformerRules empty() {
        return new AccessTransformerRules(Map.of(), 0);
    }

    /** Total rules (a class may have several). */
    public int ruleCount() {
        return ruleCount;
    }

    public int targetCount() {
        return rulesPerTarget.size();
    }

    /** Target classes in sorted order. */
    public List<String> targets() {
        return new ArrayList<>(new TreeMap<>(rulesPerTarget).keySet());
    }

    public Map<String, Integer> rulesPerTarget() {
        return rulesPerTarget;
    }

    /** Targets a playing client or server may actually load. */
    public List<String> runtimeTargets() {
        List<String> out = new ArrayList<>();
        for (String target : targets()) {
            if (!isDatagenClass(target)) {
                out.add(target);
            }
        }
        return out;
    }

    /** Targets that only exist for data generation. */
    public List<String> datagenTargets() {
        List<String> out = new ArrayList<>();
        for (String target : targets()) {
            if (isDatagenClass(target)) {
                out.add(target);
            }
        }
        return out;
    }

    /**
     * True when every target is a datagen class: the AT does not describe a
     * runtime access need, so refusing the mod "because it has ATs" is a false
     * positive (the add can still fail for other reasons).
     */
    public boolean datagenOnly() {
        return !rulesPerTarget.isEmpty() && runtimeTargets().isEmpty();
    }

    /** Short technical summary for logs and refusal messages. */
    public String describe() {
        if (ruleCount == 0) {
            return "no access transformer rules";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(ruleCount).append(" rule(s) over ").append(targetCount()).append(" target class(es)");
        List<String> runtime = runtimeTargets();
        List<String> datagen = datagenTargets();
        sb.append(" (").append(runtime.size()).append(" runtime");
        if (!datagen.isEmpty()) {
            sb.append(", ").append(datagen.size()).append(" datagen-only");
        }
        sb.append(')');
        if (datagenOnly()) {
            sb.append("; every target is a datagen class, which a playing game never loads");
        } else if (!runtime.isEmpty()) {
            sb.append("; e.g. ").append(runtime.subList(0, Math.min(3, runtime.size())));
        }
        return sb.toString();
    }

    /**
     * A target that only data generation loads. Kept deliberately narrow: the
     * vanilla/NeoForge datagen packages and a {@code datagen} package segment.
     */
    public static boolean isDatagenClass(String className) {
        return className.startsWith("net.minecraft.data.")
                || className.startsWith("net.neoforged.neoforge.data.")
                || className.contains(".datagen.")
                || className.startsWith("net.minecraft.gametest.")
                || className.startsWith("net.neoforged.testframework.");
    }

    /** Parses the AT file content; never throws on malformed lines. */
    public static AccessTransformerRules parse(String text) {
        Map<String, Integer> targets = new LinkedHashMap<>();
        int rules = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            // <modifier> <class> [member]; modifiers end with -f for "and final".
            String className = parts[1].replace('/', '.');
            if (className.isEmpty()) {
                continue;
            }
            rules++;
            targets.merge(className, 1, Integer::sum);
        }
        return new AccessTransformerRules(targets, rules);
    }

    /**
     * Reads the rules from {@code jar}.
     *
     * @return the parsed rules, or {@link #empty()} when the jar has no AT file
     * @throws IOException when the jar cannot be read
     */
    public static AccessTransformerRules read(Path jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry(ENTRY);
            if (entry == null) {
                return empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }
}
