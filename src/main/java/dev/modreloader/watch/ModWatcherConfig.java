package dev.modreloader.watch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.modreloader.ModReloader;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Independent JSON config for the mods-folder watcher.
 *
 * <p>Deliberately not a TOML config: ignored/failed fingerprints are runtime
 * bookkeeping, not user-facing settings.
 */
public final class ModWatcherConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FMLPaths.CONFIGDIR.get().resolve("modreloader/watcher.json");

    public int version = 1;
    public boolean watchModsFolder = true;
    public boolean watchInbox = true;
    public int pollSeconds = 2;
    public int stableScans = 2;
    public boolean openPromptInMainMenu = true;
    public boolean toastOnDetect = true;
    public List<IgnoredEntry> ignored = new ArrayList<>();
    public List<FailedEntry> failed = new ArrayList<>();

    public static final class IgnoredEntry {
        public String path = "";
        public long size;
        public long mtime;

        public IgnoredEntry() {
        }

        IgnoredEntry(Path path, long size, long mtime) {
            this.path = path.toAbsolutePath().normalize().toString();
            this.size = size;
            this.mtime = mtime;
        }
    }

    public static final class FailedEntry {
        public String path = "";
        public long size;
        public long mtime;
        public String kind = "";
        public String message = "";

        public FailedEntry() {
        }

        FailedEntry(Path path, long size, long mtime, String kind, String message) {
            this.path = path.toAbsolutePath().normalize().toString();
            this.size = size;
            this.mtime = mtime;
            this.kind = kind == null ? "" : kind;
            this.message = message == null ? "" : message;
        }
    }

    public static ModWatcherConfig load() {
        try {
            if (Files.isRegularFile(FILE)) {
                ModWatcherConfig config = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8),
                        ModWatcherConfig.class);
                if (config != null) {
                    config.normalise();
                    return config;
                }
            }
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot read watcher config {}", FILE, t);
        }
        ModWatcherConfig config = new ModWatcherConfig();
        config.save();
        return config;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ModReloader.LOGGER.warn("[ModReloader] cannot save watcher config {}", FILE, e);
        }
    }

    private void normalise() {
        pollSeconds = Math.max(1, Math.min(60, pollSeconds));
        stableScans = Math.max(1, Math.min(10, stableScans));
        ignored = ignored == null ? new ArrayList<>() : ignored;
        failed = failed == null ? new ArrayList<>() : failed;
    }

    public synchronized boolean isIgnored(Path path, long size, long mtime) {
        String key = path.toAbsolutePath().normalize().toString();
        for (IgnoredEntry entry : ignored) {
            if (key.equals(entry.path) && size == entry.size && mtime == entry.mtime) {
                return true;
            }
        }
        return false;
    }

    public synchronized void ignore(Path path, long size, long mtime) {
        String key = path.toAbsolutePath().normalize().toString();
        ignored.removeIf(entry -> key.equals(entry.path));
        ignored.add(new IgnoredEntry(path, size, mtime));
        save();
    }

    public synchronized boolean isFailed(Path path, long size, long mtime) {
        String key = path.toAbsolutePath().normalize().toString();
        for (FailedEntry entry : failed) {
            if (key.equals(entry.path) && size == entry.size && mtime == entry.mtime) {
                return true;
            }
        }
        return false;
    }

    public synchronized void markFailed(Path path, long size, long mtime, String kind, String message) {
        String key = path.toAbsolutePath().normalize().toString();
        failed.removeIf(entry -> key.equals(entry.path));
        failed.add(new FailedEntry(path, size, mtime, kind, message));
        save();
    }

    public synchronized void clearFailed(Path path) {
        String key = path.toAbsolutePath().normalize().toString();
        failed.removeIf(entry -> key.equals(entry.path));
        save();
    }

    public synchronized void clearIgnored() {
        ignored.clear();
        save();
    }

    /** Snapshot of ignored fingerprints, for the "ignored" view in the GUI. */
    public synchronized List<IgnoredEntry> ignoredEntries() {
        return List.copyOf(ignored);
    }

    /** Undoes {@link #ignore} so the jar becomes a candidate again. */
    public synchronized boolean unignore(Path path, long size, long mtime) {
        String key = path.toAbsolutePath().normalize().toString();
        boolean removed = ignored.removeIf(
                entry -> key.equals(entry.path) && size == entry.size && mtime == entry.mtime);
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized int ignoredCount() {
        return ignored.size();
    }

    public synchronized int failedCount() {
        return failed.size();
    }

    public Path configFile() {
        return FILE;
    }
}
