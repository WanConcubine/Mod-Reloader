package dev.modreloader.diagnose;

import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.inspect.JbrSupport;
import dev.modreloader.inspect.MixinRuntimeSupport;
import dev.modreloader.inspect.ModInspection;
import dev.modreloader.ui.ModReloaderI18n;

import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Turns a structured add failure (or a preflight rejection) into a user-facing
 * explanation. This is deliberately conservative: when the cause is unknown it
 * shows the raw detail instead of inventing a reason.
 */
public final class FailureAnalyzer {
    /**
     * @param restartRecommended the documented fix is "restart with the jar in mods/"
     * @param jbrHelps           JBR enhanced redefinition would actually help this
     *                           failure (AT, layout change) — the UI offers the
     *                           install guide only in that case
     */
    public record Report(Component title, Component summary, Component suggestion, String detail,
                         boolean restartRecommended, boolean jbrHelps) {
        public String clipboardText() {
            return title.getString() + "\n\n" + summary.getString() + "\n\n"
                    + suggestion.getString() + "\n\n" + detail;
        }
    }

    private FailureAnalyzer() {
    }

    public static Report analyze(HotAddEngine.Result result, ModInspection inspection) {
        String name = inspection == null ? "mod" : inspection.fileName();
        Component title = Component.translatable("modreloader.failure.title", name);
        String key = FailureKeys.of(result);
        Component summary;
        Component suggestion;
        if (result.rollbackPartial()) {
            summary = Component.translatable("modreloader.failure.rollback_partial.summary");
            suggestion = Component.translatable("modreloader.failure.rollback_partial.suggestion");
        } else if (result.kind() == HotAddEngine.FailureKind.MISSING_DEPENDENCY) {
            String dependency = "?";
            if (inspection != null) {
                dependency = inspection.dependencies().stream()
                        .filter(dep -> dep.required() && !dep.satisfied())
                        .map(dep -> ModReloaderI18n.dependencyName(dep.modId())
                                + " " + dep.versionRange())
                        .findFirst()
                        .orElse("?");
            }
            summary = Component.translatable("modreloader.failure.missing_dependency.summary", dependency);
            suggestion = Component.translatable("modreloader.failure.missing_dependency.suggestion");
        } else if (JbrSupport.status() == JbrSupport.Status.JBR_NO_ENHANCED
                && (result.kind() == HotAddEngine.FailureKind.JBR_REQUIRED
                    || result.kind() == HotAddEngine.FailureKind.ACCESS_TRANSFORMER)) {
            // The user already selected JBR; the missing piece is the flag a
            // managed relaunch adds. "Use JBR" here reads as "you are not on JBR".
            summary = Component.translatable("modreloader.failure." + key + ".summary");
            suggestion = Component.translatable("modreloader.suggestion.jbr_flag");
        } else {
            summary = Component.translatable("modreloader.failure." + key + ".summary");
            suggestion = Component.translatable("modreloader.failure." + key + ".suggestion");
        }

        List<String> lines = new ArrayList<>();
        if (inspection != null) {
            lines.add(I18n.get("modreloader.detail.jar", inspection.jar()));
            lines.add(I18n.get("modreloader.detail.preflight",
                    ModReloaderI18n.summary(inspection).getString()));
            lines.add(I18n.get("modreloader.detail.dependencies",
                    dependencySummary(inspection)));
            lines.add(I18n.get("modreloader.detail.jbr", JbrSupport.describe()));
            lines.add(I18n.get("modreloader.detail.mixinruntime", MixinRuntimeSupport.describe()));
        }
        lines.add(I18n.get("modreloader.detail.stage", result.stage()));
        lines.add(I18n.get("modreloader.detail.kind", result.kind()));
        lines.add(I18n.get("modreloader.detail.message", result.message()));
        if (!result.rollbackErrors().isEmpty()) {
            lines.add(I18n.get("modreloader.detail.rollback_errors", result.rollbackErrors()));
        }
        lines.add(I18n.get("modreloader.detail.log",
                Path.of("logs/latest.log").toAbsolutePath()));

        boolean jbrHelps = result.kind() == HotAddEngine.FailureKind.ACCESS_TRANSFORMER
                || result.kind() == HotAddEngine.FailureKind.JBR_REQUIRED;
        return new Report(title, summary, suggestion, String.join("\n", lines),
                restartIsTheFix(result), jbrHelps);
    }

    public static Report preflight(ModInspection inspection) {
        String name = inspection.fileName();
        Component title = Component.translatable("modreloader.failure.title", name);
        Component summary = ModReloaderI18n.blockReason(inspection);
        Component suggestion;
        if (!inspection.ok()) {
            String error = inspection.error() == null ? "" : inspection.error();
            suggestion = Component.translatable(error.contains("GAMELIBRARY")
                    ? "modreloader.failure.library.suggestion"
                    : "modreloader.preflight.suggestion.invalid");
        } else if (inspection.alreadyLoaded()) {
            suggestion = Component.translatable("modreloader.preflight.suggestion.restart");
        } else if (inspection.accessTransformers()) {
            // The engine refuses these before touching anything on a stock JVM; the
            // preflight screen is where a user learns that a restart is the fix.
            suggestion = Component.translatable("modreloader.failure.access_transformer.suggestion");
        } else if (inspection.tier() == ModInspection.Tier.GUIFEI) {
            suggestion = Component.translatable("modreloader.preflight.suggestion.guifei");
        } else if (inspection.tier() == ModInspection.Tier.HUANGHOU) {
            suggestion = Component.translatable("modreloader.preflight.suggestion.huanghou");
        } else if (inspection.tier() == ModInspection.Tier.FEI && !JbrSupport.available()) {
            suggestion = Component.translatable(
                    JbrSupport.status() == JbrSupport.Status.JBR_NO_ENHANCED
                            ? "modreloader.preflight.suggestion.jbr_flag"
                            : "modreloader.preflight.suggestion.jbr");
        } else if (inspection.mixinRuntimeMissing()) {
            suggestion = Component.translatable("modreloader.preflight.suggestion.mixin_runtime",
                    MixinRuntimeSupport.name());
        } else if (inspection.dependencies().stream()
                .anyMatch(dep -> dep.required() && !dep.satisfied())) {
            suggestion = Component.translatable("modreloader.preflight.suggestion.missing_dependency");
        } else {
            suggestion = Component.translatable("modreloader.preflight.suggestion.generic");
        }
        List<String> lines = new ArrayList<>();
        lines.add(I18n.get("modreloader.detail.jar", inspection.jar()));
        lines.add(I18n.get("modreloader.detail.preflight",
                ModReloaderI18n.summary(inspection).getString()));
        if (!inspection.dependencies().isEmpty()) {
            lines.add(I18n.get("modreloader.detail.dependencies", dependencySummary(inspection)));
        }
        if (!inspection.notes().isEmpty()) {
            lines.add(I18n.get("modreloader.detail.notes", inspection.notes()));
        }
        lines.add(I18n.get("modreloader.detail.jbr", JbrSupport.describe()));
        lines.add(I18n.get("modreloader.detail.mixinruntime", MixinRuntimeSupport.describe()));
        // A preflight rejection needs the JBR guide when an AT or a mixin layout
        // change is what stands in the way; a missing dependency is not JBR's problem.
        boolean mixinTier = inspection.tier() == ModInspection.Tier.FEI
                || (inspection.tier() == ModInspection.Tier.GUIFEI && inspection.mixins());
        boolean jbrHelps = inspection.accessTransformers()
                || (mixinTier && MixinRuntimeSupport.available() && !JbrSupport.available());
        return new Report(title, summary, suggestion, String.join("\n", lines),
                inspection.alreadyLoaded() || inspection.tier() == ModInspection.Tier.HUANGHOU
                        || (inspection.tier() == ModInspection.Tier.FEI && !JbrSupport.available()),
                jbrHelps);
    }

    /**
     * Failures whose documented fix is "put the jar in mods/ and restart" - the
     * only ones where a managed relaunch makes sense. Unknown failures keep the
     * game running instead of restarting into the same state.
     */
    private static boolean restartIsTheFix(HotAddEngine.Result result) {
        return switch (result.kind()) {
            case JBR_REQUIRED, ACCESS_TRANSFORMER, CLIENT_REGISTRATION, COREMOD, LIBRARY,
                    UNSUPPORTED_TIER, MIXIN_CONFIG, REDEFINE, ROLLBACK_PARTIAL,
                    RUNTIME_DIRTY -> true;
            default -> false;
        };
    }

    private static String dependencySummary(ModInspection inspection) {
        if (inspection.dependencies().isEmpty()) {
            return I18n.get("modreloader.detail.none");
        }
        return inspection.dependencies().stream()
                .map(dep -> {
                    String kind = dep.required()
                            ? I18n.get("modreloader.detail.dependency_required")
                            : I18n.get("modreloader.detail.dependency_optional");
                    String state = dep.satisfied()
                            ? I18n.get("modreloader.detail.dependency_ok")
                            : I18n.get("modreloader.detail.dependency_missing");
                    return ModReloaderI18n.dependencyName(dep.modId()) + " (" + kind + ", " + state + ")";
                })
                .collect(Collectors.joining(", "));
    }
}
