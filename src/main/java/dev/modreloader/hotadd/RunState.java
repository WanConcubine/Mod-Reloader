package dev.modreloader.hotadd;

/**
 * Whether this process can still be trusted after a hot-add.
 *
 * <p>Some failures happen after an operation that cannot be undone (registry
 * commit, mixin code in loaded classes, a constructor's side effects, additive
 * globals such as payloads/capabilities/commands). Rollback restores this
 * runtime's bookkeeping, not the game's reality, so further adds would build on
 * an inconsistent state.
 *
 * <p>The decision is a pure function over the flags one add accumulates; the
 * process state is a single sticky flag that only a restart clears.
 */
public final class RunState {
    /** Irreversible operations reached before a failure. */
    public record Flags(boolean rollbackPartial,
                        boolean registryCommitted,
                        boolean customRegistryOpened,
                        boolean constructed,
                        boolean mixinsSettled,
                        boolean payloadsAdapted,
                        boolean capabilitiesReplayed,
                        boolean commandsReplayed) {
    }

    private static volatile String reason;
    private static volatile long since;

    private RunState() {
    }

    /**
     * @return why the runtime cannot be trusted, or {@code null} when everything
     *         this add touched can still be rolled back
     */
    public static String reasonFor(Flags flags) {
        if (flags.rollbackPartial()) {
            return "the rollback of a previous add was incomplete";
        }
        if (flags.registryCommitted()) {
            return "registry content from a failed add is committed";
        }
        if (flags.customRegistryOpened()) {
            return "custom registries from a failed add were created or filled";
        }
        if (flags.mixinsSettled()) {
            return "mixin code is already woven into loaded classes";
        }
        if (flags.constructed()) {
            return "a mod constructor ran and its side effects cannot be undone";
        }
        if (flags.payloadsAdapted()) {
            return "network payloads from a failed add are still registered";
        }
        if (flags.capabilitiesReplayed()) {
            return "capability providers from a failed add are still registered";
        }
        if (flags.commandsReplayed()) {
            return "commands from a failed add are still in the dispatcher";
        }
        return null;
    }

    /** Sticky for the rest of the process; a restart is the only reset. */
    public static synchronized void markDirty(String why) {
        if (reason != null) {
            return;
        }
        reason = why;
        since = System.currentTimeMillis();
    }

    public static boolean isDirty() {
        return reason != null;
    }

    public static String reason() {
        return reason;
    }

    public static long dirtySince() {
        return since;
    }
}