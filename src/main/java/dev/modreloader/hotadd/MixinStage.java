package dev.modreloader.hotadd;

import net.neoforged.fml.loading.moddiscovery.ModFileParser;

import java.util.List;
import java.util.Set;

/**
 * Boundary between the hot-add engine and {@link MixinSupport}.
 *
 * <p>{@link MixinSupport} links against MixinRuntime (modId {@code mr}) at class
 * level, so the engine must never touch it when that optional dependency is
 * absent — otherwise a Type2 mod would die with {@code NoClassDefFoundError}.
 * This wrapper therefore exchanges only JDK types with the engine: loading it
 * resolves nothing, and {@link #register} runs only after the caller verified
 * MixinRuntime is installed.
 */
public final class MixinStage {
    private MixinStage() {
    }

    /**
     * @param logLines  receives ready-to-log lines
     * @param summaryOut receives at most one fragment for the result message
     * @return {@code null} on success, otherwise the joined error list
     */
    public static String register(List<ModFileParser.MixinConfig> configs, Set<String> hotModIds,
                                  String primaryModId, Rollback rollback,
                                  List<String> logLines, List<String> summaryOut) {
        MixinSupport.Report report = MixinSupport.register(configs, hotModIds, primaryModId, rollback);
        logLines.add("[ModReloader] mixin setup: registered=" + report.registered()
                + ", skipped=" + report.skipped() + ", targets=" + report.targets());
        logLines.add("[ModReloader] mixin setup: redefined=" + report.redefined()
                + ", eager=" + report.eager() + ", deferred=" + report.deferred());
        if (!report.ok()) {
            return String.join(", ", report.errors());
        }
        if (!report.registered().isEmpty()) {
            summaryOut.add(" (mixins " + report.registered() + ", redefined " + report.redefined()
                    + ", eager " + report.eager() + ", deferred " + report.deferred() + ")");
        }
        return null;
    }
}
