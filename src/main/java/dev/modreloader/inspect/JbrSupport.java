package dev.modreloader.inspect;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    /** What the running JVM can actually do, for UI and messages. */
    public enum Status {
        /** JetBrains Runtime with enhanced redefinition enabled. */
        READY,
        /** JetBrains Runtime, but the -XX flag is missing (a restart can add it). */
        JBR_NO_ENHANCED,
        /** Not a JetBrains Runtime at all. */
        NOT_JBR
    }

    private JbrSupport() {
    }

    public static boolean isJbr() {
        return looksLikeJbr(
                System.getProperty("java.vendor", ""),
                System.getProperty("java.vm.vendor", ""),
                System.getProperty("java.vm.name", ""),
                System.getProperty("java.home", ""));
    }

    /**
     * Launchers disagree about the vendor string (some report the distro wrapper
     * instead of JetBrains), so the home path counts too.
     */
    static boolean looksLikeJbr(String vendor, String vmVendor, String vmName, String home) {
        return contains(vendor, "jetbrains")
                || contains(vmVendor, "jetbrains")
                || contains(vmName, "jetbrains")
                || contains(home, "jetbrains")
                || contains(home, "jbr");
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    public static boolean enhancedRedefinition() {
        return hasEnhancedFlag(ManagementFactory.getRuntimeMXBean().getInputArguments());
    }

    static boolean hasEnhancedFlag(List<String> arguments) {
        return arguments.stream().anyMatch(arg -> arg.contains("AllowEnhancedClassRedefinition"));
    }

    public static Status status() {
        if (!isJbr()) {
            return Status.NOT_JBR;
        }
        return enhancedRedefinition() ? Status.READY : Status.JBR_NO_ENHANCED;
    }

    public static boolean available() {
        return status() == Status.READY;
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
                    : " This JVM is JBR but enhanced redefinition is off;"
                            + " use the failure screen's JBR restart once.";
        }
        Optional<Path> install = installation();
        if (install.isEmpty()) {
            return " Use the failure screen's \"Prepare JBR automatically\""
                    + " (or \"Restart on JBR\" if JBR is already installed), or wait for the sidecar path.";
        }
        return " A JBR 21 install was found at " + install.get()
                + "; use the failure screen's \"Restart on JBR\" button once.";
    }

    /**
     * Read-only lines for {@code /modreloader jbr}: the running JVM, whether
     * enhanced redefinition is actually on, where an installed JBR 21 is, and
     * the one next step. Written for a player, not for a log parser.
     */
    public static List<String> diagnose() {
        String home = System.getProperty("java.home", "?");
        String vendor = System.getProperty("java.vendor", "?");
        String version = System.getProperty("java.version", "?");
        List<String> lines = new ArrayList<>();
        lines.add("[JBR] java.home = " + home);
        lines.add("[JBR] vendor = " + vendor + ", version = " + version);
        lines.add("[JBR] isJbr = " + isJbr()
                + ", enhanced redefinition = " + enhancedRedefinition()
                + ", layout redefinition = " + (available() ? "available" : "NOT available"));
        String override = System.getProperty("modreloader.jbr", "").trim();
        if (!override.isEmpty()) {
            lines.add("[JBR] JBR override = " + override);
        }
        if (!EXTRA_ROOTS.isEmpty()) {
            lines.add("[JBR] extra roots (game dir + ancestors): " + EXTRA_ROOTS);
        }
        installation().ifPresentOrElse(
                path -> lines.add("[JBR] installed JBR 21 = " + path),
                () -> lines.add("[JBR] installed JBR 21 = not found (searched the usual roots, the game dir "
                        + "and its ancestors, one nested level under names containing jbr/java)"));
        if (available()) {
            lines.add("[JBR] layout-changing hot-add can work; no action needed.");
        } else if (isJbr()) {
            lines.add("[JBR] next: use the failure screen's JBR restart once;"
                    + " the runtime enables what is needed.");
        } else if (installation().isPresent()) {
            lines.add("[JBR] next: use the failure screen's \"Restart on JBR\" button once;"
                    + " no launcher changes or arguments needed.");
            lines.add("[JBR] stock JVM is fine for everything except hot-adding a mod that changes "
                    + "the layout of an already-loaded class.");
        } else {
            lines.add("[JBR] where to put it: inside the game directory (the folder with mods/) or next to it; "
                    + "not into mods/. Detection scans both places on every lookup.");
            lines.add("[JBR] next: install JBR 21 (JetBrains Runtime, same OS/arch): "
                    + "https://github.com/JetBrains/JetBrainsRuntime/releases (pick 21.0.x);"
                    + " the failure screen's \"Prepare JBR automatically\" can do it for you.");
            lines.add("[JBR] no hot-add needed: drop the jar into mods/ and restart - it loads as a "
                    + "normal mod. The client failure screen has the same steps as a guide.");
            lines.add("[JBR] stock JVM is fine for everything except hot-adding a mod that changes "
                    + "the layout of an already-loaded class.");
        }
        return lines;
    }

    /** Directories to search before the usual install roots (game dir and its parent). */
    private static final java.util.List<Path> EXTRA_ROOTS = new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Adds a game directory (and its parent) to the search roots.
     *
     * <p>Players often unpack JBR next to {@code .minecraft} (or inside it), which
     * is not one of the per-OS install roots. Called once at startup; the scan
     * itself runs on every {@link #installation()} call, so a JBR unpacked while
     * the game is running is found without a restart.
     */
    public static void includeDirectory(Path dir) {
        for (Path root : ancestorRoots(dir)) {
            if (!EXTRA_ROOTS.contains(root)) {
                EXTRA_ROOTS.add(root);
            }
        }
    }

    /**
     * The directory itself plus up to three ancestors (never a filesystem root).
     *
     * <p>Launchers disagree about what the "game directory" is: with version
     * isolation it can be {@code .minecraft/versions/<ver>}, while the player
     * unpacked JBR next to {@code .minecraft}. Walking up three levels covers
     * {@code .minecraft/versions/<ver>} → {@code .minecraft} → its parent.
     */
    static List<Path> ancestorRoots(Path dir) {
        java.util.ArrayList<Path> roots = new java.util.ArrayList<>();
        if (dir == null) {
            return roots;
        }
        Path current = dir.toAbsolutePath().normalize();
        for (int i = 0; i < 4 && current != null; i++) {
            if (current.getParent() == null) {
                break; // filesystem root: scanning it is pointless and slow
            }
            roots.add(current);
            current = current.getParent();
        }
        return roots;
    }

    /** Explicit override: {@code -Dmodreloader.jbr=<JBR home or bin/java>}. */
    private static Optional<Path> explicitInstall() {
        String value = System.getProperty("modreloader.jbr", "").trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value).toAbsolutePath().normalize();
            if (Files.isRegularFile(path.resolve("release"))) {
                return Optional.of(path);
            }
            // Allow pointing at <JBR>/bin/java (or java.exe).
            Path bin = path.getParent();
            Path home = bin == null ? null : bin.getParent();
            if (home != null && Files.isRegularFile(home.resolve("release"))) {
                return Optional.of(home);
            }
        } catch (Throwable ignored) {
            // Fall back to the scan; diagnose() shows the raw override.
        }
        return Optional.empty();
    }

    /** @return the home of an installed JetBrains Runtime 21+, if one is found */
    public static Optional<Path> installation() {
        Optional<Path> explicit = explicitInstall();
        if (explicit.isPresent() && isUsable(explicit.get())) {
            return explicit;
        }
        // A launcher-managed JBR often lives outside the usual install roots. If the
        // game is already running on one, that home is the best candidate: the
        // "Restart on JBR" button must work for the common case "JBR selected in the
        // launcher, but the enhanced-redefinition flag is missing".
        Optional<Path> running = runningHome(System.getProperty("java.home", ""), isJbr());
        if (running.isPresent()) {
            return running;
        }
        return findInstall(installRoots());
    }

    /** The home of the JVM that is running the game, when it is a usable JBR. */
    static Optional<Path> runningHome(String javaHome, boolean onJbr) {
        if (!onJbr || javaHome == null || javaHome.isBlank()) {
            return Optional.empty();
        }
        Path home = Path.of(javaHome);
        return isUsable(home) ? Optional.of(home) : Optional.empty();
    }

    /** Visible for the game-free test: scan the given roots in order. */
    static Optional<Path> findInstall(List<Path> roots) {
        for (Path root : roots) {
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
        java.util.ArrayList<Path> roots = new java.util.ArrayList<>(EXTRA_ROOTS);
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
            List<Path> children = entries.filter(Files::isDirectory).toList();
            List<Path> candidates = new java.util.ArrayList<>();
            for (Path child : children) {
                addCandidate(candidates, child);
                // A zip often extracts into a wrapper folder
                // (jbr-21.0.11-windows-x64/jbr-21.0.11/release): descend one more
                // level for names that look like a JVM, not for every folder.
                String name = child.getFileName().toString().toLowerCase(Locale.ROOT);
                if (!name.contains("jbr") && !name.contains("java")) {
                    continue;
                }
                try (Stream<Path> nested = Files.list(child)) {
                    nested.filter(Files::isDirectory).forEach(grandchild -> addCandidate(candidates, grandchild));
                } catch (Exception ignored) {
                    // Unreadable nested folder: keep the candidates we have.
                }
            }
            List<Path> usable = candidates.stream()
                    .filter(dir -> Files.isRegularFile(dir.resolve("release")))
                    .filter(JbrSupport::isUsable)
                    .toList();
            // Prefer a Java 21 runtime: Minecraft 1.21.1 is only validated on 21,
            // so a newer JBR that happens to sit next to it must not win.
            return usable.stream().filter(dir -> majorVersion(dir) == 21).findFirst()
                    .or(() -> usable.stream().findFirst());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static void addCandidate(List<Path> candidates, Path dir) {
        candidates.add(dir);
        candidates.add(dir.resolve("Contents").resolve("Home")); // macOS bundle layout
    }

    private static boolean isUsable(Path home) {
        try {
            String release = Files.readString(home.resolve("release"));
            boolean jetbrains = release.contains("JetBrains")
                    || home.toString().toLowerCase(Locale.ROOT).contains("jbr");
            // Minecraft 1.21.1 is validated on Java 21; a newer JBR (25 in the
            // current release list) is not a drop-in replacement, so do not offer it.
            return jetbrains && majorVersion(home) == 21;
        } catch (Exception e) {
            return false;
        }
    }

    /** Major Java version from the {@code release} file; -1 when unknown. */
    private static int majorVersion(Path home) {
        try {
            for (String line : Files.readString(home.resolve("release")).split("\\R")) {
                if (!line.startsWith("JAVA_VERSION=")) {
                    continue;
                }
                String version = line.substring("JAVA_VERSION=".length()).replace("\"", "").trim();
                String major = version.split("\\.")[0];
                return Integer.parseInt(major);
            }
        } catch (Exception ignored) {
            // Unknown version: treat as unusable rather than guessing.
        }
        return -1;
    }
}
