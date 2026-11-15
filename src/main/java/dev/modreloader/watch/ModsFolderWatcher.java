package dev.modreloader.watch;

import dev.modreloader.ModReloader;
import dev.modreloader.Platform;
import dev.modreloader.inspect.ModInspection;
import dev.modreloader.inspect.ModInspector;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Polls the mods folder and the dev inbox for new jars.
 *
 * <p>Polling is intentional: WatchService behaves differently across macOS,
 * network shares and archive managers. A file must be stable for
 * {@code stableScans} polls before it is inspected.
 */
public final class ModsFolderWatcher {
    public record Candidate(Path path, long size, long mtime, ModInspection inspection) {
        public String fingerprint() {
            return path.toAbsolutePath().normalize() + ":" + size + ":" + mtime;
        }
    }

    private record Fingerprint(long size, long mtime) {
        static Fingerprint of(Path path) throws IOException {
            FileTime modified = Files.getLastModifiedTime(path);
            return new Fingerprint(Files.size(path), modified.toMillis());
        }
    }

    private final ModWatcherConfig config;
    private final Consumer<List<Candidate>> listener;
    private final Path modsDir = FMLPaths.MODSDIR.get();
    private final Path inboxDir = FMLPaths.GAMEDIR.get().resolve("modreloader/inbox");
    private final Map<Path, Fingerprint> seen = new HashMap<>();
    private final Map<Path, Integer> stable = new HashMap<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "modreloader-watch");
        thread.setDaemon(true);
        return thread;
    });

    private boolean started;
    private volatile List<Candidate> lastCandidates = List.of();

    public ModsFolderWatcher(ModWatcherConfig config, Consumer<List<Candidate>> listener) {
        this.config = config;
        this.listener = listener;
    }

    public synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        prepareDirectories();
        baseline();
        executor.scheduleWithFixedDelay(this::poll, Math.max(1, config.pollSeconds),
                Math.max(1, config.pollSeconds), TimeUnit.SECONDS);
        ModReloader.LOGGER.info("[ModReloader] watching {} and {} for new mods",
                modsDir, inboxDir);
    }

    public synchronized void stop() {
        started = false;
        executor.shutdownNow();
    }

    public Path inboxDir() {
        return inboxDir;
    }

    public Path modsDir() {
        return modsDir;
    }

    public List<Candidate> lastCandidates() {
        return lastCandidates;
    }

    /** Scans immediately (command/UI use) and marks new candidates as reported. */
    public synchronized List<Candidate> scanNow() {
        prepareDirectories();
        List<Candidate> candidates = scan();
        if (!candidates.isEmpty()) {
            lastCandidates = List.copyOf(candidates);
        }
        return candidates;
    }

    private void prepareDirectories() {
        try {
            Files.createDirectories(inboxDir);
        } catch (IOException e) {
            ModReloader.LOGGER.warn("[ModReloader] cannot create inbox {}", inboxDir, e);
        }
    }

    private void baseline() {
        for (Path dir : watchDirs()) {
            for (Path jar : jars(dir)) {
                try {
                    seen.put(jar, Fingerprint.of(jar));
                } catch (Throwable ignored) {
                    // File disappeared during baseline.
                }
            }
        }
        ModReloader.LOGGER.info("[ModReloader] watcher baseline: {} jar(s)", seen.size());
    }

    private void poll() {
        List<Candidate> candidates;
        synchronized (this) {
            if (!started) {
                return;
            }
            candidates = scan();
            if (!candidates.isEmpty()) {
                lastCandidates = List.copyOf(candidates);
            }
        }
        if (candidates.isEmpty()) {
            return;
        }
        ModReloader.LOGGER.info("[ModReloader] watcher candidates: {}",
                candidates.stream().map(candidate -> candidate.path().getFileName().toString()).toList());
        // Hop to the client render thread when there is one; a dedicated server
        // has no such thread, so the callback runs inline.
        Platform.runOnMainThread(() -> listener.accept(candidates));
    }

    private List<Candidate> scan() {
        List<Candidate> candidates = new ArrayList<>();
        Set<Path> present = new HashSet<>();
        for (Path dir : watchDirs()) {
            for (Path jar : jars(dir)) {
                present.add(jar);
                Fingerprint fingerprint;
                try {
                    fingerprint = Fingerprint.of(jar);
                } catch (Throwable ignored) {
                    continue;
                }
                Fingerprint previous = seen.get(jar);
                if (fingerprint.equals(previous)) {
                    stable.remove(jar);
                    continue;
                }
                if (config.isIgnored(jar, fingerprint.size(), fingerprint.mtime())
                        || config.isFailed(jar, fingerprint.size(), fingerprint.mtime())) {
                    seen.put(jar, fingerprint);
                    stable.remove(jar);
                    continue;
                }
                int count = stable.merge(jar, 1, Integer::sum);
                int required = Math.max(1, config.stableScans);
                if (count < required) {
                    continue;
                }
                ModInspection inspection = ModInspector.inspect(jar);
                seen.put(jar, fingerprint);
                stable.remove(jar);
                candidates.add(new Candidate(jar, fingerprint.size(), fingerprint.mtime(), inspection));
                ModReloader.LOGGER.info("[ModReloader] new mod candidate {}: {}", jar.getFileName(),
                        inspection.summary());
            }
        }
        stable.keySet().removeIf(path -> !present.contains(path));
        return candidates;
    }

    private List<Path> watchDirs() {
        Set<Path> dirs = new LinkedHashSet<>();
        if (config.watchModsFolder) {
            dirs.add(modsDir);
        }
        if (config.watchInbox) {
            dirs.add(inboxDir);
        }
        return new ArrayList<>(dirs);
    }

    private List<Path> jars(Path dir) {
        List<Path> jars = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return jars;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> {
                        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".jar") && !name.startsWith(".");
                    })
                    .filter(path -> {
                        try {
                            return Files.size(path) > 0;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(jars::add);
        } catch (IOException e) {
            ModReloader.LOGGER.warn("[ModReloader] cannot list {}", dir, e);
        }
        return jars;
    }
}
