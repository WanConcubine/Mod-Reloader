package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Stops the server tick loop for the window in which hot-added mixins are live but
 * the mod is not initialised yet.
 *
 * <p>A hot-add keeps the start-up order: mixins apply to already-loaded classes
 * before the mod constructor runs. At the title screen nothing ticks, so that
 * order is harmless. In a running world it is not: the server thread keeps ticking
 * between the first {@code redefineClasses} and {@code construct}, and injected
 * handlers can read static state that only exists after construction. Measured
 * with AE2 (issue 68): its {@code ChunkMap} mixin called {@code AEConfig.instance()}
 * 7 s after the redefine and 4 s before the mod was constructed, and the server
 * died with an NPE. A dedicated server can only add while its world is running, so
 * this is the server path, not an edge case.
 *
 * <p>The barrier parks the server thread on a latch task submitted through
 * {@link MinecraftServer#execute}. The caller keeps working (bytecode transform,
 * construct, registry replay, common setup) and releases after common setup, when
 * the mod is fully initialised. Consequences and limits:
 *
 * <ul>
 *   <li>the server does not tick while the barrier is held; players see a freeze
 *       for the duration (typically seconds). A dedicated server's watchdog kills
 *       the process if one tick takes longer than {@code max-tick-time} (default
 *       60 s), so very large mods may need that raised;</li>
 *   <li>a paused integrated server does not tick, so the barrier is skipped
 *       there;</li>
 *   <li>a console add already runs on the server thread, so ticks are stopped by
 *       definition and the barrier is a no-op;</li>
 *   <li>if the server thread does not pick the latch up (stopping server, a
 *       server that never ticks) the add continues without the barrier and logs
 *       that the race window is open — a deadlock would be worse;</li>
 *   <li>{@code -Dmodreloader.tickBarrier=true} enables the barrier; it is
 *       <b>off by default</b>. In the 2026-11-21 experiments two in-world AE2
 *       adds with it crashed the JVM inside {@code VM_RedefineClasses}
 *       (0xC0000005) while runs without it passed, so it is not safe to use by
 *       default until that crash is understood (issue 68).</li>
 * </ul>
 *
 * <p>While the barrier is held, {@link ServerThreadHop} runs its task inline: the
 * server thread is parked, so nothing on the server side can observe the state the
 * hop was protecting.
 */
public final class ServerTickBarrier {
    private static final long ENTER_TIMEOUT_MILLIS = 10_000;
    private static final long LONG_HOLD_MILLIS = 30_000;

    private record Hold(CompletableFuture<Void> release, long since) {
    }

    private static final AtomicReference<Hold> ACTIVE = new AtomicReference<>();

    private ServerTickBarrier() {
    }

    /**
     * Parks the server thread until {@link #release()}. Returns {@code true} when
     * ticks are stopped by this call (including a barrier that is already held).
     */
    public static boolean hold(String what) {
        // Off by default: in the 2026-11-21 experiments two in-world AE2 adds with the
        // barrier parked the server thread and then crashed the JVM in
        // VM_RedefineClasses (0xC0000005), while two runs without it passed. Opt in
        // only for investigating that crash - see issue 68.
        if (!"true".equalsIgnoreCase(System.getProperty("modreloader.tickBarrier", "false"))) {
            return false;
        }
        ModReloader.LOGGER.warn("[ModReloader] experimental tick barrier enabled; the server "
                + "will not tick during this add ({})", what);
        MinecraftServer server;
        try {
            server = ServerLifecycleHooks.getCurrentServer();
        } catch (Throwable t) {
            ModReloader.LOGGER.warn("[ModReloader] cannot inspect the server for the tick barrier", t);
            return false;
        }
        if (server == null) {
            return false;
        }
        if (server.isPaused()) {
            ModReloader.LOGGER.info("[ModReloader] tick barrier skipped: the integrated server is paused");
            return false;
        }
        if (server.isSameThread()) {
            // A console command already owns the server thread: it cannot tick until
            // the command returns.
            return false;
        }
        Hold existing = ACTIVE.get();
        if (existing != null) {
            return true;
        }

        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Void> entered = new CompletableFuture<>();
        Hold hold = new Hold(release, System.nanoTime());
        if (!ACTIVE.compareAndSet(null, hold)) {
            return true;
        }
        server.execute(() -> {
            entered.complete(null);
            release.join();
        });
        try {
            entered.get(ENTER_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            ACTIVE.compareAndSet(hold, null);
            release.complete(null);
            ModReloader.LOGGER.warn("[ModReloader] the server thread did not enter the tick barrier "
                    + "within {} ms (is the server stopping or never ticking?); continuing without it - "
                    + "injected code may run before the mod is constructed ({})",
                    ENTER_TIMEOUT_MILLIS, what);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ACTIVE.compareAndSet(hold, null);
            release.complete(null);
            return false;
        } catch (ExecutionException e) {
            ACTIVE.compareAndSet(hold, null);
            ModReloader.LOGGER.warn("[ModReloader] cannot park the server thread for the tick barrier", e);
            return false;
        }
        ModReloader.LOGGER.info("[ModReloader] server ticking suspended ({})", what);
        return true;
    }

    /** Releases the barrier; safe to call when nothing is held or from a finally. */
    public static void release() {
        Hold hold = ACTIVE.getAndSet(null);
        if (hold == null) {
            return;
        }
        hold.release().complete(null);
        long millis = (System.nanoTime() - hold.since()) / 1_000_000;
        ModReloader.LOGGER.info("[ModReloader] server ticking resumed after {} ms", millis);
        if (millis > LONG_HOLD_MILLIS) {
            ModReloader.LOGGER.warn("[ModReloader] the server was frozen for {} ms; a dedicated server's "
                    + "watchdog kills it above max-tick-time (default 60000 ms) - raise it for very "
                    + "large hot-adds", millis);
        }
    }

    /** Whether this thread (or the add it is driving) currently has the server parked. */
    public static boolean held() {
        return ACTIVE.get() != null;
    }
}