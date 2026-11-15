# 项目状态

> **唯一状态源。** 每次会话先读这里；改状态只改这里，不要在各处复制。
> 最后更新：2026-11-15

## 一句话

只读分析器 v0 可用；**A0（T2 模组热加）已验证**；**A1.2 主路径已在 NeoForge 1.21.1 dev client 验证**：热加 `lanserverproperties`（4 mixin、布局变更）后进世界打开 Open to LAN，LSP 的端口/在线模式/最大玩家数控件可用。**A1.2 失败回滚已实现并跑通**：post-ModList / post-pack 强制失败后，ModList、container、module、mixin config、4 个 target 类、资源包均恢复；重试 add 成功。仍差：构造后事件监听器无法注销、存档回读未验证，因此按契约尚未完成。**mods/inbox 监听 + 预检 + toast/弹窗 + 失败分析（Phase 1/2）已实现并验证**：EasyLAN 检测→弹窗→加载 PASS；坏 jar 失败屏 PASS；Ex Nihilo T4 只提示需要重启。T4 / 软重启未开始。目标锁定 **Minecraft 1.21.1 + NeoForge 1.21.1**。

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
| **A0 热加 EasyLAN** | **isLoaded=true, container=true；resource pack `mod/easylan` 选中并重载；keyMappings 34→34；JBR 下复测通过；无异常** |
| **A1.0 probe** | **4 mixins / 4 targets / 2 loaded；+1 field / +16 methods；verdict=JBR_REDEFINE；只读** |
| **A1.1 spike** | **MixinRuntime + JBR：config 注册成功；ShareToLanScreen 字段/方法可见；UUIDUtil redefined 9155 bytes**。spike 未检查接口合并；A1.2 修正 | 历史 spike；`-Pa1` 入口已移除 |
| **A1.2 全流水线（主路径）** | **isLoaded/container=true；4 mixins；UUIDUtil/AbstractWidget redefine；ShareToLanScreen/PlayerList 首载织入；接口合并；LAN 屏打开，port=25565；3 个 EditBox；截图** | `./gradlew runClient -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar -PverifyLan=true`；`run/screenshots/a1-lan-screen.png` |
| **A1.2 失败回滚** | **post-ModList 失败：ModList/container/module/config/4 classes/资源包恢复；retry add PASS；post-pack 失败：pack 移除 + classes 恢复；构造 listener 不可注销** | `-PverifyRollback=true -PfailAfterModList=true` / `-PfailAfterPack=true`；`RollbackCheck` |
| **mods 监听 / 预检 / 弹窗** | **run/mods + run/modreloader/inbox；EasyLAN 检测 T2→弹窗→加载 PASS；坏 jar 失败屏 PASS；Ex Nihilo T4→不可加载；LSP + failAfterModList→失败屏 + 回滚 PASS；i18n zh_cn/en_us 无 key 泄漏** | `-PverifyWatch=true -PwatchJar=...`；`run/screenshots/watch-prompt.png` / `watch-failure.png` |
| 系统 JDK | 17 / 8；**Gradle toolchain JDK 21** 在 `~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x/jdk-21.0.12.1+1/Contents/Home` | `java -version`；Gradle foojay |
| **JBR 21** | **JBR-21.0.11+10-1163.125-nomod**；`enhancedRedefinition=true` | `~/Library/Java/JavaVirtualMachines/jbr-21.jdk/Contents/Home`；`runClient` 自动使用 |
| **缺 mr 启动 + T2** | **无 `Missing or unsupported`；warn「MixinRuntime (mr) is not installed」；EasyLAN `isLoaded=true, container=true`、`hot-added [easylan]`** | 2026-11-15 15:32：`run/mods` 移走 mr → `runClient -PautoAdd=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar` |
| **有 mr 回归（T3）** | **`MixinRuntime (mr) detected`；`registered=[lsp.mixins.json]`；redefined 2 / eager 2；`isLoaded=true`** | 2026-11-15 15:33：放回 mr → `runClient -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar` |
| **世界内 add 命令** | **`dispatcher has /modreloader=true`；命令返回 1；`ModList easylan: isLoaded=true, container=true`；RESULT: PASS** | 2026-11-15 15:38：`runClient -PverifyAddCommand=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar`（新建世界后执行） |
| **add T3 缺 mr 被拒** | **命令返回 0；`isLoaded=false`；错误「[lanserverproperties] declares mixins but MixinRuntime (mr) is not installed」；无 rollback 错误；RESULT: PASS** | 2026-11-15 15:39：`-PverifyAddCommand=...lanserverproperties... -PverifyAddCommandModId=lanserverproperties -PverifyAddCommandExpectFailure=true` |
| **世界内 toast（不弹窗）** | **`new mod(s) detected in world; toast only`；toast 队列命中 1（按 `toast.detected.title` 断言）；`screen=null`、`promptOpened=false`；in-world RESULT: PASS** | 2026-11-15 15:43：`-PverifyAddCommand=...EasyLAN... -PverifyAddCommandWatchJar=...mcwifipnp...`（世界内把 jar 丢进 inbox） |
| **专用服务端加载（双端化）** | **`loaded on dedicated server`；watcher 起在 Server thread；`Done (7.060s)`；无 `NoClassDefFoundError`** | 2026-11-15 16:24：`./gradlew runServer`（`run/eula.txt` 本地接受） |
| **服务端热加 T2：被 dist 拦住（回滚干净）** | **EasyLAN 失败：`Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER`；event listeners / mod list / module layer 回滚 ok** | 2026-11-15 16:24：`runServer -PautoAdd=/tmp/lanmods/EasyLAN-...jar` |
| **主菜单入口 + 待处理列表 + 忽略恢复** | **标题屏入口 (4,216) → 待处理屏列出未加载 jar `▶ [T2] menutest-...`；已忽略视图列出条目；恢复后 `ignoredCount 1 -> 0`；RESULT: PASS** | 2026-11-15 16:34：`runClient -PverifyMenu=true` |
| **双端化后客户端回归** | **`loaded on client`；`AddCmdCheck RESULT: PASS`** | 2026-11-15 16:25：`runClient -PverifyAddCommand=/tmp/lanmods/EasyLAN-...jar` |

## 验证状态

### 已验证

- **自附加 + 类重定义**（JDK 17，macOS arm64）：不依赖 `-javaagent` / `-Djdk.attach.allowAttachSelf` 拿到 `Instrumentation`；方法体替换 0.7–5 ms；新增方法被 JVM 拒绝。见 [PoC](../experiments/self-attach-poc/README.md)。
- **分析器**：合成 jar、公开 7-jar、Ex Nihilo 1.5.0、`run/mods` 都能跑；JSON / Markdown 输出可用。
- **Java 21 存在**：Gradle 为 Ex Nihilo 下载的 Temurin 21.0.12.1；`java_home` 看不到它。
- **A0 热加**：NeoForge 1.21.1 dev client 中热加 `EasyLAN`（T2，无 mixin）；`ModList.isLoaded=true`、容器存在、`@Mod` 构造 + 客户端事件无异常；资源包 `mod/easylan` 已选中并重载；JBR 下复测通过。详见 [a0-hot-add.md](features/a0-hot-add.md)。
- **JBR 21 + enhanced redefinition**：`runClient` 自动使用 JBR 21.0.11，启动日志 `enhancedRedefinition=true`。
- **A1.0 probe**：只读解析 `lanserverproperties`；4 个 mixin、4 个 target、2 个已加载；布局 +1 字段/+16 方法；JBR 可 redefine。详见 [a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)。
- **A1.1 spike**：运行时注册 `lsp.mixins.json`；未加载的 `ShareToLanScreen` 字段/方法可见；已加载的 `UUIDUtil` 用 JBR redefine 新增方法成功。**限制**：只检查了字段/方法，没有检查接口合并；`postInitialise` 缺失导致接口没织入，A1.2 修正。详见 [a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)。
- **mods 监听 / 预检 / 弹窗**：后台轮询 `run/mods` + `run/modreloader/inbox`；稳定检测 + 基线；`ModInspector` 读元数据/依赖/mixin/registry/native 预测 T2–T5；主菜单自动弹 `ModReloaderScreen`，世界中只 toast；加载走 `LoadController`，失败打开 `LoadFailureScreen`，带原因/建议/回滚/复制/日志。EasyLAN T2 检测→加载 PASS；坏 jar 预检失败屏 PASS；Ex Nihilo T4 显示“不可加载/需要重启”；LSP + `failAfterModList` 走失败屏并回滚成功。GUI/按钮/toast/命令/失败摘要已走 `Component.translatable`，`en_us` / `zh_cn` 都实测无 key 泄漏。配置在 `config/modreloader/watcher.json`，忽略/失败记录独立 JSON。详见 [gui-mod-watcher.md](features/gui-mod-watcher.md)。
- **A1.2 失败回滚**：新增 `Rollback` LIFO 事务；每个阶段先注册 undo。强制 post-ModList 失败后恢复 module / ModList / mixin config（deactivate，保留对象供重试）/ 4 个 target 类字节（JBR redefine 回原字节）/ 资源包；`RollbackCheck` 重试 add 成功。强制 post-pack 失败后资源包选中项移除、类/ModList 恢复；构造后的监听器无法注销。详见 [a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)。
- **A1.2 全流水线（主路径）**：`module → mixin → ModList → construct → events → 资源包` 合并进 `HotAddEngine`；`lsp.mixins.json` 运行时注册（config / mod id 从 jar 元数据读取，不再硬编码）；`UUIDUtil`、`AbstractWidget` 用 JBR redefine；`ShareToLanScreen` / `PlayerList` 用 `Class.forName(..., false)` 首载织入；补齐 `MixinConfig.postInitialise` 后 `IShareToLanScreenParamAccessor` 接口合并；进世界打开 LAN 屏，`getPort()=25565`、3 个 EditBox、LSP 的在线模式/PvP/最大玩家数控件可见；资源包选中并 `reloadResourcePacks()`，翻译生效。详见 [a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)。

### 未验证

- **EasyLAN 的真实 GUI**：`@Mod` 构造器已注册 `ScreenEvent.Opening` 监听器，但没有人工点过界面按钮；`keyMappings` 未增加。A1.2 的 LAN 屏验证只覆盖 `lanserverproperties`。
- ~~**游戏内 `/modreloader add` 命令**~~：**已自动化验证**（2026-11-15 15:38）；见表尺「世界内 add 命令」。注意验证走的是 dispatcher 直接执行，跳过聊天打包。
- **生产 jar / 第三方启动器**：只在 dev client 测过。
- ~~**世界内 toast 路径**~~：**已自动化验证**（2026-11-15 15:43）：世界里丢 jar 进 inbox → 检测到 → toast 进队列（按标题断言）、`screen=null`、未弹窗。见表尺「世界内 toast」。
- **`/modreloader gui` / `scan` 命令**：已注册，未自动验证。
- **忽略/失败 JSON 跨重启持久化**：GUI 内 ignore → restore 已自动验证（`-PverifyMenu`），但重启客户端后是否仍记住未验证。
- **T4（注册表内容）**：未开始。
- **Tier 预测 vs 人工标注**——还没有 ground truth，准确率未知。
- **Fabric intermediary 映射**——`class_XXX` 目标识别仍退化。
- **多版本 / 多加载器**——1.21.1 之外未测试。
- **存档一致性**——还没有写操作，谈不上验证。

## 已知问题 / 坑

1. **静态分析会漏**：`mekanismgenerators` 被标成 T2，但它通过 Mekanism API 注册内容——跨模组注册抓不到。
2. **T5 可能过保守**：AE2 因渲染 model mixin 被判 T5；是否真的必须完整重启，需要运行时验证。
3. **Fabric 生产 jar 是 intermediary 名**：`net.minecraft.class_310`，核心类识别退化。
4. **Jar-in-Jar 只拆一层**；更深 bundle 不展开。
5. **Python 分析器 vs 未来 Java 模组**：规则需要抽成声明式配置，避免两份逻辑漂移。
6. **LAN 模组是 T3 布局变更**：会给 `MinecraftServer` / `PauseScreen` / `ShareToLanScreen` 加字段；需要 JBR 或 sidecar；`online-mode` 可能要重启集成服务器才生效。详见 [use-cases.md](use-cases.md)。
7. **A1.2 仍未产品化**：依赖 JBR + `-XX:+AllowEnhancedClassRedefinition`；依赖 MixinRuntime（**第三方硬前置**，非 dev-only 便利依赖，见已知问题 12）。失败回滚只覆盖 post-ModList / post-pack：构造后的 `@Mod` 构造器和事件监听器无法注销；JVM 不能 undefine module，回滚把 Module 对象留在缓存里供重试；被移除的 mixin config 对象保留但 inactive。存档回读、第二个 T3 模组、真实 jar 启动仍未验证。
8. **Git 仓库已建立**（2026-11-15）：`main`、首次 commit、`origin` → `github.com/WanConcubine/Mod-Reloader`；尚未加 CI、tag、release，证据截图仍只在 `run/`（被忽略）。
9. **没有人工标注集**：规则准确率、混淆矩阵都还没有。
10. **没有删/更新路径**：A1.2 只实现了 add；运行时删除/卸载/更新未实现、未测试。已加载的类（尤其已织入 mixin 的 `ShareToLanScreen` / `AbstractWidget`）无法靠删 jar 卸载；需要 classloader 隔离或 T4 软重启。
11. **GUI/监听初版限制**：只提供 `zh_cn` / `en_us`；异常消息/技术值仍是原始英文（标签已本地化）；最多直接显示 7 个候选；世界内 toast 与主菜单入口/忽略恢复已自动化，`/modreloader gui`/`scan`、失败重试未自动验证；T4/T5 只提示重启，不尝试加载。
12. **MixinRuntime 是 T3 的第三方前置，已改为可选依赖**（2026-11-15）：`mr` 从 `required` 改成 `type="optional"`，因此缺它时**游戏能启动、T2 照常可用**，由我们给出人话提示（主菜单 toast + 日志 warn + 预检 + 失败屏，文案统一 `MixinRuntime (mr)`）。改的理由：`required` 时 FML `ModSorter#formatDependencyError` 只打印原始 modId（格式 `Mod ID: '%s', Requested by: '%s', Expected range: '%s', Actual version: '%s'`，缺失打印 `[MISSING]`，`[[dependencies]]` 无显示名字段），且加载阶段即终止，我方代码不执行、无法提示。为让「无 mr」真正可用，`MixinSupport`（直接 import mr 类型）被隔离在 [MixinStage](../src/main/java/dev/modreloader/hotadd/MixinStage.java) 之后，`HotAddEngine` 不再引用它；`HotAddEngine` 在 mixin 阶段前用 `MixinRuntimeSupport` 探测，缺失则 `FailureKind.MIXIN_RUNTIME` 失败并回滚。**构建仍需要** `libs/` 的 jar（`compileOnly`），而 `libs/` 被忽略 → **fresh clone 无法编译**（移走 jar 实测 24 个符号错误）；尚未反射化。代码只用它 5 个能力：取 transformer、`transformClassBytes`、`getClassBytes`（前两者有等价 public API，第三者可用自有 `Instrumentation`），以及 `MixinConfig.prepare/onSelect` 与 `MixinProcessor` 的 `configs/pendingConfigs`（包私有 + private，需反射）。`MixinEnvironment.getActiveTransformer()` 在 NeoForge 1.21.1 下是否可用**未验证**。**无 mr 启动路径已验证**：2026-11-15 15:32 移走 `run/mods` 的 mr 启动，无 FML 依赖报错，warn + toast 出现，EasyLAN（T2）热加 `isLoaded=true`；15:33 放回 mr 后 lanserverproperties（T3）回归通过。
13. **`mods.toml` 的 side 字段不可信**（2026-11-15 实测）：EasyLAN 声明 `side="BOTH"`，但它的 `@Mod` 构造器里注册了 `ScreenEvent` 监听，在专用服上直接抛 `Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER`。NeoForge 在服务端是**主动拒绝**加载客户端类，不是"没有这个类"。所以判断"能否上服务端"必须扫字节码里的 `net/minecraft/client/**` 引用（含 provider 类本身），不能只看 metadata；且扫描只能减少误判，不能保证，仍需运行期兜底（我们已有回滚）。
14. **服务端尚无"成功"样例**：目前只验证了服务端**加载** runtime、以及热加被 dist 拒绝并干净回滚；还没找到一个真正服务端安全的 T2 mod 来证明服务端热加成功路径。

## 下一步

0. **Git 收尾**：加 CI（分析器单测 + `./gradlew build`）；证据截图从 `run/screenshots` 复制进 `docs/`（`run/` 被忽略）；首个 tag `v0.1.0` 待功能稳定。**CI 会先撞上已知问题 12**：`libs/` 不随仓库分发，build 在 CI 上无 jar 可用，加 CI 前必须决定 jar 从哪来（下载脚本 / 提交 jar / 先只跑分析器单测）。
1. **人工收尾**：点一次 EasyLAN 的 GUI 按钮（`/modreloader add` 已自动化，见表尺）。
2. **A1 产品化**：构造后 listener 回滚 / 存档回读；第二个 T3 模组（如 `mcwifipnp`）；把 mr 的 5 个调用点反射化，去掉 `compileOnly` 依赖，让 fresh clone 能构建（已知问题 12）。
3. **GUI / 文件夹监听完善**：Phase 3/4——忽略/重试持久化、批量/依赖排序、i18n 收尾、`-PverifyGui` 自动化；世界内 toast 已有 harness；见 [gui-mod-watcher.md](features/gui-mod-watcher.md)。
4. **分析器校准**：50 个 1.21.1 模组，人工标注 tier；算准确率；修 `mekanismgenerators` 类漏判。
5. **游戏内 `/modreload why <mod>`**：NeoForge 1.21.1，只读。
6. **T4（ADR-0006）**：先做 managed relaunch（存盘 → 重启 → 回存档），再在 dedicated server 做「一代重建」PoC，最后才评估客户端软重启；**拒绝注入注册表**（`unfreeze` 只解除写保护，依赖它的缓存/数据包/资源/存档都不会重建）。
7. **B fallback**：只在 A 失败时提供 offline/republish；不是主线。

## 最近变更

- **2026-11-14**：分析器 v0（class 文件解析、`@Mixin` target、注册调用检测、JiJ、TOML）；9 个单测；目标锁定 1.21.1 + NeoForge；文档重组（AGENTS / status / design / glossary / use-cases / ADR）。
- **2026-11-14（补）**：修复 CLASS-retention 注解解析（`@Mixin` 在 `RuntimeInvisibleAnnotations`，之前漏读）；LAN 三个模组实测 T2/T3。
- **2026-11-14（定位）**：ADR-0005 确认“我们是 runtime，不是 mod”；主线回到 A（热加载 LAN 模组），B 降级为 fallback。
- **2026-11-14（A0）**：NeoForge 1.21.1 热加 `EasyLAN` 验证通过；自附加 agent、module 注入、ModList 重建、客户端事件、资源包注入全部跑通。详见 [a0-hot-add.md](features/a0-hot-add.md)。
- **2026-11-14（A1）**：A1 设计落地；JBR 21.0.11 已安装并接入 `runClient`；**A1.0 probe 完成**（4 mixins / +1 field / +16 methods / JBR_REDEFINE）；**A1.1 spike 完成**（MixinRuntime 注册 config；`ShareToLanScreen` 字段/方法可见；`UUIDUtil` JBR redefine 9155 bytes）；见 [a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)。
- **2026-11-15（A1.2）**：`A1Spike` 合并进 `HotAddEngine`，去掉硬编码 config/mod id；发现并修复 `postInitialise` 缺失（接口合并）、config `compatibilityLevel=JAVA_17` 降级、已 mixed 类重复 transform、资源包未选中/未重载四个问题；dev client 进世界打开 Open to LAN 验证通过（port=25565，LSP 控件 + 翻译 + 截图）。
- **2026-11-15（A1.2 回滚）**：新增 `Rollback` LIFO 事务与 `RollbackCheck`；post-ModList 强制失败后恢复 module / ModList / config / 4 个 target 类 / 资源包，重试 add 成功；post-pack 失败后资源包移除 + 类恢复通过。构造后事件监听器仍无法注销，存档回读未验证。
- **2026-11-15（GUI 初版）**：实现 mods/inbox 监听、Java 预检（T2–T5）、toast + 主菜单弹窗、加载/失败屏、独立 watcher JSON；EasyLAN 检测→加载、坏 jar 失败屏、T4 不可加载均验证。详见 [gui-mod-watcher.md](features/gui-mod-watcher.md)。
- **2026-11-15（i18n）**：新增 `en_us` / `zh_cn` 语言文件（各 103 key）；GUI、按钮、toast、命令、预检/失败摘要、复制详情标签全部走翻译 key；中英文截图验证无 key 泄漏；异常原始消息保留英文。
- **2026-11-15（git / 许可 / 作者）**：`git init -b main`，`.gitignore` 覆盖 `.gradle/`、`build/`、`run/`、`libs/`、`*.class`、`__pycache__/`，78 文件 + `LICENSE` 共 79 文件首次 commit 并推到 `origin`（GitHub `WanConcubine/Mod-Reloader`）。许可证由 Apache-2.0 改为 **MIT**：新增 `LICENSE`，`mod_license`、`neoforge.mods.toml`、README、roadmap 同步。作者改为 **Liu_Mei**（`mod_authors` / `authors` / git 提交身份）。
- **2026-11-15（记录更正）**：MixinRuntime 由「dev-only 依赖」更正为**第三方硬前置**（新增已知问题 12）：`libs/` 被忽略 + `compileOnly` 依赖 → fresh clone 编译失败（24 个符号错误）；`mr` 在 mods.toml 为 `required` → 缺它整个 mod 不加载。所有者决定保持现状。
- **2026-11-15（前置改为可选）**：`mr` 从 `required` 改为 `type="optional"`；新增 `inspect/MixinRuntimeSupport`（不引用 mr 类型）与 `hotadd/MixinStage`（把 `MixinSupport` 挡在引擎外）；缺 mr 时主菜单 toast + 日志 warn + 预检/失败屏文案统一 `MixinRuntime (mr)`；`HotAddEngine` 在 mixin 阶段前探测，缺失则 `FailureKind.MIXIN_RUNTIME` 失败并回滚；i18n `en_us`/`zh_cn` 各 **110 key**。**运行时已验证**：移走 mr 启动无 FML 报错、warn 出现、EasyLAN T2 热加 `isLoaded=true`；放回 mr 后 LSP T3 回归通过（`registered=[lsp.mixins.json]`，redefined 2，eager 2）。
- **2026-11-15（世界内 add 自动化）**：新增 `dev/AddCommandCheck` + `-PverifyAddCommand` / `-PverifyAddCommandModId` / `-PverifyAddCommandExpectFailure`；`ReloaderCommands` 记录 dispatcher 供检查执行。实测：新建世界后 `/modreloader add EasyLAN` 返回 1、`isLoaded=true`（15:38）；缺 mr 时 add `lanserverproperties` 返回 0、`isLoaded=false`、错误写明 `MixinRuntime (mr)`、无回滚错误（15:39）。status「未验证」里那条 `/modreloader add` 由此转正，下一步只剩 EasyLAN GUI 按钮。
- **2026-11-15（世界内 toast 自动化）**：`AddCommandCheck` 增加第二阶段 `-PverifyAddCommandWatchJar`：进世界后把 jar 丢进 inbox，断言**只 toast、不弹窗**。实测 15:43：`new mod(s) detected in world; toast only`；toast 队列按 `toast.detected.title` 命中 1（读 `ToastComponent.visible`/`queued`，因 `ToastInstance` 包私有，改走 `UnsafeAccess` 读字段）；`screen=null`、`promptOpened=false`；in-world RESULT: PASS。status「未验证」里的世界内 toast 一条转正。
- **2026-11-15（T4 路线 / ADR-0006）**：所有者决定 T4 走 **managed relaunch** 优先，不注入注册表；实测确认 `MappedRegistry.unfreeze()` 为 public，但它只解除写保护，`Block.BLOCK_STATE_REGISTRY`/创造栏/`BlockEntityType` 绑定/数据包/资源包/存档 palette 全不重建。后续顺序：managed relaunch → dedicated server「一代重建」PoC → 才评估客户端软重启。另：side 门禁按所有者决定做**只警告不拦**；双端（client+server）热加列为方向性目标。
- **2026-11-15（双端化第一步）**：拆出 `Platform`（不引用客户端类型）、`client/ClientBootstrap`（UI/toast/资源包/tick/dev 检查）、`client/ClientCommands`（`gui` 节点）、`server/ServerBootstrap`（控制台命令 + `ServerStartedEvent` 触发）、`ClientThreads`；`ModReloader` 去掉 `dist = Dist.CLIENT`，mods.toml 依赖改 `side = "BOTH"`；`FmlEvents`/`HotAddEngine`/`ModsFolderWatcher` 加 side 守卫；`runServer` 增加 `autoAdd`/`probe` 属性。实测：服务端加载 PASS、客户端回归 PASS、服务端热加 EasyLAN 被 dist 拒绝并干净回滚（新增已知问题 13/14）。
- **2026-11-15（待处理入口 + 忽略恢复）**：新增 `client/MenuEntry`（`ScreenEvent.Init.Post` 在 TitleScreen / PauseScreen 左下角挂「模组热加载」按钮）；`ModReloaderScreen` 增加「已忽略」视图与恢复（`ModWatcherConfig.unignore` / `ignoredEntries`），解决「点了忽略就再也找不到」；`scanNow()` 改为走不过滤 `seen` 指纹的 `scanPending()`，所以点过「稍后」的 jar 也仍留在待处理列表里；新增 `dev/MenuEntryCheck`（`-PverifyMenu`）。实测 16:32：入口按钮 (4,216) → 待处理屏 → 已忽略（1）视图 → 恢复后 `ignoredCount 1 -> 0`，RESULT: PASS。
