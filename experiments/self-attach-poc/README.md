# 自附加 + 类重定义最小验证

这个实验回答两个问题：

1. 一个**普通 Minecraft 模组**（不是 `-javaagent`）能不能在运行中拿到 `Instrumentation`？
2. 拿到之后，JVM 到底允许改什么、不允许改什么？

它是 Hotwire / NeoHotModInjector 所用机制的**最小可复现版本**，不依赖任何模组加载器。

## 运行

要求 JDK 17+（本实验在 Temurin 17.0.20.1 / macOS arm64 上验证）。

```bash
./build.sh
```

## 预期输出

```text
[agent] agentmain called, redefineSupported=true
instrumentation = sun.instrument.InstrumentationImpl@...
before: V1 add=5
after : V2-hotswapped add=105  redefine took 5.420417 ms
schema change rejected: java.lang.UnsupportedOperationException: class redefinition failed: attempted to add a method
```

毫秒数会随机器与是否首次调用浮动（首次通常几毫秒，热身后可到亚毫秒）；关键是三行语义：

- `redefineSupported=true`：自附加成功；
- `V2-hotswapped` / `add=105`：方法体替换生效；
- `attempted to add a method`：**新增方法被 JVM 拒绝**，这就是所有 sidecar / holder / trampoline 方案存在的原因。

## 文件

| 文件 | 作用 |
|---|---|
| `src/Agent.java` | `agentmain` 入口，保存 `Instrumentation` |
| `src/Main.java` | 自附加 + 重定义 + 验证 schema 限制 |
| `src/Target.java` | 被修改的类（V1） |
| `patches/v2/Target.java` | 只改方法体（应成功） |
| `patches/v3/Target.java` | 新增方法（应被拒绝） |

## 原理

### 自附加

通常动态加载 agent 需要 `jdk.attach`，且从 JDK 9 起默认不允许 attach 自身。这里绕开 Attach API：

1. 用 `sun.misc.Unsafe` 取 `MethodHandles.Lookup.IMPL_LOOKUP`；
2. 调系统 ClassLoader 的 `appendToClassPathForInstrumentation`，把 agent jar 加进类路径；
3. 直接调 `sun.instrument.InstrumentationImpl.loadAgent`。

agent jar 的 manifest 需要：

```text
Agent-Class: Agent
Launcher-Agent-Class: Agent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
```

### 重定义的两条规则

- `redefineClasses` 可以换方法体、常量池、属性；
- 不能增删字段/方法、改签名、改继承、改 `NestHost`/`Record`/`PermittedSubclasses`。

规范原文见 JVMTI `RedefineClasses`：
<https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html>

## 注意

- 这是**实验代码**，不是生产实现；`IMPL_LOOKUP` 属于 JDK 内部实现，跨版本可能失效。
- JDK 21 起动态加载 agent 会有告警（JEP 451），未来版本可能默认禁止；生产方案需要准备 `-javaagent` 兜底。
- 本实验只验证“换字节码”，不涉及 Mixin、注册表、对象世界——那些才是热重载的主要工程量。
