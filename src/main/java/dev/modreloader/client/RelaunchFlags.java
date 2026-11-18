package dev.modreloader.client;

import java.util.ArrayList;
import java.util.List;

/**
 * The extra JVM flags a managed relaunch adds on top of the launcher's arguments.
 *
 * <p>Kept in its own game-free class so the decision can be unit-tested:
 * {@link Relauncher} loads Minecraft classes and cannot run in a plain JUnit test.
 *
 * <p>Enabling late AT is a deliberate, per-relaunch choice: it rewrites
 * access/final flags of already-loaded classes process-wide, so it must never be
 * on unless the user asked for it (see the confirm screen's warning).
 */
public final class RelaunchFlags {
    public static final String ENHANCED_REDEFINITION = "-XX:+AllowEnhancedClassRedefinition";
    public static final String LATE_AT = "-Dmodreloader.lateAt=true";

    private RelaunchFlags() {
    }

    /**
     * @param enhancedRedefinitionOn the current JVM already has
     *                               {@code -XX:+AllowEnhancedClassRedefinition}
     * @param enableLateAt           also open the experimental late-AT path for the new session
     */
    public static List<String> jvmFlags(boolean enhancedRedefinitionOn, boolean enableLateAt) {
        List<String> flags = new ArrayList<>(2);
        if (!enhancedRedefinitionOn) {
            flags.add(ENHANCED_REDEFINITION);
        }
        if (enableLateAt) {
            flags.add(LATE_AT);
        }
        return List.copyOf(flags);
    }
}
