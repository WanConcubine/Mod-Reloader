# 技术债与架构升级点

> 只记录**结构性**问题与可升级点；实现进度见 [status.md](status.md)。
> 原则：先修"会让我们说错话/悄悄坏掉"的，再修"好改但收益小"的；不为未来可能的需求预先抽象。

## 事实盘点（2026-11-19 复审）

| 指标 | 现状 |
|---|---|
| Java 代码 | **26 088 行 / 147 文件**（`main` 16 620 / 90 + `dev` 8 609 / 37 + `test` 1 859 / 20） |
| `hotadd` 包 | **7 764 行**；`HotAddEngine.add` 已拆成 10 个阶段方法（2026-11-19），单方法最长约 150 行 |
| 反射 / Unsafe 站点 | **228 处 / 32 个文件**（约占 main 文件 25%）：mixin、注入器、ModList、module、注册表、网络、资源包、启动器锁 |
| 一次性事件重放 | **22 个条目 / 22 个调用点**，统一走 `inspect/ReplayCatalog` + `hotadd/OneShotReplay`（2026-11-19） |
| Java 单测 | **111**（`AddStagePlan` 5、`ReplayCatalogTest` 3、审计 3、`RunStateTest` 6、架构护栏 1…）；引擎主流程仍以真机 L1 harness 为主要证据 |
| 语言文件 | `en_us` / `zh_cn` 各 197 key（未变） |
| 包依赖 | 反向边已清零并由 `ArchitectureTest` 钉住：`reflect → inspect → capability/i18n/launch/watch/command → hotadd → client/server → ui/diagnose`；`hotadd` 不依赖两侧，`ui` 不依赖 `client` |

## 2026-11-19 复审（链路视角）

**结论**：严重技术债是**集中**的，不是全面腐烂。核心矛盾是我们用一条 700 行的顺序流程**重实现 FML 的加载顺序**，
而真实契约散落在 FML 内部。2026-11-19 的三个问题同源：

- **54**：直接 transform 的锁序（B→A）与世界生成线程（A→B）反转；
- **52**：`ServerStarted` 重放太晚，构造后到重放之间服务端会 tick 一个空 handler；
- **53**：client setup 任务必须在 payload 前，但“包可见”必须在任务前——运行期两个约束互斥。

### P0-5 生命周期顺序没有单一事实源

顺序只存在于 `HotAddEngine.add` 的代码里；阶段可跳过、失败种类、线程归属、副作用都是隐式的。
任何一次“换个位置试试”都要通读 700 行并跑真机。
**2026-11-19 增量**：`AddStagePlan`（顺序 + 默认 FailureKind）钉住契约；`add()` 拆成
validate/module/mixin/coremod/modlist/construct/registry/event/pack/client/datapack 十个阶段方法，
共享状态收进 `Run`，每段失败返回 `Result`。随后用阶段表完成 53 的重排（PACK 前移到 client setup 任务与
payload 之前）。剩下的顺序风险从“700 行隐式流程”收敛为“阶段表 + 每段一个方法”。

### P0-6 客户端启动期重放是手写清单

17 处 `acceptEvent(new ...)`；历史上每漏一个就是一类 bug（tooltip、shader、层定义）。注册审计能**发现**未覆盖事件，
但不能**覆盖**。根治方向是让 mod loading 在真实 reload 上下文里跑（启动期语义），与 P0-5 的重排是同一件事。

**2026-11-19 增量**：统一出口落地为 `inspect/ReplayCatalog` + `hotadd/OneShotReplay.fire`。
21 个重放调用点传同一个事件名常量，helper 运行期校验名字与实际事件类；`RegistrationAudit` 的 `covered`
直接读 catalog，不再有第二份手写清单（顺带纠正：层定义 2026-11-18 已重放，却一直被审计标为 behavior）。
单测 `ReplayCatalogTest` 固定唯一性/关键成员/条数；真机 clientfixture、minimap、moonlight-lateat 无 mismatch 日志。
**剩余**：这仍是“逐事件手写 + 审计兜底”，不是让 mod loading 回到真实 reload 上下文；事件类替换时靠 warn 而非编译期约束。

### P1-12 不可逆与可回滚操作混流

注册表 commit、datapack、资源包、类 redefine 不可逆；失败只剩 `ROLLBACK_PARTIAL`/dirty 文案（问题 50 的回滚
`VerifyError` 就是回滚自身失败）。缺一个正式的 RunState（clean/dirty）来拒绝后续 add、提示重启。

**2026-11-19 增量**：`hotadd/RunState` 落地——一次 add 记录 registry commit / 自定义注册表窗口 / 构造 /
mixin 落定 / payload / capability / 命令重放；任一之后再失败即置脏（`reasonFor` 纯函数，单测 6 个），
后续 `add` 在 VALIDATE 以 `RUNTIME_DIRTY` 拒绝。客户端失败屏走重启建议；专用服由 `RunStateWarner`
按间隔（默认 60s，可调）重复在控制台告警。`-PverifyRunState` / `-PserverRunState` + `-PfailAfterRegistry`
是可重复证据。
**剩余**：① “构造后失败=永久脏”偏保守，若以后能量化“构造器未执行副作用”可以放宽；② 客户端只有热加载菜单
有常驻红字，主菜单入口本身还没有角标。脏原因已 i18n（`modreloader.dirty.reason.*`）。

### P1-13 反射面大且深

228 处、32 文件；能力守卫只验名字不验语义。问题 54 的修复一次性依赖三个事实：
`transforming_class_loader` 属性、`MixinLaunchPlugin extends Legacy`、锁字段在父类。升级 NeoForge/Mixin 的风险是结构性的。

**2026-11-19 增量（语义探针，不加抽象）**：
- `LaunchPluginMonitor` 下沉到 `reflect/`，新增 `probe()`：**真的解析出锁对象**（launcher 插件列表或
  TransformingClassLoader 回退），失败时给出具体原因。
- `Capabilities.checkMixinHotAdd` 用它做语义判定：解析不到锁对象 → `mixin_hotadd=missing`，
  `applyMixins` 在 transform 之前以 `CAPABILITY` 拒绝，而不是冒 issue 54 的死锁风险。
- 顺带移除 `hotadd` 里唯一的客户端类字面量（`Minecraft.class` 回退），改用自身 classloader 向上找
  `TransformingClassLoader`；专用服启动实测 `Mixin lock-order monitor: resolved`、`mixin_hotadd=ok`。
- `reflect/AgentTransformers`：只读扫描 Mixin agent 自己的 `Instrumentation` → `mTransformerList` →
  `MixinAgent$Transformer`，区分“没有 agent（无需保护）”与“agent 在但 transformer 找不到（结构变了）”。
  `MixinAgentGuard` 用它做安装，并在两处 redefine 前按结果拒绝：
  `LateAccessTransformers`（总是 redefine 已加载类）和 `MixinSupport`（目标已加载时）——
  agent 在但包不上时以可读错误失败，而不是冒 `ClassFormatError`（问题 40）。
**剩余**：其余 ~226 处仍是“名字检查 + 使用时报错”；下一优先级是 `Instrumentation` 可用性
（`JvmAccess.get()` 的自附加路径）与 agent/redefine 的语义边界。

### P1-14 并发模型是局部补丁

`ServerThreadHop`、render-thread 假设、worldgen 死锁、Mixin blanket 抑制、异步 reload 各管一段；
没有“阶段 → 线程 / 锁序”的全局规则。

**2026-11-19 增量**：[design.md](design.md)「线程、锁序与阶段契约」给出阶段→线程/锁序表（注册表 freeze 服务器线程、
资源重载渲染线程串行链、datapack 服务器线程、Mixin 直接 transform 的 A→B 锁序、redefine 必须 blanket），并写成
新增入口的检查单。问题 54 的锁对象由 `LaunchPluginMonitor.probe()` 语义守卫，问题 40 的 agent 由
`AgentTransformers` 扫描守卫。
**剩余**：没有并发正确性的自动测试（锁序只能靠探针 + 真机复现）；chunk 重镶仍归原版（问题 29 归边界）。

### P2-15 测试与文档

93→110 个单测几乎不碰引擎主流程；L2 缺失、harness 约 600 行重复、本机 noFocus 失效。
本文档的统计也已从 11-16 的版本刷新（见上表）。

## P0：会让我们「悄悄说错话」的

### ✅ 1. 反射面兼容性守卫（已完成 2026-11-15）

`capability/Capabilities` 在启动时**只读**探测十组锚点（2026-11-16 新增 `event_bus_rollback`），打一行报告：

```
[ModReloader] capabilities: module_injection=ok modlist_rebuild=ok network_adaptation=ok mixin_hotadd=ok registry_adaptation=ok datapack_reload=ok resource_packs=ok
```

- module：`ModuleClassLoader#resolvedRoots/packageLookup`、`ModuleLayer#modules/nameToModule/servicesCatalog`、`Configuration#modules/nameToModule`、`Module`/`ResolvedModule` 构造器
- modlist：`ModList#modFiles/sortedList/mods/setLoadedMods`、`ModLoader#modList/buildMods`
- network：`NetworkRegistry#setup/PAYLOAD_REGISTRATIONS`、`ChannelAttributes#getPayloadSetup/setPayloadSetup`
- mixin：`MixinEnvironment#getActiveTransformer/compatibility` + MixinRuntime 的 `MixinProcessorUtils` / `MixinConfigUtils`
- registry：`MappedRegistry#unfreeze`、`GameData#unfreezeData/freezeData/getRegistrationOrder`、`RegisterEvent` 构造器、`BlockCallbacks#BLOCKSTATE_TO_ID_MAP`
- datapack：`MinecraftServer#reloadResources/reloadableRegistries`、`PackRepository#addPackFinder`、`ResourcePackLoader#createPackForMod/readWithOptionalMeta/MOD_DATA_ID`
- packs：`ResourcePackLoader#modResourcePacks/createPackForMod/populatePackRepository`（服务端 `n/a`）

锚点缺失时 `HotAddEngine` 在 module/modlist/mixin 阶段前 fail-fast（`FailureKind.CAPABILITY` + 中英文案），带 `data/` 的 jar 还会在**任何变更之前**检查 datapack 锚点；资源包缺失只 warn + 跳过。**剩余**：① 预检/GUI 还没读这份报告（现在只有 add 时 gate + 日志）；② 名字对不等于行为对 —— 第一版把 `createPackForMod(IModFileInfo)` 写成 `ModFileInfo`，把能力误判为不可用，靠游戏内 harness 才发现；锚点清单本身仍需要游戏内回归。

### ✅ 2. 引擎层不再依赖两侧实现（已完成 2026-11-15）

`hotadd/PlatformHooks`（`keyMappingCount` / `acceptClientLifecycle` / `injectResourcePack` / `replayCommands` / `extraConnections`），由 `ClientBootstrap` 注册 `ClientPlatformHooks`、`ServerBootstrap` 注册 `ServerPlatformHooks`。`HotAddEngine`、`FmlEvents`、`ActiveConnections` 已无 `client`/`server` import；资源包注入移到 `client/ClientResourcePacks`。

顺带修掉一个真实缺口：客户端以前从不调用 `ServerCommands.install()`，所以**单人游戏里热加模组的命令根本不会注册**；现在集成服务器也会重放（标题屏无服务器时安静跳过），副作用见 status 已知问题 15。

### ✅ 3. `dev/` 移出发布包（已完成 2026-11-15）

`src/dev/java` + Gradle `dev` source set，作为同一个 mod 的第二个 source set 参与 dev run，但不进 `jar`。主源集与 dev 之间只剩一个**按名字反射**的入口 `DevEntry.install()`；`check` 依赖 `compileDevJava`，只坏 dev 也会让构建失败。**剩余**：`ServerBootstrap` 的 `autoAdd`/`probe` 触发还在主源集（它们调用的是命令也用的主 API，风险低）。

### ✅ 4b. 构造后事件监听器无法回滚（2026-11-16 已修游戏总线那一半）

`hotadd/EventBusSnapshot` 在构造前快照游戏总线（`NeoForge.EVENT_BUS`）的 owner → 监听器实例，失败回滚时**按实例**摘除
（公开的 `ListenerList#unregister(EventListener)`）并修剪 owner 表；`Capabilities.EVENT_BUS_ROLLBACK` 守卫两个反射名。
实测（`-PfailAfterConstruct`）：修前 38 → 41（+3 泄漏），修后 38 → 38（移除 2 个，LSP 的两个 `ClientHandler` 监听器）。
**仍未解决**：构造器里的其它副作用（静态状态、全局注册、线程）、post-construct 之后的重试（FML `ModLoadingException`）。

### ✅ 4. 注册表 id 重建与 chunk 发送的竞态（已完成 2026-11-16）

**症状**：进世界后热加众星捧月，客户端收到一个 chunk 包报 `IllegalArgumentException: No value with id -1`（`LinearPalette.read`），
随后服务端以「网络协议错误」断开玩家（status 已知问题 28）。同一次 add 刚执行过 `RegistryStage.commit`（清 `BLOCKSTATE_TO_ID_MAP` → `GameData.freezeData()`）。

**为什么**：id 重建在**调用线程**（dev harness 里是 render 线程）执行，而集成服务器线程可能同时在序列化 chunk；
`IdMapper.add()` 在重建窗口内对尚未重新加入的 BlockState 会给出 -1，服务器把 -1 写进 palette，客户端解码即失败。

**修法（2026-11-16 已实施）**：新增 `hotadd/ServerThreadHop`，把 commit / abort 里的「清 id + freeze」投到服务器线程
（`server.isSameThread()` 或还没有服务器时直跑，否则 `execute` + 等完成；超时回退到调用线程并 warn，队列里那份用原子 claim 防止重复执行）；
`RegisterEvent` 重放仍留在调用线程。验收：`-PverifyAddCommand + -PverifyGameplay` 连跑 3 次，
3/3 `GameplayCheck RESULT: PASS`、capability 五项 present、0 处 `No value with id`。
细节与剩余边界（单人共享同一张表，窗口只是被挪走/缩小）见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §11。

## P1：结构上不干净，但现在还能忍

4. ✅ **`command ↔ client` 循环**（已完成 2026-11-16）：`ReloaderCommands` 不再 import `ClientCommands`；
   `tree()` 变成 public、新增 `remember(dispatcher)`（dev harness 用），客户端在自己的入口
   （`client/ClientCommands#register`，由 `ClientBootstrap` 订阅）里做 `register(withGui(tree()))`。
   依赖方向现在是 **client → command** 单向。
   验证：`./gradlew build`；`runClient -PverifyAddCommand=<clientfixture> -PverifyGuiLayers -PverifyItemDecorators`
   → `dispatcher has /modreloader=true`、`AddCmdCheck RESULT: PASS`（2026-11-16 21:0x）。
5. **一次性事件重放没有统一出口**：命令（`RegisterCommandsEvent`）已有，payload（`RegisterPayloadHandlersEvent`）
   已用同样套路，贵妃级的 `RegisterEvent` 大概率还要用。三处各自处理"重放会让所有 mod 监听器再跑一遍"这个坑。
   → 一个 `OneShotReplay` 帮助类，集中记录这个副作用与降级策略。
   **2026-11-16 补了可见性那一半**：`inspect/RegistrationAudit` 在 `VALIDATE` 列出 jar 引用的每个启动期事件及其分级，
   已知崩溃类变更前拒绝（[client-registration-audit.md](features/client-registration-audit.md)）。
   同一天 `hotadd/CapabilityReplay` 补了第一个按收益排序的重放（`RegisterCapabilitiesEvent`，只投新容器）。
   这仍不能替代统一出口——它只告诉我们「还差哪个」，每个重放仍是手写的。
6. **harness 样板重复**：4 个检查各自复制"建世界 / 连服 / 等遮罩"逻辑（`AddCommandCheck`、`PayloadCheck`、
   `RemotePayloadCheck`、`MenuEntryCheck`），约 600 行里有大半是重复。现在它们都在 `src/dev`，抽取更安全。
   **2026-11-19 增量**：抽出 `dev/TestWorld`（真实建世界屏 → 按 Create → 等玩家 + settle）；
   `PayloadCheck`、`AddCommandCheck`、`AtSpikeCheck`、`CapabilityCheck`、`CreativeTitleCheck`（含 warm-up 的
   两次建世界）与 `LanScreenCheck` 全部迁入。已实测：payload / clientfixture / AtSpike / capabilities /
   creative-at-title(+warm) 全 PASS；`LanScreenCheck` 因本机缺 `lanserverproperties` 未跑。
   **剩余**：`TestConnection`（RemotePayloadCheck / MpRegistry* 的连服样板）未抽；L2 断言与 noFocus 不稳未动。
7. ✅ **`design.md` 落后于双端化**（已完成 2026-11-15）：补了「双端化与引擎边界」一节，写明 `PlatformHooks`、
   `Capabilities`、`dev` 源集与两侧 bootstrap 的职责。

9. ✅ **包依赖反向边（2026-11-19）**：`client → server`、`diagnose → ui`、`command → ui`、`ui → client`、
   `capability → hotadd` 全部消除：
   - `hotadd/UnsafeAccess`、`hotadd/JvmAccess` → `reflect/`（纯 JVM 工具）；
   - `hotadd/MixinRuntimeApi` → `inspect/`；
   - `ui/ModReloaderI18n` → `i18n/`（UI 与 diagnose 共用）；
   - `server/ServerCommands` → `command/`（两侧共用同一份命令重放）；
   - `client/{PendingAdd,Relauncher,RelaunchScripts,RelaunchFlags,JbrProvisioner}` → `launch/`（UI 与 bootstrap 共用）。
   `ArchitectureTest` 直接扫 `src/main/java`，任何新增的越层 import 都失败（111 测试）。
   回归：`-PverifyRelaunchPlan=true` PASS、`hud` MATCH、`./gradlew build` 绿。

## P2：便宜且能防止回归

8. ✅ **没有 Java 单测**（已完成 2026-11-15）：`src/test/java` 三个 JUnit 测试 —— ① i18n 键集一致 + `%s`
   占位符一致；② 每个 `FailureKind` 都有 `.summary/.suggestion`（`FailureKeys` 把映射从
   `FailureAnalyzer` 抽出来，才能不加载游戏测试）；③ summary/suggestion 成对。`./gradlew test` 3 passed。
   2026-11-16 补了 3 个 JiJ 路径校验测试（`JarInJarTest`：绝对路径/`..`/非 jar 被拒、抽取文件名净化），
   6 个命令短名测试（`JarPathResolverTest`），11 个注册审计分级测试（`RegistrationAuditTest`；纯规则，不需要 ASM/游戏），6 个 AT 规则测试（`AccessTransformerRulesTest`），现在 **93 passed**。
9. ✅ **`libs/` 编译依赖**（已完成 2026-11-16，已知问题 12）：新增 `hotadd/MixinRuntimeApi`（trusted lookup 调时解析 mr 的 10 个方法 + `probe()` 给能力守卫）与 `hotadd/ClassBytes`（自己做「读已加载类字节」，不再借 mr 的 `InstUtils`），删掉 `compileOnly files('libs/...')`。
   证据：移走 `libs/` 后 `./gradlew clean build` 通过（6 tests）；有 mr 的妃级热加/回滚/重试 PASS，无 mr 时妃级仍被拦。`libs/` 目录已无引用，可删。
10. ✅ **CI 已在远端跑通**（2026-11-19 复核）：GitHub Actions 共 36 次 run，最新一次对应 `4557e7b`
    （P1-13 agent 探针）为 `success`；下文是当初首次接入时的记录。`.github/workflows/ci.yml` 两个 job —— 分析器（`python3 -m unittest`）
    与运行时（Temurin 21 + `./gradlew build`，即 §6 的「完成」定义：单测 + dev harness 编译）。
    （历史注记）当时本机未触发过 Actions；而且 `./gradlew build` 在干净机器上
    要下载 NeoForge 反编译产物（首次 10–30 分钟），workflow 里给了 60 分钟超时。
    另外本机 `maven.neoforged.net` 的 DNS 会解析到无关 IP，`build.gradle` 已用 `exclusiveContent` 把 JUnit
    固定到 Maven Central，避免依赖解析卡在 `SYN_SENT`。

11. ✅ **`runServer` 未接 JBR**（已完成 2026-11-16，已知问题 22）：`build.gradle` 的 JBR 配置抽成 `useJbr` helper，
    两个 run 任务都用 JBR 21 + `-XX:+AllowEnhancedClassRedefinition`（JBR 缺失时 warn，不静默降级）。
    验证：专用服 `enhancedRedefinition=true`、ZeroCore2 `redefined net.minecraft.world.inventory.Slot` 并 `hot-added`；客户端回归 PASS。

## 刻意不做（避免过度设计）

- 不引入 DI 容器、不抽象事件总线、不做通用插件系统。
- 不把 `HotAddEngine.add` 一次性重写成阶段框架：回滚与失败分类已经验证过，重写收益不抵风险。
  **2026-11-19 起改为渐进式**：先阶段表（`AddStagePlan` + 单测），再逐段抽方法，每次行为可回归；
  53 的重排（pack/reload → payload）是这条渐进路线的第一个真实用例。
- 不为"未来可能支持任意 mod"预留适配器接口——等贵妃级真的按 mod 写适配器时再抽。

## 建议的动手顺序

1. ✅ 阶段表与顺序测试（2026-11-19：`AddStagePlan` + `AddStagePlanTest`，109 测试绿）
2. 逐段抽方法：VALIDATE/MODULE 先抽（纯校验，最安全），再 MIXIN/COREMOD
3. 借问题 53 重排 PACK 与 payload 阶段（用阶段表约束，不再散落修改）
4. `OneShotReplay` 统一出口（P1-5），同时覆盖 client 重放清单
5. harness 抽 `TestWorld` / `TestConnection`（P1-6）
6. CI 远端正跑一次（P2-10）
7. 旧条目：P1-4 ✅、P2-11 ✅（见下）
