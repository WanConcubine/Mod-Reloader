# 技术债与架构升级点

> 只记录**结构性**问题与可升级点；实现进度见 [status.md](status.md)。
> 原则：先修"会让我们说错话/悄悄坏掉"的，再修"好改但收益小"的；不为未来可能的需求预先抽象。

## 事实盘点（2026-11-15，架构收口后）

| 指标 | 现状 |
|---|---|
| Java 代码 | **6935 行 / 11 个包**（`main` 4834 + `dev` 2101 + `test` 105） |
| `dev/` 夹具 | **9 文件 / 2101 行 ≈ 30%**，独立 `src/dev/java` source set；**不在发布 jar 里**（jar 内 0 条 `dev/modreloader/dev/`） |
| 反射 / Unsafe 站点 | 8 个文件（mixin、ModList、module、网络、资源包、JVM） |
| Java 单测 | **3**（`./gradlew test`，不需要游戏） |
| 语言文件 | `en_us` / `zh_cn` 各 **134 key**，键集与 `%s` 占位符由单测核对 |
| 包依赖 | `hotadd` **不再**依赖 `client` / `server`；剩 `command ↔ client`、`client → server`（集成服务器的命令重放）、`diagnose → ui`（文案） |

## P0：会让我们「悄悄说错话」的

### ✅ 1. 反射面兼容性守卫（已完成 2026-11-15）

`capability/Capabilities` 在启动时**只读**探测六组锚点，打一行报告：

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

## P1：结构上不干净，但现在还能忍

4. **`command ↔ client` 循环**：`ReloaderCommands` 为了 `gui` 节点 import 了 `ClientCommands`。
   → 让客户端注册时装饰命令树（`ClientBootstrap` 里 `register(ClientCommands.withGui(tree()))`），
   `command` 只保留共享树。
5. **一次性事件重放没有统一出口**：命令（`RegisterCommandsEvent`）已有，payload（`RegisterPayloadHandlersEvent`）
   已用同样套路，贵妃级的 `RegisterEvent` 大概率还要用。三处各自处理"重放会让所有 mod 监听器再跑一遍"这个坑。
   → 一个 `OneShotReplay` 帮助类，集中记录这个副作用与降级策略。
6. **harness 样板重复**：4 个检查各自复制"建世界 / 连服 / 等遮罩"逻辑（`AddCommandCheck`、`PayloadCheck`、
   `RemotePayloadCheck`、`MenuEntryCheck`），约 600 行里有大半是重复。现在它们都在 `src/dev`，抽取更安全。
   → 抽 `dev/TestWorld`（建世界 + 等 ready）与 `dev/TestConnection`（连服 + 等 join）。
7. ✅ **`design.md` 落后于双端化**（已完成 2026-11-15）：补了「双端化与引擎边界」一节，写明 `PlatformHooks`、
   `Capabilities`、`dev` 源集与两侧 bootstrap 的职责。

## P2：便宜且能防止回归

8. ✅ **没有 Java 单测**（已完成 2026-11-15）：`src/test/java` 三个 JUnit 测试 —— ① i18n 键集一致 + `%s`
   占位符一致；② 每个 `FailureKind` 都有 `.summary/.suggestion`（`FailureKeys` 把映射从
   `FailureAnalyzer` 抽出来，才能不加载游戏测试）；③ summary/suggestion 成对。`./gradlew test` 3 passed。
9. **`libs/` 编译依赖**（已知问题 12）：`compileOnly files('libs/...')` + `libs/` 被忽略 → fresh clone 编不过。
   → 反射化那 5 个调用点，或把 jar 放进仓库（需所有者拍板）。
10. **没有 CI**：分析器的 Python 测试和 `./gradlew test` 都能跑；Java 侧完整 `./gradlew build` 仍要等 P2-9。
    另外本机 `maven.neoforged.net` 的 DNS 会解析到无关 IP，`build.gradle` 已用 `exclusiveContent` 把 JUnit
    固定到 Maven Central，避免依赖解析卡在 `SYN_SENT`。

## 刻意不做（避免过度设计）

- 不引入 DI 容器、不抽象事件总线、不做通用插件系统。
- 不把 `HotAddEngine.add` 拆成阶段框架：它是 360 行的顺序流程，但回滚与失败分类已经验证过，重写收益不抵风险。
- 不为"未来可能支持任意 mod"预留适配器接口——等贵妃级真的按 mod 写适配器时再抽。

## 建议的动手顺序

1. P1-4（`command ↔ client` 循环，客户端装饰命令树）
2. P1-5（`OneShotReplay`，同时把贵妃级的 `RegisterEvent` 重放位置留好）
3. P1-6（harness 抽 `TestWorld` / `TestConnection`）
4. P2-9 → P2-10（`libs/` 反射化，然后 CI 跑分析器单测 + `./gradlew build`）
