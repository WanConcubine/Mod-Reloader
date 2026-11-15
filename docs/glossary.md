# 术语表

> 新词先写这里，再在文档/代码里用。避免同义词漂移。

## 分级

| 术语 | 含义 |
|---|---|
| **嫔（pin）** | 有代码，但无 mixin、无注册表；类加载 + 入口点。旧称 T2。 |
| **妃（fei）** | 代码 + Mixin，无注册表内容；类重定义 + 离线 re-mix + 布局拆分。旧称 T3。 |
| **贵妃（guifei）** | 注册表内容（方块/物品/维度等）；实验性 add-only 热加，删除/更新/完整生效仍需重启。旧称 T4。 |
| **皇后（huanghou）** | 不支持热换（native、核心渲染、性能/库模组）；只能完整重启。旧称 T5。 |
| **T?** | 分析失败（坏 jar / 无法读取），不是一个等级。 |
| **T0 / T1** | 资源 / 数据变更，属于**原版能力**（`F3+T` / `/reload`），**不进入本项目的分级**。 |

> 2026-11-15 起分级改称 **嫔 / 妃 / 贵妃 / 皇后**，不再使用 Tn 编号。
> 历史文档（[history.md](history.md)、ADR、调研）保留旧写法，按上表对应即可。

## 重载方式

| 术语 | 含义 |
|---|---|
| **Hot reload** | 把变更（代码/资源/数据）应用到运行中的游戏；不一定是增删模组。 |
| **Hot swap** | 在运行中增删/替换模组。 |
| **Soft restart** | 进程内软重启：保留 JVM/窗口/GL/音频，丢弃整个“游戏层” ClassLoader 并新建一代。 |
| **Hard restart** | 关进程重开。 |
| **Dry-run** | 切换前预演：只验证、不真正应用。 |
| **Rollback** | 失败后回到切换前状态。 |
| **Dogfood** | 用自己的工具处理自己的项目（本项目用 Ex Nihilo 做首个测试对象）。 |
| **Reload Doctor** | 本项目的产品代号：只读分析器 + 未来的游戏内 `/modreload why`。 |

## Mixin / 字节码

| 术语 | 含义 |
|---|---|
| **Mixin** | SpongePowered 的字节码织入框架；目标类首次加载时把模组代码合并进去。 |
| **Mixin config** | `*.mixins.json`，列出 mixin 类、package、refmap、client/server 等。 |
| **`@Mixin`** | 标注 mixin 类，声明 target（类）。分析器直接解析这个注解。 |
| **`@Redirect` / `@Overwrite`** | 侵入性注入方式；`@Overwrite` 直接覆盖目标方法。 |
| **Refmap** | 混淆映射文件，让 mixin 在 production 中找到正确成员。 |
| **postInitialise** | Mixin config 在 `prepare` 后必须调用的步骤；不调用时 `MixinInfo` 未 validate，字段/方法仍会合并，但接口不会合并（A1.1 的坑）。 |
| **Eager target load** | 运行时注册 mixin 后，对未加载 target 执行 `Class.forName(..., false)`，让 Mixin 在首载时织入；已 mixed 的类不能再次 transform。 |
| **Rollback / undo transaction** | 热加失败时按 LIFO 逆序撤销：module、ModList、mixin config、target 字节、资源包。构造后的监听器不能注销，是当前回滚边界。 |
| **Candidate** | 文件夹监听发现的、尚未处理的新 jar；带指纹和预检结果。 |
| **Preflight** | 加载前只读预检：mod 元数据、依赖、mixin/registry/native、预测 tier；不加载类、不改状态。 |
| **Watch baseline** | 启动完成时的 jar 指纹集合；基线内文件不会被当成“新增”提示。 |
| **AW / Access Widener** | Fabric 的访问拓宽机制（可见性、final）。 |
| **Sidecar** | 把“新增方法”搬到目标类旁边的伴生类（nestmate），绕过 JVM 不能加方法的限制。 |
| **Holder** | 把“新增字段”搬到每个实例的伴生对象里（weak map），绕过不能加字段的限制。 |
| **Tripwire** | 在模组方法入口插入的检查点，用于发现“已关闭模组仍在运行”。 |
| **Journal** | 启动期记账：原始字节、mixin 落点、注册项；关闭时按 journal 清理。 |
| **Off-window** | 模组关闭期间产生的对象；重新开启时需要补初始化。 |

## 加载器 / 平台

| 术语 | 含义 |
|---|---|
| **Knot** | Fabric 的自定义 ClassLoader / 启动器；游戏和所有模组在同一个 ClassLoader 里。 |
| **ModLauncher** | Forge/NeoForge 的模块化启动框架。 |
| **ModuleLayer** | Java 9+ 的模块层；FML 用它组织游戏和模组。 |
| **FML** | Forge Mod Loader；NeoForge 的加载器核心。 |
| **ModDevGradle** | NeoForge 官方 Gradle 插件（`net.neoforged.moddev`）。 |
| **JiJ / Jar-in-Jar** | 模组把依赖打包进 `META-INF/jars/` 或 `META-INF/jarjar/`。 |
| **Intermediary** | Fabric 的稳定中间名（`class_310`），生产环境常用。 |
| **Yarn** | Fabric 的可读映射（intermediary → named）。 |
| **Mojmap** | Mojang 官方映射；NeoForge 1.21 运行时用官方名。 |
| **Parchment** | 在 Mojmap 上补参数名/javadoc 的映射。 |

## JVM

| 术语 | 含义 |
|---|---|
| **Instrumentation** | `java.lang.instrument` 的入口；`redefineClasses` / `retransformClasses`。 |
| **JVMTI** | JVM Tool Interface；规定类重定义的底层约束。 |
| **Redefine** | 用新字节码替换已加载类；只能改方法体/常量池/属性。 |
| **Retransform** | 重新跑一遍 ClassFileTransformer，再定义类。 |
| **Class unloading** | 只有定义类的 ClassLoader 不可达时，类才可能被卸载。 |
| **JBR** | JetBrains Runtime；`-XX:+AllowEnhancedClassRedefinition` 可增删方法/字段。 |
| **DCEVM** | 另一套增强类重定义 JVM（Java 11 之前常用）。 |
| **Self-attach** | 进程内自己加载 java agent 拿 `Instrumentation`（本项目 PoC 已验证）。 |

## 注册表

| 术语 | 含义 |
|---|---|
| **Registry** | Minecraft 的注册表；方块/物品/实体等都注册在这里。 |
| **BuiltInRegistries** | 内置注册表的静态入口。 |
| **DeferredRegister** | NeoForge 的延迟注册器；`DeferredRegister#register` 是分析器的强信号。 |
| **RegisterEvent** | NeoForge 的注册事件；`RegisterEvent#register` 也是强信号。 |
| **Datapack registry** | 由数据包加载的注册表（附魔、伤害类型、纹饰等），`/reload` 可重建。 |
| **Freeze** | 注册表在 bootstrap 后冻结；再注册会抛异常。 |
| **Numeric ID** | 注册表条目的整数 ID；会进存档和网络包，删除条目会让后面的 ID 前移。 |

## 运行时架构 / 工程

| 术语 | 含义 |
|---|---|
| **Runtime** | 本项目的热加载器本身：加载用户已信任的第三方模组，不替代它们的功能（ADR-0005）。 |
| **Side bootstrap** | 端侧接线：`ClientBootstrap`（UI/toast/资源包/tick）与 `ServerBootstrap`（控制台命令/启动触发）。 |
| **PlatformHooks** | 引擎唯一的端相关出口（key mapping、客户端生命周期、资源包、命令重放、本端额外连接）；客户端与服务端各一个实现，由 side bootstrap 注册。 |
| **Capability / 锚点（anchor）** | 引擎依赖的私有 FML/JDK 字段、方法、构造器。启动时 `Capabilities` 只读探测并打 `capabilities:` 报告；锚点缺失时对应阶段 fail-fast，而不是静默跳过。 |
| **Integration server** | 单人游戏内的服务端；它同样会触发 `RegisterCommandsEvent`，所以客户端也安装命令捕获。 |
| **Dev source set / DevEntry** | `src/dev/java`：游戏内验证 harness。不进发布 jar；主源集只能按名字反射 `DevEntry.install()`，编译期无法引用。 |
| **Harness** | 自动化游戏内验证入口（`-PverifyXxx`）：建世界/连服、执行动作、断言并打 `RESULT: PASS/FAIL`。 |
| **History** | [history.md](history.md)：追加式过程记录（做了什么、证据、为什么）。状态只看 [status.md](status.md)。 |
| **Registry adaptation（注册表适配）** | 贵妃级的路线：unfreeze + 重放 `RegisterEvent` + 重建依赖缓存，让注册表内容「就地」生效；只支持 add-only，且单向提交、不可回滚。见 [research-t4-registry.md](research-t4-registry.md)。 |
| **Bake callback** | NeoForge 在 `freeze()` 时执行的回调；重算 BlockState id 映射、方块缓存/loot table、属性校验等。贵妃级适配的「依赖缓存重建」就靠它。 |
| **One-shot replay（一次性事件重放）** | 把启动期只发生一次的事件（命令、payload、`RegisterEvent`）在热加后重新投递；按容器重放，避免全局重放导致已加载 mod 的监听器再跑一遍。 |
| **单向提交（commit-only）** | 贵妃级适配的失败模型：注册表条目无法撤销，提交前尽量预检，提交后失败只能把运行时标记为不可信并要求重启。 |
| **Registry window（注册表窗口）** | 贵妃级热加的 unfreeze → construct → RegisterEvent 重放 → 清空 state id → freeze 这一段；窗口内失败可以恢复冻结状态，freeze 之后不可回滚。 |
