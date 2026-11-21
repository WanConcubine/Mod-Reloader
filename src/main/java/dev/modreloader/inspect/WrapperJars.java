package dev.modreloader.inspect;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Distribution wrappers: jars with no mods.toml of their own whose only payload
 * is a jar-in-jar that <em>is</em> a mod (MezzConfig's "standalone" jar).
 *
 * <p>At startup FML unwraps it for free: the manifest says
 * {@code FMLModType: GAMELIBRARY} (classes only, none here) and the jarjar
 * locator turns the nested file into a mod. A hot-add only sees the outer file,
 * so without this the wrapper is refused as "library jar, not a mod".
 *
 * <p>Read-only: nothing is extracted and no mod class is loaded. Results are
 * memoized by path/size/mtime because the watcher re-inspects every poll.
 */
public final class WrapperJars {
    static final String METADATA = "META-INF/jarjar/metadata.json";
    private static final String NEOFORGE_TOML = "META-INF/neoforge.mods.toml";
    private static final String LEGACY_TOML = "META-INF/mods.toml";
    private static final Pattern PATH = Pattern.compile("\"path\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MOD_ID = Pattern.compile("(?m)^\\s*modId\\s*=\\s*\"([^\"]+)\"");
    private static final Map<String, Optional<Nested>> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> IDS = new ConcurrentHashMap<>();

    private WrapperJars() {
    }

    /** @param nestedPath the path inside the wrapper, as written in metadata.json */
    public record Nested(String nestedPath, byte[] modsToml) {
    }

    /**
     * @return the first nested jar that declares {@code [[mods]]}, or {@code null}
     *         when {@code jar} is not a wrapper (it has mods of its own or no jarjar)
     */
    public static Nested nestedMod(Path jar) {
        String key = cacheKey(jar);
        if (key == null) {
            return null;
        }
        Optional<Nested> cached = CACHE.computeIfAbsent(key, ignored -> Optional.ofNullable(read(jar)));
        return cached.orElse(null);
    }

    private static String cacheKey(Path jar) {
        try {
            return jar.toAbsolutePath().normalize() + "|" + Files.size(jar) + "|"
                    + Files.getLastModifiedTime(jar).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    public static boolean isWrapper(Path jar) {
        return nestedMod(jar) != null;
    }

    /** {@code modId}s of the nested jars that declare mods (for dependency checks). */
    public static List<String> nestedModIds(Path jar) {
        String key = cacheKey(jar);
        if (key == null) {
            return List.of();
        }
        return IDS.computeIfAbsent(key, ignored -> readIds(jar));
    }

    private static List<String> readIds(Path jar) {
        List<String> ids = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            byte[] metadata = readEntry(zip, METADATA);
            if (metadata == null) {
                return ids;
            }
            for (String path : nestedPaths(metadata)) {
                byte[] toml = nestedModsToml(readEntry(zip, path));
                if (toml == null) {
                    continue;
                }
                Matcher matcher = MOD_ID.matcher(new String(toml, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    if (!ids.contains(matcher.group(1))) {
                        ids.add(matcher.group(1));
                    }
                }
            }
        } catch (IOException ignored) {
            // Unreadable zip: the add path reports it.
        }
        return ids;
    }

    private static Nested read(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            if (hasMods(readEntry(zip, NEOFORGE_TOML)) || hasMods(readEntry(zip, LEGACY_TOML))) {
                return null;
            }
            byte[] metadata = readEntry(zip, METADATA);
            if (metadata == null) {
                return null;
            }
            for (String path : nestedPaths(metadata)) {
                byte[] toml = nestedModsToml(readEntry(zip, path));
                if (toml != null) {
                    return new Nested(path, toml);
                }
            }
        } catch (IOException ignored) {
            // Let the normal "not a mod" path report unreadable jars.
        }
        return null;
    }

    private static List<String> nestedPaths(byte[] metadata) {
        List<String> paths = new ArrayList<>();
        Matcher matcher = PATH.matcher(new String(metadata, StandardCharsets.UTF_8));
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        return paths;
    }

    private static boolean hasMods(byte[] toml) {
        return toml != null && new String(toml, StandardCharsets.UTF_8).contains("[[mods]]");
    }

    /** @return the nested jar's mods.toml bytes when it declares {@code [[mods]]} */
    static byte[] nestedModsToml(byte[] nestedJar) {
        if (nestedJar == null) {
            return null;
        }
        byte[] toml = readEntryOrNull(nestedJar, NEOFORGE_TOML);
        if (toml == null) {
            toml = readEntryOrNull(nestedJar, LEGACY_TOML);
        }
        return hasMods(toml) ? toml : null;
    }

    private static byte[] readEntry(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        return entry == null ? null : zip.getInputStream(entry).readAllBytes();
    }

    private static byte[] readEntryOrNull(byte[] jar, String name) {
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry()) {
                if (entry.getName().equals(name)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    in.transferTo(out);
                    return out.toByteArray();
                }
            }
        } catch (IOException ignored) {
            // Not a readable zip.
        }
        return null;
    }
}