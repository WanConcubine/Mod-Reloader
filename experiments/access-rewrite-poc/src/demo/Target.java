package demo;

/**
 * The "vanilla" class at runtime: every member the mod is not allowed to touch
 * is private (or package-private), and the class itself is public. This is the
 * class a stock JVM will not let us change — the spike rewrites the caller
 * instead.
 */
public class Target {
    private int secret;
    private static int counter;
    /** Volatile read in both benchmark targets so the JIT cannot fold the loop away. */
    private volatile int guard;

    private Target(int seed) {
        this.secret = seed;
        counter = seed;
    }

    private int secretMethod(int x) {
        return secret + x + guard;
    }

    private static int staticSecret(int x) {
        return counter + x;
    }

    /** Public baseline for the benchmark. */
    public int publicCall(int x) {
        return secret + x + guard;
    }

    public static Target createPublic(int seed) {
        return new Target(seed);
    }

    /** Package-private: a class-level AT cannot be emulated by rewriting callers. */
    static class Hidden {
        int v;

        Hidden() {
        }
    }
}
