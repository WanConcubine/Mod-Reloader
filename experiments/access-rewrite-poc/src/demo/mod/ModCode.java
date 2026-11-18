package demo.mod;

import demo.Target;

/**
 * "Mod code" compiled against the AT-widened stub. None of these accesses are
 * legal against the real {@code demo.Target}: the spike rewrites every call site
 * to a generated static accessor backed by a MethodHandle.
 */
public final class ModCode {
    private ModCode() {
    }

    /** Exercises private ctor, private instance/static field, private instance/static method. */
    public static String run() {
        Target target = new Target(7);          // private constructor
        int a = target.secret;                  // private field read
        target.secret = a + 5;                  // private field write
        int b = target.secretMethod(a);         // private instance method
        int c = Target.counter;                 // private static field read
        Target.counter = c + 1;                 // private static field write
        int d = Target.staticSecret(b);         // private static method
        return a + "," + target.secret + "," + b + "," + c + "," + d;
    }

    /** Hot loop for a rough cost comparison against a direct public call. */
    public static long bench(int iterations) {
        Target target = new Target(1);
        long sum = 0;
        for (int i = 0; i < iterations; i++) {
            sum += target.secretMethod(1);
        }
        return sum;
    }
}
