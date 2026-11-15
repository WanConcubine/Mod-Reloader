package dev.modreloader.agent;

import java.lang.instrument.Instrumentation;

/**
 * Loaded by the JVM as an agent (from a temporary jar), so it must only depend
 * on JDK classes. The mod's own classes are loaded by the game class loader and
 * cannot be used here.
 */
public final class AgentMain {
    private static volatile Instrumentation instrumentation;

    private AgentMain() {
    }

    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    public static Instrumentation getInstrumentation() {
        return instrumentation;
    }
}
