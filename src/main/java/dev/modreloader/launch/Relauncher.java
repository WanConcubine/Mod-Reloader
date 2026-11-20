package dev.modreloader.launch;

import dev.modreloader.ModReloader;
import dev.modreloader.inspect.JbrSupport;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.client.server.IntegratedServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Managed relaunch: save the game, start a new process with the same JVM and the
 * same argument list, then exit this one.
 *
 * <p>A JVM cannot replace itself, so "switch to JBR" or "apply a restart-only mod"
 * always means a new process. The new process is started by a generated script
 * that waits a few seconds, {@code cd}s to the game directory and then re-runs the
 * command line the launcher gave us; the wait keeps the two JVMs from writing the
 * same instance directory at once.
 *
 * <p><b>Deliberately does not touch {@code mods/}</b>: installing a jar there is the
 * player's/launcher's job. This class only restarts the game the user already has
 * (or, later, restarts it on JBR when they explicitly ask).
 *
 * <p>Relaunching cannot be guaranteed: the launcher that started the game may kill
 * the process tree, and some platforms do not expose the full command line. Every
 * failure path returns a message and leaves the current game running; the caller
 * shows it instead of exiting.
 */
public final class Relauncher {
    /** Set on the child so it can wait for this process to disappear and log the origin. */
    public static final String WAIT_FOR_PID = "modreloader.relaunched.waitForPid";
    public static final String RELAUNCHED = "modreloader.relaunched";
    private static final int WAIT_SECONDS = 2;
    private static final int MAX_LOG_TAIL = 4000;

    private Relauncher() {
    }

    /**
     * The arguments of this process, without the executable.
     *
     * <p>{@code ProcessHandle.current().info().arguments()} is empty for the
     * current process on Windows in several JDKs, which used to make managed
     * relaunch impossible there ("the launcher did not expose the game's
     * command line"). Fall back to Win32_Process.CommandLine via PowerShell/WMIC,
     * which does report it. The first token (the java executable) is dropped.
     */
    public static Optional<String[]> currentArguments() {
        Optional<String[]> direct = ProcessHandle.current().info().arguments();
        if (direct.isPresent() && direct.get().length > 0) {
            return direct;
        }
        if (!isWindows()) {
            return Optional.empty();
        }
        Optional<String> raw = windowsCommandLine();
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        List<String> parsed = RelaunchScripts.splitWindowsCommandLine(raw.get());
        if (!parsed.isEmpty() && looksLikeJavaExe(parsed.get(0))) {
            parsed.remove(0);
        }
        return parsed.isEmpty() ? Optional.empty() : Optional.of(parsed.toArray(String[]::new));
    }

    private static boolean looksLikeJavaExe(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        return lower.endsWith("java.exe") || lower.endsWith("javaw.exe")
                || lower.endsWith("/java") || lower.endsWith("\\java");
    }

    private static Optional<String> windowsCommandLine() {
        long pid = ProcessHandle.current().pid();
        // The command line contains Unicode paths. PowerShell's console output is
        // in the OEM code page (GBK on a Chinese Windows), so reading it as UTF-8
        // turned "C:\\Dstick\\[模组热重载测试]" into "[????????????]" and the child JVM
        // failed with InvalidPathException. Force UTF-8 for PowerShell and decode
        // WMIC (which cannot be forced) with the native charset.
        Optional<String> viaPowerShell = runForCommandLine(new String[]{
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8; "
                        + "(Get-CimInstance Win32_Process -Filter 'ProcessId=" + pid + "').CommandLine"},
                java.nio.charset.StandardCharsets.UTF_8);
        if (viaPowerShell.isPresent()) {
            return viaPowerShell;
        }
        return runForCommandLine(new String[]{
                        "wmic", "process", "where", "processid=" + pid, "get", "commandline", "/value"},
                nativeCharset());
    }

    private static Optional<String> runForCommandLine(String[] command,
                                                      java.nio.charset.Charset charset) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            byte[] bytes = process.getInputStream().readAllBytes();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0) {
                return Optional.empty();
            }
            String output = new String(bytes, charset).trim();
            if (output.startsWith("CommandLine=")) {
                output = output.substring("CommandLine=".length()).trim();
            }
            return output.isBlank() ? Optional.empty() : Optional.of(output);
        } catch (Throwable ignored) {
            return Optional.empty();
        }
    }

    private static java.nio.charset.Charset nativeCharset() {
        String name = System.getProperty("native.encoding",
                System.getProperty("sun.jnu.encoding", "UTF-8"));
        try {
            return java.nio.charset.Charset.forName(name);
        } catch (Throwable ignored) {
            return java.nio.charset.StandardCharsets.UTF_8;
        }
    }

    /**
     * A launchable java binary. Windows has no POSIX execute bit, so a present
     * java.exe is the contract there; on POSIX we still require +x.
     */
    public static boolean isRunnable(Path java) {
        if (java == null || !Files.isRegularFile(java)) {
            return false;
        }
        return isWindows() || Files.isExecutable(java);
    }

    /** @return null when a relaunch is possible, otherwise why it is not */
    public static String blocker() {
        Optional<String[]> arguments = currentArguments();
        if (arguments.isEmpty() || arguments.get().length == 0) {
            return "the launcher did not expose the game's command line";
        }
        Path java = executable();
        if (!isRunnable(java)) {
            return "java executable not found: " + java;
        }
        if (isWindows() && !RelaunchScripts.windowsCanRepresent(stripRelaunchFlags(arguments.get()))) {
            return "the command line has characters cmd.exe cannot quote";
        }
        return null;
    }

    public static boolean canRelaunch() {
        return blocker() == null;
    }

    /**
     * Saves the world, spawns the replacement and exits. Never returns normally:
     * on failure it returns the reason and the caller keeps the game running.
     */
    public static Optional<String> saveAndRestart(Minecraft minecraft, String reason) {
        return saveAndRestart(minecraft, reason, executable(), List.of());
    }

    /**
     * B: restart on a different JVM. The caller must have confirmed with the user -
     * this overrides the launcher's choice for this session only; the next launch
     * still uses whatever the launcher is configured with.
     *
     * @param java          the JVM to run the replacement on (JBR for the enhanced path)
     * @param extraJvmArgs flags to add (for JBR, {@code -XX:+AllowEnhancedClassRedefinition})
     */
    public static Optional<String> saveAndRestart(Minecraft minecraft, String reason, Path java,
                                                  List<String> extraJvmArgs) {
        if (minecraft == null) {
            return Optional.of("no client");
        }
        if (!isRunnable(java)) {
            return Optional.of("java executable not found: " + java);
        }
        String blocker = blocker();
        if (blocker != null) {
            return Optional.of(blocker);
        }
        ModReloader.LOGGER.info("[ModReloader] managed relaunch requested ({})", reason);
        // 1. Save: the new process must not see a half-written world.
        try {
            minecraft.options.save();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot save options before relaunch", t);
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            try {
                server.saveEverything(true, true, true);
                ModReloader.LOGGER.info("[ModReloader] saved the integrated server before relaunch");
            } catch (Throwable t) {
                ModReloader.LOGGER.error("[ModReloader] cannot save the world before relaunch", t);
                return Optional.of("cannot save the world: " + t);
            }
        }
        // 2. Same JVM, same arguments, plus the marker the child waits on.
        List<String> arguments = stripRelaunchFlags(
                currentArguments().orElse(new String[0]));
        long pid = ProcessHandle.current().pid();
        // Before the main class: the JVM stops parsing options there, so these
        // must not be appended after it (they would become program arguments).
        List<String> markers = new ArrayList<>(extraJvmArgs);
        markers.add("-D" + RELAUNCHED + "=true");
        markers.add("-D" + WAIT_FOR_PID + "=" + pid);
        List<String> childArguments = RelaunchScripts.withJvmFlags(arguments, markers);
        Path gameDir = minecraft.gameDirectory.toPath();
        try {
            // Launch the JVM directly: a generated .cmd used to be needed to wait
            // and to detach, but cmd.exe re-encodes the file in the OEM code page,
            // so a game/JBR path with Chinese characters or brackets failed with
            // "系统找不到指定的路径". ProcessBuilder passes Unicode paths natively;
            // the child waits for this PID (awaitPreviousProcess) before writing
            // the instance directory, so no shell-side delay is needed either.
            Path relaunchDir = gameDir.resolve("modreloader").resolve("relaunch");
            Files.createDirectories(relaunchDir);
            Path log = relaunchDir.resolve("relaunch-" + System.currentTimeMillis() + ".log");
            // javaw.exe on Windows: no console window for the replacement game.
            Path javaExe = java;
            if (isWindows()) {
                Path javaw = java.resolveSibling("javaw.exe");
                if (isRunnable(javaw)) {
                    javaExe = javaw;
                }
            }
            List<String> command = new ArrayList<>();
            command.add(javaExe.toString());
            command.addAll(childArguments);
            Process child = new ProcessBuilder(command)
                    .directory(gameDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .start();
            ModReloader.LOGGER.info("[ModReloader] replacement JVM spawned: pid={} java={} log={}",
                    child.pid(), java, log);
            // 3. Prove a replacement JVM is actually running before this one leaves.
            //    On Windows the child's command line may be unreadable, so it writes
            //    a marker itself as soon as it boots (Relauncher.signalStarted).
            if (!awaitReplacement(gameDir, pid, 12)) {
                return Optional.of("the replacement game did not start" + tail(gameDir));
            }
            ModReloader.LOGGER.info("[ModReloader] replacement game started; exiting this process");
        } catch (Throwable t) {
            ModReloader.LOGGER.error("[ModReloader] cannot start the replacement process", t);
            return Optional.of(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        // 4. Leave. The world is saved; the new process waits for this PID.
        System.exit(0);
        return Optional.empty();
    }

    /**
     * Writes the script next to the game (not in {@code mods/}): one file per
     * attempt, so a failed launch leaves its log for the next diagnosis.
     */
    public static Path writeScript(Path gameDir, Path executable, List<String> arguments,
                                   int waitSeconds) throws IOException {
        Path dir = gameDir.resolve("modreloader").resolve("relaunch");
        Files.createDirectories(dir);
        String stamp = String.valueOf(System.currentTimeMillis());
        boolean windows = isWindows();
        Path script = dir.resolve("relaunch-" + stamp + (windows ? ".cmd" : ".sh"));
        String text = windows
                ? RelaunchScripts.windows(executable, arguments, gameDir, waitSeconds)
                : RelaunchScripts.posix(executable, arguments, gameDir, waitSeconds);
        Files.writeString(script, text, StandardCharsets.UTF_8);
        if (!windows) {
            try {
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE);
                Files.setPosixFilePermissions(script, perms);
            } catch (UnsupportedOperationException ignored) {
                // Not a POSIX filesystem; /bin/sh does not need the bit to read it.
            }
        }
        return script;
    }

    /** Starts the script detached-ish (own console on Windows, own shell elsewhere). */
    public static Process spawn(Path script, Path gameDir) throws IOException {
        Path log = script.resolveSibling(script.getFileName() + ".log");
        ProcessBuilder builder;
        if (isWindows()) {
            // `start` gives the new game its own console; /min keeps it out of the way.
            builder = new ProcessBuilder("cmd", "/c", "start", "", "/min", script.toString());
        } else {
            // setsid detaches from the terminal/process group when available; plain sh is
            // the fallback (the script's sleep still covers a slow launcher cleanup).
            if (Files.isExecutable(Path.of("/usr/bin/setsid"))) {
                builder = new ProcessBuilder("/usr/bin/setsid", "/bin/sh", script.toString());
            } else if (Files.isExecutable(Path.of("/bin/setsid"))) {
                builder = new ProcessBuilder("/bin/setsid", "/bin/sh", script.toString());
            } else {
                builder = new ProcessBuilder("/bin/sh", script.toString());
            }
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.directory(gameDir.toFile());
        return builder.start();
    }

    /**
     * Called on the child at client startup: wait for the previous process to be
     * gone before touching the instance directory.
     */
    public static void awaitPreviousProcess() {
        String raw = System.getProperty(WAIT_FOR_PID);
        if (raw == null) {
            return;
        }
        long pid;
        try {
            pid = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return;
        }
        long deadline = System.currentTimeMillis() + 15_000;
        boolean logged = false;
        while (System.currentTimeMillis() < deadline) {
            Optional<ProcessHandle> previous = ProcessHandle.of(pid);
            if (previous.isEmpty() || !previous.get().isAlive()) {
                ModReloader.LOGGER.info(
                        "[ModReloader] relaunched process: previous JVM {} is gone; continuing", pid);
                return;
            }
            if (!logged) {
                logged = true;
                ModReloader.LOGGER.info(
                        "[ModReloader] relaunched process: waiting for the previous JVM {} to exit", pid);
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        ModReloader.LOGGER.warn("[ModReloader] previous JVM {} still alive after 15s; continuing", pid);
    }

    /** Removes markers from a parent relaunch so they do not accumulate. */
    public static List<String> stripRelaunchFlags(String[] arguments) {
        List<String> kept = new ArrayList<>();
        for (String argument : arguments) {
            if (argument.startsWith("-D" + WAIT_FOR_PID + "=")
                    || argument.startsWith("-D" + RELAUNCHED + "=")) {
                continue;
            }
            kept.add(argument);
        }
        return kept;
    }

    /** The JBR that will run the replacement (same JVM for A', JBR for B). */
    public static Path executable() {
        Path home = Path.of(System.getProperty("java.home", ""));
        return home.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** JBR that could run the replacement, if one is installed on this machine. */
    public static Optional<Path> jbrExecutable() {
        return JbrSupport.installation().map(home ->
                home.resolve("bin").resolve(isWindows() ? "java.exe" : "java"));
    }

    /**
     * B: is a JBR relaunch worth offering? Only when a JBR 21 is installed and the
     * current JVM is not already JBR with enhanced redefinition.
     */
    public static boolean jbrRelaunchAvailable() {
        return !JbrSupport.available() && jbrExecutable().isPresent();
    }

    /** Flags a JBR replacement needs on top of the launcher's arguments. */
    public static List<String> jbrFlags() {
        return jbrFlags(false);
    }

    /**
     * @param enableLateAt also add {@code -Dmodreloader.lateAt=true} so the new
     *                     session can apply a hot-added mod's access transformers
     *                     (experimental, JBR-only; the caller must have warned)
     */
    public static List<String> jbrFlags(boolean enableLateAt) {
        return RelaunchFlags.jvmFlags(JbrSupport.enhancedRedefinition(), enableLateAt);
    }

    private static String tail(Path gameDir) {
        try {
            Path dir = gameDir.resolve("modreloader").resolve("relaunch");
            if (!Files.isDirectory(dir)) {
                return "";
            }
            Path latest = null;
            try (var files = Files.list(dir)) {
                latest = files.filter(p -> p.getFileName().toString().endsWith(".log"))
                        .max(Comparator.comparingLong(p -> p.toFile().lastModified()))
                        .orElse(null);
            }
            if (latest == null) {
                return "";
            }
            String text = Files.readString(latest, StandardCharsets.UTF_8);
            if (text.length() > MAX_LOG_TAIL) {
                text = text.substring(text.length() - MAX_LOG_TAIL);
            }
            return "; child log: " + text;
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Polls for a process whose command line carries this relaunch marker. The
     * child JVM starts after the script's wait, so give it a few seconds; a
     * launcher that kills the process tree never produces one.
     */
    /**
     * Marker the replacement JVM writes as soon as it boots.
     *
     * <p>Scanning other processes' command lines is not portable: Windows often
     * reports them empty, which made a perfectly good relaunch look like a
     * failure ("the replacement game did not start"). The marker is written by
     * the child itself, so it works on every platform.
     */
    static Path startedMarker(Path gameDir, long parentPid) {
        return gameDir.resolve("modreloader").resolve("relaunch")
                .resolve("started-" + parentPid + ".txt");
    }

    /** Child side: called as early as possible when the relaunched JVM boots. */
    public static void signalStarted() {
        String raw = System.getProperty(WAIT_FOR_PID);
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            Path gameDir = FMLPaths.GAMEDIR.get();
            Path marker = startedMarker(gameDir, Long.parseLong(raw.trim()));
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, Long.toString(ProcessHandle.current().pid()),
                    StandardCharsets.UTF_8);
            ModReloader.LOGGER.info("[ModReloader] relaunched process: wrote {}", marker);
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot write the relaunch marker", t);
        }
    }

    private static boolean awaitReplacement(Path gameDir, long parentPid, int seconds) {
        Path marker = startedMarker(gameDir, parentPid);
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(marker)) {
                ModReloader.LOGGER.info("[ModReloader] replacement game started (marker {})", marker);
                return true;
            }
            if (replacementVisible(parentPid)) {
                ModReloader.LOGGER.info(
                        "[ModReloader] replacement game started (command line of pid {})", parentPid);
                return true;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Fallback for platforms that do expose other processes' command lines. */
    private static boolean replacementVisible(long parentPid) {
        String marker = "-D" + WAIT_FOR_PID + "=" + parentPid;
        return ProcessHandle.allProcesses().anyMatch(handle -> handle.pid() != parentPid
                && handle.info().commandLine().orElse("").contains(marker));
    }
}
