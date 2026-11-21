package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs one critical section on the server thread and waits for it.
 *
 * <p>Why this exists: the block-state id map is a single mutable {@code IdMapper}
 * that both sides of the game read. Rebuilding it is only safe while nobody is
 * encoding or decoding block states. Vanilla encodes chunks on the server thread,
 * so a rebuild started on any other thread (the client/render thread in the dev
 * harness, the GUI, the inbox watcher) can land in the middle of a chunk
 * serialisation, and an id that is not in the map yet is written as {@code -1}
 * (measured: {@code LinearPalette.read -> No value with id -1}, status known
 * issue 28). The rebuild therefore has to happen on the thread that owns the
 * chunks.
 *
 * <p>Behaviour:
 *
 * <ul>
 *   <li>no server yet (title screen, or before the server owns its thread): run
 *       inline — there are no chunks in flight to race with;</li>
 *   <li>already on the server thread (a command typed in chat, a console
 *       command): run inline;</li>
 *   <li>otherwise: hand the task to {@link MinecraftServer#execute} and block
 *       until it ran.</li>
 * </ul>
 *
 * <p>If the server thread does not pick the task up within
 * {@value #TIMEOUT_SECONDS} s (a server that is stopping, or one that never
 * ticks), the caller runs it inline and warns: that is exactly the race the hop
 * exists to avoid, but a deadlock would be worse. The queued task is claimed
 * atomically, so it never runs twice — a later tick of the server thread sees
 * the claim and does nothing.
 */
public final class ServerThreadHop {
    /** Generous: a server that needs longer than this is not going to run it. */
    private static final long TIMEOUT_SECONDS = 30;

    private ServerThreadHop() {
    }

    /** Same shape as {@link java.util.concurrent.Callable}, but it may throw anything. */
    public interface Task<T> {
        T run() throws Throwable;
    }

    /**
     * Runs {@code task} on this side's server thread (the dedicated one, or the
     * integrated one) and returns its result.
     *
     * @param what short label used in the log lines
     * @throws Throwable whatever the task threw; unwrapped from the hop
     */
    public static <T> T call(String what, Task<T> task) throws Throwable {
        if (ServerTickBarrier.held()) {
            // The server thread is parked in the tick barrier while an in-world add
            // is between its first redefine and common setup. Nothing on the server
            // side runs, so the exclusive access this hop exists for is already
            // guaranteed - hopping would just wait for the parked thread.
            return task.run();
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            // Title screen / a dedicated server that is not running yet: there is
            // no server thread and no code reading the state concurrently.
            return task.run();
        }
        if (server.isSameThread()) {
            return task.run();
        }

        ModReloader.LOGGER.info("[ModReloader] {}: hopping from the thread '{}' to the server thread",
                what, Thread.currentThread().getName());
        AtomicBoolean claimed = new AtomicBoolean();
        CompletableFuture<T> hop = new CompletableFuture<>();
        server.execute(() -> {
            // Lost the race to the timeout fallback: the caller already ran it.
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            try {
                hop.complete(task.run());
            } catch (Throwable t) {
                hop.completeExceptionally(t);
            }
        });
        try {
            return hop.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            if (!claimed.compareAndSet(false, true)) {
                // The server thread started it and is still inside it: running the
                // task a second time would corrupt the registry.
                throw new IllegalStateException(what + ": the server thread started the task but did "
                        + "not finish it within " + TIMEOUT_SECONDS + " s", e);
            }
            ModReloader.LOGGER.warn("[ModReloader] {}: the server thread did not run the task within "
                            + "{} s (is it stopping?); running it on the calling thread '{}' instead. "
                            + "This is the window that produced 'No value with id -1' before, so the "
                            + "runtime may be left desynced",
                    what, TIMEOUT_SECONDS, Thread.currentThread().getName());
            return task.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(what + ": interrupted while waiting for the server thread", e);
        } catch (ExecutionException e) {
            throw e.getCause() == null ? e : e.getCause();
        }
    }
}
