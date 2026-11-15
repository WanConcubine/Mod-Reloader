# 项目状态

> **唯一状态源。** 每次会话先读这里；改状态只改这里，不要在各处复制。过程记录见 [history.md](history.md)。
> 最后更新：2026-11-15

## 一句话

只读分析器 v0 可用；**A0（T2 热加）已验证**；**A1.2 主路径与失败回滚已在 NeoForge 1.21.1 dev client 验证**（`lanserverproperties`：4 mixin、布局变更、LAN 屏可用；post-ModList / post-pack 失败可恢复并重试）。仍差**构造后监听器无法注销、存档回读未验证**，按契约尚不算完成。**GUI/监听初版**（检测→预检→弹窗/toast→加载/失败屏）已验证 EasyLAN、坏 jar、Ex Nihilo T4 三个场景。**双端化**：dedicated server 能加载并热加 T2；命令通过重放 `RegisterCommandsEvent` 生效（93→94）；payload 适配 E1/E2/E3 全部通过（含跨进程）。**架构收口（2026-11-15）**：引擎不再依赖两端（`PlatformHooks`）、启动校验反射锚点（`Capabilities`）、`dev/` 移出发布包、3 个无游戏 Java 单测。**T4 注册表热加（实验性，server-only）已验证**：热加一个方块 + BlockItem、放置存盘、重启读档 PASS；仍限 add-only、无客户端/datapack、UI 未放行。T4 软重启未开始；一次性启动事件与注册表仍不支持（[ADR-0007](decisions/0007-dual-side-server-hotadd.md)）。

## 当前目标

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| 分析器 | Fabric + NeoForge，语料与校准锁 1.21.1 |
| 运行时首发 | NeoForge 1.21.1 |
| 首个 dogfood | `../Ex-Nihilo-Satellitium`（T4 内容模组） |
| 身份 | **runtime，不是 mod**：热加载第三方模组，不替代它们的功能（ADR-0005） |
| 产品锚点 | 热加载缺失的客户端工具模组；LAN 是第一个场景（[use-cases.md](use-cases.md)） |
| 论文 | 短期不做（ADR-0004） |

原因见 [ADR-0001](decisions/0001-target-1.21.1-neoforge.md)：作者自有项目是 NeoForge 1.21.1；其 dev 整合包 9 个模组里 6 个是 T4/T5；T3 工具价值有限。

## 表尺（可复现）

| 项 | 结果 | 命令 / 来源 |
|---|---|---|
| 分析器单测 | **9 passed** | `cd tools/analyzer && python3 -m unittest discover -s tests -v` |
| 合成 jar | 2 classes / 1 mixin → **T3, 0.65** | 临时合成样本（不入库，可按 tests 规则重建） |
| 公开 7-jar 语料 | Fabric 5 + NeoForge 2；**T3 3 / T4 1 / T5 3**；1.9 s | `/tmp/corpus_stable`（临时，不入库） |
| Ex Nihilo 1.5.0 | **T4, 0.78**；0.11 s | `../Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar` |
| `run/mods` 9 个模组 | **T2 2 / T3 1 / T4 4 / T5 2**；5.1 s | `~/Projects/Ex-Nihilo-Satellitium/run/mods` |
| LAN 3 个模组 | **T2 1 / T3 2**；0.05 s | `lanserverproperties`、`mcwifipnp`、`EasyLAN`（1.21.1 NeoForge） |
| 启动到进世界 | **约 83 s**（dev 环境） | `run/logs/latest.log`：23:19:57.701 ModLauncher → 23:21:20.791 世界加载 |
| **A0 热加 EasyLAN** | **isLoaded=true, container=true；resource pack `mod/easylan` 选中并重载；keyMappings 34→34；JBR 下复测通过；无异常** | 见 [a0-hot-add.md](features/a0-hot-add.md) |
| **A1.2 全流水线（主路径）** | **isLoaded/container=true；4 mixins；UUIDUtil/AbstractWidget redefine；ShareToLanScreen/PlayerList 首载织入；接口合并；LAN 屏打开，port=25565；3 个 EditBox；截图** | `runClient -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar -PverifyLan=true`；`run/screenshots/a1-lan-screen.png` |
| **A1.2 失败回滚** | **post-ModList：ModList/container/module/config/4 classes/资源包恢复，retry add PASS；post-pack：pack 移除 + classes 恢复；构造 listener 不可注销** | `-PverifyRollback=true -PfailAfterModList=true` / `-PfailAfterPack=true`；`RollbackCheck` |
| **GUI / 监听** | **EasyLAN 检测→加载、坏 jar 失败屏、Ex Nihilo T4 不可加载、LSP 回滚屏、ignore→restore（1→0）、世界内 toast、放入→删除→拷回 均 PASS；jar 内无 key 泄漏** | 见 [gui-mod-watcher.md](features/gui-mod-watcher.md)；截图在 `run/screenshots/` |
| **JDK / JBR 21** | **编译用 Gradle toolchain Temurin 21；运行用 JBR 21.0.11，`enhancedRedefinition=true`；系统 JDK 17/8** | `java -version`；`runClient` 自动切 JBR |
| **MixinRuntime 可选** | **无 mr：启动正常、warn + toast、EasyLAN（T2）可热加；有 mr：LSP（T3）`registered=[lsp.mixins.json]`、redefined 2 / eager 2** | 2026-11-15 15:32 / 15:33：移走 / 放回 `run/mods` 的 mr |
| **世界内 add（成功 / 缺 mr 被拒）** | **成功：返回 1、`isLoaded=true`、RESULT: PASS；缺 mr：返回 0、`isLoaded=false`、错误写明 `MixinRuntime (mr)`、无 rollback 错误** | 2026-11-15 15:38 / 15:39：`-PverifyAddCommand`（后者加 `-PverifyAddCommandExpectFailure`） |
| **服务端热加** | **T2 夹具可热加；命令重放 93→94；tick 到达；payload E1/E2/E3 通过；dist 门禁拦截并干净回滚；服务端 capabilities `resource_packs=n/a`** | 见 [server-hotadd.md](features/server-hotadd.md) |
| **引擎边界（P0-2）** | **`hotadd` 不再 import `client`/`server`；`PlatformHooks` 由两侧 bootstrap 注册；客户端 add / 服务端热加 / payload E2 全 PASS** | 2026-11-15 20:34 / 21:02 / 21:04 |
| **反射锚点守卫（P0-1）** | **client：`module_injection=ok modlist_rebuild=ok network_adaptation=ok mixin_hotadd=ok resource_packs=ok`；server：`resource_packs=n/a`；两端 add 仍 PASS** | 2026-11-15 21:06 / 21:08：`[ModReloader] capabilities:` |
| **Java 单测（P2-8）** | **3 passed**：i18n 键集 + `%s` 占位符一致、每个 `FailureKind` 都有 summary/suggestion、两者成对 | `./gradlew test`；[LangKeysTest.java](../src/test/java/dev/modreloader/LangKeysTest.java) |
| **T4 注册表热加（server-only）** | **blocks 1060→1061、items 1333→1334、states 26684→26685，stoneId 1→1 稳定；放置 `(0,100,0)` 存盘；重启读档 `isT4Block=true`、entryId/stateId 不变，RESULT: PASS** | 2026-11-15 21:48：`runServer -PautoAdd=/tmp/t4fixture/t4fixture-1.0.0.jar -Pt4fixtureSelftest=true` → fixture 放进 `run/mods` → `runServer -Pt4fixtureSelftest=true -Pt4fixtureSelftestRead=true`；见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md) |
| **T4 注册表探针（只读）** | **BLOCK 1060 / ITEM 1333 均 frozen；`registryAccess(BLOCK)` 与 `BuiltInRegistries.BLOCK` 同实例；blockstate id map 26684（stone=1）；89 个内置注册表；`RegisterEvent` 构造器包私有；`unfreeze()` public；裸 re-freeze 后 stone 1→26685（id 漂移）** | 2026-11-15 21:28 / 21:30：`runClient -PverifyAddCommand=... -PverifyRegistryProbe=true [-PverifyRegistryRefreeze=true]`；见 [research-t4-registry.md](research-t4-registry.md) |
| **dev 移出发布包（P0-3）** | **生产 jar 内 `dev/modreloader/dev/` 0 条目；`check` 会编译 `src/dev`；dev harness 仍可用（AddCmdCheck PASS）** | 2026-11-15 21:07：`./gradlew build` + `runClient -PverifyAddCommand=...` |

## 验证状态

### 已验证

- **分析器**：合成 jar、公开 7-jar、Ex Nihilo 1.5.0、`run/mods` 都能跑；JSON / Markdown 输出可用；9 个单测。
- **自附加 + 类重定义**（JDK 17，macOS arm64）：不依赖 `-javaagent` / `-Djdk.attach.allowAttachSelf` 拿到 `Instrumentation`；方法体替换 0.7–5 ms；新增方法被 JVM 拒绝。见 [PoC](../experiments/self-attach-poc/README.md)。
- **Java 21 / JBR 21**：Gradle 用 Temurin 21 toolchain；`runClient` 自动切 JBR 21.0.11 + `-XX:+AllowEnhancedClassRedefinition`（`enhancedRedefinition=true`）。
- **A0**：dev client 热加 `EasyLAN`（T2，无 mixin）；`ModList.isLoaded=true`、容器存在、`@Mod` 构造 + 客户端事件无异常；资源包选中并重载。详见 [a0-hot-add.md](features/a0-hot-add.md)。
- **A1.0 / A1.1 / A1.2**：probe → spike → 全流水线；`lsp.mixins.json` 运行时注册、JBR redefine 已加载 target、首载织入未加载 target、`postInitialise` 修复接口合并、LAN 屏控件可用。失败回滚覆盖 post-ModList / post-pack（module / ModList / config / 类的原始字节 / 资源包），post-ModList 重试成功。详见 [a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)。
- **监听 / 预检 / GUI**：后台轮询 `run/mods` + `run/modreloader/inbox`；稳定检测 + 基线；`ModInspector` 预测 T2–T5；主菜单弹窗、世界中只 toast；失败打开 `LoadFailureScreen`；配置与忽略/失败记录独立 JSON；i18n `en_us`/`zh_cn`（各 125 key，键集由单测核对）。详见 [gui-mod-watcher.md](features/gui-mod-watcher.md)。
- **双端化**：dedicated server 加载、热加 T2 夹具、命令重放（93→94）、tick 事件到达；payload 适配 E1/E2/E3（含跨进程）。机制与证据见 [server-hotadd.md](features/server-hotadd.md)；边界与副作用见已知问题 13/14。
- **T4 注册表热加（实验性，server-only）**：`hotadd/RegistryStage` 在构造前 `unfreezeData()`、构造后按 `getRegistrationOrder()` 逐注册表按容器重放 `RegisterEvent`、清空 BlockState id 映射再 `freezeData()`；fixture 端到端 PASS（见表尺）。限制：add-only、不可回滚、仅服务端、datapack 与客户端未做、UI 仍拒绝 T4。见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md)。
- **T4 调研（只读探针 + 受控 re-freeze）**：注册表与同步机制见 [research-t4-registry.md](research-t4-registry.md)；结论是「机制可行，但只能 add-only、单向提交、存档绑定该 mod，且 datapack 内容仍是空白」，裸 re-freeze 会移动 BlockState id（必须先清空重建）。[ADR-0008](decisions/0008-t4-registry-adaptation-poc.md) 为 Proposed。
- **架构收口（2026-11-15）**：
  - **P0-2 引擎边界**：`HotAddEngine` / `FmlEvents` / `ActiveConnections` 不再引用 `Minecraft`、`ServerCommands`、`ResourcePackLoader`、`ClientConnections`；改由 [PlatformHooks](../src/main/java/dev/modreloader/hotadd/PlatformHooks.java)（+ [ClientPlatformHooks](../src/main/java/dev/modreloader/client/ClientPlatformHooks.java) / [ServerPlatformHooks](../src/main/java/dev/modreloader/server/ServerPlatformHooks.java)）提供 key mapping、客户端生命周期、资源包、命令重放、本端额外连接。顺带修掉缺口：**客户端以前从不重放 `RegisterCommandsEvent`**，单人游戏里热加模组的命令根本不存在；现在集成服务器也重放（标题屏安静跳过）。
  - **P0-1 能力守卫**：[Capabilities](../src/main/java/dev/modreloader/capability/Capabilities.java) 启动时只读探测五组锚点并打一行报告；module/modlist/mixin 缺失时 `FailureKind.CAPABILITY` fail-fast，资源包只 warn + 跳过。局限见已知问题 16。
  - **P0-3 dev 源集**：8 个 harness 在 `src/dev/java`（独立 source set，不进发布 jar）；主源集只按名字反射 [DevEntry](../src/dev/java/dev/modreloader/dev/DevEntry.java)；`check` 依赖 `compileDevJava`。
  - **P2-8 单测**：3 个 JUnit 测试（无游戏）；`FailureAnalyzer` 的键映射抽成 [FailureKeys](../src/main/java/dev/modreloader/diagnose/FailureKeys.java)。

### 未验证

- **EasyLAN 的真实 GUI**：`@Mod` 构造器已注册 `ScreenEvent.Opening` 监听器，但没有人工点过界面按钮；`keyMappings` 未增加。A1.2 的 LAN 屏验证只覆盖 `lanserverproperties`。
- **生产 jar / 第三方启动器**：只在 dev client 测过。
- **`/modreloader gui` / `scan` 命令**：已注册，未自动验证（`/modreloader add`、世界内 toast、主菜单入口与忽略恢复已有 harness）。
- **忽略/失败 JSON 跨重启持久化**：GUI 内 ignore → restore 已自动验证，重启客户端后是否仍记住未验证。
- **已加载后删除 jar**：行为已知（mod 仍留在 ModList，没有卸载路径，不会再成为候选），但没有跑过专门检查。
- **T4（注册表内容）**：未实现，只在调研阶段（[research-t4-registry.md](research-t4-registry.md)）；没有注册表写入实验。
- **Tier 预测 vs 人工标注**：没有 ground truth，准确率未知。
- **Fabric intermediary 映射**：`class_XXX` 目标识别仍退化。
- **多版本 / 多加载器**：1.21.1 之外未测试。
- **存档一致性**：还没有写操作，谈不上验证。

## 已知问题 / 坑

1. **静态分析会漏**：`mekanismgenerators` 被标成 T2，但它通过 Mekanism API 注册内容——跨模组注册抓不到。
2. **T5 可能过保守**：AE2 因渲染 model mixin 被判 T5；是否真的必须完整重启，需要运行时验证。
3. **Fabric 生产 jar 是 intermediary 名**：`net.minecraft.class_310`，核心类识别退化。
4. **Jar-in-Jar 只拆一层**；更深 bundle 不展开。
5. **Python 分析器 vs 未来 Java 模组**：规则需要抽成声明式配置，避免两份逻辑漂移。
6. **LAN 模组是 T3 布局变更**：会给 `MinecraftServer` / `PauseScreen` / `ShareToLanScreen` 加字段；需要 JBR 或 sidecar；`online-mode` 可能要重启集成服务器才生效。详见 [use-cases.md](use-cases.md)。
7. **A1.2 仍未产品化**：依赖 JBR + `-XX:+AllowEnhancedClassRedefinition`；依赖 MixinRuntime（第三方前置，见 12）。失败回滚只覆盖 post-ModList / post-pack：构造后的构造器和事件监听器无法注销；JVM 不能 undefine module（Module 对象留在缓存供重试）；被移除的 mixin config 保留但 inactive。存档回读、第二个 T3 模组、真实 jar 启动仍未验证。
8. **Git 仓库已建立**（2026-11-15）：`main`、`origin` → `github.com/WanConcubine/Mod-Reloader`；尚未加 CI、tag、release，证据截图仍只在 `run/`（被忽略）。
9. **没有人工标注集**：规则准确率、混淆矩阵都还没有。
10. **没有删/更新路径**：只实现了 add；运行时删除/卸载/更新未实现、未测试。已加载的类（尤其已织入 mixin 的）无法靠删 jar 卸载；需要 classloader 隔离或 T4 软重启。
11. **GUI/监听初版限制**：只有 `zh_cn` / `en_us`；异常消息/技术值仍是原始英文（标签已本地化）；最多直接显示 7 个候选；`/modreloader gui`/`scan`、失败重试未自动验证；T4/T5 只提示重启，不尝试加载。
12. **MixinRuntime 是 T3 的第三方前置，已改为可选依赖**（2026-11-15）：`mr` 从 `required` 改成 `type="optional"`，缺它时游戏能启动、T2 照常，由我们给出人话提示（toast + 日志 + 预检 + 失败屏，统一 `MixinRuntime (mr)`）；`required` 时 FML `ModSorter#formatDependencyError` 只打印原始 modId 且直接终止，我方无法提示。`MixinSupport`（直接 import mr）隔离在 [MixinStage](../src/main/java/dev/modreloader/hotadd/MixinStage.java) 之后，`HotAddEngine` 只用 `MixinRuntimeSupport` 探测。**构建仍需要** `libs/` 的 jar（`compileOnly`，被忽略）→ fresh clone 编不过（24 个符号错误）；代码只用 5 个能力（transformer、`transformClassBytes`、`getClassBytes`、`MixinConfig.prepare/onSelect`、`MixinProcessor.configs/pendingConfigs`），尚未反射化。无 mr 启动（15:32）与有 mr 的 T3 回归（15:33）都验证过。
13. **`mods.toml` 的 side 字段不可信**（2026-11-15 实测）：EasyLAN 声明 `side="BOTH"`，但 `@Mod` 构造器注册了 `ScreenEvent` 监听，在专用服上直接抛 `Attempted to load class ... for invalid dist DEDICATED_SERVER`。NeoForge 在服务端是**主动拒绝**客户端类。判断"能否上服务端"必须扫字节码里的 `net/minecraft/client/**` 引用（含 provider 类本身），不能只看 metadata；扫描也只能减少误判，仍需运行期兜底（已有回滚）。
14. **服务端：命令靠重放生效，payload 可适配但有边界**：重放 `RegisterCommandsEvent` 后命令真的进了 dispatcher（93→94），重复触发的 `ServerTickEvent` 也能到达。**副作用**：重放是全局总线事件，所有已加载 mod 的监听器会再跑一遍；Brigadier 合并节点所以实测幂等，但有副作用的监听器会执行两次。payload 通过反射解冻 `NetworkRegistry.setup` + 重放 `RegisterPayloadHandlersEvent` 注册，E2 起还能把新 channel 补进已建立连接的 `PayloadSetup`；E3 跨进程验证。**仍缺**：① 握手（两端必须是同一个 jar，人工保证）；② 与 `@ApiStatus.Internal` 对着干，锁 1.21.1；③ `ServerStartedEvent` 之类一次性事件、注册表内容（T4）。
15. **客户端重放 `RegisterCommandsEvent` 的副作用**（2026-11-15）：补上单人游戏命令注册的同时，集成服务器每次热加都会让**所有**已加载模组的命令监听器再跑一遍（与 14 同源）。热加发生在标题屏（无集成服务器）时不重放。
16. **Capabilities 只查名字，不查行为**：字段/方法/构造器存在且签名对上，不等于语义没变。它的价值是「框架漂移时立刻给人话失败而不是静默跳过」，不能代替游戏内 harness；锚点清单自己也会写错（第一版把 `createPackForMod(IModFileInfo)` 写成 `ModFileInfo`，被 harness 抓到）。
17. **T4 的第一个坑：裸 unfreeze/freeze 会移动 BlockState id**（2026-11-15 实测）：不注册任何东西，`GameData.unfreezeData()` → `freezeData()` 之后 `stone` 的 state id 从 1 变成 26685。原因是 `BlockCallbacks.onBake` 用 `IdMapper.add()` 重建映射，而 `add()` 无条件分配新 id（旧 id 槽位保留）。适配路线必须先清空 `BlockCallbacks.BLOCKSTATE_TO_ID_MAP` 再 freeze，否则在 re-freeze 之后写出的区块会用重启后不存在的 id。细节见 [research-t4-registry.md](research-t4-registry.md)。
18. **T4 目前只到 server-only add-only**（2026-11-15）：引擎已能热加方块/物品并保证存盘/重启可读，但 UI 仍把 T4 标成不可加载（`ModInspection.canHotLoad`）、客户端资源包/创造栏/登录同步未做、datapack 内容与实体类 post-register 事件未重放。见 [features/t4-registry-hotadd.md](features/t4-registry-hotadd.md) §4。

## 下一步

> 结构性技术债与架构升级点见 [tech-debt.md](tech-debt.md)（P0 已完成；下一步 P1-4 → P1-5 → P1-6）。

0. **Git 收尾**：加 CI（分析器单测 + `./gradlew test`/`build`）；证据截图从 `run/screenshots` 复制进 `docs/`；首个 tag `v0.1.0` 待功能稳定。**CI 会先撞上已知问题 12**：`libs/` 不随仓库分发，完整 `build` 在 CI 上无 jar 可用，加 CI 前必须决定 jar 从哪来（下载脚本 / 提交 jar / 先只跑分析器单测与 `test`）。
1. **人工收尾**：点一次 EasyLAN 的 GUI 按钮（`/modreloader add` 已自动化，见表尺）。
2. **A1 产品化**：构造后 listener 回滚 / 存档回读；第二个 T3 模组（如 `mcwifipnp`）；把 mr 的 5 个调用点反射化，去掉 `compileOnly` 依赖，让 fresh clone 能构建（已知问题 12）。
3. **GUI / 文件夹监听完善**：Phase 3/4——忽略/重试持久化、批量/依赖排序、`-PverifyGui` 自动化；见 [gui-mod-watcher.md](features/gui-mod-watcher.md)。
4. **分析器校准**：50 个 1.21.1 模组，人工标注 tier；算准确率；修 `mekanismgenerators` 类漏判。
5. **游戏内 `/modreload why <mod>`**：NeoForge 1.21.1，只读。
6. **T4（ADR-0008 已接受）**：server-only、add-only 的注册表热加已跑通（见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md)）；后续按顺序扩大：① UI 放行 T4（带警告）；② 客户端资源包 + 创造栏 + 登录前热加；③ 失败语义（dirty 暴露）；④ datapack 内容评估。managed relaunch（ADR-0006）仍是不支持内容的兜底。硬约束：建连接前完成、BlockState id 先清空重建、提交后不可回滚。
7. **B fallback**：只在 A 失败时提供 offline/republish；不是主线。

## 最近变更

> 逐步过程与证据见 [history.md](history.md)；这里只留最近几条。

- **2026-11-15（T4 注册表热加跑通）**：`RegistryStage` + fixture 端到端验证：服务端热加方块/物品、放置存盘、重启读档 PASS，BlockState id 稳定。见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md)。
- **2026-11-15（T4 调研开题）**：只读注册表探针 + 受控 re-freeze 实验；发现裸 re-freeze 会移动 BlockState id（stone 1→26685）；写 [research-t4-registry.md](research-t4-registry.md) 与 [ADR-0008](decisions/0008-t4-registry-adaptation-poc.md)（Proposed）。
- **2026-11-15（架构收口）**：P0-2 引擎解耦（`PlatformHooks`）、P0-1 `Capabilities` 锚点守卫、P0-3 `dev` 源集、P2-8 三个 Java 单测；客户端补上集成服务器命令重放。
- **2026-11-15（payload E3）**：跨进程 payload 适配打通（专用服 + 独立客户端各自热加）。
- **2026-11-15（payload E1/E2）**：连接前/连接后注册 payload 都可适配，已建连接会就地补 `PayloadSetup`。
- **2026-11-15（服务端命令重放）**：`RegisterCommandsEvent` 重放后根命令 93→94，tick 持续到达。
- **2026-11-15（世界内 add / toast / 忽略恢复）**：harness 覆盖；主菜单入口改为右上角图标。
