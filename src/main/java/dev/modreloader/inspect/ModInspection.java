package dev.modreloader.inspect;

import java.nio.file.Path;
import java.util.List;

/**
 * Lightweight, read-only preflight result for a candidate jar.
 *
 * <p>This is a prediction for the UI. It is not a proof that the mod can be
 * hot-added: T2/T3 can still fail, and T4/T5 are deliberately not attempted.
 */
public record ModInspection(Path jar, String fileName, List<Mod> mods, Tier tier,
                            boolean mixins, boolean registry, boolean nativeCode,
                            boolean alreadyLoaded, boolean clientSideOnly,
                            List<Dependency> dependencies, List<String> notes, String error) {

    public enum Tier {
        T2, T3, T4, T5
    }

    public record Mod(String modId, String name, String version) {
    }

    public record Dependency(String modId, String versionRange, boolean required, boolean satisfied) {
    }

    public boolean ok() {
        return error == null;
    }

    public boolean canHotLoad() {
        if (!ok() || alreadyLoaded || tier == Tier.T4 || tier == Tier.T5) {
            return false;
        }
        for (Dependency dependency : dependencies) {
            if (dependency.required() && !dependency.satisfied()) {
                return false;
            }
        }
        return tier != Tier.T3 || JbrSupport.available();
    }

    public String blockReason() {
        if (!ok()) {
            return error;
        }
        if (alreadyLoaded) {
            return "already loaded; updating requires a restart";
        }
        if (tier == Tier.T4) {
            return "registry content: requires restart/soft restart";
        }
        if (tier == Tier.T5) {
            return "native/coremod: requires full restart";
        }
        for (Dependency dependency : dependencies) {
            if (dependency.required() && !dependency.satisfied()) {
                return "missing dependency: " + dependency.modId() + " " + dependency.versionRange();
            }
        }
        if (tier == Tier.T3 && !JbrSupport.available()) {
            return "T3 layout changes require JBR 21 + -XX:+AllowEnhancedClassRedefinition";
        }
        return null;
    }

    public String summary() {
        if (!ok()) {
            return fileName + " · " + error;
        }
        String ids = mods.stream()
                .map(mod -> mod.modId() + " " + mod.version())
                .reduce((a, b) -> a + ", " + b)
                .orElse("?");
        return ids + " · " + tier + (mixins ? " · mixins" : "")
                + (registry ? " · registry" : "") + (nativeCode ? " · native" : "");
    }
}
