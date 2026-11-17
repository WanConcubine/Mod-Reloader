package mixinfixture;

/**
 * Mixin target that is deliberately <em>not</em> loaded at start-up.
 *
 * <p>{@code value()} returns 1 without the mixin and 2 with it, so the harness can
 * tell "the mixin was applied" apart from "the engine only rebuilt ModList".
 */
public final class Probe {
    private Probe() {
    }

    public static int value() {
        return 1;
    }
}
