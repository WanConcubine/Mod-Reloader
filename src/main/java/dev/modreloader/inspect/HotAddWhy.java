package dev.modreloader.inspect;

import dev.modreloader.Platform;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * "Why can / can't this jar be hot-added?" — the data behind {@code /modreloader why}.
 *
 * <p>The UI already shows a tier and a one-line block reason, but when a mod does
 * not hot-add the useful information is spread over four places that are each
 * computed at a different stage: {@link ModInspection} (tier / content shape /
 * dependencies), {@link AccessTransformerRules} (the AT boundary),
 * {@link RegistrationAuditScanner} (startup-only mod-bus events) and the side /
 * JBR / MixinRuntime facts. This class puts them in one ordered report whose last
 * line is the verdict the engine would reach, in the same order as the
 * {@code HotAddEngine} gates.
 *
 * <p>Read-only: it inspects the jar and the loaded mod list, never mutates
 * anything. The command binds it to a jar path; the server harness calls it
 * directly to assert the verdict.
 */
public final class HotAddWhy {
    /**
     * @param lines     human-readable report, one line per fact, verdict last
     * @param hotAddable true when the runtime would attempt the add
     */
    public record Report(List<String> lines, boolean hotAddable) {
        public Report {
            lines = List.copyOf(lines);
        }

        /** The last line (the verdict), or an empty string. */
        public String verdict() {
            for (int i = lines.size() - 1; i >= 0; i--) {
                if (lines.get(i).startsWith("verdict: ")) {
                    return lines.get(i).substring("verdict: ".length());
                }
            }
            return "";
        }
    }

    private HotAddWhy() {
    }

    public static Report of(Path jar) {
        List<String> lines = new ArrayList<>();
        ModInspection inspection;
        try {
            inspection = ModInspector.inspect(jar);
        } catch (Throwable t) {
            lines.add("why " + jar.getFileName() + ": cannot inspect the jar: " + t);
            return new Report(lines, false);
        }
        lines.add("why " + inspection.fileName() + " (" + Platform.side() + ")");
        if (!inspection.ok()) {
            lines.add("  unreadable: " + inspection.error());
            lines.add("verdict: refused (not a valid mod jar)");
            return new Report(lines, false);
        }

        lines.add("  ids: " + ids(inspection));
        lines.add("  tier: " + inspection.tier().id()
                + (inspection.mixins() ? ", mixins" : "")
                + (inspection.registry() ? ", registry content" : ""));
        lines.add("  content: registry=" + inspection.registry()
                + ", mixins=" + inspection.mixins()
                + ", native=" + inspection.nativeCode()
                + ", client-only=" + inspection.clientSideOnly()
                + ", already-loaded=" + inspection.alreadyLoaded());

        AccessTransformerRules at = AccessTransformerRules.empty();
        try {
            at = AccessTransformerRules.read(jar);
            lines.add("  access transformer: " + at.describe());
        } catch (Throwable t) {
            lines.add("  access transformer: cannot read (" + t + ")");
        }

        List<String> missing = new ArrayList<>();
        for (ModInspection.Dependency dependency : inspection.dependencies()) {
            if (dependency.required() && !dependency.satisfied()) {
                missing.add(dependency.modId() + " " + dependency.versionRange());
            }
        }
        if (!inspection.dependencies().isEmpty()) {
            StringBuilder deps = new StringBuilder();
            for (ModInspection.Dependency dependency : inspection.dependencies()) {
                if (deps.length() > 0) {
                    deps.append(", ");
                }
                deps.append(dependency.modId())
                        .append(dependency.required() ? " (required" : " (optional")
                        .append(dependency.satisfied() ? ", present)" : ", MISSING)");
            }
            lines.add("  dependencies: " + deps);
        }

        RegistrationAudit.Report audit = null;
        try {
            audit = RegistrationAuditScanner.audit(jar, Platform.isClient());
            lines.add("  registration audit: " + audit.line());
        } catch (Throwable t) {
            lines.add("  registration audit: unavailable (" + t + ")");
        }
        for (String note : inspection.notes()) {
            if (note.startsWith("references client classes")) {
                // Static scanning cannot know whether these classes are on the startup
                // path: client+server mods keep them in client-only code, while EasyLAN
                // registers a ScreenEvent listener in its @Mod constructor and fails on
                // a dedicated server. Warning, not refusal (the add path rolls back).
                lines.add("  warning: " + note + " - if they are on the startup path this add "
                        + "fails on a dedicated server (the runtime rolls back cleanly)");
            } else {
                lines.add("  note: " + note);
            }
        }

        boolean hotAddable = decide(inspection, at, audit, missing, lines);
        return new Report(lines, hotAddable);
    }

    /** Mirrors the engine's VALIDATE gates, in the same order. */
    private static boolean decide(ModInspection inspection, AccessTransformerRules at,
                                  RegistrationAudit.Report audit, List<String> missing,
                                  List<String> lines) {
        if (inspection.alreadyLoaded()) {
            lines.add("verdict: already loaded (updating needs a restart; only add is implemented)");
            return false;
        }
        if (inspection.clientSideOnly() && !Platform.isClient()) {
            lines.add("verdict: refused on a dedicated server (this mod references client-side "
                    + "classes, which NeoForge refuses on DEDICATED_SERVER; hot-add it on a client)");
            return false;
        }
        if (inspection.accessTransformers() && !at.datagenOnly()) {
            lines.add("verdict: refused (access transformers apply at class load; restart with the "
                    + "jar in mods/) - " + at.describe());
            return false;
        }
        if (inspection.accessTransformers() && at.datagenOnly()) {
            lines.add("verdict: refused (access transformers apply at class load), but every target "
                    + "is a datagen class: this refusal is conservative");
            return false;
        }
        if (audit != null && audit.fatal()) {
            lines.add("verdict: refused (registers " + audit.shortNames(RegistrationAudit.Severity.CRASH)
                    + " on the mod bus; this runtime does not replay it, using the content would crash)");
            return false;
        }
        if (!missing.isEmpty()) {
            lines.add("verdict: refused (missing dependency: " + missing + ")");
            return false;
        }
        if (inspection.tier() == ModInspection.Tier.HUANGHOU) {
            lines.add("verdict: refused (needs a full restart: native/coremod or invasive core mixins)");
            return false;
        }
        if (inspection.tier() == ModInspection.Tier.GUIFEI) {
            String prefix = "experimental add-only (registry content; the save depends on the mod and "
                    + "the commit cannot be undone)";
            lines.add("verdict: " + prefix + mixinCaveat(inspection, lines));
            return true;
        }
        if (inspection.tier() == ModInspection.Tier.FEI) {
            String caveat = mixinCaveat(inspection, lines);
            lines.add("verdict: hot-add with mixins (JBR redefinition)" + caveat);
            return caveat.isEmpty();
        }
        lines.add("verdict: hot-add (no mixins, no registry content)");
        return true;
    }

    /** Empty when the mixin machinery is available, otherwise the reason it is not. */
    private static String mixinCaveat(ModInspection inspection, List<String> lines) {
        if (!inspection.mixins()) {
            return "";
        }
        if (!JbrSupport.available()) {
            return "; JBR is missing (" + JbrSupport.describe() + ")";
        }
        if (!MixinRuntimeSupport.available()) {
            return "; " + MixinRuntimeSupport.name() + " is not installed";
        }
        return "";
    }

    private static String ids(ModInspection inspection) {
        List<String> ids = new ArrayList<>();
        for (ModInspection.Mod mod : inspection.mods()) {
            ids.add(mod.modId() + " " + mod.version());
        }
        return ids.isEmpty() ? "?" : String.join(", ", ids);
    }
}
