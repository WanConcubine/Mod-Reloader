package dev.modreloader.inspect;

import java.lang.management.ManagementFactory;
import java.util.Locale;

/**
 * Detection for JetBrains Runtime's enhanced class redefinition.
 *
 * <p>JBR alone is not enough: {@code -XX:+AllowEnhancedClassRedefinition} must
 * be present, otherwise layout-changing redefines are rejected.
 */
public final class JbrSupport {
    private JbrSupport() {
    }

    public static boolean isJbr() {
        return System.getProperty("java.vendor", "").toLowerCase(Locale.ROOT).contains("jetbrains");
    }

    public static boolean enhancedRedefinition() {
        return ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .anyMatch(arg -> arg.contains("AllowEnhancedClassRedefinition"));
    }

    public static boolean available() {
        return isJbr() && enhancedRedefinition();
    }

    public static String describe() {
        return "JBR=" + isJbr() + ", enhancedRedefinition=" + enhancedRedefinition();
    }
}
