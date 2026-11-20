package dev.modreloader.launch;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Builds the tiny launcher script a managed relaunch spawns.
 *
 * <p>Pure: no game classes, no I/O. The script exists because the game cannot
 * replace its own JVM in-process. We write a short "wait, then start the game
 * again" script and spawn it; when this process exits, the script starts a new
 * one with the same argument list. The wait is what keeps two JVMs from writing
 * the same instance directory at the same time.
 *
 * <p>Quoting is the whole risk here: arguments come from whatever launcher
 * started the game and contain classpaths, paths with spaces and (rarely) shell
 * metacharacters. POSIX gets single quotes (with the {@code '\''} escape),
 * Windows gets double quotes and {@code %}-doubling; anything Windows cannot
 * represent safely is rejected so the caller can fall back to "please restart
 * manually" instead of launching a corrupted command line.
 */
public final class RelaunchScripts {
    private RelaunchScripts() {
    }

    /** Options whose value is the following token rather than part of the option. */
    private static final Set<String> SEPARATE_VALUE = Set.of(
            "-cp", "-classpath", "--class-path",
            "-p", "--module-path", "--upgrade-module-path",
            "--patch-module", "--add-modules", "--limit-modules",
            // Launchers (PCL2 among them) pass these with a space, not '=':
            //   --add-opens java.base/java.lang=ALL-UNNAMED
            // Treating the module spec as the main class put our -D flags between
            // the option and its value: "Error: --add-opens requires modules to
            // be specified".
            "--add-opens", "--add-exports", "--add-reads");

    /**
     * Adds JVM options in front of the class/jar/module the JVM launches.
     *
     * <p>Where they sit is the whole point: a JVM stops parsing options at the
     * main class, so {@code -XX:...}/{@code -D...} appended at the end of the
     * launcher's command line become <em>program</em> arguments and silently do
     * nothing. The managed relaunch used to append them, which is why the JBR
     * flag never reached the replacement JVM.
     */
    public static List<String> withJvmFlags(List<String> arguments, List<String> flags) {
        if (flags.isEmpty()) {
            return List.copyOf(arguments);
        }
        int at = launchTargetIndex(arguments);
        List<String> out = new ArrayList<>(arguments.size() + flags.size());
        out.addAll(arguments.subList(0, at));
        out.addAll(flags);
        out.addAll(arguments.subList(at, arguments.size()));
        return out;
    }

    /** Index of the first token the JVM reads as the main class, jar or module. */
    static int launchTargetIndex(List<String> arguments) {
        int i = 0;
        while (i < arguments.size()) {
            String token = arguments.get(i);
            if (token.equals("-jar") || token.equals("-m") || token.equals("--module")) {
                return i;
            }
            if (SEPARATE_VALUE.contains(token)) {
                i += 2;
                continue;
            }
            if (token.startsWith("-")) {
                i++;
                continue;
            }
            return i;
        }
        return arguments.size();
    }

    /**
     * Splits a Windows command line into arguments (CommandLineToArgvW rules for
     * quotes and backslashes). Used when the JDK does not expose the current
     * process's argv on Windows.
     */
    public static List<String> splitWindowsCommandLine(String commandLine) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        int backslashes = 0;
        for (char c : commandLine.toCharArray()) {
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == '"') {
                if (backslashes % 2 == 1) {
                    current.append("\\".repeat(backslashes / 2)).append('"');
                } else {
                    current.append("\\".repeat(backslashes / 2));
                    inQuotes = !inQuotes;
                }
                backslashes = 0;
                continue;
            }
            if (backslashes > 0) {
                current.append("\\".repeat(backslashes));
                backslashes = 0;
            }
            if (!inQuotes && Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
                continue;
            }
            current.append(c);
        }
        if (backslashes > 0) {
            current.append("\\".repeat(backslashes));
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }

    /** @return the script text, POSIX sh-compatible */
    public static String posix(Path executable, List<String> arguments, Path workingDir,
                               int waitSeconds) {
        StringBuilder script = new StringBuilder();
        script.append("#!/bin/sh\n");
        script.append("# generated by Mod Reloader: wait for the old game, then start a new one\n");
        script.append("sleep ").append(Math.max(0, waitSeconds)).append('\n');
        script.append("cd ").append(quotePosix(workingDir.toString())).append(" || exit 1\n");
        script.append("exec ").append(quotePosix(executable.toString()));
        for (String argument : arguments) {
            script.append(' ').append(quotePosix(argument));
        }
        script.append('\n');
        return script.toString();
    }

    /** @return the script text, cmd.exe-compatible */
    public static String windows(Path executable, List<String> arguments, Path workingDir,
                                 int waitSeconds) {
        StringBuilder script = new StringBuilder();
        script.append("@echo off\r\n");
        // UTF-8 so a Chinese/space path in the game dir or the JBR path is read
        // correctly; without this cmd.exe uses the OEM code page and reports
        // "系统找不到指定的路径" for a path that exists.
        script.append("chcp 65001 >nul\r\n");
        script.append("rem generated by Mod Reloader: wait for the old game, then start a new one\r\n");
        script.append("timeout /t ").append(Math.max(0, waitSeconds)).append(" /nobreak >nul\r\n");
        script.append("cd /d ").append(quoteWindows(workingDir.toString()))
                .append(" || (echo [relaunch] cannot cd to the game dir & exit /b 1)\r\n");
        script.append('"').append(executable).append('"');
        for (String argument : arguments) {
            script.append(' ').append(quoteWindows(argument));
        }
        script.append("\r\n");
        return script.toString();
    }

    /**
     * Single-quotes an argument. Inside single quotes sh has no expansion, so the
     * only character that needs care is the quote itself ({@code '\''} closes,
     * escapes one quote, reopens).
     */
    public static String quotePosix(String argument) {
        return "'" + argument.replace("'", "'\\''") + "'";
    }

    /**
     * Double-quotes an argument for cmd.exe. {@code %} is doubled because cmd
     * expands {@code %VAR%} even inside quotes; a literal {@code "} cannot be
     * represented reliably, so it is rejected (the caller then tells the user to
     * restart manually instead of starting a broken command).
     */
    public static String quoteWindows(String argument) {
        if (argument.indexOf('"') >= 0 || argument.indexOf('\n') >= 0
                || argument.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("argument cannot be quoted for cmd.exe: " + argument);
        }
        return '"' + argument.replace("%", "%%") + '"';
    }

    /** @return true when {@link #windows} can represent every argument */
    public static boolean windowsCanRepresent(List<String> arguments) {
        for (String argument : arguments) {
            try {
                quoteWindows(argument);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    /** For logs/tests: the script line as the OS would see it. */
    public static List<String> command(Path executable, List<String> arguments) {
        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(arguments);
        return command;
    }
}
