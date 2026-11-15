import java.lang.instrument.Instrumentation;

/**
 * 被 loadAgent 调用的 agent 入口。
 * 只做一件事：把 Instrumentation 存下来，供进程内其它代码使用。
 */
public final class Agent {
    static volatile Instrumentation inst;

    public static void agentmain(String args, Instrumentation instrumentation) {
        inst = instrumentation;
        System.out.println("[agent] agentmain called, redefineSupported="
                + instrumentation.isRedefineClassesSupported());
    }

    public static Instrumentation get() {
        return inst;
    }
}
