package dev.modreloader;

import dev.modreloader.diagnose.FailureKeys;
import dev.modreloader.hotadd.HotAddEngine;
import dev.modreloader.hotadd.RunState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the two things that used to be checked by hand (or not at all):
 * both language files carry the same keys, and every {@link HotAddEngine.FailureKind}
 * has a summary and a suggestion in both languages.
 */
class LangKeysTest {
    private static final Pattern ENTRY = Pattern.compile(
            "^\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"", Pattern.MULTILINE);

    @Test
    void languageFilesHaveTheSameKeysAndPlaceholders() throws IOException {
        Map<String, String> en = load("en_us");
        Map<String, String> zh = load("zh_cn");

        assertFalse(en.isEmpty(), "en_us.json is empty");
        assertEquals(en.keySet(), zh.keySet(), "en_us and zh_cn disagree on the key set");

        for (Map.Entry<String, String> entry : en.entrySet()) {
            String key = entry.getKey();
            assertFalse(entry.getValue().isBlank(), "empty English value for " + key);
            assertFalse(zh.get(key).isBlank(), "empty Chinese value for " + key);
            assertEquals(placeholders(entry.getValue()), placeholders(zh.get(key)),
                    "placeholder mismatch for " + key);
        }
    }

    @Test
    void playerCopyDoesNotExposeJvmFlags() throws IOException {
        // ADR-0015: players see "restart once on JBR", never the flags themselves.
        for (String language : new String[]{"en_us", "zh_cn"}) {
            for (Map.Entry<String, String> entry : load(language).entrySet()) {
                for (String token : new String[]{"-XX", "-Dmodreloader", "lateAt"}) {
                    assertFalse(entry.getValue().contains(token),
                            language + " " + entry.getKey() + " exposes JVM flag " + token);
                }
            }
        }
    }

    @Test
    void everyFailureKindHasBothMessages() throws IOException {
        Map<String, String> en = load("en_us");
        Map<String, String> zh = load("zh_cn");

        Set<String> checked = new LinkedHashSet<>();
        for (HotAddEngine.FailureKind kind : HotAddEngine.FailureKind.values()) {
            String stem = FailureKeys.of(kind);
            checked.add(stem);
            for (String suffix : new String[]{".summary", ".suggestion"}) {
                String key = "modreloader.failure." + stem + suffix;
                assertTrue(en.containsKey(key), "missing en_us key " + key + " for " + kind);
                assertTrue(zh.containsKey(key), "missing zh_cn key " + key + " for " + kind);
            }
        }

        // NONE and UNKNOWN intentionally share the "unknown" stem.
        assertTrue(checked.contains("unknown"));
        assertTrue(checked.contains("rollback_partial"));
    }

    @Test
    void dirtyReasonsAndBadgesAreTranslated() throws IOException {
        Map<String, String> en = load("en_us");
        Map<String, String> zh = load("zh_cn");
        for (RunState.Reason reason : RunState.Reason.values()) {
            assertTrue(en.containsKey(reason.key()), "missing en_us key " + reason.key());
            assertTrue(zh.containsKey(reason.key()), "missing zh_cn key " + reason.key());
        }
        for (String key : new String[]{
                "modreloader.gui.dirty", "modreloader.detail.dirty_reason",
                "modreloader.failure.runtime_dirty.summary",
                "modreloader.failure.runtime_dirty.suggestion"}) {
            assertTrue(en.containsKey(key), "missing en_us key " + key);
            assertTrue(zh.containsKey(key), "missing zh_cn key " + key);
        }
    }

    @Test
    void everyFailureKeyStemIsTranslated() throws IOException {
        Map<String, String> en = load("en_us");
        long stems = en.keySet().stream()
                .filter(key -> key.startsWith("modreloader.failure.")
                        && key.endsWith(".summary"))
                .count();
        assertEquals(stems, en.keySet().stream()
                .filter(key -> key.startsWith("modreloader.failure.")
                        && key.endsWith(".suggestion"))
                .count(), "a failure stem has a summary but no suggestion");
    }

    private static Map<String, String> load(String language) throws IOException {
        String path = "/assets/modreloader/lang/" + language + ".json";
        try (InputStream in = LangKeysTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, String> entries = new LinkedHashMap<>();
            Matcher matcher = ENTRY.matcher(text);
            while (matcher.find()) {
                entries.put(matcher.group(1), matcher.group(2));
            }
            return entries;
        }
    }

    /** Sorted {@code %s}/{@code %d} signature, so a dropped argument fails the test. */
    private static String placeholders(String value) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("%[sd]").matcher(value);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return String.join(",", found);
    }
}
