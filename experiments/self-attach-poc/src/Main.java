import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 最小验证：在普通 JVM 进程里拿到 Instrumentation，并观察类重定义的两条规则。
 *
 * 1) 自附加：不依赖 -javaagent，也不依赖 -Djdk.attach.allowAttachSelf。
 *    做法与 Hotwire / NHMJ 相同：
 *      - 取得 IMPL_LOOKUP；
 *      - 调 ClassLoader#appendToClassPathForInstrumentation 把本 jar 加进系统类路径；
 *      - 直接调 sun.instrument.InstrumentationImpl#loadAgent。
 * 2) 改方法体 -> 成功；新增方法 -> UnsupportedOperationException。
 */
public final class Main {
    public static void main(String[] args) throws Throwable {
        Path agentJar = Path.of(args[0]).toAbsolutePath();
        Path v2 = Path.of(args[1]).toAbsolutePath();
        Path v3 = Path.of(args[2]).toAbsolutePath();

        Instrumentation inst = attachToSelf(agentJar);
        System.out.println("instrumentation = " + inst);

        System.out.println("before: " + Target.hello() + " add=" + Target.add(2, 3));

        long t0 = System.nanoTime();
        inst.redefineClasses(new ClassDefinition(Target.class, Files.readAllBytes(v2)));
        long t1 = System.nanoTime();

        System.out.println("after : " + Target.hello() + " add=" + Target.add(2, 3)
                + "  redefine took " + (t1 - t0) / 1_000_000.0 + " ms");

        try {
            inst.redefineClasses(new ClassDefinition(Target.class, Files.readAllBytes(v3)));
            System.out.println("SCHEMA CHANGE ACCEPTED (unexpected on stock HotSpot)");
        } catch (Throwable e) {
            System.out.println("schema change rejected: " + e.getClass().getName() + ": " + e.getMessage());
        }
    }

    private static Instrumentation attachToSelf(Path agentJar) throws Throwable {
        MethodHandles.Lookup trusted = trustedLookup();

        ClassLoader system = ClassLoader.getSystemClassLoader();
        MethodHandle append = trusted.findVirtual(system.getClass(),
                        "appendToClassPathForInstrumentation",
                        MethodType.methodType(void.class, String.class))
                .bindTo(system);
        append.invokeWithArguments(agentJar.toString());

        MethodHandle load = trusted.findStatic(Class.forName("sun.instrument.InstrumentationImpl"),
                "loadAgent", MethodType.methodType(void.class, String.class));
        load.invokeWithArguments(agentJar.toString());

        Instrumentation inst = Agent.get();
        if (inst == null) {
            throw new IllegalStateException("loadAgent returned but agentmain was not called");
        }
        return inst;
    }

    /**
     * 通过 Unsafe 取 MethodHandles.Lookup.IMPL_LOOKUP，绕过模块访问检查。
     * 仅用于实验；生产代码应评估 JDK 版本兼容性与安全影响。
     */
    private static MethodHandles.Lookup trustedLookup() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);

        Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, implLookup);
        long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, implLookup);
        return (MethodHandles.Lookup) unsafeClass
                .getMethod("getObject", Object.class, long.class)
                .invoke(unsafe, base, offset);
    }
}
