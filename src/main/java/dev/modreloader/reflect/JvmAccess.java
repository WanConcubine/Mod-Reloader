package dev.modreloader.reflect;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import dev.modreloader.reflect.UnsafeAccess;

/**
 * Obtains {@link Instrumentation} without launching the game with {@code -javaagent}.
 *
 * <p>We write a tiny agent jar containing {@code AgentMain}, append it to the
 * system class path and call {@code sun.instrument.InstrumentationImpl.loadAgent}
 * through a trusted lookup.
 */
public final class JvmAccess {
    private static final String AGENT_CLASS = "dev.modreloader.agent.AgentMain";
    private static volatile Instrumentation instrumentation;

    private JvmAccess() {
    }

    public static synchronized Instrumentation get() {
        if (instrumentation != null) {
            return instrumentation;
        }
        Instrumentation resolved;

        resolved = findRunning();
        if (resolved == null) {
            try {
                Path agentJar = createAgentJar();
                loadAgent(agentJar);
                resolved = findRunning();
                if (resolved == null) {
                    throw new IllegalStateException(
                            "loadAgent returned but " + AGENT_CLASS + " was not called");
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Throwable e) {
                throw new IllegalStateException("cannot obtain Instrumentation", e);
            }
        }
        // Semantic check at the single acquisition point: every caller redefines
        // classes. A JVM that says no here cannot hot-add at all, and the failure
        // should name that instead of surfacing later as a redefine error.
        if (!resolved.isRedefineClassesSupported()) {
            throw new IllegalStateException(
                    "this JVM's Instrumentation does not support class redefinition; "
                            + "hot-add needs it");
        }
        instrumentation = resolved;
        return resolved;
    }

    private static Instrumentation findRunning() {
        try {
            Class<?> agent = Class.forName(AGENT_CLASS, false, ClassLoader.getSystemClassLoader());
            Object value = agent.getMethod("getInstrumentation").invoke(null);
            return value instanceof Instrumentation inst ? inst : null;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot ask " + AGENT_CLASS + " for Instrumentation", e);
        }
    }

    private static void loadAgent(Path agentJar) throws Throwable {
        String path = agentJar.toAbsolutePath().normalize().toString();
        ClassLoader system = ClassLoader.getSystemClassLoader();

        MethodHandle append = UnsafeAccess.trusted()
                .findVirtual(system.getClass(), "appendToClassPathForInstrumentation",
                        MethodType.methodType(void.class, String.class))
                .bindTo(system);
        append.invokeWithArguments(path);

        Class<?> instrumentationImpl = ClassLoader.getSystemClassLoader()
                .loadClass("sun.instrument.InstrumentationImpl");
        MethodHandle load = UnsafeAccess.trusted()
                .findStatic(instrumentationImpl, "loadAgent",
                        MethodType.methodType(void.class, String.class));
        load.invokeWithArguments(path);
    }

    private static Path createAgentJar() throws Exception {
        Path jar = Files.createTempFile("modreloader-agent", ".jar");
        jar.toFile().deleteOnExit();

        Manifest manifest = new Manifest();
        Attributes attrs = manifest.getMainAttributes();
        attrs.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attrs.putValue("Premain-Class", AGENT_CLASS);
        attrs.putValue("Agent-Class", AGENT_CLASS);
        attrs.putValue("Launcher-Agent-Class", AGENT_CLASS);
        attrs.putValue("Can-Redefine-Classes", "true");
        attrs.putValue("Can-Retransform-Classes", "true");

        String entry = AGENT_CLASS.replace('.', '/') + ".class";
        try (InputStream in = JvmAccess.class.getClassLoader().getResourceAsStream(entry)) {
            if (in == null) {
                throw new IllegalStateException("agent class resource missing: " + entry);
            }
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
                out.putNextEntry(new JarEntry(entry));
                in.transferTo(out);
                out.closeEntry();
            }
        }
        return jar;
    }
}
