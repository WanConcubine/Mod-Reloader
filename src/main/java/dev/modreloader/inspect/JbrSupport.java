package dev.modreloader.inspect;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Detection for JetBrains Runtime's enhanced class redefinition.
 *
 * <p>JBR alone is not enough: {@code -XX:+AllowEnhancedClassRedefinition} must
 * be present, otherwise layout-changing redefines are rejected.
 *
 * <p>The runtime cannot switch the JVM it is already running on, so when JBR is
 * missing the useful thing is to say <em>where</em> an install is: the user only
 * has to select it in the launcher. {@link #hint()} does that by scanning the
 * usual per-OS install roots for a JetBrains Runtime 21+.
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

    /**
     * Actionable addendum for a refusal caused by the missing JBR: names an
     * installed JBR 21 when one can be found, empty otherwise.
     */
    public static String hint() {
        if (isJbr()) {
            return enhancedRedefinition()
                    ? ""
                    : " This JVM is JBR but was started without -XX:+AllowEnhancedClassRedefinition;"
                            + " add that flag to the launcher.";
        }
        Optional<Path> install = installation();
        if (install.isEmpty()) {
            return " Install JBR 21 and select it in the launcher, or wait for the sidecar path.";
        }
        return " A JBR 21 install was found at " + install.get()
                + "; select it in the launcher (and add -XX:+AllowEnhancedClassRedefinition).";
    }

    /** @return the home of an installed JetBrains Runtime 21+, if one is found */
    public static Optional<Path> installation() {
        for (Path root : installRoots()) {
            Optional<Path> found = scan(root);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    private static List<Path> installRoots() {
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        java.util.ArrayList<Path> roots = new java.util.ArrayList<>();
        if (os.contains("mac")) {
            roots.add(Path.of(home, "Library", "Java", "JavaVirtualMachines"));
        } else if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            String programFiles = System.getenv("ProgramFiles");
            if (local != null) {
                roots.add(Path.of(local, "Programs"));
                roots.add(Path.of(local, "JetBrains"));
            }
            if (programFiles != null) {
                roots.add(Path.of(programFiles, "Java"));
                roots.add(Path.of(programFiles, "JetBrains"));
            }
        } else {
            roots.add(Path.of("/usr/lib/jvm"));
            roots.add(Path.of("/opt"));
            roots.add(Path.of(home, ".jdks"));
        }
        return roots;
    }

    /** Looks one level down for a JBR home (macOS wraps it in Contents/Home). */
    private static Optional<Path> scan(Path root) {
        if (!Files.isDirectory(root)) {
            return Optional.empty();
        }
        try (Stream<Path> entries = Files.list(root)) {
            return entries
                    .filter(Files::isDirectory)
                    .flatMap(entry -> Stream.of(entry, entry.resolve("Contents").resolve("Home")))
                    .filter(dir -> Files.isRegularFile(dir.resolve("release")))
                    .filter(JbrSupport::isJetBrains21)
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isJetBrains21(Path home) {
        try {
            String release = Files.readString(home.resolve("release"));
            boolean jetbrains = release.contains("JetBrains")
                    || home.toString().toLowerCase(Locale.ROOT).contains("jbr");
            if (!jetbrains) {
                return false;
            }
            for (String line : release.split("\\R")) {
                if (line.startsWith("JAVA_VERSION=")) {
                    String version = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                    return version.startsWith("21") || version.startsWith("2")
                            && Integer.parseInt(version.split("\\.")[0]) > 21;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
