package demo.spike;

import demo.Target;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Stage-0 verdict for "implement access transformers ourselves, without JBR".
 *
 * <p>Runs four checks against a synthetic mod compiled against an AT-widened
 * stub: (1) without rewriting the mod is refused (the fixture is meaningful),
 * (2) with call-site rewriting it runs, (3) the target class was never changed,
 * (4) a package-private type reference is still refused (the hard boundary).
 * A fifth, rough benchmark compares the generated accessor with a direct call.
 */
public final class SpikeMain {
    private static final List<AccessRewriter.Rule> RULES = List.of(
            new AccessRewriter.Rule("demo/Target", "secret", "I", AccessRewriter.Kind.FIELD),
            new AccessRewriter.Rule("demo/Target", "counter", "I", AccessRewriter.Kind.STATIC_FIELD),
            new AccessRewriter.Rule("demo/Target", "secretMethod", "(I)I", AccessRewriter.Kind.METHOD),
            new AccessRewriter.Rule("demo/Target", "staticSecret", "(I)I", AccessRewriter.Kind.STATIC_METHOD),
            new AccessRewriter.Rule("demo/Target", "<init>", "(I)V", AccessRewriter.Kind.CONSTRUCTOR));

    private SpikeMain() {
    }

    public static void main(String[] args) throws Exception {
        Path classes = Path.of(args.length > 0 ? args[0] : "out/modonly/demo/mod");
        boolean ok = true;

        byte[] modBytes = Files.readAllBytes(classes.resolve("ModCode.class"));

        // 1. Control: without rewriting, the direct access must be refused by the JVM.
        Throwable control = invokeExpectingFailure(modBytes, "demo.mod.ModCode", "run");
        ok &= report("control: unrewritten mod is refused",
                chainHas(control, IllegalAccessError.class), describe(control));

        // 2. Rewrite the caller, then run it against the real (private) target.
        byte[] rewritten = AccessRewriter.rewrite(modBytes, RULES);
        Class<?> modClass = new ModLoader().define("demo.mod.ModCode", rewritten);
        Object result = modClass.getMethod("run").invoke(null);
        ok &= report("rewritten mod runs", "7,12,19,7,27".equals(result), "run()=" + result);

        // 3. The target class must still be private: we changed the caller, not the target.
        Field secret = Target.class.getDeclaredField("secret");
        Method secretMethod = Target.class.getDeclaredMethod("secretMethod", int.class);
        boolean stillPrivate = Modifier.isPrivate(secret.getModifiers())
                && Modifier.isPrivate(secretMethod.getModifiers())
                && Modifier.isPrivate(Target.class.getDeclaredConstructor(int.class).getModifiers());
        ok &= report("target stays private", stillPrivate,
                "field/method/ctor modifiers unchanged");

        // 4. Boundary: a package-private nested type cannot be fixed by rewriting callers.
        byte[] badBytes = Files.readAllBytes(classes.resolve("BadCode.class"));
        Throwable boundary = invokeExpectingFailure(badBytes, "demo.mod.BadCode", "use");
        ok &= report("boundary: package-private type is refused",
                chainHas(boundary, IllegalAccessError.class), describe(boundary));

        // 5. Rough cost: accessor + MethodHandle.invokeExact vs a direct public call.
        // Five rounds, first two warm up the JIT, best round is reported.
        int iterations = 2_000_000;
        int rounds = 5;
        Method bench = modClass.getMethod("bench", int.class);
        long bestAccessor = Long.MAX_VALUE;
        long bestDirect = Long.MAX_VALUE;
        long sum = 0;
        Target target = Target.createPublic(1);
        for (int round = 0; round < rounds; round++) {
            long start = System.nanoTime();
            Object value = bench.invoke(null, iterations);
            long afterAccessor = System.nanoTime();
            long baseline = 0;
            for (int i = 0; i < iterations; i++) {
                baseline += target.publicCall(1);
            }
            long afterBaseline = System.nanoTime();
            if (round >= 2) {
                bestAccessor = Math.min(bestAccessor, afterAccessor - start);
                bestDirect = Math.min(bestDirect, afterBaseline - afterAccessor);
                sum = ((Number) value).longValue();
            }
        }
        double accessorNs = bestAccessor / (double) iterations;
        double directNs = bestDirect / (double) iterations;
        System.out.printf("[perf] rewritten private call: %.2f ns/call (sum=%d)%n", accessorNs, sum);
        System.out.printf("[perf] direct public call:      %.2f ns/call%n", directNs);

        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        if (!ok) {
            System.exit(1);
        }
    }

    private static boolean report(String label, boolean passed, String detail) {
        System.out.println((passed ? "[ok]   " : "[FAIL] ") + label + " — " + detail);
        return passed;
    }

    private static Throwable invokeExpectingFailure(byte[] bytes, String className, String method) {
        try {
            Class<?> type = new ModLoader().define(className, bytes);
            type.getMethod(method).invoke(null);
            return null;
        } catch (InvocationTargetException e) {
            return e.getCause();
        } catch (Throwable t) {
            return t;
        }
    }

    private static boolean chainHas(Throwable throwable, Class<? extends Throwable> type) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    private static String describe(Throwable throwable) {
        if (throwable == null) {
            return "no error (access was allowed)";
        }
        StringBuilder text = new StringBuilder(throwable.getClass().getSimpleName());
        if (throwable.getMessage() != null) {
            text.append(": ").append(throwable.getMessage());
        }
        return text.toString();
    }

    private static final class ModLoader extends ClassLoader {
        ModLoader() {
            super("spike-mod", SpikeMain.class.getClassLoader());
        }

        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }
}
