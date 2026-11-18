package dev.modreloader.hotadd;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.jarjar.metadata.ContainedJarMetadata;
import net.neoforged.jarjar.metadata.Metadata;
import net.neoforged.jarjar.metadata.MetadataIOHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The jars a NeoForge mod bundles in {@code META-INF/jarjar}.
 *
 * <p>At startup FML's {@code JarInJarDependencyLocator} reads
 * {@code META-INF/jarjar/metadata.json}, turns every nested jar into its own mod
 * file and puts it on the game module path — that is how a mod's bundled
 * libraries (e.g. {@code PinIn} for {@code jecharacters}) become resolvable.
 * A hot-added mod never went through that locator, so its nested jars do not
 * exist as far as the game layer is concerned and the first class that touches
 * one fails with {@link NoClassDefFoundError}.
 *
 * <p>This class re-does the locator's first half for one jar: read the metadata,
 * extract each nested jar to a stable file, and hand back a {@link SecureJar}
 * per entry so {@link ModuleInjector} can expose it as a module.
 *
 * <p>Boundaries:
 * <ul>
 *   <li>Only the class path is restored. A nested jar that declares its own
 *       {@code mods.toml} gets its classes but no {@code ModContainer}, so its
 *       {@code @Mod} constructor never runs ({@code declaresMod()}).</li>
 *   <li>An obfuscated nested jar needs the deobfuscation step FML does at build
 *       time; it is skipped instead of loading the wrong bytes.</li>
 *   <li>Extraction is content-addressed under {@code <gamedir>/modreloader/jarjar/},
 *       so a retry after a failed add reuses the same path (the JVM cannot
 *       undefine the module, and {@link ModuleInjector} keys retries by path).</li>
 * </ul>
 */
public final class JarInJar {
    /** Where NeoForge looks for the jar-in-jar manifest. */
    public static final String METADATA_PATH = "META-INF/jarjar/metadata.json";
    private static final String[] MODS_TOML = {"META-INF/neoforge.mods.toml", "META-INF/mods.toml"};
    /** Refuse to copy a nested jar bigger than this; nothing real comes close. */
    private static final long MAX_NESTED_BYTES = 256L * 1024 * 1024;
    private static final int MAX_NESTED_JARS = 64;
    /** How many JiJ levels are unpacked (Supplementaries needs two). */
    private static final int MAX_NESTED_DEPTH = 3;

    /**
     * @param declaredPath the path inside the outer jar, as written in metadata.json
     * @param file         the extracted file on disk
     * @param jar          the extracted jar as a {@link SecureJar}
     * @param moduleName   the automatic (or declared) module name of {@code jar}
     * @param declaresMod  the nested jar is a real mod (mods.toml and not
     *                     {@code FMLModType: GAMELIBRARY}): classes only, no container
     * @param manifestMixins mixin configs named by {@code MixinConfigs} in its manifest
     */
    public record Nested(String declaredPath, Path file, SecureJar jar, String moduleName,
                         boolean declaresMod, List<String> manifestMixins) {
    }

    /**
     * @param nested  jars that were extracted and can be injected as modules
     * @param skipped one human-readable reason per declared entry that was not
     */
    public record Result(List<Nested> nested, List<String> skipped) {
        public static final Result EMPTY = new Result(List.of(), List.of());

        public List<SecureJar> secureJars() {
            return nested.stream().map(Nested::jar).toList();
        }

        /** Nested jars that are not real mods: they only need to be on the module path. */
        public List<SecureJar> libraryJars() {
            return nested.stream().filter(n -> !n.declaresMod()).map(Nested::jar).toList();
        }

        /**
         * Mixin configs declared in the manifest of nested libraries. A game
         * library has no {@code ModFile}, so its configs would otherwise never be
         * registered with Mixin.
         */
        public List<String> libraryMixinConfigs() {
            return nested.stream().filter(n -> !n.declaresMod())
                    .flatMap(n -> n.manifestMixins().stream()).toList();
        }
    }

    private JarInJar() {
    }

    /** Reads and extracts the nested jars of {@code outerJar}; empty when it has none. */
    public static Result extract(Path outerJar) throws IOException {
        List<Nested> nested = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        collect(outerJar, "", 0, nested, skipped, new int[]{0});
        return new Result(List.copyOf(nested), List.copyOf(skipped));
    }

    /**
     * Extracts one level and recurses into nested jars that bundle more jars.
     * Supplementaries → mixinsquared-forge → mixinsquared-common is two levels;
     * stopping after the first left {@code MixinSquaredBootstrap} missing
     * (NoClassDefFoundError when Mixin loads its config).
     */
    private static void collect(Path jarFile, String prefix, int depth, List<Nested> nested,
                                List<String> skipped, int[] seen) throws IOException {
        if (depth > MAX_NESTED_DEPTH) {
            skipped.add(prefix + " (nesting deeper than " + MAX_NESTED_DEPTH + " levels)");
            return;
        }
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            ZipEntry metadataEntry = zip.getEntry(METADATA_PATH);
            if (metadataEntry == null) {
                return;
            }
            Optional<Metadata> metadata;
            try (InputStream in = zip.getInputStream(metadataEntry)) {
                metadata = MetadataIOHandler.fromStream(in);
            }
            if (metadata.isEmpty()) {
                skipped.add(prefix + METADATA_PATH + " could not be parsed");
                return;
            }

            for (ContainedJarMetadata contained : metadata.get().jars()) {
                if (++seen[0] > MAX_NESTED_JARS) {
                    skipped.add("more than " + MAX_NESTED_JARS + " nested jars declared");
                    return;
                }
                String path = checkedPath(contained.path());
                if (path == null) {
                    skipped.add(prefix + contained.path()
                            + " (not a relative .jar path inside the jar)");
                    continue;
                }
                String declaredPath = prefix.isEmpty() ? path : prefix + "!/" + path;
                if (contained.isObfuscated()) {
                    // FML deobfuscates those at build time; loading the raw bytes
                    // would give us classes with the wrong names.
                    skipped.add(declaredPath + " (isObfuscated: needs deobfuscation, restart required)");
                    continue;
                }
                ZipEntry entry = zip.getEntry(path);
                if (entry == null) {
                    skipped.add(declaredPath + " (declared but not present in the jar)");
                    continue;
                }
                if (entry.getSize() > MAX_NESTED_BYTES) {
                    skipped.add(declaredPath + " (" + entry.getSize() + " bytes, over the size limit)");
                    continue;
                }
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                if (bytes.length > MAX_NESTED_BYTES) {
                    // getSize() is -1 for streamed zips, so re-check what we read.
                    skipped.add(declaredPath + " (" + bytes.length + " bytes, over the size limit)");
                    continue;
                }
                Path file = materialize(bytes, fileNameOf(path));
                SecureJar secureJar = SecureJar.from(JarContents.of(file));
                nested.add(new Nested(declaredPath, file, secureJar, secureJar.name(),
                        declaresMod(file), manifestMixinConfigs(file)));
                collect(file, declaredPath, depth + 1, nested, skipped, seen);
            }
        }
    }

    /**
     * Extracted path for one nested jar, or {@code null} when the declared path
     * would escape the outer jar or is not a jar at all.
     */
    static String checkedPath(String declared) {
        if (declared == null) {
            return null;
        }
        String path = declared.replace('\\', '/').trim();
        if (path.isEmpty() || path.startsWith("/") || path.contains(":")) {
            return null;
        }
        for (String segment : path.split("/")) {
            if (segment.equals("..")) {
                return null;
            }
        }
        return path.endsWith(".jar") ? path : null;
    }

    /** Last path segment, reduced to characters that are safe in a file name. */
    static String fileNameOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String safe = name.replaceAll("[^A-Za-z0-9._+-]", "_");
        return safe.endsWith(".jar") ? safe : safe + ".jar";
    }

    /**
     * Writes the nested jar to {@code <gamedir>/modreloader/jarjar/<hash>/<name>.jar}.
     * The directory is the content hash, so the path is stable across retries while
     * two different copies of the same library stay apart (their module names would
     * collide, which {@link ModuleInjector} reports instead of mixing them).
     */
    private static Path materialize(byte[] bytes, String fileName) throws IOException {
        Path dir = FMLPaths.GAMEDIR.get().resolve("modreloader").resolve("jarjar")
                .resolve(sha256(bytes).substring(0, 16));
        Path file = dir.resolve(fileName);
        if (Files.isRegularFile(file) && Files.size(file) == bytes.length) {
            return file;
        }
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "extract-", ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    /**
     * A nested jar is a mod only when it ships a mods.toml and is not a
     * {@code GAMELIBRARY}: those have a {@code DefaultModFileInfo} instead of a
     * {@code ModFileInfo} and no ModContainer, so treating them as mods fails at
     * the nested-mod registration step (measured with Supplementaries' bundled
     * MixinSquared).
     */
    static boolean declaresMod(Path jarFile) {
        if (dev.modreloader.inspect.ModInspector.isGameLibrary(jarFile)) {
            return false;
        }
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            for (String toml : MODS_TOML) {
                if (zip.getEntry(toml) != null) {
                    return true;
                }
            }
            return false;
        } catch (IOException broken) {
            return false;
        }
    }

    /** {@code MixinConfigs} from the manifest, split on commas; empty when absent. */
    static List<String> manifestMixinConfigs(Path jarFile) {
        try (ZipFile zip = new ZipFile(jarFile.toFile())) {
            ZipEntry manifest = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifest == null) {
                return List.of();
            }
            try (InputStream in = zip.getInputStream(manifest)) {
                String raw = new java.util.jar.Manifest(in).getMainAttributes().getValue("MixinConfigs");
                if (raw == null || raw.isBlank()) {
                    return List.of();
                }
                List<String> configs = new ArrayList<>();
                for (String part : raw.split(",")) {
                    String name = part.trim();
                    if (!name.isEmpty()) {
                        configs.add(name);
                    }
                }
                return List.copyOf(configs);
            }
        } catch (IOException broken) {
            return List.of();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM", e);
        }
    }
}
