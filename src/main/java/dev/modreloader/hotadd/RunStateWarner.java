package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps saying, on a dedicated server's console, that the runtime is dirty.
 *
 * <p>A dirty server keeps running and looks healthy until someone hits the
 * residue (a half-registered command, a payload channel whose classes are gone),
 * and the person who can act - the operator - is not looking at a client toast.
 * The message repeats on an interval so it survives a busy console; the only
 * reset is a restart.
 */
public final class RunStateWarner {
    private static final long INTERVAL_SECONDS =
            Long.getLong("modreloader.dirtyLogSeconds", 60L);

    private static ScheduledExecutorService executor;
    private static final AtomicLong logged = new AtomicLong();

    private RunStateWarner() {
    }

    /** Starts the repeat on a dedicated server; a no-op elsewhere or when already started. */
    public static synchronized void ensureStarted(boolean client) {
        if (executor != null || client) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modreloader-dirty-warner");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(RunStateWarner::log, INTERVAL_SECONDS, INTERVAL_SECONDS,
                TimeUnit.SECONDS);
    }

    /** Warning lines emitted so far; the dev harness asserts the repeat. */
    public static long logCount() {
        return logged.get();
    }

    private static void log() {
        try {
            String reason = RunState.reason();
            if (reason == null) {
                return;
            }
            logged.incrementAndGet();
            ModReloader.LOGGER.warn(
                    "[ModReloader] runtime is still DIRTY (since {}): {}. Hot-adds are refused; "
                            + "restart the server with the working jars in mods/ - players will "
                            + "disconnect during the restart.",
                    Instant.ofEpochMilli(RunState.dirtySince()), reason);
        } catch (Throwable ignored) {
            // A logging thread must never take the server down.
        }
    }
}