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
