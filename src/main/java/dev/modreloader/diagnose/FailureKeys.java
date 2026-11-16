package dev.modreloader.diagnose;

import dev.modreloader.hotadd.HotAddEngine;

/**
 * Maps a structured failure to its translation-key stem.
 *
 * <p>Split out of {@link FailureAnalyzer} so it can be unit-tested without a
 * game: it imports no Minecraft classes. A new {@link HotAddEngine.FailureKind}
 * that is not mapped here is a compile-time error (the switch is exhaustive),
 * and the test asserts both language files carry the matching messages.
 */
public final class FailureKeys {
    private FailureKeys() {
    }

    public static String of(HotAddEngine.Result result) {
        return of(result.rollbackPartial()
                ? HotAddEngine.FailureKind.ROLLBACK_PARTIAL
                : result.kind());
    }

    public static String of(HotAddEngine.FailureKind kind) {
        return switch (kind) {
            case MISSING_FILE -> "missing_file";
            case NOT_A_MOD -> "not_a_mod";
            case INVALID_METADATA -> "invalid_metadata";
            case ALREADY_LOADED -> "already_loaded";
            case MISSING_DEPENDENCY -> "missing_dependency";
            case JBR_REQUIRED -> "jbr_required";
            case MIXIN_RUNTIME -> "mixin_runtime";
            case CAPABILITY -> "capability";
            case NETWORK_PAYLOAD -> "network_payload";
            case UNSUPPORTED_TIER -> "unsupported_tier";
            case MIXIN_CONFIG -> "mixin_config";
            case MIXIN_TARGET -> "mixin_target";
            case REDEFINE -> "redefine";
            case MODULE -> "module";
            case MODLIST -> "modlist";
            case REGISTRY -> "registry";
            case CONSTRUCT -> "construct";
            case EVENT -> "event";
            case RESOURCE_PACK -> "resource_pack";
            case DATAPACK -> "datapack";
            case ACCESS_TRANSFORMER -> "access_transformer";
            case CLIENT_REGISTRATION -> "client_registration";
            case JAR_IN_JAR -> "jar_in_jar";
            case ROLLBACK_PARTIAL -> "rollback_partial";
            case NONE, UNKNOWN -> "unknown";
        };
    }
}
