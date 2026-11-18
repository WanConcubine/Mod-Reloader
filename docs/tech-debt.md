# 技术债与架构升级点

> 只记录**结构性**问题与可升级点；实现进度见 [status.md](status.md)。
> 原则：先修"会让我们说错话/悄悄坏掉"的，再修"好改但收益小"的；不为未来可能的需求预先抽象。

## 事实盘点（2026-11-16，P1/P2 收口之后）

| 指标 | 现状 |
|---|---|
| Java 代码 | **17624 行 / 13 个包**（`main` 9966 / 65 文件 + `dev` 7118 / 30 文件 + `test` 540 / 5 文件） |
| `dev/` 夹具 | **30 文件 / 7118 行 ≈ 40%**，独立 `src/dev/java` source set；**不在发布 jar 里**（jar 内 0 条 `dev/modreloader/dev/`） |
| 反射 / Unsafe 站点 | **19 个文件**（mixin、ModList、module、网络、资源包、注册表同步、事件总线快照、JVM 等） |
| Java 单测 | **29**（`./gradlew test`，不需要游戏；i18n 3 / 命令短名 6 / JiJ 3 / AT 规则 6 / 注册审计 11） |
| 语言文件 | `en_us` / `zh_cn` 各 **187 key**，键集与 `%s` 占位符由单测核对 |
| 包依赖 | `hotadd` **不再**依赖 `client` / `server`；`command ↔ client` 循环**已消除**（2026-11-16：client → command 单向）；剩 `client → server`（集成服务器的命令重放）、`diagnose → ui`（文案） |

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
   → 抽 `dev/TestWorld`（建世界 + 等 ready）与 `dev/TestConnection`（连服 + 等 join）。
7. ✅ **`design.md` 落后于双端化**（已完成 2026-11-15）：补了「双端化与引擎边界」一节，写明 `PlatformHooks`、
   `Capabilities`、`dev` 源集与两侧 bootstrap 的职责。

## P2：便宜且能防止回归

8. ✅ **没有 Java 单测**（已完成 2026-11-15）：`src/test/java` 三个 JUnit 测试 —— ① i18n 键集一致 + `%s`
   占位符一致；② 每个 `FailureKind` 都有 `.summary/.suggestion`（`FailureKeys` 把映射从
   `FailureAnalyzer` 抽出来，才能不加载游戏测试）；③ summary/suggestion 成对。`./gradlew test` 3 passed。
   2026-11-16 补了 3 个 JiJ 路径校验测试（`JarInJarTest`：绝对路径/`..`/非 jar 被拒、抽取文件名净化），
   6 个命令短名测试（`JarPathResolverTest`），11 个注册审计分级测试（`RegistrationAuditTest`；纯规则，不需要 ASM/游戏），6 个 AT 规则测试（`AccessTransformerRulesTest`），现在 **90 passed**。
9. ✅ **`libs/` 编译依赖**（已完成 2026-11-16，已知问题 12）：新增 `hotadd/MixinRuntimeApi`（trusted lookup 调时解析 mr 的 10 个方法 + `probe()` 给能力守卫）与 `hotadd/ClassBytes`（自己做「读已加载类字节」，不再借 mr 的 `InstUtils`），删掉 `compileOnly files('libs/...')`。
   证据：移走 `libs/` 后 `./gradlew clean build` 通过（6 tests）；有 mr 的妃级热加/回滚/重试 PASS，无 mr 时妃级仍被拦。`libs/` 目录已无引用，可删。
10. ⚠️ **CI 已写、还没在远端跑过**（2026-11-16）：`.github/workflows/ci.yml` 两个 job —— 分析器（`python3 -m unittest`）
    与运行时（Temurin 21 + `./gradlew build`，即 §6 的「完成」定义：单测 + dev harness 编译）。
    **未验证**：本机没有 push 权限/没触发过 Actions，所以「CI 绿」这件事仍未被证明；而且 `./gradlew build` 在干净机器上
    要下载 NeoForge 反编译产物（首次 10–30 分钟），workflow 里给了 60 分钟超时。
    另外本机 `maven.neoforged.net` 的 DNS 会解析到无关 IP，`build.gradle` 已用 `exclusiveContent` 把 JUnit
    固定到 Maven Central，避免依赖解析卡在 `SYN_SENT`。

11. ✅ **`runServer` 未接 JBR**（已完成 2026-11-16，已知问题 22）：`build.gradle` 的 JBR 配置抽成 `useJbr` helper，
    两个 run 任务都用 JBR 21 + `-XX:+AllowEnhancedClassRedefinition`（JBR 缺失时 warn，不静默降级）。
    验证：专用服 `enhancedRedefinition=true`、ZeroCore2 `redefined net.minecraft.world.inventory.Slot` 并 `hot-added`；客户端回归 PASS。

## 刻意不做（避免过度设计）

- 不引入 DI 容器、不抽象事件总线、不做通用插件系统。
- 不把 `HotAddEngine.add` 拆成阶段框架：它是 360 行的顺序流程，但回滚与失败分类已经验证过，重写收益不抵风险。
- 不为"未来可能支持任意 mod"预留适配器接口——等贵妃级真的按 mod 写适配器时再抽。

## 建议的动手顺序

1. ✅ P1-4（`command ↔ client` 循环，客户端装饰命令树；已完成 2026-11-16）
2. P1-5（`OneShotReplay`，同时把贵妃级的 `RegisterEvent` 重放位置留好）
3. P1-6（harness 抽 `TestWorld` / `TestConnection`）
4. P2-10（CI：分析器单测 + `./gradlew build`；`libs/` 反射化已于 2026-11-16 完成）
5. P2-11（✅ 已完成 2026-11-16）
