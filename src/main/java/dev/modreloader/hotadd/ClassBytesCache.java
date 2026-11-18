package dev.modreloader.hotadd;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Last bytes this runtime installed for a class, keyed by dotted name.
 *
 * <p>Late AT and mixin redefines are sequential: without this cache the mixin
 * stage reads the original class resource and silently overwrites the late AT
 * result (measured with JourneyMap: `public-f Screen init` was applied, then the
 * mixin redefine restored `final`, and {@code JmUILegacy} failed to load).
 * Rollbacks write the restored bytes back here too.
 */
public final class ClassBytesCache {
    private static final Map<String, byte[]> BY_CLASS = new ConcurrentHashMap<>();

    private ClassBytesCache() {
    }

    public static byte[] get(String dotted) {
        return BY_CLASS.get(dotted);
    }

    public static void put(String dotted, byte[] bytes) {
        if (dotted != null && bytes != null) {
            BY_CLASS.put(dotted, bytes);
        }
    }
}
