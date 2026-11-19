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
    /**
     * Why the process cannot be trusted. The key is what the UI translates; the
     * English sentence goes to logs and {@code Result.message} (console, log
     * file), which are not localized.
     */
    public enum Reason {
        ROLLBACK_PARTIAL("rollback_partial",
                "the rollback of a previous add was incomplete"),
        REGISTRY("registry", "registry content from a failed add is committed"),
        CUSTOM_REGISTRY("custom_registry",
                "custom registries from a failed add were created or filled"),
        MIXINS("mixins", "mixin code is already woven into loaded classes"),
        CONSTRUCT("construct", "a mod constructor ran and its side effects cannot be undone"),
        PAYLOADS("payloads", "network payloads from a failed add are still registered"),
        CAPABILITIES("capabilities",
                "capability providers from a failed add are still registered"),
        COMMANDS("commands", "commands from a failed add are still in the dispatcher");

        private final String key;
        private final String english;

        Reason(String stem, String english) {
            this.key = "modreloader.dirty.reason." + stem;
            this.english = english;
        }

        /** Translation key, resolved by the UI. */
        public String key() {
            return key;
        }

        /** Log/console sentence; never shown to players. */
        public String english() {
            return english;
        }
    }

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

    private static volatile Reason reason;
    private static volatile long since;

    private RunState() {
    }

    /**
     * @return why the runtime cannot be trusted, or {@code null} when everything
     *         this add touched can still be rolled back
     */
    public static Reason reasonFor(Flags flags) {
        if (flags.rollbackPartial()) {
            return Reason.ROLLBACK_PARTIAL;
        }
        if (flags.registryCommitted()) {
            return Reason.REGISTRY;
        }
        if (flags.customRegistryOpened()) {
            return Reason.CUSTOM_REGISTRY;
        }
        if (flags.mixinsSettled()) {
            return Reason.MIXINS;
        }
        if (flags.constructed()) {
            return Reason.CONSTRUCT;
        }
        if (flags.payloadsAdapted()) {
            return Reason.PAYLOADS;
        }
        if (flags.capabilitiesReplayed()) {
            return Reason.CAPABILITIES;
        }
        if (flags.commandsReplayed()) {
            return Reason.COMMANDS;
        }
        return null;
    }

    /** Sticky for the rest of the process; a restart is the only reset. */
    public static synchronized void markDirty(Reason why) {
        if (reason != null) {
            return;
        }
        reason = why;
        since = System.currentTimeMillis();
    }

    public static boolean isDirty() {
        return reason != null;
    }

    public static Reason reason() {
        return reason;
    }

    /** English sentence for logs and Result messages; null when clean. */
    public static String describe() {
        Reason current = reason;
        return current == null ? null : current.english();
    }

    public static long dirtySince() {
        return since;
    }
}