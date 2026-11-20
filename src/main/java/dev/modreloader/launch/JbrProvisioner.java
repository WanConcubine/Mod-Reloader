package dev.modreloader.launch;

import dev.modreloader.ModReloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Downloads and unpacks JBR 21 for the current platform.
 *
 * <p>This is the "player does nothing" path: one call prepares a runtime that
 * {@link dev.modreloader.inspect.JbrSupport} will then find next to the game
 * directory. Assets and SHA-512 values are pinned to the official JetBrains
 * release (jbr-release-21.0.11b1163.125, served by cache-redirector.jetbrains.com),
 * so a tampered download is rejected before extraction.
 */
public final class JbrProvisioner {
    private static final String VERSION = "21.0.11";
    private static final String BUILD = "b1163.125";
    private static final String BASE = "https://cache-redirector.jetbrains.com/intellij-jbr/";

    public record Asset(String name, String sha512, boolean zip) {
    }

    /** Progress messages are coarse on purpose; the screen only shows the phase. */
    public interface Progress {
        void update(String message);
    }

    private JbrProvisioner() {
    }

    /** @return the asset for this OS/arch, or empty when there is no pinned build */
    public static Optional<Asset> asset() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        boolean arm = arch.contains("aarch64") || arch.contains("arm64");
        if (os.contains("win")) {
            return arm ? Optional.of(windowsArm64()) : Optional.of(windowsX64());
        }
        if (os.contains("mac")) {
            return arm ? Optional.of(macArm64()) : Optional.empty();
        }
        if (os.contains("linux")) {
            return arm ? Optional.of(linuxArm64()) : Optional.of(linuxX64());
        }
        return Optional.empty();
    }

    /** All pinned assets; exposed for the checksum sanity test. */
    static java.util.List<Asset> assets() {
        return java.util.List.of(windowsX64(), windowsArm64(), macArm64(), linuxX64(), linuxArm64());
    }

    public static String downloadUrl(Asset asset) {
        return BASE + asset.name();
    }

    /** Directory under the game dir; JbrSupport scans direct children for release+bin. */
    public static Path installDir(Path gameDir) {
        return gameDir.resolve("jbr-" + VERSION);
    }

    /**
     * Downloads, verifies and unpacks JBR 21 into {@link #installDir(Path)}.
     *
     * @return the JBR home (contains release and bin/java)
     */
    public static Path install(Path gameDir, Progress progress) throws Exception {
        Asset asset = asset().orElseThrow(() ->
                new IllegalStateException("no pinned JBR build for this platform; "
                        + "put a JBR 21 next to the game directory instead"));
        Path install = installDir(gameDir);
        if (isRuntimeHome(install)) {
            progress.update("已存在");
            return install;
        }
        Path work = gameDir.resolve("modreloader").resolve("jbr-download");
        Files.createDirectories(work);
        Path archive = work.resolve(asset.name());
        progress.update("正在下载…");
        download(asset, archive, progress);
        progress.update("校验中…");
        verify(archive, asset);
        try {
            return installArchive(gameDir, archive, asset, progress);
        } finally {
            // The archive and the unpack scratch are large; the installed runtime
            // is the only thing that needs to stay.
            try {
                Files.deleteIfExists(archive);
                deleteTree(work);
            } catch (IOException ignored) {
                // Leaving a cache copy behind is harmless.
            }
        }
    }

    /** Verifies the pinned SHA-512 before anything is extracted. */
    static void verify(Path archive, Asset asset) throws Exception {
        String actual = sha512(archive);
        if (!actual.equalsIgnoreCase(asset.sha512())) {
            throw new IOException("JBR 校验失败（下载可能被篡改）：" + actual);
        }
    }

    /** Extracts an already-downloaded (and verified) archive into the game dir. */
    static Path installArchive(Path gameDir, Path archive, Asset asset, Progress progress)
            throws Exception {
        Path install = installDir(gameDir);
        Path work = gameDir.resolve("modreloader").resolve("jbr-download");
        Path unpacked = work.resolve("unpacked");
        deleteTree(unpacked);
        Files.createDirectories(unpacked);
        progress.update("解压中…");
        if (asset.zip()) {
            unzip(archive, unpacked);
        } else {
            untar(archive, unpacked);
        }
        Path home = findHome(unpacked).orElseThrow(() ->
                new IOException("解压后找不到 JBR home（release + bin）"));
        deleteTree(install);
        Files.move(home, install, StandardCopyOption.REPLACE_EXISTING);
        deleteTree(unpacked);
        makeBinExecutable(install);
        if (!isRuntimeHome(install)) {
            throw new IOException("JBR 安装不完整：" + install);
        }
        progress.update("已就绪");
        return install;
    }

    /** Layout only: release + a java binary, ignoring the execute bit (zip extraction loses it). */
    static boolean isRuntimeLayout(Path home) {
        if (!Files.isRegularFile(home.resolve("release"))) {
            return false;
        }
        Path bin = home.resolve("bin");
        return Files.isRegularFile(bin.resolve("java"))
                || Files.isRegularFile(bin.resolve("java.exe"));
    }

    static boolean isRuntimeHome(Path home) {
        if (!isRuntimeLayout(home)) {
            return false;
        }
        Path bin = home.resolve("bin");
        Path chosen = Files.isRegularFile(bin.resolve("java"))
                ? bin.resolve("java") : bin.resolve("java.exe");
        // Windows has no POSIX execute bit; a present java.exe is the contract.
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return true;
        }
        return Files.isExecutable(chosen);
    }

    private static void download(Asset asset, Path target, Progress progress) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(java.time.Duration.ofSeconds(20))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl(asset)))
                .header("User-Agent", "ModReloader")
                .timeout(java.time.Duration.ofMinutes(10))
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " 下载 " + asset.name());
        }
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        Files.deleteIfExists(temp);
        long total = response.headers().firstValueAsLong("content-length").orElse(-1);
        long read = 0;
        try (InputStream in = response.body();
             var out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            long lastReport = 0;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                read += n;
                if (read - lastReport > 8L * 1024 * 1024) {
                    lastReport = read;
                    progress.update("正在下载… " + (total > 0
                            ? String.format(Locale.ROOT, "%.0f%%", read * 100.0 / total)
                            : String.format(Locale.ROOT, "%.0f MB", read / 1048576.0)));
                }
            }
        } catch (Throwable t) {
            Files.deleteIfExists(temp);
            throw t;
        }
        if (read == 0) {
            Files.deleteIfExists(temp);
            throw new IOException("JBR 下载为空：" + asset.name());
        }
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        ModReloader.LOGGER.info("[ModReloader] JBR downloaded: {} ({} MB)", asset.name(),
                Files.size(target) / 1048576.0);
    }

    private static String sha512(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) > 0) {
                digest.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void unzip(Path archive, Path target) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path out = safeResolve(target, entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(zip, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void untar(Path archive, Path target) throws Exception {
        List<String> command = List.of("tar", "-xzf", archive.toString(), "-C", target.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IOException("tar 解压失败：" + output);
        }
    }

    private static Path safeResolve(Path base, String name) throws IOException {
        Path resolved = base.resolve(name).normalize();
        if (!resolved.startsWith(base)) {
            throw new IOException("压缩包包含非法路径：" + name);
        }
        return resolved;
    }

    /** Zip extraction does not carry the POSIX execute bit; restore it for JBR's bin. */
    private static void makeBinExecutable(Path home) {
        Path bin = home.resolve("bin");
        if (!Files.isDirectory(bin)) {
            return;
        }
        try (var files = Files.list(bin)) {
            for (Path file : files.toList()) {
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    Files.setPosixFilePermissions(file,
                            java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                } catch (UnsupportedOperationException ignored) {
                    // Windows: no POSIX bits.
                } catch (IOException ignored) {
                    // Leave the extracted bits as they are; launch will report it.
                }
            }
        } catch (IOException ignored) {
            // Same: the final isRuntimeHome check decides.
        }
    }

    private static Optional<Path> findHome(Path root) throws IOException {
        if (isRuntimeHome(root)) {
            return Optional.of(root);
        }
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                if (Files.isDirectory(entry) && isRuntimeLayout(entry)) {
                    return Optional.of(entry);
                }
            }
        }
        try (var entries = Files.walk(root, 3)) {
            return entries.filter(Files::isDirectory)
                    .filter(JbrProvisioner::isRuntimeLayout)
                    .findFirst();
        }
    }

    private static void deleteTree(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best effort; a leftover file only wastes space.
                }
            });
        } catch (IOException ignored) {
            // Same: the install step will fail explicitly if it matters.
        }
    }

    private static Asset windowsX64() {
        return new Asset("jbr-21.0.11-windows-x64-b1163.125.zip",
                "845fc37fa1af3435b5fa6c3c84a24ffa0d5bcec2938b08973a2c238c1de2a8d6"
                        + "3a58e2806f883414f2664503f20949e39e62a0805e79acb6402aa0f619442f65", true);
    }

    private static Asset windowsArm64() {
        return new Asset("jbr-21.0.11-windows-aarch64-b1163.125.zip",
                "19be07df7f3b409bc7c91677d199e37ee7c78e4e2c5929eae18275ee20ade276"
                        + "63719fec2b81db706fd6861535c2c3cabdd7fcfa9a7a9893de4ccd0815291faa", true);
    }

    private static Asset macArm64() {
        return new Asset("jbr-21.0.11-osx-aarch64-b1163.125.tar.gz",
                "ecc474537e8e0bc652b1392a4b6fb343f92c91895e5ff8795b3e292e8345d81f"
                        + "96b75be301e8d875e7bc25ef7ada57c9b6d6765771604c364dd725c04d57595b", false);
    }

    private static Asset linuxX64() {
        return new Asset("jbr-21.0.11-linux-x64-b1163.125.tar.gz",
                "9080e035778d65439be5f8256640201ce8edb83d605f3d8f9c127c7928c2604a"
                        + "8989921f40127b21e0bb5098f946524f077c9760ed09bca34484a8f3855da1ae", false);
    }

    private static Asset linuxArm64() {
        return new Asset("jbr-21.0.11-linux-aarch64-b1163.125.tar.gz",
                "62a326113448517e018929a5ccce752088e9cba270594e11a01f8177a5e2ae13"
                        + "946de75efeda5e1b47a3919cefebec5e5a323e40a5f8085b70e67b2f70af8b31", false);
    }
}
