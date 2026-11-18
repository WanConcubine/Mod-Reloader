package dev.modreloader.client;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RelaunchScriptsTest {
    @Test
    void posixQuotesSpacesMetacharactersAndQuotes() {
        assertEquals("'plain'", RelaunchScripts.quotePosix("plain"));
        assertEquals("'a b'", RelaunchScripts.quotePosix("a b"));
        assertEquals("'$HOME'", RelaunchScripts.quotePosix("$HOME"));
        assertEquals("'`x`'", RelaunchScripts.quotePosix("`x`"));
        // close quote, escape one quote, reopen
        assertEquals("'a'\\''b'", RelaunchScripts.quotePosix("a'b"));
    }

    @Test
    void splitsAWindowsCommandLine() {
        String line = "\"C:\\Program Files\\Java\\bin\\java.exe\" -Xmx2G "
                + "\"-Dfoo=a b\" net.minecraft.client.main.Main --x";
        assertEquals(List.of("C:\\Program Files\\Java\\bin\\java.exe", "-Xmx2G",
                        "-Dfoo=a b", "net.minecraft.client.main.Main", "--x"),
                RelaunchScripts.splitWindowsCommandLine(line));
        assertEquals(List.of("java", "-cp", "a;b", "Main"),
                RelaunchScripts.splitWindowsCommandLine("java -cp \"a;b\" Main"));
        assertEquals(List.of("a\"b"),
                RelaunchScripts.splitWindowsCommandLine("\"a\\\"b\""));
    }

    @Test
    void jvmFlagsLandBeforeTheMainClass() {
        // Vanilla-shaped command line: the JVM stops reading options at the main
        // class, so a flag appended at the end would be a program argument.
        List<String> args = List.of("-Xmx2G", "-cp", "libs/*",
                "net.minecraft.client.main.Main", "--username", "Dev");
        assertEquals(List.of("-Xmx2G", "-cp", "libs/*", RelaunchFlags.LATE_AT,
                        "net.minecraft.client.main.Main", "--username", "Dev"),
                RelaunchScripts.withJvmFlags(args, List.of(RelaunchFlags.LATE_AT)));
    }

    @Test
    void jvmFlagsSkipOptionValuesAndModuleArguments() {
        // NeoForge userdev shape: -p and --add-modules take a separate value.
        List<String> args = List.of("-p", "mods", "--add-modules", "ALL-MODULE-PATH",
                "-DlegacyClassPath=libs/*", "cpw.mods.bootstraplauncher.BootstrapLauncher",
                "--launchTarget", "forgeclient");
        List<String> out = RelaunchScripts.withJvmFlags(args, List.of("-XX:+AllowEnhancedClassRedefinition"));
        assertEquals(List.of("-p", "mods", "--add-modules", "ALL-MODULE-PATH",
                        "-DlegacyClassPath=libs/*", "-XX:+AllowEnhancedClassRedefinition",
                        "cpw.mods.bootstraplauncher.BootstrapLauncher",
                        "--launchTarget", "forgeclient"),
                out);
    }

    @Test
    void jvmFlagsStayAfterAddOpensValue() {
        List<String> args = List.of("--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "-Xmx2G", "net.minecraft.client.main.Main", "--username", "Dev");
        assertEquals(List.of("--add-opens", "java.base/java.lang=ALL-UNNAMED",
                        "-Xmx2G", "-Dmodreloader.lateAt=true",
                        "net.minecraft.client.main.Main", "--username", "Dev"),
                RelaunchScripts.withJvmFlags(args, List.of("-Dmodreloader.lateAt=true")));
        // Equals form is a single token and must not consume the next one.
        List<String> equalsForm = List.of("--add-opens=java.base/java.lang=ALL-UNNAMED", "Main", "--x");
        assertEquals(List.of("--add-opens=java.base/java.lang=ALL-UNNAMED", "-Dfoo=bar", "Main", "--x"),
                RelaunchScripts.withJvmFlags(equalsForm, List.of("-Dfoo=bar")));
    }

    @Test
    void jvmFlagsGoBeforeJarAndModuleLaunches() {
        assertEquals(List.of("-Xmx1G", "-Dmodreloader.lateAt=true", "-jar", "game.jar", "--x"),
                RelaunchScripts.withJvmFlags(List.of("-Xmx1G", "-jar", "game.jar", "--x"),
                        List.of("-Dmodreloader.lateAt=true")));
        assertEquals(List.of("-Dx=y", "--module", "app/mod.Main"),
                RelaunchScripts.withJvmFlags(List.of("--module", "app/mod.Main"), List.of("-Dx=y")));
    }

    @Test
    void noFlagsLeavesTheCommandLineAlone() {
        List<String> args = List.of("-cp", "a", "Main");
        assertEquals(args, RelaunchScripts.withJvmFlags(args, List.of()));
    }

    @Test
    void posixScriptWaitsBeforeStarting() {
        String script = RelaunchScripts.posix(Path.of("/jbr/bin/java"),
                List.of("-cp", "/a b/c.jar", "Main"), Path.of("/game dir"), 3);
        assertTrue(script.startsWith("#!/bin/sh\n"));
        assertTrue(script.contains("sleep 3\n"));
        assertTrue(script.contains("cd '/game dir' || exit 1"));
        assertTrue(script.contains("exec '/jbr/bin/java' '-cp' '/a b/c.jar' 'Main'"));
    }

    @Test
    void windowsScriptQuotesAndDoublesPercent() {
        String script = RelaunchScripts.windows(Path.of("C:/jbr/bin/java.exe"),
                List.of("-Dfoo=%BAR%", "C:/a b/game.jar"), Path.of("C:/game"), 3);
        assertTrue(script.startsWith("@echo off\r\n"));
        assertTrue(script.contains("timeout /t 3 /nobreak >nul"));
        assertTrue(script.contains("cd /d \"C:/game\""));
        assertTrue(script.contains("\"C:/jbr/bin/java.exe\""));
        assertTrue(script.contains("\"-Dfoo=%%BAR%%\""));
        assertTrue(script.contains("\"C:/a b/game.jar\""));
    }

    @Test
    void windowsRejectsArgumentsItCannotRepresent() {
        assertThrows(IllegalArgumentException.class, () -> RelaunchScripts.quoteWindows("a\"b"));
        assertThrows(IllegalArgumentException.class, () -> RelaunchScripts.quoteWindows("a\nb"));
        assertFalse(RelaunchScripts.windowsCanRepresent(List.of("a\"b")));
        assertTrue(RelaunchScripts.windowsCanRepresent(List.of("a b", "C:/x")));
    }
}
