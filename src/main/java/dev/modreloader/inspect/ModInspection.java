package dev.modreloader.inspect;

import java.nio.file.Path;
import java.util.List;

/**
 * Lightweight, read-only preflight result for a candidate jar.
 *
 * <p>This is a prediction for the UI. It is not a proof that the mod can be
 * hot-added: pin/fei can still fail, and guifei/huanghou are deliberately not attempted.
 */
public record ModInspection(Path jar, String fileName, List<Mod> mods, Tier tier,
                            boolean mixins, boolean registry, boolean nativeCode,
                            boolean alreadyLoaded, boolean clientSideOnly,
                            boolean accessTransformers,
                            List<Dependency> dependencies, List<String> notes, String error) {

    /**
     * Hot-add tiers. The names are the project's own ranks; there is no numeric
     * tier id — T0/T1 (resources/data) are vanilla capabilities and not part of
     * this model.
     */
    public enum Tier {
        PIN("pin"),
        FEI("fei"),
        GUIFEI("guifei"),
        HUANGHOU("huanghou");

        private final String id;

        Tier(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String key() {
            return "modreloader.tier." + id;
        }
    }

    public record Mod(String modId, String name, String version) {
    }

    public record Dependency(String modId, String versionRange, boolean required, boolean satisfied) {
    }

    public boolean ok() {
        return error == null;
    }

    public boolean canHotLoad() {
        return canHotLoad(false, false);
    }

    /**
     * @param allowRegistryContent true when the registry-adaptation path is available;
     *                             guifei content is still experimental and add-only.
     */
    public boolean canHotLoad(boolean allowRegistryContent) {
        return canHotLoad(allowRegistryContent, false);
    }

    /**
     * @param allowRegistryContent        true when the registry-adaptation path is available;
     *                                    guifei content is still experimental and add-only.
     * @param allowLateAccessTransformers true when {@code -Dmodreloader.lateAt=true} is set, i.e.
     *                                    the experimental JBR path that applies a mod's access
     *                                    transformers to already-loaded classes before its own
     *                                    classes load. Off by default: FML applies ATs only while
     *                                    it loads classes, so on a stock JVM an AT is a hard gate.
     *                                    Keep this in sync with the engine's VALIDATE check.
     */
    public boolean canHotLoad(boolean allowRegistryContent, boolean allowLateAccessTransformers) {
        if (!ok() || alreadyLoaded || tier == Tier.HUANGHOU) {
            return false;
        }
        if (accessTransformers && !allowLateAccessTransformers) {
            return false;
        }
        if (tier == Tier.GUIFEI && !allowRegistryContent) {
            return false;
        }
        for (Dependency dependency : dependencies) {
            if (dependency.required() && !dependency.satisfied()) {
                return false;
            }
        }
        if (tier == Tier.FEI || (tier == Tier.GUIFEI && mixins)) {
            // JBR is not a gate any more: a mixin that keeps the class layout still
            // applies on a stock JVM, and a target that is not loaded is transformed
            // on load. Only a layout change on an already-loaded class needs JBR, and
            // that is detected per target by MixinSupport while nothing is mutated.
            return MixinRuntimeSupport.available();
        }
        return true;
    }

    /** Fei-tier mods need MixinRuntime to reopen Mixin's frozen config list. */
    public boolean mixinRuntimeMissing() {
        return tier == Tier.FEI && !MixinRuntimeSupport.available();
    }

    public String blockReason() {
        if (!ok()) {
            return error;
        }
        if (alreadyLoaded) {
            return "already loaded; updating requires a restart";
        }
        if (tier == Tier.GUIFEI) {
            return "registry content: requires restart/soft restart";
        }
        if (tier == Tier.HUANGHOU) {
            return "native/coremod: requires full restart";
        }
        for (Dependency dependency : dependencies) {
            if (dependency.required() && !dependency.satisfied()) {
                return "missing dependency: " + dependency.modId() + " " + dependency.versionRange();
            }
        }
        if ((tier == Tier.FEI || (tier == Tier.GUIFEI && mixins)) && !JbrSupport.available()) {
            return "fei-tier without JBR: layout-preserving mixins apply, layout-changing "
                    + "targets are refused (" + JbrSupport.describe() + ")" + JbrSupport.hint();
        }
        if (mixinRuntimeMissing()) {
            return "fei-tier mixins require " + MixinRuntimeSupport.name() + " to be installed";
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
        return ids + " · " + tier.id() + (mixins ? " · mixins" : "")
                + (registry ? " · registry" : "") + (nativeCode ? " · native" : "");
    }
}
