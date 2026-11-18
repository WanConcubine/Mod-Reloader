package demo;

/**
 * Compile-time stub of the "vanilla" class, as it looks *after* an access
 * transformer (all members public). The mod is compiled against this file only;
 * it is never on the runtime classpath. At runtime the real {@code Target}
 * (private members, below in src/) is loaded, which is exactly the AT situation:
 * the mod's bytecode carries direct accesses the JVM will refuse.
 */
public class Target {
    public int secret;
    public static int counter;

    public Target(int seed) {
    }

    public int secretMethod(int x) {
        return 0;
    }

    public static int staticSecret(int x) {
        return 0;
    }

    public int publicCall(int x) {
        return 0;
    }

    /** Made public by a class-level AT in real mods; package-private at runtime. */
    public static class Hidden {
        public int v;

        public Hidden() {
        }
    }
}
