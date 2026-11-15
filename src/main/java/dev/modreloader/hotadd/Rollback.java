package dev.modreloader.hotadd;

import dev.modreloader.ModReloader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * LIFO undo stack for a hot-add attempt.
 *
 * <p>Each stage registers its undo action as soon as it starts mutating state.
 * On failure the actions run in reverse order; every failure is logged and
 * collected instead of aborting the remaining undo steps.
 */
final class Rollback {
    @FunctionalInterface
    interface Action {
        void run() throws Throwable;
    }

    private record Step(String name, Action action) {
    }

    private final Deque<Step> steps = new ArrayDeque<>();

    void add(String name, Action action) {
        steps.push(new Step(name, action));
    }

    boolean isEmpty() {
        return steps.isEmpty();
    }

    /** Runs all undo steps in reverse order and returns human-readable errors. */
    List<String> run() {
        List<String> errors = new ArrayList<>();
        while (!steps.isEmpty()) {
            Step step = steps.pop();
            try {
                step.action().run();
                ModReloader.LOGGER.info("[ModReloader] rollback: {} ok", step.name());
            } catch (Throwable t) {
                errors.add(step.name() + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
                ModReloader.LOGGER.error("[ModReloader] rollback failed: {}", step.name(), t);
            }
        }
        return errors;
    }
}
