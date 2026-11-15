# 技术债与架构升级点

> 只记录**结构性**问题与可升级点；实现进度见 [status.md](status.md)。
> 原则：先修"会让我们说错话/悄悄坏掉"的，再修"好改但收益小"的；不为未来可能的需求预先抽象。

## 事实盘点（2026-11-15）

| 指标 | 现状 |
|---|---|
| Java 代码 | 6341 行 / 11 个包 |
| `dev/` 夹具 | **8 文件 / 2008 行 ≈ 28%**，且有 5 个类被打进发布 jar |
| 反射 / Unsafe 站点 | 12 个文件（mixin、ModList、module、网络、资源包、JVM） |
| Java 单测 | **0**（`src/test` 不存在）；验证全靠 1–3 分钟的游戏内 harness |
| 语言文件 | `en_us` / `zh_cn` 各 123 key，键集一致（靠脚本核对，无测试） |
| 包依赖 | 存在循环：`hotadd ↔ client`、`command ↔ client` |

## P0：会让我们「悄悄说错话」的

1. **反射面没有兼容性守卫**。`MixinSupport`、`NetworkAdaptation`、`ModuleInjector`、`ModListInjector`
   依赖 `@ApiStatus.Internal` 的字段/方法名。NeoForge 一升级，表现可能是崩溃，也可能是**静默失效**。
   → 启动时校验关键锚点（`NetworkRegistry.setup`、`PAYLOAD_REGISTRATIONS`、`MixinProcessor.configs`、
   `ModList` 内部字段），生成 **Capabilities 报告**；锚点缺失时把对应能力标记为不可用并给出人话提示，
   而不是等到用户热加时才炸。
2. **引擎层依赖两侧实现**：`hotadd → client`（`ActiveConnections` 用 `ClientConnections`）、
   `hotadd → server`（`HotAddEngine` 直接调 `ServerCommands`）。方向反了：引擎本该与端无关。
   → 一个极小的 `PlatformHooks` 接口（`keyMappingCount` / `injectResourcePack` / `replayCommands`），
   由 `ClientBootstrap` / `ServerBootstrap` 注册实现。**一个接口 + 两个实现**，不是框架。
3. **`dev/` 打进发布包**。28% 的代码和全部验证开关跟着用户一起发出去。
   → 独立的 source set（`src/dev/java`）+ Gradle `dev` 配置；生产 jar 不含 `dev/`，
   也不会因为反射被顺手调用。

## P1：结构上不干净，但现在还能忍

4. **`command ↔ client` 循环**：`ReloaderCommands` 为了 `gui` 节点 import 了 `ClientCommands`。
   → 让客户端注册时装饰命令树（`ClientBootstrap` 里 `register(ClientCommands.withGui(tree()))`），
   `command` 只保留共享树。
5. **一次性事件重放没有统一出口**：命令（`RegisterCommandsEvent`）已有，payload（`RegisterPayloadHandlersEvent`）
   已用同样套路，T4 的 `RegisterEvent` 大概率还要用。三处各自处理"重放会让所有 mod 监听器再跑一遍"这个坑。
   → 一个 `OneShotReplay` 帮助类，集中记录这个副作用与降级策略。
6. **harness 样板重复**：4 个检查各自复制"建世界 / 连服 / 等遮罩"逻辑（`AddCommandCheck`、`PayloadCheck`、
   `RemotePayloadCheck`、`MenuEntryCheck`），约 600 行里有大半是重复。
   → 抽 `dev/TestWorld`（建世界 + 等 ready）与 `dev/TestConnection`（连服 + 等 join）。
7. **`design.md` 落后于双端化**：仍按"纯客户端"描述架构。要么更新，要么标注为 A0/A1 时期的历史文档。

## P2：便宜且能防止回归

8. **没有 Java 单测**：`src/test/java` 加两个不需要游戏的测试——① i18n 键集一致；
   ② `FailureKind` 每个值都能映射到 `.summary/.suggestion`（当前 `NONE` 落到 `unknown`，是隐式的，
   应该显式断言）。CI 才有东西可跑。
9. **`libs/` 编译依赖**（已知问题 12）：`compileOnly files('libs/...')` + `libs/` 被忽略 → fresh clone 编不过。
   → 反射化那 5 个调用点，或把 jar 放进仓库（需所有者拍板）。
10. **没有 CI**：分析器的 Python 测试已经能跑；Java 侧至少要能 `./gradlew build`（P2-9 解决后）。

## 刻意不做（避免过度设计）

- 不引入 DI 容器、不抽象事件总线、不做通用插件系统。
- 不把 `HotAddEngine.add` 拆成阶段框架：它是 360 行的顺序流程，但回滚与失败分类已经验证过，重写收益不抵风险。
- 不为"未来可能支持任意 mod"预留适配器接口——等 T4 真的按 mod 写适配器时再抽。

## 建议的动手顺序

1. P0-2（PlatformHooks，去掉引擎对两侧的反向依赖）
2. P0-3（`dev/` 移出发布包）
3. P0-1（Capabilities  Compatibility guard，同时给 T4 适配做前置）
4. P2-8（两个 Java 单测，把 i18n/enum 一致性钉死）
5. P1-4 / P1-5 / P1-6（清理循环与重复）
