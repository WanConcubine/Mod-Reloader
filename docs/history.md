# 变更历史

> 追加式记录：做了什么、证据、为什么。**状态只看 [status.md](status.md)**；这里不重复当前状态，只留过程。
> 新条目加在对应日期最后。
> 文中 `dev/XxxCheck` 等路径是当时写法；2026-11-15 起这些 harness 在 `src/dev/java/dev/modreloader/dev/`（独立 source set，不进发布 jar）。

## 2026-11-14

- **2026-11-14**：分析器 v0（class 文件解析、`@Mixin` target、注册调用检测、JiJ、TOML）；9 个单测；目标锁定 1.21.1 + NeoForge；文档重组（AGENTS / status / design / glossary / use-cases / ADR）。
- **（补）**：修复 CLASS-retention 注解解析（`@Mixin` 在 `RuntimeInvisibleAnnotations`，之前漏读）；LAN 三个模组实测 T2/T3。
- **（定位）**：ADR-0005 确认“我们是 runtime，不是 mod”；主线回到 A（热加载 LAN 模组），B 降级为 fallback。
- **（A0）**：NeoForge 1.21.1 热加 `EasyLAN` 验证通过；自附加 agent、module 注入、ModList 重建、客户端事件、资源包注入全部跑通。详见 [a0-hot-add.md](features/a0-hot-add.md)。
- **（A1）**：A1 设计落地；JBR 21.0.11 已安装并接入 `runClient`；**A1.0 probe 完成**（4 mixins / +1 field / +16 methods / JBR_REDEFINE）；**A1.1 spike 完成**（MixinRuntime 注册 config；`ShareToLanScreen` 字段/方法可见；`UUIDUtil` JBR redefine 9155 bytes）；见 [a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)。

## 2026-11-15

- **（A1.2）**：`A1Spike` 合并进 `HotAddEngine`，去掉硬编码 config/mod id；发现并修复 `postInitialise` 缺失（接口合并）、config `compatibilityLevel=JAVA_17` 降级、已 mixed 类重复 transform、资源包未选中/未重载四个问题；dev client 进世界打开 Open to LAN 验证通过（port=25565，LSP 控件 + 翻译 + 截图）。
- **（A1.2 回滚）**：新增 `Rollback` LIFO 事务与 `RollbackCheck`；post-ModList 强制失败后恢复 module / ModList / config / 4 个 target 类 / 资源包，重试 add 成功；post-pack 失败后资源包移除 + 类恢复通过。构造后事件监听器仍无法注销，存档回读未验证。
- **（GUI 初版）**：实现 mods/inbox 监听、Java 预检（T2–T5）、toast + 主菜单弹窗、加载/失败屏、独立 watcher JSON；EasyLAN 检测→加载、坏 jar 失败屏、T4 不可加载均验证。详见 [gui-mod-watcher.md](features/gui-mod-watcher.md)。
- **（i18n）**：新增 `en_us` / `zh_cn` 语言文件（各 103 key）；GUI、按钮、toast、命令、预检/失败摘要、复制详情标签全部走翻译 key；中英文截图验证无 key 泄漏；异常原始消息保留英文。
- **（git / 许可 / 作者）**：`git init -b main`，`.gitignore` 覆盖 `.gradle/`、`build/`、`run/`、`libs/`、`*.class`、`__pycache__/`，78 文件 + `LICENSE` 共 79 文件首次 commit 并推到 `origin`（GitHub `WanConcubine/Mod-Reloader`）。许可证由 Apache-2.0 改为 **MIT**：新增 `LICENSE`，`mod_license`、`neoforge.mods.toml`、README、roadmap 同步。作者改为 **Liu_Mei**（`mod_authors` / `authors` / git 提交身份）。
- **（记录更正）**：MixinRuntime 由「dev-only 依赖」更正为**第三方硬前置**（新增已知问题 12）：`libs/` 被忽略 + `compileOnly` 依赖 → fresh clone 编译失败（24 个符号错误）；`mr` 在 mods.toml 为 `required` → 缺它整个 mod 不加载。所有者决定保持现状。
- **（前置改为可选）**：`mr` 从 `required` 改为 `type="optional"`；新增 `inspect/MixinRuntimeSupport`（不引用 mr 类型）与 `hotadd/MixinStage`（把 `MixinSupport` 挡在引擎外）；缺 mr 时主菜单 toast + 日志 warn + 预检/失败屏文案统一 `MixinRuntime (mr)`；`HotAddEngine` 在 mixin 阶段前探测，缺失则 `FailureKind.MIXIN_RUNTIME` 失败并回滚；i18n `en_us`/`zh_cn` 各 **110 key**。**运行时已验证**：移走 mr 启动无 FML 报错、warn 出现、EasyLAN T2 热加 `isLoaded=true`；放回 mr 后 LSP T3 回归通过（`registered=[lsp.mixins.json]`，redefined 2，eager 2）。
- **（世界内 add 自动化）**：新增 `dev/AddCommandCheck` + `-PverifyAddCommand` / `-PverifyAddCommandModId` / `-PverifyAddCommandExpectFailure`；`ReloaderCommands` 记录 dispatcher 供检查执行。实测：新建世界后 `/modreloader add EasyLAN` 返回 1、`isLoaded=true`（15:38）；缺 mr 时 add `lanserverproperties` 返回 0、`isLoaded=false`、错误写明 `MixinRuntime (mr)`、无回滚错误（15:39）。status「未验证」里那条 `/modreloader add` 由此转正，下一步只剩 EasyLAN GUI 按钮。
- **（世界内 toast 自动化）**：`AddCommandCheck` 增加第二阶段 `-PverifyAddCommandWatchJar`：进世界后把 jar 丢进 inbox，断言**只 toast、不弹窗**。实测 15:43：`new mod(s) detected in world; toast only`；toast 队列按 `toast.detected.title` 命中 1（读 `ToastComponent.visible`/`queued`，因 `ToastInstance` 包私有，改走 `UnsafeAccess` 读字段）；`screen=null`、`promptOpened=false`；in-world RESULT: PASS。status「未验证」里的世界内 toast 一条转正。
- **（T4 路线 / ADR-0006）**：所有者决定 T4 走 **managed relaunch** 优先，不注入注册表；实测确认 `MappedRegistry.unfreeze()` 为 public，但它只解除写保护，`Block.BLOCK_STATE_REGISTRY`/创造栏/`BlockEntityType` 绑定/数据包/资源包/存档 palette 全不重建。后续顺序：managed relaunch → dedicated server「一代重建」PoC → 才评估客户端软重启。另：side 门禁按所有者决定做**只警告不拦**；双端（client+server）热加列为方向性目标。
- **（双端化第一步）**：拆出 `Platform`（不引用客户端类型）、`client/ClientBootstrap`（UI/toast/资源包/tick/dev 检查）、`client/ClientCommands`（`gui` 节点）、`server/ServerBootstrap`（控制台命令 + `ServerStartedEvent` 触发）、`ClientThreads`；`ModReloader` 去掉 `dist = Dist.CLIENT`，mods.toml 依赖改 `side = "BOTH"`；`FmlEvents`/`HotAddEngine`/`ModsFolderWatcher` 加 side 守卫；`runServer` 增加 `autoAdd`/`probe` 属性。实测：服务端加载 PASS、客户端回归 PASS、服务端热加 EasyLAN 被 dist 拒绝并干净回滚（新增已知问题 13/14）。
- **（待处理入口 + 忽略恢复）**：新增 `client/MenuEntry`（`ScreenEvent.Init.Post` 在 TitleScreen / PauseScreen 左下角挂「模组热加载」按钮）；`ModReloaderScreen` 增加「已忽略」视图与恢复（`ModWatcherConfig.unignore` / `ignoredEntries`），解决「点了忽略就再也找不到」；`scanNow()` 改为走不过滤 `seen` 指纹的 `scanPending()`，所以点过「稍后」的 jar 也仍留在待处理列表里；新增 `dev/MenuEntryCheck`（`-PverifyMenu`）。实测 16:32：入口按钮 (4,216) → 待处理屏 → 已忽略（1）视图 → 恢复后 `ignoredCount 1 -> 0`，RESULT: PASS。
- **（塞入/删除语义）**：新增 `dev/DeleteCheck`（`-PverifyDelete`）覆盖「放入→删除→同字节拷回」；修掉两个粗糙点：`seen` 指纹在文件消失后未清理（会遮蔽之后同 size/mtime 的重新拷入）→ `scan()` 现在按 `present` 同步清理；加载已消失的 jar 之前只会报 `unknown`（"原因无法分类"）→ 新增 `FailureKind.MISSING_FILE` 与中英文案，且不再写 failed 记录。实测 16:38：四条断言全 PASS。
- **（入口改成 logo）**：`MenuEntry` 从文字按钮改到**右上角**（原来左下角压着版本/版权行），新增 `client/LogoButton` （`assets/modreloader/textures/gui/modreloader_logo.png`，64×64，按 pose 缩放绘制；悬停高亮 + tooltip + 旁白）；`MenuEntryCheck` 改为按类型查找并断言位于右上角，顺带截图。实测 16:42：`(399,4) 24x24 topRight=true`，待处理/已忽略/恢复仍 PASS。注：`blit()` 不缩放，第一版只采样了 64×64 纹理的左上角，截图发现后改为 pose 缩放。
- **（入口改为 Minecraft 风格图标）**：用户反馈原 logo 过于扁平。改为 20×20 像素图：带斜面的石头槽位（1px 黑边 + 左上浅/右下深 + 青色高光）+ 草方块，另附 `modreloader_logo_hovered.png` 悬停态；`LogoButton` 改成 1:1 绘制（不再缩放），键盘焦点额外画 1px 白框；尺寸从 24 改为 20。实测 16:50：`-PverifyMenu=true` PASS，截图确认右上角渲染正确。注：期间我的批量替换脚本误把 `LogoButton.java` 写坏（把每个 `}` 替换成整段方法），已整文件重写修复。
- **（屏幕语义重排）**：用户指出按钮布局不合理且有重复语义。删除「稍后」（与「关闭」行为完全相同）、不再把「不可加载」做成按钮（保留「加载」并置灰，原因显示在详情行）；主操作统一一行等宽（加载/忽略/关闭），视图切换独立一行（查看已忽略（N）／返回待处理）；忽略后立即从列表移除；空列表只保留「关闭」+ 提示「把 jar 放进 mods/ 或 modreloader/inbox」。i18n 121 key（两语言一致）。实测 16:57：布局截图 + RESULT: PASS。logo 已按所有者要求改为「⇄ 形状 + 草方块配色」，2026-11-15 经所有者确认。
- **（logo 定稿）**：按所有者要求「⇄ 形状 + 草方块配色」重画 20×20 图标：石头槽位（黑边 + 青色斜面）内含绿色「→」与棕色「←」，配 `modreloader_logo_hovered.png` 悬停态；曾试过草方块、等距方块、环形箭头、徽标叠加等方案，环形箭头在 20px 下无法画干净（成 "C/Q" 带刺），最终改用 ⇄。所有者确认后提交。
- **（服务端热加成功路径）**：新增最小服务端安全夹具 `experiments/server-fixture/`（`@Mod` + 构造日志，无客户端类/注册表/mixin/payload；`build.sh` 用 `serverLegacyClasspath.txt` 编译并打包）。实测 17:05：`runServer -PautoAdd=...servertest-1.0.0.jar` → `hot-added [servertest]`、`isLoaded=true`、`keyMappings -1 -> -1`（客户端专用路径被正确跳过）。已知问题 14 收窄为「启动期事件无法重放」。
- **（服务端热加：命令重放）**：所有者指出原夹具「没有实用意义」——它只证明构造能跑。改成真实形态夹具（构造器里注册 `/servertest` + 监听 `ServerTickEvent`），先实测出边界：tick 能到、命令收不到。随后新增 `server/ServerCommands`：启动时抓 dispatcher/selection/buildContext，热加后重放 `RegisterCommandsEvent`。实测 17:12：命令注册成功（`root commands 93 -> 94`）、tick 持续到达、无重复注册异常。已知问题 14 收窄为「payload / 一次性事件 / 注册表仍不行」。
- **（payload 精确拒绝）**：所有者追问「热加的 mod 注册不了 payload，那我们在干嘛」。复查代码后修正了说法——不是「事件收不到」，而是 NeoForge 在 `NetworkRegistry.setup` 之后硬性禁止：`Cannot register payload ... after registration phase.`。新增 `FmlEvents.probeNetworkPayloads`（重放 `RegisterPayloadHandlersEvent` 探测）+ `FailureKind.NETWORK_PAYLOAD` + 中英文案；负向夹具 `build-payload.sh`。实测 17:19 被拒绝且回滚干净、17:20 命令夹具仍成功（无误伤）。
- **（payload 适配 E1 通过）**：所有者追问「能不能主动适配」，于是做了 `hotadd/NetworkAdaptation`：反射置 `NetworkRegistry.setup=false` → 重放 `RegisterPayloadHandlersEvent` → 置回 true，并 diff 出新增 payload id。新增 `-PverifyPayload` + `dev/PayloadCheck`；夹具改成真的发包（玩家加入时发、客户端收到写 marker）。实测 17:39：`adapted payloads for payloadfix: [payloadfix:ping]` → `PING RECEIVED on side client` → RESULT: PASS。边界：单 JVM（单人）、且热加在建连接之前；跨进程与「连接后热加」仍未验证。
- **（payload 适配 E2 通过）**：新增 `ActiveConnections`（服务端玩家连接 + 客户端连接）与 `patchConnections`，把新注册的 payload 补进已建立连接的 `PayloadSetup`。夹具增加周期性发包（并用 `NetworkRegistry.hasChannel` 守卫，避免在未协商时发送）。实测 18:12：进世界后热加 → 两条连接被修补 → 3s 后客户端收到包 → PASS。排障记录：之前几次运行卡在建世界，原因是**热加触发的资源包重载遮罩未清**时就去开建世界屏；harness 已加等待遮罩清除。
- **（payload 适配 E3：跨进程通过）**：新增 `dev/RemotePayloadCheck` + `-PverifyPayloadRemote`，客户端在标题屏热加后程序化连接 `127.0.0.1:25565`；专用服用 `-PautoAdd` 热加同一个 jar。实测 20:20：客户端 `joined 127.0.0.1:25565` → `PING RECEIVED on side client` → PASS。说明：两个 JVM 无共享静态状态，通道是靠各自注册 + 连接时协商成立的；夹具用 `hasChannel` 守卫发包、服务端周期发包，客户端收到的 marker 写 `payload-received-remote.txt` 以免与服务端同名文件混淆。
- **（架构收口：引擎边界 / Capabilities / dev 源集 / 单测）**：按 [tech-debt.md](tech-debt.md) 的顺序做了四项。① **P0-2**：新增 `hotadd/PlatformHooks`（`keyMappingCount` / `acceptClientLifecycle` / `injectResourcePack` / `replayCommands` / `extraConnections`），`ClientPlatformHooks` + `ServerPlatformHooks` 由两侧 bootstrap 注册；`HotAddEngine`、`FmlEvents`、`ActiveConnections` 不再 import client/server 类型；资源包注入搬到 `client/ClientResourcePacks`。实测：客户端 `-PverifyAddCommand` PASS（keyMappings 34→34、pack 选中）、服务端 `-PautoAdd=servertest` PASS（`root commands 93 -> 94`）、payload E2 `-PverifyPayload ... -PverifyPayloadAfterJoin=true` PASS（`patched payload setup` → `PING RECEIVED`）。② **P0-1**：`capability/Capabilities` 启动探测五组反射锚点，日志 `capabilities: module_injection=ok modlist_rebuild=ok network_adaptation=ok mixin_hotadd=ok resource_packs=ok`（服务端 `resource_packs=n/a`）；`FailureKind.CAPABILITY` + 中英文案；module/modlist/mixin 缺失时 fail-fast，资源包缺失只跳过。③ **P0-3**：`dev/` 8 个 harness 移到 `src/dev/java`，Gradle `dev` source set + `check` 依赖 `compileDevJava`；主源集只按名字反射 `DevEntry.install()`；生产 jar 0 条 `dev/modreloader/dev/`。④ **P2-8**：`src/test/java` 三个 JUnit 测试（`./gradlew test` 3 passed）；`FailureAnalyzer` 的失败键映射抽到 `diagnose/FailureKeys` 以便无游戏测试。另：`build.gradle` 给 JUnit 用 `exclusiveContent` 指向 Maven Central —— 本机 `maven.neoforged.net` 的 DNS 被劫持（解析到无关 IP），不隔离时依赖解析会卡在 SYN_SENT 数分钟。

- **（T4 调研开题：注册表适配评估）**：新增 `src/dev/.../RegistryProbeCheck`（`-PverifyRegistryProbe=true`）只读输出注册表事实：BLOCK 1060 / ITEM 1333 均 frozen、`registryAccess(BLOCK)` 与 `BuiltInRegistries.BLOCK` 同实例、blockstate id map 26684（stone=1）、89 个内置注册表、`RegisterEvent` 构造器包私有、`unfreeze()` public、BLOCK bake callback 1 个。受控实验（`-PverifyRegistryRefreeze=true`，一次性世界）调用 `GameData.unfreezeData()`/`freezeData()`：15 ms 完成、不抛异常，但 **BlockState id 漂移**（stone 1→26685，grass 9→26693），因为 `BlockCallbacks.onBake` 用 `IdMapper.add()` 无重复检查。由此得出：适配必须先清空并重建 `BLOCKSTATE_TO_ID_MAP`；注册表无法撤销 → 单向提交；登录配置阶段的 `FrozenRegistryPayload` 是唯一同步机会（连接后热加无法同步）。产出 [research-t4-registry.md](research-t4-registry.md) 与 [ADR-0008](decisions/0008-t4-registry-adaptation-poc.md)（Proposed）。

- **（T4 注册表热加跑通：server-only add-only）**：新增 `hotadd/RegistryStage`：构造前 `GameData.unfreezeData()`（支持构造器里直接 `Registry.register`），构造后按 `GameData.getRegistrationOrder()` 的 89 个注册表逐个 `container.acceptEvent(new RegisterEvent(...))`（构造器包私有，trusted lookup），然后清空 `BlockCallbacks.BLOCKSTATE_TO_ID_MAP` 再 `GameData.freezeData()`（必须先清空，否则 state id 漂移）。`HotAddEngine` 按 `ModInspector` 的 registry 预测决定是否开窗口，新增 `Stage.REGISTRY` / `FailureKind.REGISTRY` / `Capabilities.REGISTRY_ADAPTATION`，`FmlEvents` 拆成 construct/finish 以便把注册表阶段插在 common setup 之前。新增夹具 [experiments/t4-fixture](../experiments/t4-fixture/README.md)（一个方块 + BlockItem + 放置/存盘/读档自检）。实测 21:48：blocks 1060→1061、items 1333→1334、states 26684→26685、stoneId 1→1 稳定；放置 `(0,100,0)` 存盘；fixture 放进 `run/mods` 重启后 `isT4Block=true`、entryId/stateId 不变，RESULT: PASS。限制：仅服务端、add-only、不可回滚、datapack/客户端未做、UI 仍拒绝 T4。文档：[features/t4-registry-hotadd.md](../docs/features/t4-registry-hotadd.md)，ADR-0008 转为 Accepted。

- **（T4 UI 放行）**：`ModInspection.canHotLoad(boolean allowT4)` 与 `ModReloaderI18n.blockReason(inspection, allowT4)` 增加 T4 分支；UI/`LoadController`/`scan` 命令都读 `Capabilities.REGISTRY_ADAPTATION` 决定是否放行；新增 `modreloader.preflight.t4_loadable` 中英文案（实验性 / 仅新增 / 存档依赖 / 不可回滚）；T4 + mixin 仍要求 JBR + MixinRuntime。`WatchCheck` 增加 `-PwatchModId`，并新增 `docs/features/images/t4-registry-prompt.png`。实测 22:18：watcher 检测 → 提示屏显示 `[T4]` 与警告 → Load → registry 窗口（blocks 1060→1061、stoneId 1→1 稳定）→ `WatchCheck RESULT: PASS`。注意：Load 仍是单击提交，没有确认框。

- **（T4 客户端路径：资源包 + 创造栏）**：`PlatformHooks.afterRegistryContent()`（client：`CreativeModeTabRegistry.sortTabs()` + 对每个 tab `buildContents(ItemDisplayParameters)`；server：no-op）在 registry commit 之后调用，失败只 warn。fixture 增加 blockstate/model/lang 与 `BuildCreativeModeTabContentsEvent` 监听（把物品加进 `minecraft:building_blocks`）。实测 22:21（单人/集成服务器，in-world `/modreloader add`）：`registry commit ... stoneId 1 -> 1 (stable)` → `[T4Fixture] added item to creative tab minecraft:building_blocks` → `rebuilt 14 creative tab(s)` → `resource packs selected after add: [... mod/t4fixture]` → `AddCmdCheck RESULT: PASS`。多人登录同步仍未验证。

- **（分级改称嫔/妃/贵妃/皇后）**：T0/T1 属于原版能力（资源/数据重载），不再进入本项目的分级，从嫔级开始；T2/T3/T4/T5 改称 **嫔 / 妃 / 贵妃 / 皇后**。代码：`ModInspection.Tier` 改为 `PIN/FEI/GUIFEI/HUANGHOU`，新增 `modreloader.tier.*`（zh 嫔/妃/贵妃/皇后，en Concubine/Consort/Noble Consort/Empress），提示屏行标签与预检摘要都用本地化名字；`canHotLoad(allowRegistryContent)` / `blockReason(..., allowRegistryContent)`。分析器：输出改为 `RESOURCE/DATA/PIN/FEI/GUIFEI/HUANGHOU`（RESOURCE/DATA 是原版能力），表格列宽与 summary 同步。文档：glossary/design/status/AGENTS/README/features 全量改用新名字，历史文档（history/research/ADR）保留旧写法，glossary 给出映射。实测 22:51：T4 fixture 走 GUI，提示屏显示 `[贵妃]` 与 `t4fixture 1.0.0 · 贵妃 · registry`，`WatchCheck RESULT: PASS`；截图 `docs/features/images/t4-registry-prompt.png`。

- **（真实 dogfood：无中生有·众星捧月 1.5.0 注册层热加）**：对 `exnihilosatellitium-1.21.1-1.5.0.jar`（80 方块 / 181 物品 / 无 mixin）跑贵妃级路径。标题屏 `-PautoAdd` 与进世界 `/modreloader add` 都 PASS：`registry commit: blocks 1060 -> 1140, items 1333 -> 1514, states 26684 -> 26818 (cleared 26684), stoneId 1 -> 1 (stable)`；payload 适配 `exnihilosatellitium:set_machine_output`；`rebuilt 15 creative tab(s)`；资源包 `mod/exnihilosatellitium` 选中并重载；`AddCmdCheck RESULT: PASS`。**限制**：299 个配方 / 77 个战利品表 / 标签 / 自定义机器数据都是 datapack 内容，热加后不在当前数据包列表里，所以「能注册」不等于「能完整游玩」；放置/破坏/合成未实测，完整生效仍需重启。记录见 [features/t4-registry-hotadd.md](../docs/features/t4-registry-hotadd.md) §5。

- **（datapack 热加载跑通：注入 pack + 原版 `/reload`）**：新增 `hotadd/DatapackStage` 与 `PlatformHooks.reloadDataPacks`（client 取集成服务器、server 取 `ServerLifecycleHooks`）：把热加 jar 作为 `Pack`（`ResourcePackLoader.createPackForMod` + `readWithOptionalMeta`，缺 `pack.mcmeta` 也能用）加到服务器 `PackRepository`，`addPackFinder` + `reload()` 后调 `MinecraftServer.reloadResources(selectedIds)`；新增 `Stage.DATAPACK` / `FailureKind.DATAPACK` 与中英文案（`LangKeysTest` 覆盖）。三个不显然的点：① 必须在 `RegistryStage.commit` 之后，否则 recipe/loot 里的 item id 解析成 air；② GUI/harness 在客户端线程热加，`DatapackStage` 因此把注入投递到服务器线程并等待（`PackRepository`/`ReloadableServerResources` 不是线程安全的）；③ 标题屏没有 server → 记 pending、`ServerStartedEvent` 再注入；但 `ClientResourcePacks.inject` 会把 mod 塞进 `ResourcePackLoader.modResourcePacks`，世界加载时 `mod_data` 已带上它，所以注入前先数 namespace 数据，已在则 `already live` 短路（不多付一次重载）。
  证据（2026-11-15 23:29，单人集成服务器）：`-PverifyAddCommand=...exnihilosatellitium...jar -PverifyAddCommandModId=exnihilosatellitium -PverifyDatapack=true` → `datapack reload: pack mod/exnihilosatellitium selected; recipes 0 -> 164, loot tables 0 -> 75, block tags 0 -> 1, item tags 0 -> 2`；同一次重载里 Ex Nihilo 自己的 hammer(55)/sieve(7 inputs/119 drops)/barrel(16)/crook(10)/crucible(39+9)/compost(67) 全部重读（`FMLModContainer.constructMod` 会 `AutomaticEventSubscriber.inject`，所以 `@EventBusSubscriber` 在热加时也会注册）；harness 断言真实 3×3 合成网格产出 `exnihilosatellitium:wooden_crucible`、该方块战利品表掉落自身 → `datapack RESULT: PASS`。164 不是丢数据：jar 里 298 个 recipe 文件有 134 个带 `neoforge:conditions`（依赖未装的模组），静态核对期望值正是 164 / 跳过 134；75 个 loot table 是全部。标题屏路径（`-PverifyDatapackAtTitle=true`，23:42）走 `already live (recipes=164, loot tables=75, block tags=1, item tags=2); no reload needed`，同一套断言 PASS。专用服 t4-fixture 补了最小 datapack（recipe / loot_table / tags，**故意不带 `pack.mcmeta`**）：热加后 `recipes 0 -> 1, loot 0 -> 1, block tags 0 -> 1`、`datapack (after hot-add) RESULT: PASS`；复制进 `run/mods` 重启后 `isT4Block=true`（entryId 1060 / stateId 26684 不变）+ `datapack (after restart) RESULT: PASS`，成功标准 4（存盘 → jar 进 mods/ → 重启 → 存档仍可读）成立。
  排障：标题屏 harness 第一次卡在 `Minecraft.doWorldLoad` 的 `for (; !server.isReady() || overlay != null;)` 循环——热加触发的客户端资源包重载遮罩还没清就去建世界，而 `runTick(false)` 不会清遮罩；harness 改为标题屏热加后等 `mc.getOverlay() == null` 再建世界（人类也点不穿遮罩），并加 `already live` 短路避免多余重载。`Capabilities` 新增第六组 `datapack_reload`（`reloadResources`/`reloadableRegistries`/`addPackFinder`/`createPackForMod`/`readWithOptionalMeta`），带 `data/` 的 jar 在 VALIDATE 就先检查，避免"注册表已提交才发现锚点漂移"；启动日志变成 `… registry_adaptation=ok datapack_reload=ok resource_packs=ok`（23:48 复跑确认）。文档：[features/t4-datapack-hotadd.md](features/t4-datapack-hotadd.md)、[ADR-0009](decisions/0009-datapack-hotadd.md)（Proposed，含"为什么不用方案 B"）；已知问题新增 19（RELOADABLE vs WORLDGEN、存档记录 `mod/<modId>`、重启时可能重复列出但对加载无影响）。边界：add-only、不可回滚、世界生成注册表（维度/生物群系/结构）仍需重启、连接后注册表快照同步仍未做。

- **（dogfood 玩法验收 + 创造页签崩溃修复）**：新增 `dev/GameplayCheck`（`-PverifyGameplay`，配合 `-PverifyAddCommand` / `-PverifyGameplayModId`；`-PverifyGameplayKeepOpen` 检查完留在世界）：热加众星捧月后在**服务器线程**驱动真实交互，而不是只看计数。① `/exnihilo tables`（mod 自己的诊断命令，走 replay 后的 dispatcher + 自定义 `CommandSource` 捕获输出）→ `sieve 7 inputs / 119 drops; hammer 55; crook 10; barrel 16; compost 67; crucible 39 melt / 9 heat`（顺带证明 datapack reload 重建的 `Commands` 会重新 fire `RegisterCommandsEvent`，mod 命令没丢）；② 铁锤砸圆石 → `BlockDropsEvent` 被换成 `minecraft:gravel x1`；③ 筛子：放 `exnihilosatellitium:sieve`、装 `iron_mesh`、放 `minecraft:dirt`（只有已注册输入才被接受）→ 每 3 tick 点一次维持 `activeUntil`，240 tick 后掉 `cobblestone_pebble x6 / andesite x5 / granite x5 / diorite x6 / deepslate x3 / grass_seeds x1`；④ 真实 3×3（铁锭×2 + 木棍×2）命中 `iron_hammer`。排障：第一版 `contains("minecraft:gravel")` 与掉落描述 `"<id> x<count>"` 不匹配；craft 网格把 `" yx"` 的 y 放到 index 3（应为 4）；`server.execute()` 在服务器线程上是**同步执行**，自递归的 `keepUsing` 链在一个 tick 内跑完导致筛子不推进——改成从客户端 tick 每 3 tick 投递一次。
  手动验收（所有者把 keep-open 的世界开了 LAN、切创造模式、打开创造物品栏）**抓到真 bug**：`ArrayIndexOutOfBoundsException: Index 7 out of bounds for length 7`（`CreativeModeInventoryScreen.renderTabButton`）。根因在我们的 `afterRegistryContent()`：`CreativeModeTabRegistry.sortTabs()` 每次调用都 `add` HOTBAR/SEARCH/OP_BLOCKS/INVENTORY 到 `DEFAULT_TABS` 且不清空，正常启动 4 个、热加后再调一次变 8 个；创造界面用 `(index % (size/2)) + 5` 给非当前页的默认页签算列，8 个时出现列 7/8，而 `renderTabButton` 只有 7 张贴图、`Mth.clamp(i, 0, length)` 上界写成了 `length` → 越界。修法：`ClientPlatformHooks.afterRegistryContent()` 调 `sortTabs()` 前先用 `UnsafeAccess.getStatic` + `clear()` 清空 `DEFAULT_TABS`（失败只 warn），使其幂等。新增 `-PverifyCreative`：切创造、断言 `defaults == 4`、真的 `mc.setScreen(new CreativeModeInventoryScreen(...))` 渲染 40 tick；实测修前 `defaults=8` 崩、修后 `defaults=4` + `creative RESULT: PASS`。文档 [features/dogfood-gameplay.md](features/dogfood-gameplay.md)；status 新增表尺行与已知问题 20；手动项（桶/坩埚/堆肥/网耐久）列入「未验证」。

- **（机器 GUI：重放 `RegisterMenuScreensEvent`）**：手动验收打开 `exnihilosatellitium:fuel_machine` 时日志只有 `Failed to create screen for menu type: exnihilosatellitium:fuel_machine`，界面不出来。根因：`MenuScreens.init()`（`@ApiStatus.Internal`，启动时调一次）才 fire `RegisterMenuScreensEvent`，把 screen 工厂写进 `MenuScreens.SCREENS`；热加 mod 的 `@EventBusSubscriber` 注册了但事件早过了，所以它的 2 个 GUI 没有工厂。修法：`ClientPlatformHooks.acceptClientLifecycle()` 里重放该事件——不能直接用真 map（`SCREENS.register` 对已存在 key 抛 `Duplicate attempt to register screen`，事件又是全局的，会把已加载 mod 的监听器再跑一遍），改成先投递到**临时 map** 再把真 map 里没有的条目合并进去；失败只 warn。实测：`menu screens replayed: 2 new, 27 total`，`GameplayCheck` 断言 `2 type(s) in exnihilosatellitium, missing=[]`，`creative RESULT: PASS` 不变。同时把「其它客户端注册事件（BE 渲染器/颜色/客户端扩展/客户端 reload listener）未重放」记进 status 已知问题 21——它们同样是一次性事件，但 key 重复会抛异常或依赖启动时建好的 dispatcher，需要单独设计。

- **（客户端注册重放：渲染器 / 染色 / 客户端扩展）**：手动验收还发现桶/坩埚/筛子的**内部内容不渲染**、瓷桶颜色不对。根因属于同一类「启动期一次性事件」，但比 GUI 更绕：provider 表（`BlockEntityRenderers.PROVIDERS`）重放是纯 `put`、安全，**可真正的渲染器实例是启动时由 `BlockEntityRenderDispatcher.onResourceManagerReload` 从 provider 烤出来的**——只放 provider 不重建 dispatcher 就永远不会实例化。新增 `client/ClientRegistrationReplay`：① 重放 `EntityRenderersEvent.RegisterRenderers` 后显式调 `BlockEntityRenderDispatcher` / `EntityRenderDispatcher` 的 `onResourceManagerReload` 重建实例；② 重放 `RegisterColorHandlersEvent.Block/.Item`（`ItemColors`/`BlockColors` 纯 put，幂等）；③ `RegisterClientExtensionsEvent` 的四个静态 map（`block/item/mob_effect/fluid_type`）重复 key 会抛异常，改成「换成临时 map → 重放 → 把原 map 没有的条目合并回去 → 还原」。`GameplayCheck` 增加断言：mod 的每个 BE type 都要在 dispatcher 的 renderers map 里、`ItemColors` 里要有瓷桶、`FLUID_TYPE_EXTENSIONS` 里要有 mod 的 3 个流体。实测：`renderers replayed; block entity renderers rebuilt`、`client extensions replayed: 3 new`、`beRenderers 6/6 (missing=[])`、`porcelainBucketTintRegistered=true`、`fluidExtensions=3`，`GameplayCheck RESULT: PASS` + `creative RESULT: PASS`。排障：第一版断言用 `getColor(空桶,1) != -1`，但 handler 对空桶返回 `0xFFFFFFFF`（Java 里就是 -1），改成查 `ItemColors` 的私有 map 是否含该 item。仍未重放：`RegisterClientReloadListenersEvent`（真 manager 会重复注册，需要临时 manager + diff）、`RegisterLayerDefinitions`（模型层启动时已 bake）、`ColorResolvers`——记进 status 已知问题 21。

- **（物品图标 missing model：补注册 shaper）**：所有者反馈「模组物品栏贴图全为紫黑方块、方块模型正常」。根因又是启动期快照，但在 `ItemRenderer`：它的构造器遍历 `BuiltInRegistries.ITEM`，把每个 item → `ModelResourceLocation.inventory(id)` 注册进 NeoForge 的 `RegistryAwareItemModelShaper`；热加的 181 个物品不在那次遍历里，`getItemModel(item)` 返回 null → `getModel` 退化成 `ModelManager.getMissingModel()`（紫黑立方体）。方块模型不受影响，因为它们是资源重载时按 blockstate JSON 解析的。修法：`ClientRegistrationReplay.registerItemModels` 把 shaper 里还没有的 item 补注册；热加触发的客户端资源重载会走 `ItemRenderer.onResourceManagerReload → rebuildCache()` 把它们解析成真实模型（所以补注册可以在重载之前）。`GameplayCheck` 增加断言，且放在资源重载之后（`verify` 阶段）而不是 add 后立刻。实测：修前 `item models: 0/181 resolved, missing=[...]` FAIL；修后 `item model shaper: registered 181 new item(s)`、`item models: 181/181 resolved, missing=[]`、`RESULT: PASS`。文档 dogfood-gameplay §2.4、status 表尺/已知问题 21。

- **（配置未加载：放自动机器就崩）**：所有者反馈放自动筛（`electric_sieve`）后客户端崩：`Description: Rendering Block Entity` → `IllegalStateException: Cannot get config value before config is loaded`（`MachineRenderer.render` → `MachineBlockEntity.ticksPerOperation` → `ConfigValues` → `ModConfigSpec$ConfigValue`）。根因：`ModContainer#registerConfig` 只把 spec 登记进 `ConfigTracker`，真正的加载在 FML 启动流程里；热加 mod 的 config 被登记但从没加载，第一次读值就抛（服务器线程是 BE tick 崩，客户端是渲染线程崩）。修法：新增 `hotadd/ModConfigs`，在 `FmlEvents.construct` 之后从 `ConfigTracker.INSTANCE.configsByMod` 取该容器登记的 `ModConfig`，对未加载的调用包私有 `ConfigTracker.openConfig(config, configDir, null)`（内部 resolve/load/post `ModConfigEvent.Loading`，并挂 FileWatcher）；**不要再调 `loadConfig`**——第一版这么写过，会在文件刚创建时二次读取并触发 `ParsingException → recreate`，报 `DirectoryNotEmptyException: run/config`。`SERVER` 类型配置需要世界的 `serverconfig` 路径（`ServerLifecycleHooks.getServerConfigPath` 私有），现在跳过 + warn。排障细节：`ConfigTracker.configsByMod` 是**实例字段**（不是 static），`UnsafeAccess.getStatic` 会抛 `IllegalArgumentException`，要用 `UnsafeAccess.get(ConfigTracker.INSTANCE, ...)`。验证：`loaded 1 config(s) for exnihilosatellitium`、`GameplayCheck client registrations: configs 1/1 loaded`；`GameplayCheck` 现在会在测试田里放一台 `electric_sieve` 并让它 tick+渲染 140 tick（读配置），`RESULT: PASS`、`creative RESULT: PASS`，筛子仍正常出 5 种卵石 + 胡萝卜。文档 dogfood-gameplay §2.5、status 表尺/已知问题 22。

- **（客户端 reload listener 重放）**：补完「客户端启动期注册」的最后一项。`RegisterClientReloadListenersEvent` 同样只在启动时 fire 一次；众星捧月用它把机器表在客户端再读一遍（`clientOnly = true`，给 JEI / 连专用服时用），热加后这 6 个 listener 不存在。修法：`ClientRegistrationReplay.replayClientReloadListeners` 先建一个临时 `ReloadableResourceManager` 投递事件，再按**类名**去重把真 manager 里没有的 listener 注册进去（事件每次给新实例，按身份去重无效；同一类注册两次会在每次资源重载里跑两遍）。实测：`client reload listeners replayed: 6 new, 33 total`、`GameplayCheck client registrations: ..., clientReloadListeners=6`、`RESULT: PASS` / `creative RESULT: PASS`。同一次会话的手动验收（09:22–09:28，6m56s）正常退出、无崩溃、无新 crash-report。文档 dogfood-gameplay §2.3/§4、status 表尺/已知问题 21。

- **（热加兼容性矩阵首轮：AT 是硬边界，JiJ 是可补缺口）**：新增 `dev/CompatCheck`（`-PverifyCompat=<jar>[,<jar>]`）：标题屏逐个热加、记录预测 tier / 阶段 / kind / 注册表增量 / isLoaded，不按预测改行为。用本机 `../Ex-Nihilo-Satellitium/run/mods` 的 9 个真实 NeoForge 1.21.1 模组实测：**ZeroCore2（贵妃、含 1 个 mixin）PASS**（items 1333→1334，redefine Slot/GuiGraphics/ModelBakery）；**6 个带 `META-INF/accesstransformer.cfg`**（Jade/Mekanism/AE2/guideme/ExtremeReactors2/JEI），Jade 与 JEI 实测被拒绝——原先会在构造时 `IllegalAccessError`（Jade 复现，且失败发生在 mixin 注入之后，回滚清不掉注入 handler，客户端随后 `NoClassDefFoundError: snownee/jade/util/JadeFont` 崩）；**jecharacters** 在 CONSTRUCT 失败 `NoClassDefFoundError: me/towdium/pinin/Keyboard`（`META-INF/jarjar` 内嵌依赖没被抽出）；**MekanismGenerators** 因缺 mekanism 在 MODLIST 报含糊的 `no ModContainer built`。修复：① 新增 `FailureKind.ACCESS_TRANSFORMER`（+ 中英文案 + `FailureKeys`），`HotAddEngine` 在**任何变更前**按 `META-INF/accesstransformer.cfg` 拒绝，`ModInspection.accessTransformers` + `canHotLoad` + `blockReason` 进 UI 预检；② 缺依赖也在 VALIDATE 直接 `MISSING_DEPENDENCY` 并列出所需 id/版本；③ 失败信息在「mixin 已注入」时明确写 "restart the game" 并打 ERROR（注入的代码无法撤销）。证据：Jade `stage=VALIDATE kind=ACCESS_TRANSFORMER`、blocks/items 不变、客户端不崩；MekanismGenerators `kind=MISSING_DEPENDENCY needs [mekanism [10.7.19]] first`。结论写进 [features/hotadd-compatibility-matrix.md](features/hotadd-compatibility-matrix.md)：AT 是硬边界（6/9），JiJ 可补，tier 预测不覆盖加载期依赖；status 新增表尺行 / 已知问题 23 / 下一步 8（JiJ 抽取）。

- **（JiJ 内嵌依赖抽取：jecharacters 从 CONSTRUCT 失败转为 PASS）**：矩阵首轮留下的可补缺口。FML 正常启动由 `JarInJarDependencyLocator` 读 `META-INF/jarjar/metadata.json`、把嵌套 jar 注册成独立 mod file 并放进 game layer 的 module path；热加的 mod 没走过这条路，所以 `jecharacters` 构造时 `NoClassDefFoundError: me/towdium/pinin/Keyboard`（`PinIn-1.6.0.jar`、`JustEnoughCharacters.coremod-neoforge.jar` 两个都没有 `mods.toml`，是纯库）。做法：
  ① 新增 `hotadd/JarInJar`：用 NeoForge 自己的 `net.neoforged.jarjar.metadata.MetadataIOHandler` 解析（不自己拼 JSON），把每个嵌套 jar 抽到 `<gamedir>/modreloader/jarjar/<sha256 前 16 位>/<原文件名>` 再 `SecureJar.from(...)`。
  路径按**内容哈希**寻址是有意的：JVM 不能 undefine module，`ModuleInjector` 用「同名 module 必须同路径」判定能否复用；如果每次 add 都抽到随机 tmp 目录，回滚后重试就会撞 `Module name PinIn was already used by ...`。声明路径做了越界检查（绝对路径 / `..` / 带 `:` / 非 `.jar` 一律拒绝），`isObfuscated: true` 与缺失条目只 warn 跳过。
  ② `ModuleInjector.addToGameLayer(Collection<ModFileInfo>)` 增加 `(files, Collection<SecureJar> extraJars)` 重载（旧签名委托），把嵌套 jar 和 mod 的 module 一起注入（双向 read 边 + `resolvedRoots` + `packageLookup`）。额外 jar 的包用 `putIfAbsent`：万一嵌套库和 mod 自己的包撞了，保留 mod 为主，只 warn——免得悄悄顶掉外层 mod 的类。
  ③ 新增 `FailureKind.JAR_IN_JAR` + 中英文案（`FailureKeys` + `LangKeysTest` 覆盖）+ 3 个无游戏单测（`JarInJarTest`：路径校验与文件名净化），单测从 3 个变 6 个。
  证据（2026-11-16 09:56）：`./gradlew runClient -PverifyCompat=<jecharacters jar>` → `jar-in-jar: 2 nested jar(s) from jecharacters-neoforge-4.5.15.jar -> modules [JustEnoughCharacters.coremod.neoforge, PinIn]`、`hot-added [jecharacters] (modules [jecharacters, PinIn, JustEnoughCharacters.coremod.neoforge])`、`isLoaded=true`、`mod/jecharacters` 资源包选中并重载、`[CompatCheck] RESULT ... ok=true`（整轮 13 s，无异常）。回归 09:57：同一进程先 ZeroCore2（mixin redefine `Slot`/`GuiGraphics`/`ModelBakery`）再 jecharacters，两个都 `ok=true`——说明新重载没动 mod file 那条路。
  另跑了一次「回滚后重试」：`-PverifyCompat=<jecharacters>,<jecharacters> -PfailAfterModList=true` → 两次都在 `stage=MODLIST` 被注入的失败打断，`rollback: mod list ok` / `rollback: module layer ok`，第二次**没有** `Module name ... already used`——内容寻址路径让 module 复用成立（09:58）。
  边界（写进 status 已知问题 23 / 矩阵 §3.2）：嵌套 **mod**（带 `mods.toml`）只拿到 module、没有 `ModContainer`，`@Mod` 构造器不跑；coremod（`FMLCorePlugin`）不会运行 → jecharacters 的 `JeiTransformer` 不生效，「能热加」不等于「拼音搜索能用」，功能侧没有验证。

- **（MixinRuntime 反射化：构建不再需要 `libs/`）**：已知问题 12 的最后一条。原先 `build.gradle` 有 `compileOnly files('libs/MixinRuntime-1.0.2-mc1.21.jar')`，而 `libs/` 被 gitignore → fresh clone 直接编不过（24 个符号错误），CI 也就无从谈起。做法：① 新增 `hotadd/MixinRuntimeApi`，用 `UnsafeAccess.trusted()` 的 lookup 在**调用时**解析 mr 的 10 个方法（`MixinConfigUtils.onSelect/prepare`、`MixinProcessorUtils.getProcessor/getConfigs/getPendingConfigs/setConfigs/setPendingConfigs/addIntoProcessor`、`MixinTransformerUtils.getTransformer/transform`），参数类型用 Mixin 自己的 `IMixinConfig`/`IMixinTransformer`（mr 和我们在同一个 game layer，Class 对象相同，所以签名精确匹配）；② `probe()` 永远不抛，返回人话的缺失项，`Capabilities.checkMixinHotAdd` 直接读它——这样反射漂移在启动时就变成 `mixin_hotadd=missing`，而不是应用 mixin 途中的 `NoSuchMethodError`；③ `hotadd/ClassBytes` 接管了原先借 mr `InstUtils.getClassBytes` 的「读已加载类字节」：挂一个只记录 bytes 的 pass-through `ClassFileTransformer`（返回 null = 不改字节）再 `retransformClasses` 目标类，纯 JVM API；dev 侧的 `RollbackCheck` 也改用它；④ 删掉 `compileOnly`。
  两个刻意的设计点：`probe()` 不碰 `JvmAccess`（否则启动期做能力检查就会自附加 agent，慢且意外），模块 read edge 只在真正调用 mr 时才加；`ensureReadable` 失败只 warn——trusted lookup 本身不需要它，留着是为了别的直连路径。
  证据：把 `libs/` 移到 `/tmp` 后 `./gradlew clean build` → **BUILD SUCCESSFUL**、6 个单测全过（10:10）。有 mr 的妃级全路径：`-PautoAdd=<lsp> -PverifyRollback=true -PfailAfterModList=true` → `capabilities: ... mixin_hotadd=ok`、`RollbackCheck` 先断言回滚干净、再重试 add 成功（`redefined [ShareToLanScreen, UUIDUtil, AbstractWidget, PlayerList]`）、`RESULT: PASS`（10:11）——这一条覆盖了 probe/register/prepare/onSelect/getTransformer/addIntoProcessor/transform/ClassBytes 以及回滚里的 `setConfigs`/`setPendingConfigs` 和「复用已 prepare 的 config」重试路径。缺 mr 的负向路径：移走 `run/mods` 里的 mr 后 `capabilities: ... mixin_hotadd=missing`、妃级被拒、`AddCmdCheck RESULT: PASS`（10:13），跑完把 jar 放回。`libs/` 目录现在没有任何引用，可删。

- **（AT「晚注入」量测：结论是不做）**：矩阵 §3.1 说 AT 是硬边界，但「只对热加后才加载的类注入 AT」还有一个理论窗口，所以先量再决定。新增只读 `dev/AtSpikeCheck`（`-PverifyAtSpike=<jar>[,<jar>]`）：解析每个 jar 的 `META-INF/accesstransformer.cfg`（`<modifier> <class> [member]`，`/` 归一成 `.`），在**标题屏**和**进世界后**各数一次 `Instrumentation.getAllLoadedClasses()`，按目标类统计（AT 是类粒度生效的，所以类粒度正是正确指标），并按 vanilla/neoforge/other 分桶、打印未加载样本。为了让「进世界」那组数字真实，harness 用 `CreateWorldScreen.openFresh` 建一个测试世界（和 `-PverifyAddCommand` 同款，会留下 `run/saves` 存档）。
  实测（10:14–10:15，6 个带 AT 的本机 jar）：合计 295 条规则 / 151 个目标类；**标题屏 122/151 已加载（19% 未加载）**，**进世界 139/151 已加载（8% 未加载）**。逐 jar 未加载占比：Jade 18%→8%、JEI 25%→8%、Mekanism 16%→7%、AE2 27%→8%、guideme 6%→6%、ExtremeReactors2 100%→100%（唯一目标是 datagen 的 `RecipeProvider`，运行期永不加载）。未加载的集中在客户端 GUI（`EditBox`/`CreativeModeInventoryScreen`/`ConnectScreen`）、datagen/gametest（`RecipeProvider`/`BlockModelGenerators`/`GameTestHelper`）、服务端类（`ChunkMap`/`ServerGamePacketListenerImpl`）——恰恰是客户端热加基本不会碰的。
  结论：**不实装**。① 收益上限就是未加载占比，最好时机也只有 19%；② 只要 mod 构造期用到的那个目标类已加载（Jade 实测就是），补上其它类的 AT 也救不回来；③ 代价是要在类定义期挂 transformer，与 NeoForge patch 流水线和 Mixin 变换顺序竞争，而失败点会落在「注册表已提交、不可回滚」之后——不如把资源投到软重启（ADR-0006）。
  顺带发现（记录、未做）：ER2 唯一一条 AT 是 datagen 用的，说明「带 AT 一律拒绝」会误伤「AT 只服务 datagen」的模组；可做的小改进是把 AT 目标组成（已加载/未加载/是否 datagen）写进拒绝文案，**放行不在结论内**。文档：矩阵新增 §8（含复现命令），status 表尺 + 已知问题 23 + 下一步 0 同步。

- **（贵妃级多人：连接后热加的注册表 id 同步）**：原本的结论是「T4 热加必须在建连接之前完成」，因为 NeoForge 只在登录的 configuration 阶段同步注册表（`SyncRegistries` + `FrozenRegistryPayload`），连接后再热加，在线客户端拿不到新 id。这次把这个「补不了」的缺口补上了。
  关键观察（读 NeoForge 源码，`neoforge-21.1.265-sources.jar`）：登录期客户端处理 `Completed` 时就是调 `RegistryManager.applySnapshot(snapshots, false)`，而 `applySnapshot` / `takeSnapshot` / `revertToFrozen` / `RegistrySnapshot.STREAM_CODEC` **都是 public**；`applySnapshot` 内部 `unfreeze → clear(false) → registerIdMapping → freeze()`，`freeze` 会跑 bake callback（`BlockCallbacks.onBake` 从注册表顺序重建 `BLOCKSTATE_TO_ID_MAP`），所以魔改后的 block state id 与登录同步走的是同一条路。缺的只是「play 阶段没有这条 payload」。
  做法：① 新增 `hotadd/RegistrySyncPayload`（一个注册表的 snapshot + `last` 标记，复用 `RegistrySnapshot.STREAM_CODEC`）与 `hotadd/RegistrySyncRequestPayload`（空 body，客户端主动拉），在 `ModReloader` 构造里用 mod bus 的 `RegisterPayloadHandlersEvent` 声明成 play 双向通道；② `RegistrySync.broadcastAfterCommit()` 在 `RegistryStage.commit` 之后取 `takeSnapshot(SYNC_TO_CLIENT)`、每个注册表一个 payload（最后一个标 `last`），发给 `PlatformHooks.registrySyncTargets()`（专用服=玩家列表；客户端=集成服务器的非 memory 连接，host 自己跳过，和 NeoForge 登录期规则一致）；③ 客户端 `ClientPlatformHooks.acceptRegistrySnapshot` 先做**两端条目集合一致**校验——`applySnapshot` 只重排 id、不删多余条目，若快照是客户端集合的子集，客户端多出来的条目会变成「没有 id」(=`getId` -1)，同样错位；不一致就完全拒绝并提示把 jar 也装到客户端，`applySnapshot` 返回 missing 则 `revertToFrozen()` 回到登录快照，**不踢人**；成功后 `afterRegistryContent()` 重建创造栏 + 聊天提示；④ 反向：客户端 commit 后 `PlatformHooks.requestRegistrySnapshot()`（单人不发，那是问自己）；⑤ `Capabilities` 新增第七组 `registry_sync`（`takeSnapshot`/`applySnapshot`/`revertToFrozen`/`STREAM_CODEC`）。
  排障：第一版 `keySetMismatch` 用 `for (Object key : registry.keySet()) if (key instanceof ResourceKey)` —— 1.21 里 `MappedRegistry.keySet()` 返回的是 **`Set<ResourceLocation>`**（`byLocation`），于是「客户端本地集合」恒为空，日志出现「server has 1333 entries the client does not」。改成直接 `new HashSet<>(registry.keySet())`。
  另一处坑：服务端侧没有 dev harness 入口（`DevEntry` 只由 `ClientBootstrap` 装，且引用了 `ClientTickEvent` 等客户端类）→ 新增 `dev/ServerDev`（只装服务端安全的 harness），由 `ServerBootstrap` 按名字反射安装。
  验证（2026-11-16 10:40，两个 JVM）：客户端进世界后按「t4fixture → 众星捧月」热加（blocks=1141/items=1515），服务端按「众星捧月 → t4fixture」热加（顺序刻意相反 → id 必然不同）。中间两次 push 被客户端正确拒绝（`client has 182 entries the server does not`，因为服务端那时还没有 t4fixture），服务端第二次 push 后 `applied registry snapshot from the server: 30 registries, 5043 entries`，然后 `ids match the server exactly (29475 lines, blocks=1141, items=1515)`、`RESULT: PASS`——29475 行 = 所有方块 id + 物品 id + 每个 block state id，全量与服端一致。服务端侧日志：`registry snapshot: 30 registries -> 1 connected player(s)` ×2、`answered a registry sync request from Dev with 30 registries` ×2、datapack 仍正常（recipes 0→164）。
  单人不回归：`-PverifyAddCommand=<dogfood> -PverifyDatapack=true` → `datapack RESULT: PASS`、`AddCmdCheck RESULT: PASS`，且只打一行 `registry snapshot: 30 registries -> 0 connected player(s)`（没有多余网络动作）。
  顺带发现（记录未改）：`runServer` 没接 JBR，专用服上 ZeroCore2 的 mixin redefine `Slot` 直接失败（`UnsupportedOperationException: attempted to change superclass or interfaces`），同一操作在 `runClient`（JBR + `-XX:+AllowEnhancedClassRedefinition`）成功 → status 已知问题 23。文档：[t4-registry-hotadd.md](features/t4-registry-hotadd.md) §9，research §1.4 加了「2026-11-16 更新」，glossary 新增「注册表快照同步」。

- **（`runServer` 接 JBR：专用服妃级热加打通）**：已知问题 22 / tech-debt 11。`build.gradle` 里原先只有 `runClient` 配了 JBR 执行器与 `-XX:+AllowEnhancedClassRedefinition`，`runServer` 用的是 toolchain JVM（日志：`JVM JetBrains s.r.o. 21.0.11; enhancedRedefinition=false`）——二进制是 JBR，缺的正是那个开关。于是同一条 `Instrumentation.redefineClasses`（ZeroCore2 给 `Slot` 加接口/字段）在客户端成功、在专用服上 `UnsupportedOperationException: attempted to change superclass or interfaces` + `ROLLBACK_PARTIAL`。
  改法：把配置抽成 `useJbr(JavaExec)` helper，`runClient` / `runServer` 共用；JBR 不存在时**只 warn、不静默降级**（原文如此，避免又出现「以为在 JBR 上其实不是」的情况）。
  证据（2026-11-16 10:58）：`./gradlew runServer -PautoAdd=<ZeroCore2>` → `[ModReloader] loaded on dedicated server; JVM JetBrains s.r.o. 21.0.11; enhancedRedefinition=true`、`[ModReloader] mixin setup: redefined=[net.minecraft.world.inventory.Slot], eager=[], deferred=[]`、`redefined net.minecraft.world.inventory.Slot with mixins (8261 bytes)`、`hot-added [zerocore]`，payload 6 个照常适配。客户端回归（调用点被重构过）：`-PverifyCompat=<jecharacters>` → `enhancedRedefinition=true` + `ok=true`。
  边界：只验证了这一个模组；「服务端 + 客户端同时热加妃级 mod」没测（多人同时妃级仍属未验证）。文档：AGENTS Java 21 段与规则 12、README、status 表尺新增「专用服妃级热加（JBR）」行 + 已知问题 22 改成 ✅、tech-debt 11 ✅、server-hotadd §4。

- **（专用服控制台：短名 add + TAB 补全）**：起因是「有没有更便捷的重载指令」。原来的 `/modreloader add` 只吃绝对路径——控制台不能拖文件、不能浏览，等于每次都要粘一长串路径，打字成本就是主要摩擦。改动三处：
  ① 新增 `command/JarPathResolver`（纯 `java.nio`，无游戏依赖）：绝对路径 → 当前目录相对路径 → 「裸文件名」依次在 `mods/`、`modreloader/inbox/` 里找；剥掉双引号；带子路径的输入（`nested/x.jar`）**不会**拿去 base 目录里搜，避免误加载同名文件；未找到时把尝试过的每个路径都返回给命令层。6 个 JUnit 测试覆盖这四种情况（含「子路径不搜 base 目录」和引号）。
  ② `add` 与 `probe` 加 `SuggestionProvider`：只列 `mods/` 与 inbox 的 `*.jar` 文件名（不做 mod 检查——控制台每次按键都会要一次补全），所以 `add <TAB>` 直接出短名。
  ③ `scan` 每行末尾追加 `→ /modreloader add <文件名>`，配合 ① 就是「复制这一个 token」。
  新增 i18n `modreloader.command.add.not_found`（带两个 `%s`：输入 + 查找位置）与 `modreloader.command.scan.hint`，中英占位符一致（`LangKeysTest` 覆盖）。
  验证：`./gradlew test` 12 passed（原 6 + 新增 6）。专用服端到端 `-PserverAddCommand`（新 harness `dev/ServerAddCmdCheck`，走服务端 dispatcher，和 jline 控制台同一条执行路径）2026-11-16 11:17：
  `scan` 返回 1 且行尾带 `→ /modreloader add t4fixture-1.0.0.jar`；`add definitely-not-here-1.0.jar` 返回 0 并打印 `No jar found for '…'. Looked in: [/…/run/…, /…/run/mods/…, …]`；
  TAB 补全为 `[MixinRuntime-1.0.2-mc1.21.jar, t4fixture-1.0.0.jar]`；`add t4fixture-1.0.0.jar` 返回 1、`Loaded: hot-added [t4fixture]`、`isLoaded=true`、`RESULT: PASS`。harness 跑完把拷贝进 `mods/` 的 jar 删掉，避免污染后续运行。
  边界：这只是「名字 → 路径」的便利，**没有**自动挑候选、没有自动加载（贵妃级不可回滚，加载仍由人决定，见上一段）。

- **（热加告知层：在线客户端必须知情）**：用户追问「服务端热加了双端模组，服里的玩家会怎样？会被提示具体哪个 mod+版本并指引安装吗？还是能同步？」。读代码 + 实测后发现两件事：① 今天**基本没有提示**——嫔/妃级 mod 客户端完全无感；贵妃级只有一条通用文案（`mixin/sync.mismatched`），没有 mod 名/版本；② 后果比预想的硬。
  实测（`dev/MpNoticeServerCheck` + `dev/MpNoticeClientCheck`，2026-11-16 11:48，专用服 + **故意不装该 jar** 的客户端）：服务端 commit 注册表 + datapack 重载后，客户端 `DecoderException: Failed to decode packet 'clientbound/minecraft:update_recipes'` 直接断开——**不需要 mod 自己发包**，配方引用了它不认识的物品就够了；而且之后重连会被登录快照的 `server-with-unknown-keys` 再拒一次，**装回同一个 jar 之前进不来**。这和「服务器启动时就装了该 mod」语义一致，但热加把它变成了在线时发生的不可撤销降级。
  实现（`hotadd/ModNotice` + `ServerModNoticePayload` + `ModNoticeAckPayload`）：服务端把本次热加的 modId/显示名/版本/jar 文件名/sha256 前 8 位/tier/是否注册表内容发给所有在线玩家；客户端聊天 + toast 两条文案，没有该 mod 时明写「你很快会被断开、装回同一 jar 前进不来、在自己客户端热加也可以」，并用 `mod_notice_ack` 回报自己有哪些 id；服务端日志因此出现 `Dev has [] of the hot-added mod id(s)`；有玩家在线 + 注册表内容时 commit 前再打一条 WARN。
  一个必须记住的时序：通知要**放在 `RegistryStage.commit` 与 datapack 重载之前**。第一版放在管线末尾，实测通知到达的是一条已经被 `update_recipes` 弄死的连接——玩家什么都没看到。挪到 construct 之后（约提前 1–2 秒）后：`received 1 notice(s)`、`still connected=true`、随后才断。
  结论写进 [ADR-0011](decisions/0011-hotadd-must-inform-clients.md) 与 [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §10：**jar/资源/payload channel 都无法从服务端同步，只有注册表 id 映射能同步（且要求客户端本地已有条目）**；我们的缓冲层只能让它**可解释、可提前准备**，不能让它不发生。未决：有玩家在线时是否默认拒绝注册表内容热加（要求显式 force）。

- **（Searchables / 布局边界：两个真 bug，一个我判断错的假设）**：用户报「加载键位冲突显示前置 Searchables 时报 `redefine EditBox: attempted to change superclass or interfaces`」。我先按「JBR 不能改已加载类的接口」做了变更前拒绝，并写了 `-PverifyMixinLayout` harness（先 `Class.forName(EditBox)` 再 add）。结果 harness 第一次跑就暴露两个**真 bug**：
  ① `ClassBytes.of(...)` 的 `retransformClasses` **本身就会应用准备好的 mixin**——refuse 之后 `redefined=[]`，但 `EditBox` 已经多了 `AccessEditBox` 接口，即「读取原始字节」这一步就在改类。改成优先从类加载器读 class 文件资源（`readClassResource`），只有读不到才退回 retransform 并 warn。
  ② MixinRuntime 的 `MixinTransformerUtils.transform(Class)` 不是纯函数：它内部先 `InstUtils.getClassBytes`（同样 retransform）再 `transformClassBytes`，最后**自己 redefine**。所以引擎的「检测」调用它就已经把类改了。现在引擎自己调 `IMixinTransformer#transformClassBytes`（Mixin 原生字节级 API）再自己 `redefineClasses`，`MixinRuntimeApi` 里删掉了 `transform(Class)` 并写明原因。
  修完这两个之后跑 harness：JBR 下 **`EditBox` 加接口是成功的**（`redefined=[EditBox]`、add ok=true），LSP 的 `AbstractWidget` 也一样——**我原先「JBR 不能改接口」的假设是错的**。用户的失败因此不是布局边界，而是**那次会话没有 JBR**（他们的提示屏原文就写着「妃级布局变更需要 JBR 21 + …」，即 `JbrSupport.available()==false`）。
  最终落点：① 布局检测只在 **JBR 不可用**时拒绝（文案带 `JbrSupport.describe()`），JBR 可用时只 warn 并照常尝试；② `HotAddEngine` 在 MIXIN 阶段**前置**检查 JBR（`FailureKind.JBR_REQUIRED`），让控制台路径也得到干净失败，不再等到 mixin config 激活后才炸。
  回归：LSP `-PverifyRollback=true -PfailAfterModList=true` → `redefined=[UUIDUtil, AbstractWidget, PlayerList, ShareToLanScreen]`、重试成功、`RollbackCheck RESULT: PASS`；Searchables 在标题屏 `-PverifyCompat` 仍 PASS；`-PverifyMixinLayout` PASS（JBR 下成功加接口）。
  顺带查了创造页签那条：用众星捧月在主菜单热加 + 进世界 + 打开创造栏，**364 个物品可见、15 个页签含 mod 自己的页签**——用户报的「没有创造物品栏」在本机**复现不出来**；`CACHED_PARAMETERS` 置空这件事在「本会话从没开过创造栏」的路径上 A/B 都能过（cache 本来就是空的），所以它只保留为「先开过创造栏 → 回主菜单热加 → 再进世界」这条**未实测序列**的防御性保护，代码注释里写明了这一点。

- **（AppleSkin 悬停崩溃 = 漏了一个客户端注册事件；由此立项「注册审计」）**：用户报「热加 AppleSkin 后悬停食物崩溃」，同时问「是不是意味着要针对性单独适配」。崩溃报告（`run/crash-reports/crash-2026-11-16_14.56.09-client.txt`，是用户真实点击产生的）给了确定答案：
  `IllegalArgumentException: Unknown TooltipComponent` @ `ClientTooltipComponent.create` → `ClientHooks.gatherTooltipComponentsFromElements` → `CreativeModeInventoryScreen.render`。
  AppleSkin 的食物提示是自定义 `TooltipComponent`（`TooltipOverlayHandler$FoodTooltip`），它的 `ClientTooltipComponent` 工厂走 `RegisterClientTooltipComponentFactoriesEvent`——**这个一次性事件我们从来没重放**。所以是**我们的缺口**，不是模组不兼容。
  修法：`ClientRegistrationReplay.replayTooltipComponentFactories`——临时 map 投递事件给新容器，再把真身 `ClientTooltipComponentManager.FACTORIES`（NeoForge 的 `ImmutableMap`）读出来合并、只加没有的 key、整体换回（不能就地 put，`ImmutableMap` 会抛）。实测 `tooltip component factories replayed: 1 new, 1 total`、`-PverifyCompat=<appleskin>` → `ok=true isLoaded=true`；**悬停渲染那一步没有复测**（需要鼠标，用户会话已结束），所以表尺里标了「未复测」。
  顺着这条线数了一遍 NeoForge 1.21.1 的 `IModBusEvent`：**47 个候选，我们覆盖约 14 个**，失败形态从「静默」到「点一下崩」到「行为变了」不等（`RegisterGuiLayersEvent` 静默、`RegisterRecipeBookCategoriesEvent`/`RegisterParticleProvidersEvent`/`RegisterShadersEvent` 崩、`RegisterCapabilitiesEvent` 行为变）。用户问「没发现的事件就会漏吗」——答案是会，而且这是**枚举清单**的结构性问题。
  因此立项 [features/client-registration-audit.md](features/client-registration-audit.md)（Proposed，未实现）：扫 jar 的类引用 → 找 `IModBusEvent` → 减去「已重放 / 非一次性 / datagen」三张名单 → 在 `VALIDATE` 打清单，已知致命项像 AT 那样变更前拒绝。它把「漏」变成可报告、可拒绝、可排期的数据；矩阵跑真实语料时的输出就是下一个该补哪个事件。
  顺带修正：用户上一轮报的 Searchables 失败被我先误判成「JBR 不能给已加载类加接口」，实测 JBR **可以**（`EditBox`/`AbstractWidget` 都成功），真因是**那次会话没有 JBR**（提示屏原文就是「妃级布局变更需要 JBR 21 + …」）。见同日另一条记录。

- **（注册审计：把「漏了哪个启动期事件」变成可报告/可拒绝）**：接着 [status.md](status.md) 下一步 0。起因是 AppleSkin 悬停崩溃暴露的第六个「漏重放」缺口，而当时的做法是一张手写清单（47 个候选、覆盖约 14 个），漏一个就是一类 bug。
  实现分两层，理由是可测试性：**`inspect/RegistrationAudit`** 只放分级规则（不 import ASM、不 import 游戏类），**`inspect/RegistrationAuditScanner`** 负责扫 jar。
  扫描用 ASM 的 `ClassReader` 收集类头/字段与方法描述符/签名/注解/指令（含 `invokeDynamic` 的 lambda 方法与 `ldc` 的 `Type`/`Handle`）/try-catch 类型，只留 `net/neoforged/**` 且简单名含 `Event` 的，`Class.forName(name, false, loader)`（不初始化）+ `IModBusEvent.isAssignableFrom` 判定；模组自己的类一个都不加载。
  分级：`covered`（`ClientRegistrationReplay`/`FmlEvents`/`NetworkAdaptation`/`RegistryStage` 已重放）、`recurring`（`ModelEvent.*`/`TextureAtlasStitchedEvent`/`BuildCreativeModeTabContentsEvent`/`SoundEngineLoadEvent`/`RegisterConfigurationTasksEvent`/`RenderLevelStageEvent`）、`datagen`、`crash`（粒子/配方书/shader/sprite source，**变更前拒绝**）、`behavior`（capabilities/默认组件/自注册 registry/属性/刷怪/POI/模型层，warn）、`silent`、`base`（抽象基类）、`other-side`（专用服上的客户端事件）。
  接进 `Stage.VALIDATE`（依赖/AT 预检之后、module 注入之前）：打一行 `registration audit <ids>: N mod-bus event(s); covered [...] ...`；`crash` 非空返回 `FailureKind.CLIENT_REGISTRATION`（新 i18n `summary/suggestion`，`FailureKeys` 映射，`LangKeysTest` 自动覆盖）。审计自身异常只 warn。
  三个实现中修掉的坑：① **`InnerClasses` 属性里的外层类不是引用**——第一版把 `outerName` 也收进来，于是每个用嵌套事件（`EntityRenderersEvent$RegisterRenderers`）的模组都多报一个抽象基类 `EntityRenderersEvent`，语料里立刻可见；② **`IModBusEvent` 是 marker 接口**，模组自定义事件类 `implements` 它会把它扫出来，接口不是事件，跳过；③ **抽象基类**（`RegisterColorHandlersEvent` 等）确实被引用（Mekanism 有个 helper 收基类再分发给 `$Block`/`$Item`），单独给 `base` 分级，不藏也不当缺口。
  **与提案的一处有意差别**：提案把 `RegisterCapabilitiesEvent` 列为拒绝项；实测语料 5/16 个模组（众星捧月、ZeroCore2、AE2、Mekanism、ExtremeReactors2）都注册 capabilities，拒绝会让内容模组全线不可热加而它们的方块/物品/配方仍然能用。改成 `behavior`（大声 warn、不拒绝），重放列为下一优先级。
  新增 harness `dev/RegAuditCheck`（`-PverifyRegAudit=<jar>,...`，只读）：① 逐个 jar 打分级（不热加，所以 AT/缺依赖的 jar 也能进语料）；② 现造两个最小 mod jar（`neoforge.mods.toml` + 一个字段引用事件），一个引用 `RegisterParticleProvidersEvent`（必须被 `HotAddEngine` 以 `CLIENT_REGISTRATION` 拒绝在 `VALIDATE`），一个只引用 `RegisterGuiLayersEvent`（必须不判致命）。
  证据（2026-11-16 15:20–15:21，本机）：`./gradlew test` **20 passed**（新增 8 个纯规则测试）；`runClient -PverifyRegAudit=<16 jars>` → `[RegAudit] RESULT: PASS`、`fatal path graded=true refused=true silentNotFatal=true`；语料共 **129 处事件引用**：61 covered / 24 recurring / 6 datagen / 19 silent / **15 behavior** / **3 crash** / 1 base；3 处 crash 全在 AE2（粒子）与 Mekanism（粒子、shader）——两者本来就因 AT 在 `VALIDATE` 被拒，**没有任何当前 PASS 的模组变成被拒**。合成 jar 的实测日志：`[RegAudit] engine on crash jar: ok=false stage=VALIDATE kind=CLIENT_REGISTRATION`，且 `registry content detected: false`、没有 module 注入。引擎集成回归：`-PverifyCompat=<appleskin>` → `registration audit [appleskin]: 4 mod-bus event(s); covered [...]; silent [client.event.RegisterGuiLayersEvent]`、`RESULT ... ok=true stage=NONE kind=NONE isLoaded=true`。
  **审计第一次给出的真实缺口**：众星捧月的 `CapabilityEvents.registerCapabilities`（`@EventBusSubscriber @SubscribeEvent`，javap 验证）给桶（item/fluid）、坩埚（fluid）、机器（item/energy）与流体容器物品注册 provider，而 `RegisterCapabilitiesEvent` 从未重放 → 热加会话里这些 capability **没有登记**。status 的「桶/坩埚/堆肥手动项未验」此前不知道原因，现在写成已知问题 26 + 下一步第一项（重放，验证方式：放桶后断言 `Capabilities.ItemHandler.BLOCK`/`FluidHandler.BLOCK` 非 null）。
  专用服路径也跑了一遍（`runServer -PautoAdd=<appleskin>`，2026-11-16 15:25）：`registration audit [appleskin]: 4 mod-bus event(s); covered [fml.event.lifecycle.FMLClientSetupEvent, network.event.RegisterPayloadHandlersEvent]; other-side [client.event.RegisterClientTooltipComponentFactoriesEvent, client.event.RegisterGuiLayersEvent]`——客户端事件在服务端记为 `other-side`，`Class.forName` 没有因为事件类引用客户端类型而失败。（顺带观察：AppleSkin 这个纯客户端模组在专用服上竟然 `isLoaded=true` 热加成功，因为构造期不加载客户端类；这是既有的 dist 门禁缺口，不在本次范围。）
  边界写进 [features/client-registration-audit.md](features/client-registration-audit.md) §7：启发式（只看类引用，反射/字符串拿不到）、只看外层 jar（不扫 `META-INF/jarjar`）、`unresolved` 不致命、服务端跳过客户端事件。tech-debt P1-5 注明它只补了「可见性」这一半，统一的重放出口仍欠。

- **（众星捧月创造栏：模组自己的页签第一次被自动验证）**：用户指出「众星捧月创造栏还没测」。核对后确实如此：`-PverifyCreative` 只断言了 `defaults==4`（页签越界崩溃的回归）和「**某个** tab 里能看到 `exnihilosatellitium` 的物品」，页签本身有没有注册、在不在界面的分页里、点开是不是空的，都没有断言；dogfood 文档里也只写了「创造物品栏渲染 40 tick」。
  补在 [dev/GameplayCheck](../src/dev/java/dev/modreloader/dev/GameplayCheck.java) 的 creative 阶段：① 从 `BuiltInRegistries.CREATIVE_MODE_TAB` 里找 namespace 为该 mod 的页签（按 mod 物品数取最多的）；② 断言 `getDisplayItems()` 非空且含该 namespace 物品（页签内容在热加后确实被重建）；③ 断言它在 `CreativeTabsScreenPage#getVisibleTabs()` 的某一页里（否则没有按钮可点）；④ 用界面自己的私有 `selectTab(tab)`（`UnsafeAccess.trusted()` + `MethodHandle`）选中，断言静态 `selectedTab` 切过去、`ItemPickerMenu` 被填充，再渲染 40 tick（选中后的真实渲染路径）。mod 如果没有自己的页签（如 t4fixture），只记一行跳过、不失败。
  实测（2026-11-16 15:45，`runClient -PverifyAddCommand=<众星捧月> -PverifyAddCommandModId=exnihilosatellitium -PverifyGameplay=true -PverifyCreative=true`）：
  `creative screen: all=15 defaults=4 sorted=11`、`items from 'exnihilosatellitium' visible: 364`、
  `creative own tab candidate: exnihilosatellitium:main (无中生有：众星捧月) items=182 modItems=182`、
  `creative own tab is on page 2/2 of the screen`、`selected own tab …`、`own tab rendered: 182 tab items, 46 in the menu`、
  `creative inventory rendered 40 ticks without crashing (with the mod's own tab selected)`、`creative RESULT: PASS`；同一次 `GameplayCheck RESULT: PASS`。
  边界：没有模拟鼠标点页签按钮（用的是界面自己的 `selectTab`）；页签在第 2/2 页是 NeoForge 的分页行为。文档：[dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1、status 表尺。

  同一天用户指出「是在**主菜单**热加载导致没出现创造栏」——上面那条是进世界后 `/modreloader add`，走的不是 reported 路径。
  于是补 `dev/CreativeTitleCheck`（`-PverifyCreativeAtTitle=<jar>`）：标题屏热加 → 建新世界 → 切创造 → 开创造栏 →
  找到 mod 自己的页签 → 若不在当前页就按界面真实的 `>` 翻页按钮 → 断言页签在当前页 → 选中 → 渲染 40 tick。
  实测（2026-11-16 15:52，当前代码）：`title-screen add ok=true`、`registry tabs (15)`、`own tab candidate: exnihilosatellitium:main items=182`、
  `on page 2/2`、`page 1 does not show the mod's tab (...); pressing the page button`、
  `mod's tab is now on the current page: [exnihilosatellitium:main, minecraft:hotbar, minecraft:search, minecraft:inventory]`、
  `selected own tab ...`、`own tab rendered 40 ticks: 182 tab items, 45 in the menu`、`RESULT: PASS`。
  也就是说：**当前代码上主菜单热加这条路径是通过的**；唯一“打开时看不到”的原因是页签在第 2 页（第 1 页是 10 个 vanilla 页签 + hotbar/search/inventory）。
  旧构建上是否失败没有对照（那次报告与 title-screen 分支/`CACHED_PARAMETERS` 失效逻辑的先后不明），所以只写「这条路径第一次被自动验证，当前通过」，不写「修好了」。
  文档：[dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1.1、status。
  **结局（2026-11-16 16:2x）**：所有者反馈「无法复现，我放弃了」。最后一次手动 1.4.2 会话（16:16–16:20）日志显示：
  点是（`UI loading … 1.4.2.jar`）→ `UI load succeeded` → 进世界 → 正常退出，无崩溃；日志里也没有切创造模式的记录。
  另核对：1.4.2 与 1.5.0 发布 jar 内容一致（只差 MANIFEST/mods.toml 版本号，`ModCreativeTabs.class` 哈希相同）。
  因此**不改代码**，把四条 harness（进世界 add / 标题屏冷 / 标题屏热缓存 / 正常安装）留作回归，并在 status 已知问题 27 里写明「未定位、未修复、需要那台日志」。
  环境已还原：1.5.0 放回 `run/mods/`（正常安装），inbox 清空；1.4.2 副本留在 `/tmp/dogfood-142-inbox.jar`。
  又补了两个对照（2026-11-16 16:03–16:05）：**warm cache**（`-PverifyCreativeAtTitleWarm`：先进世界开一次创造栏 → 暂停菜单 Save and Quit → 标题屏热加 → 再进世界）——
  `at add: sortedTabs=10 modTabsInSorted=0 server=null`、add ok、页签第 2/2 页、182 项、PASS；**正常安装**（jar 在 `run/mods`，`-PverifyCreativeAtTitleInstalled`，不热加）——同样第 2/2 页、182 项、PASS。
  结论：三条路径（进世界后 add / 标题屏 add / 正常安装）唯一「打开时看不到」的原因都是**页签在第 2 页**（NeoForge 分页，`>` 在屏幕左上/右上）；
  用户选的「翻页也找不到任何页签」在四条路径上**没有复现**，需要那次会话的 `latest.log`/截图才能继续。

- **（capability 重放：审计指出的第一个真实缺口补上了）**：注册审计的语料说 5/16 个模组引用 `RegisterCapabilitiesEvent`，其中最疼的是众星捧月：`CapabilityEvents.registerCapabilities`（`@EventBusSubscriber @SubscribeEvent`）给桶（item/fluid）、坩埚（fluid）、机器（item/energy）和流体容器物品注册 provider；热加后这些 provider 不登记，所以「桶倒不进去 / 机器不动」在热加会话里是必然的。
  实现 `hotadd/CapabilityReplay`：在 registry commit 之后、`FmlEvents.finish`（common setup）之前，用 `container.acceptEvent(new RegisterCapabilitiesEvent())` **只投新容器**（`ModContainer.acceptEvent` 走该容器自己的 mod bus，已加载模组不会重复注册）；事件构造器是 package-private，用 trusted lookup 拿；`CapabilityHooks.initFinished` 在真实启动后为 true，会让 `setProxyable` 抛，所以临时复位为 false、finally 恢复。provider 列表是 add-only，失败只 warn、绝不让 add 失败（此时注册表可能已提交）。
  新增 `Capabilities.Capability.CAPABILITY_REPLAY` 锚点（事件构造器 + `initFinished` 字段）与 `-PverifyCapabilities` harness（`dev/CapabilityCheck`）：标题屏热加 → 建世界 → 放桶/坩埚/机器 → 用 `Capabilities.ItemHandler/FluidHandler/EnergyStorage.BLOCK` 查询；查询必须投到服务器线程（第一版在客户端 tick 里直接 `setBlockAndUpdate`，方块在了但没有 block entity，五项全 MISSING——这也解释了为什么一开始误以为重放无效）。
  证据（2026-11-16 19:0x–19:1x，当前代码）：`capability providers replayed for exnihilosatellitium: item 16/48, fluid 24/27, energy 4/4 block(s) for this mod/total; dispatch reached 1 listener(s)`；`[CapabilityCheck] providers: [barrel.item=present, barrel.fluid=present, crucible.fluid=present, machine.item=present, machine.energy=present]`、`RESULT: PASS`。**A/B 反证**：`-PskipCapabilityReplay=true` → 五项全 `MISSING`、`RESULT: FAIL`（BE 存在，所以确实是 provider 缺失）。进世界 `/modreloader add` 的 `GameplayCheck` 也加了同样五项断言并 PASS。`./gradlew test` 21 passed（新增 capability=covered 规则测试）。
  审计随之把 `RegisterCapabilitiesEvent` 从 `behavior` 移入 `covered`；16 个 jar 复跑：**66 covered / 24 recurring / 6 datagen / 19 silent / 10 behavior / 3 crash / 1 base**（仍 129 处引用，零 PASS 变拒绝）。文档：status 表尺/已知问题 26/下一步 0、[dogfood-gameplay.md](features/dogfood-gameplay.md) §1.1、[client-registration-audit.md](features/client-registration-audit.md) §3.2/§4/§5/§7、[ADR-0012](decisions/0012-registration-audit-severity.md) 补充、glossary。
  顺带记录一个**一次性复现的竞态**（已知问题 28）：进世界 add 时客户端收到一个 chunk 包报 `No value with id -1`，同一次 add 刚做过 BlockState id 重建；之后同路径重跑两次正常。怀疑是 render 线程的 id 重建与服务器线程的 chunk 序列化并发，未定位；建议修法是把「清 id + freeze」投到服务器线程。

- **（注册表 id 重建的竞态修掉了 / P0-1）**：把 18:50 那次一次性复现的 `No value with id -1` 定位并修掉。定位靠读代码而不是复现：
  `BLOCKSTATE_TO_ID_MAP` 就是 `Block.BLOCK_STATE_REGISTRY`（NeoForge `NeoForgeRegistryCallbacks$BlockCallbacks` 里的
  `ClearableObjectIntIdentityMap`），`RegistryStage.commit` 原来在调用线程做「`clear()` → `GameData.freezeData()`」，
  而 dev harness 的 `-PverifyAddCommand` 是从客户端 tick 直接 `dispatcher.execute("modreloader add …")`（render 线程）；
  清空到 bake 重建之间 `IdMapper.getId` 一律 -1，服务器线程同时在序列化 chunk → -1 进 palette → 客户端 `LinearPalette.read` 崩。
  新增 `hotadd/ServerThreadHop`（`ServerLifecycleHooks.getCurrentServer()`；`isSameThread()` 或没有服务器时直跑，否则
  `server.execute` + 等完成；超时回退到调用线程并 WARN；`AtomicBoolean` claim 保证队列里那份不会与回退重复执行），
  commit 与 abort 两处「清 id + freeze」都走它；`RegisterEvent` 重放仍留调用线程。
  证据（2026-11-16 19:27:07 / 19:27:53 / 19:28:32，dev client + 众星捧月 1.5.0，`-PverifyAddCommand … -PverifyGameplay=true` 连跑 3 次）：
  每次都有 `registry freeze: hopping from the thread 'Render thread' to the server thread`；
  `registry commit: 89 registries replayed; blocks 1060 -> 1140, items 1333 -> 1514, states 26684 -> 26818 (cleared 26684 entries), stoneId 1 -> 1 (stable)`；
  3/3 `AddCmdCheck RESULT: PASS` + `GameplayCheck RESULT: PASS`，capability 五项 present，三次日志 0 处 `No value with id`。
  `./gradlew build` 通过（21 单测）。文档：[t4-registry-hotadd.md](features/t4-registry-hotadd.md) §11（含剩余边界：单人两端共享同一张表，
  窗口只是被挪到服务器线程并缩小）、[tech-debt.md](tech-debt.md) §4、status 表尺/已知问题 28/下一步（下一条是 P0-2 GuiLayers）。
  改动后另跑两条回归（2026-11-16 19:30–19:31）：专用服 `runServer -PautoAdd=/tmp/t4fixture/t4fixture-1.0.0.jar -Pt4fixtureSelftest=true`
  → commit 打在 `[Server thread]`（没有 hop 日志，`isSameThread()` 分支生效）、`stoneId 1 -> 1 (stable)`、`world saved; RESULT: PLACED`、
  `datapack (after hot-add) RESULT: PASS`；嫔级 `-PverifyAddCommand=<EasyLAN>` → `AddCmdCheck RESULT: PASS`（不经过 hop）。
  顺带记一笔：run3 的日志里有一条 authlib 取皮肤的 `JsonSyntaxException`（离线环境返回非 JSON），与本次改动无关。

- **（HUD 层重放 / P0-2）**：注册审计 silent 桶里第一个被补的事件。`client/GuiLayerReplay` 把 live `GuiLayerManager`
  的层表**副本**交给 `container.acceptEvent(new RegisterGuiLayersEvent(copy))`（只跑新模组的监听器），然后整表换入——
  必须用副本是因为事件 API 是 `registerBelow/Above(VanillaGuiLayers.HOTBAR, …)`，投进空列表会 "ordered against an
  unregistered layer"；整表换入是安全的，因为事件只能插入/包裹、不能删除或改序。从服务器线程（聊天命令）触发时，
  换入投到客户端线程，避免与 HUD 渲染并发。`ClientRegistrationReplay.replay` 调它，审计把该事件从 `silent` 移入 `covered`。
  harness：`AddCommandCheck` 新增 `-PverifyGuiLayers=true`（`dev/GuiLayers` 只读 live 层表）：add 前记基线，
  add 后等 60 tick（HUD 每帧都在画新层），断言层数增长、新层属于该 mod 的命名空间、**原有层相对顺序不变**、仍在世界里且无 screen。
  证据（2026-11-16 19:47，AppleSkin 3.0.9）：`GUI layer baseline before add: 25 layer(s)` →
  `GUI layers replayed for appleskin: 6 new (25 -> 31 total); new layers: [appleskin:health_offset, appleskin:health_restored,
  appleskin:food_offset, appleskin:exhaustion_level, appleskin:saturation_level, appleskin:hunger_restored]` →
  `GUI layers RESULT: PASS (replayed, order preserved, 60 ticks rendered with the new layers)` → `AddCmdCheck RESULT: PASS`。
  审计复跑（19:48，16 jar / 129 引用）：**68 covered / 24 recurring / 6 datagen / 17 silent / 10 behavior / 3 crash / 1 base**，
  RESULT: PASS（顺带把合成 silent jar 的示例事件从 `RegisterGuiLayersEvent` 换成 `RegisterItemDecorationsEvent`，
  单测里用 GUI 层当 silent 例子的两处也换成物品装饰，新增 `guiLayersAreCoveredSinceTheReplayExists`）。
  回归：众星捧月 `-PverifyAddCommand … -PverifyGameplay -PverifyCreative` PASS（capability 五项 present、创造栏 182 项 / 第 2/2 页 / 渲染 40 tick）。
  文档：status 表尺/已知问题 21、[client-registration-audit.md](features/client-registration-audit.md) §4/§5/§6、[dogfood-gameplay.md](features/dogfood-gameplay.md) §4。
- **（AT 晚注入逐 jar 判死 / P1-1）**：新增 `inspect/AccessTransformerRules`（纯解析：规则数、目标类、排除
  `net.minecraft.data.*` / `neoforge.data.*` / `.datagen.` / gametest / testframework 后的运行期目标、`datagenOnly()`、一行 `describe()`），
  6 个单测（含用 `ZipOutputStream` 现造一个带 AT 的 jar）。`dev/AtSpikeCheck` 改用它，并逐 jar 输出判定：
  「是否**所有**运行期 AT 目标都未加载」——一个已加载目标就足够让晚 AT 不可能。同时 `HotAddEngine` 的 AT 拒绝文案
  现在带组成（`… (57 rule(s) over 39 target class(es) (39 runtime, 0 datagen-only); e.g. […]); access transformers only apply at startup …`）。
  证据（2026-11-16 19:52，6 jar）：标题屏 `SUMMARY: 6 jar(s) with ATs; late-AT viable on 0 of them (1 jar(s) have datagen-only ATs)`；
  进世界同样 **0/6**（Jade 32→35 / 39 已加载、JEI 9→11 / 12、Mekanism 38→42 / 45、AE2 26→33 / 33、guideme 16→16 / 17；
  ExtremeReactors2 的运行期目标是 **0**，唯一目标是 `net.minecraft.data.recipes.RecipeProvider`）。
  结论：**「晚注入 AT」彻底关闭**（不是「先量着看」）。表与日志原文进 [matrix](features/hotadd-compatibility-matrix.md) §8.1，
  status 表尺/已知问题 23、下一步（下一条 P1-2）。

- **（物品装饰重放 + 第一个客户端注册夹具 / P2-1 一半）**：`RegisterItemDecorationsEvent` 是审计 silent 桶里剩下的第一个。
  NeoForge 在 `ItemDecoratorHandler.init()` 里把事件烘成静态 `ImmutableMap<Item, ItemDecoratorHandler>`，每个 handler 的
  `itemDecorators` 又是 `ImmutableList`——所以 `client/ItemDecoratorReplay` 的做法是：读 live lookup → 每个 item 的装饰列表拷成
  `ArrayList` → `container.acceptEvent(new RegisterItemDecorationsEvent(copies))`（只跑新模组的监听器，别的模组的装饰来自副本）→
  用 trusted lookup 调私有 `ItemDecoratorHandler(List)` 重建 handler → 把整个 lookup 原子换回；从服务器线程触发时投到客户端线程。
  更重要的缺口是**没有 subject**：dogfood 没有 HUD 层也没有物品装饰，这两条重放以前改坏了也没人知道。新增第一个客户端夹具
  `experiments/client-fixture`（`build.sh` 照 t4-fixture 的 legacy classpath 方式编）：一个 `@Mod` 在构造器里挂
  `RegisterGuiLayersEvent`（`clientfixture:hud_marker`，画 2×2 像素）+ `RegisterItemDecorationsEvent`（`minecraft:diamond` 上画 2×2 像素）。
  harness：`AddCommandCheck` 新增 `-PverifyItemDecorators=true`（`dev/ItemDecorators` 只读 `DECORATOR_LOOKUP`）与
  `-PverifyItemDecoratorItem=<item>`；`-PverifyGuiLayers=true` 复用。
  证据（2026-11-16 20:0x）：`GUI layers replayed for clientfixture: 1 new (25 -> 26 total); new layers: [clientfixture:hud_marker]`、
  `item decorations replayed for clientfixture: 1 new decorator(s) (0 -> 1 decorated item(s), 0 -> 1 decorator(s) total)`、
  `GUI layers RESULT: PASS (replayed, order preserved, 60 ticks rendered …)`、`item decorations RESULT: PASS`、`AddCmdCheck RESULT: PASS`。
  审计复跑（16 jar）：**69 covered / 24 recurring / 6 datagen / 16 silent / 10 behavior / 3 crash / 1 base**（129 引用不变），PASS；
  合成 silent jar 的示例事件改为 `RegisterDimensionSpecialEffectsEvent`（物品装饰已 covered）。
  **顺带两个发现并处理**：
  1. `-PverifyAddCommand` 热加夹具时（19:53）客户端崩过一次：`Tesselating liquid in world` →
     `FluidSpriteCache.getFluidSprites` 的 `Map.getOrDefault(null, …)` NPE，发生在资源重载之后、渲染 worker 线程上；
     同一条命令第二次跑 PASS → 竞态。缓解：`ClientResourcePacks.inject` 在「无 `assets/`、无 `META-INF/jarjar`、且本次 add
     没新增 client reload listener」时跳过整次资源包注入与重载（无资源的 mod 本来也没什么可加载，省一次几秒的全量重载，
     同时去掉这个竞争面）。回归：众星捧月（有 assets）仍然 `resource packs selected … mod/exnihilosatellitium` +
     `resource reload finished`、`GameplayCheck RESULT: PASS`、capability 五项 present；夹具则打印
     `no client resources in [clientfixture]; skipping the resource pack injection and reload` 且两条重放断言仍 PASS。
     已知问题 29 记录了这次崩溃与「真正的串行化未做」。
  2. 审计剩余事件的逐个结论写进 [client-registration-audit.md](features/client-registration-audit.md) §7.1（P2-2 的一半）：
     `RegisterLayerDefinitions`（要重建 `EntityModelSet`）、`ColorResolvers`、`ModifyDefaultComponentsEvent`、`NewRegistryEvent`
     /`ModifyRegistriesEvent`（框架启动后关闭）、实体属性/刷怪/POI 系列（没有实体 subject）、`AddPackFindersEvent`（我们已有等价注入路径）等，
     每个都给出「不做 / 等 subject」的理由，不留「以后再看看」。

- **（datapack 删除/更新：从「大概不可行」到「机制上不可能」/ P2-3 一半）**：给 `AddCommandCheck` 加了
  `-PverifyDatapackRemove=true`（跟在 `-PverifyDatapack` 后）：热加众星捧月 → 断言 recipes 164 / loot 75 / 真实合成与掉落 →
  把 `mod/exnihilosatellitium` 从 `server.getPackRepository()` 的 selected 里移除 → 在服务器线程 `reloadResources` → 再读。
  2026-11-16 20:1x 实测：`selected ids=[vanilla, mod_data]` 但
  `selected packs=[vanilla, mod_data, mod/mr, mod/modreloader, mod/neoforge, mod/exnihilosatellitium]`，
  **recipes 仍 164、loot 仍 75** → `datapack REMOVE RESULT: NOT POSSIBLE`（harness PASS：测到了预期结论）。
  根因有代码依据：`ResourcePackLoader.makePack` 把全部 mod 数据包做成 **required** 聚合包 `mod_data` 的子包
  （`PackSelectionConfig(true, TOP, false)` + `withChildren(hiddenPacks)`），而 `PackRepository.rebuildSelected` /
  `expandAndRemoveRootChildren` 每次都把 required 包的 children 重新加进来（"these will always be present"）。
  所以「deselect 一个子包」只改 id 列表，不改参与 reload 的 pack 集合。更新同样不做：children 在启动期固定、
  包资源 supplier 绑在被热加的 `IModFile`（`SecureJar`）上，换内容读到的还是旧的，等价于 managed relaunch。
  结论 + 日志原文进 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §9；harness 留作「这条边界仍然成立」的回归
  （NeoForge 哪天改了聚合结构，它会 FAIL）。第一版这个 harness 把「删不掉」当失败（RESULT: FAIL）——改成测量语义后 PASS，
  并打印 `selected ids` / `selected packs` 两个集合，避免以后又要重新推导。
- **（Git 收尾的一半：CI workflow + 截图入仓 / P1-3）**：`.github/workflows/ci.yml`：`analyzer` job 跑
  `python3 -m unittest discover -s tests -v`（`tools/analyzer`），`runtime` job 用 Temurin 21 + `gradle/actions/setup-gradle`
  跑 `./gradlew build`（单测 + dev harness 编译，即 AGENTS §6 的完成定义），60 分钟超时、总是上传测试报告。
  **注意：没有 push，所以「CI 绿」没有被证明**（status/tech-debt 都按未完成写）。
  截图：`run/screenshots/` 里的 `a1-lan-screen.png`、`watch-prompt{,-en,-t4}.png`、`watch-failure.png`、`pending-screen.png`
  复制进 `docs/features/images/`，并把 `a1-mixin-hotadd.md`、`gui-mod-watcher.md` 与 `README.md` 里指向 `run/screenshots/`
  的链接改成 `images/…`（README 相对仓库根，用 `docs/features/images/…`）——`run/` 是 gitignore 的，之前那些图在仓库里根本不存在。

- **（WORLDGEN 边界：标题屏热加 + 新建世界就能拿到维度 / P1-2）**：新增 `experiments/worldgen-fixture`——**只有数据**的夹具
  （`data/wgfixture/dimension/wgflat.json` 超平坦 + `dimension_type`，`build.sh` 输出 `/tmp/wgfixture/wgfixture-1.0.0.jar`），
  以及 `-PverifyWorldgen=<维度 id>`（`AddCommandCheck`）：标题屏热加（`-PverifyDatapackAtTitle`）→ 新建世界 →
  断言 `Registries.DIMENSION` 里有该 id 且 `server.getLevel(key) != null`（"能进"而不是"json 存在"）。
  **修前实测**（2026-11-16 20:13）：`worldgen: dimension wgfixture:wgflat registered=false level=false`，FAIL——
  标题屏走 `defer`，注入点是 `ServerStartedEvent`，世界已加载完，WORLDGEN 层补不上。
  **修法**：`DatapackStage.apply(server == null)` 里先 `preregister`：把 `ResourcePackLoader.createPackForMod(fileInfo)`
  放进 NeoForge 的 `modResourcePacks`（`IModFile → ResourcesSupplier`），下一次 world load 的
  `ServerPacksSource.create → populatePackRepository → buildPackFinder` 会把包作为 required `mod_data` 的子包带上，
  维度随世界注册；`defer` 保留作 RELOADABLE 数据的兜底。**修后实测**（20:14）：
  `datapack for [wgfixture] pre-registered for the next world load (mod data packs now 4)`、
  `worldgen: dimension wgfixture:wgflat registered=true level=true`、`worldgen RESULT: PASS`。
  附带效果：标题屏热加众星捧月现在 world load 时就是 `already live (recipes=164, loot tables=75, block tags=1, item tags=2)`，
  世界启动后那次多余的全量数据重载被短路掉（省一次 reload）。
  回归：`-PverifyDatapackAtTitle + -PverifyDatapack` PASS；进世界 `-PverifyGameplay` PASS（capability 五项 present）。
  边界：**已存在的世界仍需重启**（维度在它的 world load 时定死）；**专用服启动期**热加时 `modResourcePacks` 还没被 FML
  初始化（`findResourcePacks` 懒加载），`preregister` 只能 warn 并退回 `defer`，WORLDGEN 仍不可用（要覆盖得在热加前触发一次
  `findResourcePacks`，没做）。文档：[t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §10、status 表尺/已知问题 19、
  [ADR-0009](decisions/0009-datapack-hotadd.md) 补注（不改结论）、AGENTS §3.16。

- **（A1 产品化两小步：第二个妃级模组 PASS；客户端内保存→重开的自动化卡死并撤掉 / P2-4 一部分）**：
  ① `AddCommandCheck` 新增 `-PverifyAddCommand2=<jar>` + `-PverifyAddCommand2ModId=<id>`：第一个 add 验证完后，
  用同一条命令路径再加第二个，断言两边都在 ModList、第一个没被破坏。实测（2026-11-16 20:2x，干净的复跑）：
  `primary add verified: exnihilosatellitium` → `second fei-tier mod: lanserverproperties isLoaded=true container=true
  (first exnihilosatellitium still loaded=true)` → `SECOND ADD RESULT: PASS` → `AddCmdCheck RESULT: PASS`。
  也就是说同一会话里「贵妃级 + 妃级（4 mixin + 布局变更）」连续两次 in-world add 成立。
  ② 为了补 AGENTS §6 的「存档可读」，先写了 `-PverifySaveReadback`（放 `exnihilosatellitium:wooden_crucible` →
  `saveEverything` → `mc.disconnect(new TitleScreen())` → `createWorldOpenFlows().openWorld(levelId, …)` → 重开后断言模组仍活、方块仍在）。
  **两次运行都卡死**：第一次 level id 取错（`getWorldPath(ROOT).getFileName()` 得到 `"."`），第二次卡在
  `Minecraft.disconnect → runTick → Window.updateDisplay → glfwSwapBuffers`（jcmd Thread.print 证据），同会话早些时候还出现过
  `GLFW: Failed to locate a primary monitor`。判断：`disconnect` 内部会跑一次 `runTick`，在显示/交换缓冲不可用时直接阻塞；
  这是环境问题但后果是"自动化永久卡住"，不可接受 → **删掉整条路径**（enum/阶段/属性/build.gradle 映射都清掉），
  记为已知问题 30，并写明「要覆盖同一件事就走专用服（无 GL）路径」——存档可读的既有证据仍是 t4-fixture 的
  「热加 → 放方块 → 存盘 → 重启读档 `isT4Block=true`」。文档：[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) §10、
  status 表尺/已知问题 30/下一步（P2-4 剩构造后 listener 回滚、服务端+客户端同时妃级）。

- **（分析器首轮真机校准 + AT 维度 / P3 一部分）**：新增 `tools/analyzer/calibrate.py` 与
  `tools/analyzer/calibration/ground-truth.tsv`（ground truth 只按 jar **文件名**索引，不提交任何 jar）：
  逐 jar 输出 `tier / AT / JiJ / 已验证(tier/结果) / match`，有不一致时退出码 1（可进 CI）。首轮 21 个 jar 里
  11 个有真机标注 → **10 个 tier 一致、1 个已知过估**（ZeroCore2：分析器 GUIFEI，真机只需 fei——它注册的是 capabilities
  而非方块/物品内容；过估只多一条警告，方向安全，暂不修）。
  实质改进两处：① **AT 之前完全没解析**（只认 Fabric `accessWidener`），而 AT 是运行期第一拒绝原因。
  `metadata.py` 现在解析 `META-INF/accesstransformer.cfg`（`at_rules` / `at_targets`，语义与运行期
  `inspect/AccessTransformerRules` 一致），`rules.py` 把它写成理由、`report.py` 默认表格加 **AT 列**；
  6 个 AT jar 的规则数与游戏内 `-PverifyAtSpike` 的独立测量**逐个相同**（Jade 57 / JEI 21 / AE2 75 / Mekanism 94 /
  guideme 47 / ER2 1）= 两条完全不同的代码路径互证。顺带纠正：**MekanismGenerators 没有 AT**，它在矩阵里是缺依赖被拒
  （「6 个 AT jar」始终是 Jade/JEI/AE2/Mekanism/guideme/ER2）。
  ② **修掉一个漏估方向的 bug**：真实 mod 用 `DeferredRegister$Blocks` / `$Items` 这些**嵌套子类**的 `register*`，
  旧规则 `cls_name.endswith("/DeferredRegister")` 漏掉 → t4fixture 被预测成 PIN，而运行期实际开了注册表窗口（GUIFEI）。
  改成「类名含 `DeferredRegister` 且方法名以 `register` 开头」，并用 `DeferredRegisterNestedClassesTest` 钉住。
  证据（2026-11-16 20:4x）：校准表 21 行、`labelled jars: 11, matches: 10, mismatches: 1`；`run.py scan /tmp/lanmods --verbose`
  在默认表格里显示新的 `AT` 列；分析器单测 **14 passed**（原 9 + AT 规则 2 + AT 解析 2 + 嵌套 DeferredRegister 1）。
  文档：[analyzer-calibration.md](features/analyzer-calibration.md)、tools/analyzer/README.md、AGENTS §2、status 表尺/未验证/最近变更。
  仍缺：Fabric 语料没校准（intermediary 名）、3 个语料未标注、置信度数值没校准。

- **（`/modreloader why`：只读诊断命令 / P3 一部分）**：新增 `inspect/HotAddWhy`：把「为什么能/不能热加」按**引擎 VALIDATE 的顺序**
  收成报告——tier/内容形状（registry/mixins/native/client-only/already-loaded）→ AT 组成（`AccessTransformerRules.describe()`）→
  依赖（required/present/MISSING）→ 注册审计行 → notes → 最后一行 `verdict:`（与 `HotAddEngine` 的 gate 顺序一致：
  already loaded / 专用服上的客户端类 / AT（含 datagen-only 的保守性说明）/ 致命审计事件 / 缺依赖 / HUANGHOU / GUIFEI / FEI / PIN）。
  `ReloaderCommands` 加了 `why <jar>` 节点（复用 `JarPathResolver` 短名与 TAB 补全，控制台与客户端都能用），返回值 1 表示
  **诊断跑过**而不是「能热加」。
  顺带修掉一个真实误判：`ModInspector` 原来只看 `clientSideOnly` 元数据，**看不见字节码里的客户端类引用**；EasyLAN 声明
  `side=BOTH` 却在 `@Mod` 构造器里注册 `ScreenEvent` → 专用服必崩（已知问题 13）。现在 `RegistryVisitor` 同时收集
  `net/minecraft/client/**` 与 `com.mojang/blaze3d/**` 引用，写进 notes（`references client classes: …`）。第一版把它当
  `clientSideOnly=true` 直接拒绝——**错**：Jade 这类双端模组的客户端代码在服务端根本不加载，静态扫描分不出来；
  改成 warning + 运行期兜底（dist 门禁 + 回滚），`clientSideOnly` 仍以元数据为准。
  证据（2026-11-16 20:5x，专用服）：`runServer -PserverWhy="<jade>|access transformer,<t4fixture>|experimental add-only,
  <EasyLAN>|references client classes"` → 三条 `matches=true`、控制台 `modreloader why <jade>` 返回 1、
  `[ServerWhy] RESULT: PASS`；Jade 的 AT 组成是 57 规则/39 目标（与 `-PverifyAtSpike` 一致）。
  期间踩的坑：`-PserverWhy` 一开始加到 `runs.client` 块里（`runServer` 看不到），以及上一轮被 kill 的专用服留下了
  `run/world/session.lock`（`DirectoryLock$LockException`）——已按 pid 清掉并复跑。
  文档：[server-hotadd.md](features/server-hotadd.md) §5、status 表尺/P3、AGENTS §2。

- **（解掉 `command ↔ client` 包循环 / tech-debt P1-4）**：`ReloaderCommands` 原来为了 `gui` 节点 import 了
  `client/ClientCommands`，形成 `command → client` 依赖（专用服不加载客户端类所以一直没炸，但方向是错的：
  `hotadd` 已经做到不依赖两侧，命令这一层还留着回头路）。改法：`ReloaderCommands.tree()` 变 public、
  新增 `remember(CommandDispatcher)`（dev harness `AddCommandCheck` 仍要用客户端 dispatcher），
  客户端在 `client/ClientCommands#register` 里 `register(withGui(tree()))`，`ClientBootstrap` 订阅它。
  依赖变成 `client → command` 单向，`command` 包里不再出现任何客户端类型。
  验证（2026-11-16 21:0x）：`./gradlew build` 通过（29 单测）；`runClient -PverifyAddCommand=<clientfixture>
  -PverifyGuiLayers=true -PverifyItemDecorators=true` → `[AddCmdCheck] dispatcher has /modreloader=true`
  （证明 `remember` 在客户端注册路径上被调用）、`GUI layers: 25 -> 26`、`item decorations: minecraft:diamond 0 -> 1`、
  `AddCmdCheck RESULT: PASS`。文档：[tech-debt.md](tech-debt.md) §4（P1-4 完成、动手顺序第 1 项划掉）、status 最近变更。

- **（构造后失败回滚：游戏总线监听器按实例摘除 / P2-4 一项）**：先量后修。
  **量**：给 `HotAddEngine` 加 `-PfailAfterConstruct`（构造完成、注册表提交之前的注入点），加 `dev/GlobalBusListeners`
  （只读 `NeoForge.EVENT_BUS` 的 `listeners` 表求和）；用 `-PautoAdd=<lsp> -PverifyRollback=true -PfailAfterConstruct=true` 实测
  **38 → 41（+3）**：一次构造后失败留下 3 个监听器，而回滚只能恢复 ModList/module/mixin/pack——§6「失败可回滚」的真实缺口。
  **修**：新增 `hotadd/EventBusSnapshot`：构造前把 `owner → 监听器实例` 深拷贝一份；回滚时算出构造期间**新增的实例**，
  对 bus 的每个 `ListenerList` 调公开的 `unregister(EventListener)`，再从 owner 表里删掉它们（空的 owner 键一并清掉）。
  第一版按 **owner** 撤销（`IEventBus#unregister(owner)`），实测只回收了 2 个、还剩 1 个——那个监听器被追加到了一个
  **已存在**的 owner 上，owner 级撤销碰不到；所以最终改成**按实例**。锚点加进 `Capabilities`（`event_bus_rollback=ok`，
  两个反射名：`EventBus#listeners` / `EventBus#listenerLists`，移除动作本身是公开 API），`RestoreResult` 记 `added/before/after`。
  **证据**（2026-11-16 21:1x）：`game-bus listeners 38 -> 38 (2 removed)`、`game-bus listener rollback: clean (baseline 38 -> 38)`、
  状态检查（ModList/module/target 类字节/资源包）全过、`RollbackCheck RESULT: PASS`。
  顺带量到一个边界并写进文档：**post-construct 之后重试仍失败**（FML `ModLoadingException: Loading errors encountered`，
  mixin 已经应用过一次），所以这条路径的承诺是「干净回滚 + 重启」，可重试的仍是 pre-construct 路径；
  `-PfailAfterModList` 回归仍 PASS 且重试成功（`retry result: hot-added [lanserverproperties] …`）、`-PfailAfterPack` PASS、
  `-PverifyAddCommand=<clientfixture>` PASS。文档：status 表尺/已知问题 7/P2-4、AGENTS §3.10、[tech-debt.md](tech-debt.md) §4b、本文件。

- **（服务端+客户端同时妃级：P2-4 收口）**：P2-4 的最后一项。新增一对 MP harness：
  `dev/MpFeiServerCheck`（`-PmpFeiServer="<jar>|<modId>"`：等客户端 `mp-fei-client-ready.txt` + 3 s 宽限，在服务器线程热加自己的副本，
  再观察 10 s 玩家是否还在线，然后自己 halt）与 `dev/MpFeiClientCheck`（`-PverifyMpFei="<jar>|<modId>"`：标题屏 → ConnectScreen 连
  `127.0.0.1:25565` → 进世界 → 本地热加 → 写标记 → 观察 16 s 是否仍连着）。握手用文件标记，和 `-PmpRegistry*` 一样的做法。
  证据（2026-11-16 21:2x，两个进程）：服务端 `server hot-add zerocore: ok=true … redefined [net.minecraft.world.inventory.Slot]`；
  客户端 `client hot-add zerocore: ok=true … redefined [Slot, GuiGraphics, ModelBakery]`；
  服务端 `both sides hot-added zerocore (server isLoaded=true, players still connected=true)` → `RESULT: PASS`；
  客户端 `after both adds: still connected=true client isLoaded=true` → `RESULT: PASS`。
  覆盖：两侧各自热加同一个妃级 jar 时，mixin 重定义 + payload 注册 + 客户端资源重载不打断已有连接。
  不覆盖：两侧 jar 不一致（客户端不装 → 仍不支持，见 status 已知问题 18）、贵妃级注册表内容的两侧同时热加（§9 的 id 同步是另一条路径）。
  至此 **P2-4 四项全部有 harness PASS**：第二个妃级模组（同会话）、构造后游戏总线监听器回滚、服务端+客户端同时妃级、存档回读（专用服内容路径）。
  文档：status 表尺/已验证/P2-4、AGENTS §2+§3.10、[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) §10.3。

- **（整条验收清单回归通过 / 收口）**：这一轮动过 `EventBusSnapshot`（每次 add 都拍快照）、`ModInspector`（客户端类扫描）、
  `DatapackStage`（标题屏预注册）、`ClientResourcePacks`（无资源时跳过重载），所以把 status 的验收清单**整条**跑了一遍，
  而不是只跑单条：`./gradlew build` ✅、`./gradlew test` ✅（29 passed）、
  `-PverifyCapabilities=<dogfood>` ✅（五项 provider present，`item 16/48`）、
  `-PverifyCapabilities=<dogfood> -PskipCapabilityReplay=true` ❌ **反证正确**（五项全 MISSING）、
  `-PverifyAddCommand=<dogfood> -PverifyGameplay=true` ✅（`AddCmdCheck RESULT: PASS` + `GameplayCheck RESULT: PASS`）、
  `-PverifyRegAudit=<16 jars>` ✅、`-PverifyCreativeAtTitle=<dogfood>` ✅。
  结论：P1/P2 项在本轮改动之后没有回归；status 的验收清单保持「全绿（含一条故意为红的反证）」。

## 2026-11-16（deepseek-flash）late coremod：让 hot-add 的 `ICoreMod` 变换真的跑起来；JBR 门禁收窄

所有者实测反馈两件事：①「通用拼音搜索（jecharacters）可加载，但没有实际生效」；②「鼠标手势（Mouse Tweaks）无法加载，
提示需要 JBR 21+」。前者是 status 已知问题 24 里写过的「coremod 不运行」，这次做了；后者调查后确认**提示本身没错**，
但门禁位置过严，一并收窄。

- **问题 1 根因**：jecharacters 的 `ICoreMod`（`JechCoreMod`）注册在**嵌套 jar** 的
  `META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod` 里。FML 在类加载时跑它，热加 jar 完全没走这条路，
  所以 mod 能加载、JEI 的搜索类却没被改写成 `Match.contains`。新增 `hotadd/LateCoremod`：
  扫外层 + `META-INF/jarjar` 的服务文件 → 把 coremod jar 复制一份、写唯一 `Automatic-Module-Name` →
  **先于 `ModuleInjector`** 在独立 `ModuleLayer`（父层 game layer）里加载 → 对每个 `ITransformer` 的 class 级目标：
  已加载 `redefineClasses`、未加载用 `ClassFileTransformer` 在 define 时变换、回滚可恢复。
  两处踩坑都写进 ADR-0013：① 嵌套 coremod jar 与外层 mod 共享 `me.towdium.jecharacters` 包 →
  `JechClassTransformer.class.getClassLoader().getResourceAsStream("me/towdium/jecharacters/targets.json")` 返回 null，
  `JechCoreMod` 构造抛 `RuntimeException: Could not find targets.json`；② 先把同一个 jar 注进 game layer 再解析子层 →
  `ResolutionException: module ... contains package me.towdium.jecharacters.asm`。独立层 + 先建后注解决两者。
- **问题 1 证据**（JEI 19.27.0.340 放进 `run/mods`；测完已移走）：
  `./gradlew runClient -PverifyPinyin=/Users/…/jecharacters-neoforge-4.5.15.jar` →
  `coremod setup: coremods [me.towdium.jecharacters.coremod.JechCoreMod], transformed-at-load
  [mezz.jei.gui.search.ElementSearchLowMem, mezz.jei.gui.ingredients.IngredientFilter,
  mezz.jei.gui.search.ElementPrefixParser, net.minecraft.client.searchtree.SearchTree], deferred 80 target(s) not loaded`；
  `ElementSearchLowMem.matches("tieding", "铁锭", stub) = true`（**变换前同一调用是 `"铁锭".contains("tieding")` = false**，
  所以是 A/B 断言），`[PinyinCheck] RESULT: PASS`。注意 `redefined []`：这次 4 个目标都是 on-load 路径，
  redefine 路径只有代码路径，记在 status「未验证」。
- **问题 2 根因**：Mouse Tweaks 2.26.1 的 accessor mixin 把 `AbstractContainerScreenAccessor` 接口合并进
  `AbstractContainerScreen`（标题屏时该类已加载）→ 布局变更 → **确实需要 JBR**。实测：
  `-PverifyCompat=<MouseTweaks>`（JBR + `-XX:+AllowEnhancedClassRedefinition`）→ `ok=true`、
  `redefined net.minecraft.client.gui.screens.inventory.AbstractContainerScreen`；
  `-PnoJbr=true` → `stage=MIXIN kind=JBR_REQUIRED`，消息点名
  `AbstractContainerScreen adds interface(s) [yalter/mousetweaks/mixin/AbstractContainerScreenAccessor]`，
  `isLoaded=false`、blocks/items 1060/1333 不变 → 拒绝发生在任何状态变更之前。
- **门禁收窄**：删掉 `HotAddEngine` 里「有 mixin 就必须 JBR」的前置检查，改由 `MixinSupport` 逐目标判定布局
  （与 coremod 同规则）；`ModInspection.canHotLoad`/`blockReason`、`ModReloaderI18n.preflightReason`、`HotAddWhy`
  同步为「可以尝试，布局变更会被拒」；新增 `JbrSupport.hint()`，在拒绝消息里给出本机 JBR 安装路径
  （macOS `~/Library/Java/JavaVirtualMachines`、Linux `/usr/lib/jvm`、Windows `%LOCALAPPDATA%/Programs` 等）。
  新增 `build.gradle -PnoJbr=true`（不加 enhanced 标志，跑 stock 语义）。
- **免 JBR 的正向验证**：新增夹具 `experiments/mixin-fixture`（`@Mod` + `@Inject` 进**本 jar 自己**、
  热加时还没加载的 `Probe`，`value()` 1→2）。`./build.sh /tmp/mixinfixture` 后
  `./gradlew runClient -PnoJbr=true -PverifyMixinFixture=/tmp/mixinfixture/mixinfixture-1.0.0.jar` →
  `eager=[mixinfixture.Probe]`、`Probe.value() = 2`、`RESULT: PASS`（`enhancedRedefinition=false`）。
  结论：**未加载目标的 mixin 本来就不需要 JBR**，旧门禁是过严；Mouse Tweaks 这类仍需要。
- **coremod 回滚**：at-load 路径的变换也能回滚（之前只回滚 redefine 路径）。`onLoad` 记录变换前字节，
  rollback 时逐个 `redefineClasses` 回去。证据：`-PautoAdd=<jecharacters> -PfailAfterModList=true`（JEI 在 run/mods）→
  4 个类全部恢复（`ElementSearchLowMem 10569 -> 10509`、`IngredientFilter 20096 -> 20035`、
  `ElementPrefixParser 11846 -> 11861`、`SearchTree 3430 -> 3385`），`rollback: restore coremod-transformed classes ok`、
  `rollback: coremod load transformer ok`，失败发生在 MODLIST 注入点。边界：改布局的变换回滚不了（`redefineClasses` 会拒绝），
  会作为 rollback error 报出来。
- **工程**：新增 `Stage.COREMOD` / `FailureKind.COREMOD` + 中英文案（`LangKeysTest` 的全 FailureKind 覆盖自动生效）；
  新增 `-PverifyPinyin` / `-PverifyMixinFixture` / `-PnoJbr`；`LateCoremod.transformedBytes()` 让 harness 断言
  「引擎写进类里的字节」而不是再 retransform 读回（后者对非 retransformable transformer 会拿到变换前字节）。
  回归：`./gradlew build` 通过（含 29 个无游戏单测 + dev harness 编译）。
- **文档**：status 表尺新增两行、已知问题 24/25 改写、`a1-mixin-hotadd.md` §10.4、矩阵 §3.2/§7、AGENTS §2 命令与
  规则 10/14、ADR-0013（Proposed）。

## 2026-11-16（deepseek-flash）tier 注册信号修正：为什么"好多模组被列为贵妃"

所有者问「好多模组被列为贵妃且需要 JBR」。查下来是两个独立信号叠在一起：

- **贵妃（runtime）判定过宽**：`ModInspector.RegistryVisitor` 只要 jar 里**出现**注册表类名
  （`net/minecraft/core/Registry`、`Registries`、`registries/BuiltInRegistries`、`.../DeferredRegister`、
  `NeoForgeRegistries`、`.../event/RegisterEvent`）就置 `registry=true` → 贵妃。读取注册表（tooltip 查 `BuiltInRegistries.ITEM`、
  GUI/配方展示）和注册内容完全分不开。实测 9 个真实 jar：
  `Jade` 有 12 个类引用注册表类型、**0 次注册调用** → 仍被判贵妃（Python 分析器判妃）；
  `MekanismGenerators` 走的是 `mekanism/common/registration/impl/BlockDeferredRegister#register`（不是 NeoForge 的
  `DeferredRegister`），精确类名匹配抓不到 → 分析器判贵妃、runtime 判嫔。
  另外，analyzer 的注册信号一直是「`register*` 调用 + `DeferredRegister` 引用」，两条实现从 2026-11 起就漂移
  （status 已知问题 5），这就是「同一批 jar，UI 说贵妃、分析器说妃」的直接原因。
- **"需要 JBR" 不是贵妃带来的**：preflight / `why` 里 `mixinTier = 妃级 || (贵妃 && 有 mixin)`，在没有 enhanced redefinition
  的 JVM 上显示 JBR 提示。所以任何带 mixin 的模组都会显示它（贵妃只是其中一类）；它也不是逐模组的硬性要求——
  2026-11-16 已收窄为「已加载类 + 布局变更」（见上一条 / a1 §10.4）。

修正：新增 `inspect/RegistrySignal`（无游戏依赖、可单测），`ModInspector` 只按
① `register*` 调用（owner 是 `net/minecraft/core/Registry`、`*BuiltInRegistries`、`*RegisterEvent`、任意 `*DeferredRegister`）
② 任意 `DeferredRegister` 类型引用（含 `$Blocks`/`$Items` 子类、字段/方法描述符）判定；单纯读注册表不算。
Java 侧语义与 `tools/analyzer` 的 `registration_refs` + `DeferredRegister` 标记对齐。

证据（离线，ASM 扫 9 个真实 jar，按 runtime 规则算 tier）：

```
ExtremeReactors2  old=GUIFEI  new=GUIFEI
Jade              old=GUIFEI  new=FEI      <-- 12 classes / 0 registration calls
Mekanism          old=GUIFEI  new=GUIFEI
MekanismGenerators old=PIN    new=GUIFEI   <-- own BlockDeferredRegister#register
ZeroCore2         old=GUIFEI  new=GUIFEI
ae2               old=GUIFEI  new=GUIFEI
guideme           old=GUIFEI  new=GUIFEI
jecharacters      old=PIN     new=PIN
jei               old=GUIFEI  new=GUIFEI
```

`./gradlew test`：`RegistrySignalTest` 5 个断言全 PASS（`Registry.register`/`DeferredRegister#register`/`RegisterEvent#register` 算注册；
`BuiltInRegistries#get`、`Screen#register`、`DeferredRegister#create`、只读 `Registries` 不算）。
**没有起客户端**：这次只改 tier 预测/门禁的输入，注册表热加路径本身已有贵妃级 harness；`guideme` 这类
「JNI native 方法」的 tier 仍只有 analyzer 判 HUANGHOU，runtime inspector 仍是贵妃（另一条 drift，未在本次范围）。

## 2026-11-16（deepseek-flash）粒子 provider 重放：Balm 在 VALIDATE 被假拒绝

用户报 `balm-neoforge-1.21.1-21.0.67.jar` 加载失败，分类 `CLIENT_REGISTRATION`：
「registers [client.event.RegisterParticleProvidersEvent] ... 用到就会崩」。

- **根因**：审计对「引用了一次性事件」是保守近似；Balm 是库（Blay 系抽象层），自己不注册粒子，
  它把 `RegisterParticleProvidersEvent` 的 listener 挂到**消费方 mod 的 bus**（`initializeMod(...)` 里
  `modBus.addListener(...)`），消费方调用 `registerParticleProviders` 时才真正注册。jar 里当然有事件引用，
  审计分不出「库的胶水」和「真的注册了粒子」，于是按 crash 拒绝。**这不是 Balm 的问题，是审计粒度太粗。**
- **修复**：`RegisterParticleProvidersEvent` 本来就可重放（`ParticleEngine` provider 表是 put 语义、事件构造器公开）。
  新增 `ClientRegistrationReplay#replayParticleProviders`：构造事件、**定向投给新容器**（`container.acceptEvent`）——
  实测 Balm 的 listener 就在消费方容器上，靶向重放正好命中，也不需要全局 post 把别人的 handler 再跑一遍；
  日志用 `providers` 表前后大小报告新增数。`registerSpriteSet` 的 sprite set 由下一次资源重载建立（pack 阶段会重载）。
- 审计分级同步：`RegisterParticleProvidersEvent` 从 `crash` 移入 `covered`，CRASH 由四类变三类；
  单测 `particleProvidersAreCoveredByTheReplay` 钉住；`RegAuditCheck` 的合成 crash jar 改用
  `RegisterRecipeBookCategoriesEvent`；语料计数 129 处引用 → **71 covered / 1 crash**（AE2 12 covered、
  Mekanism 15 covered + 1 crash（shader））。
- 证据（dev client，JBR，`-PverifyCompat=/tmp/balm.jar`）：
  `registration audit [balm]: 24 mod-bus event(s); covered [... RegisterParticleProvidersEvent ...]`；
  `mixin setup: redefined=[ModelBakery, Screen, AbstractContainerScreen, PlayerList, CropBlock, OptionsScreen, Slot,
  MinecraftServer, Entity, MouseHandler, Minecraft, KeyMapping, ReloadableServerResources, StemBlock]`；
  `particle providers replayed: 0 new, 109 total`（Balm 自己不注册粒子，符合预期）；
  `RESULT balm.jar predicted=GUIFEI ok=true ... isLoaded=true`。
- **对用户环境（`JBR=false`）的结论**：这次只修 `VALIDATE` 的假拒绝。Balm 的 mixin 是 accessor 型，
  上面 14 个目标在标题屏已加载，所以在没有 enhanced redefinition 的 JVM 上接下来会停在 `MIXIN / JBR_REQUIRED`
  （消息点名类与布局变更）。要么换 JBR 21 + `-XX:+AllowEnhancedClassRedefinition`，要么把 jar 放进 `mods/` 重启。
- 边界：没有 assets 却用 `registerSpriteSet` 的 jar（跳过资源重载）sprite set 不会建，粒子仍可能异常；
  库胶水型的其它一次性事件（如 Balm 的 renderers/screens 已经由现有重放覆盖）没有逐个复查。


## 2026-11-16（deepseek-flash）托管重启 A′/B：保存世界 → 用同 JVM / JBR 重新拉起游戏

所有者问「换 JBR 能不能由我们做」。结论：JVM 不能替换自己，只能起新进程；且**改 `mods/` 是玩家/启动器的事**，
我们不该代劳。于是按「先 A′（同 JVM 重启）再 B（用 JBR 重启）」实现：

- `client/RelaunchScripts`（纯，可单测）：生成 `<gamedir>/modreloader/relaunch/relaunch-<ts>.sh|.cmd` ——
  `sleep 2 → cd <gamedir> → exec <java> <原 argv>`；POSIX 单引号（含 `'\''` 转义），Windows 双引号 + `%` 翻倍，
  cmd.exe 无法表示的参数（含 `"`/换行）**拒绝**而不是拼坏命令。
- `client/Relauncher`：
  - `saveAndRestart`：保存 options + 单人 `IntegratedServer.saveEverything(true,true,true)` →
    原 `ProcessHandle.arguments()` + `-Dmodreloader.relaunched=true` / `-Dmodreloader.relaunched.waitForPid=<pid>` →
    写脚本 → `setsid /bin/sh`（有则用）/ `cmd /c start` 启动并重定向日志 →
    **扫描命令行带标记的 JVM 确认替代进程出现**（最多 6s）；没出现就**不退出**，返回原因 + 子进程日志尾部；
  - 子进程 `ClientBootstrap.install()` 调 `awaitPreviousProcess()`：轮询旧 PID 最多 15s，避免两个 JVM 同时写实例目录；
  - B：`jbrRelaunchAvailable()`（本机有 JBR 21 且当前不是 JBR+flag）、`jbrFlags()`（缺则加
    `-XX:+AllowEnhancedClassRedefinition`）、`saveAndRestart(mc, reason, java, extraArgs)` 重载。
- UI：`LoadFailureScreen` 在 `FailureAnalyzer.Report.restartRecommended()`（JBR_REQUIRED / ACCESS_TRANSFORMER /
  CLIENT_REGISTRATION / COREMOD / UNSUPPORTED_TIER / MIXIN_CONFIG / REDEFINE / ROLLBACK_PARTIAL）时显示
  「保存并重启游戏」；B 额外显示「用 JBR 重启」→ `RelaunchConfirmScreen` 显式确认（文案写明只影响本次会话）。
  不可自动重启时把 `Relauncher.blocker()` 的原因显示出来，不留死路。
- 验证：`RelaunchScriptsTest` 4 个单测（引号/元字符/`%`/不可表示）；`-PverifyRelaunchPlan=true`（dev，macOS，JBR 21）：
  `canRelaunch=true`、plan 12 args（16644 chars）、spawn `java -version` → exit 0 且输出 `openjdk version "21.0.11"`、
  JBR + `-XX:+AllowEnhancedClassRedefinition` spawn → exit 0；`RESULT: PASS`。
- **未验证**：真实“新窗口起来、旧进程退出”的端到端（会退出客户端，不适合自动 harness），Windows/PCL2 的进程树行为尤其未知；
  B 只影响本次会话，长期要在启动器里选 JBR。A′ 只是自动化“重启”那一步，**不会替玩家把 jar 放进 `mods/`**——
  所以它适用于“玩家已装好 jar、只差重启”以及“用 JBR 重启”两种场景。

## 2026-11-17（deepseek-flash）测试流水线 + JiJ 嵌套 mod/资源包 + 小地图/鼠标手势 L1

所有者反馈：热加能力值得怀疑和补强，需要测试流水线；常用模组（鼠标手势、小地图）与“很多模组带前置”；
以及玩家频繁遇到的 JBR 问题。这一轮把三件事都落到了可复现的证据上。

### 1. 兼容性测试流水线 v0（`tools/pipeline/`）

- 语料 `corpus.json`：11 个真实 jar，pinned `version` + `file` + `sha256`；`fetch` 只解析精确版本（不取 latest），
  jar 落 `/tmp/modreloader-corpus`，不进仓库。
- 场景表达“前置”：`jars` 顺序 + `mods.toml` 的 **required** 依赖闭包（忽略 platform/optional）；
  `check` 报“依赖在 corpus 但不在场景”“顺序反了”“`run/mods` 已加载”“JiJ 提供”四类；负向场景用
  `requires_absent` 防止环境把 `MISSING_DEPENDENCY` 变成 PASS。
- expectation 是一等结果：`pass` / `refuse` + 精确 `FailureKind` / `unknown`；**干净拒绝也是 PASS**。
- `run` 调 `-PverifyCompat`，解析 `[CompatCheck] START/RESULT/DONE`，写 `run.log` + `report.json` + `report.md`；
  `overall_verdict` 要求 `DONE n/n` 且 exit 0，否则 `INCOMPLETE`（防“RESULT ok=true 后崩溃”被当 PASS）。
- 单测 12 个（解析/顺序/负向/语料），`tools/pipeline/README.md` 与 [hotadd-test-pipeline.md](features/hotadd-test-pipeline.md)。

证据（每行都是 pipeline report）：

| 场景 | 结果 |
|---|---|
| `minimap`（Xaero's Minimap 26.6.0） | **PASS**：`ok=true`、`isLoaded=true`；嵌套 `xaerolib 1.7.3` container；6 mixin config、20 类 redefine；key 34→57；HUD 25→26；`mod/xaerolib`+`mod/xaerominimap`；`DONE 1/1` |
| `mouse-gestures`（Mouse Tweaks 2.26.1） | **PASS**：`ok=true`、`isLoaded=true`、`redefined AbstractContainerScreen` |
| `mouse-gestures-nojbr` | **PASS**：`ok=false kind=JBR_REQUIRED`，点名 `AbstractContainerScreen` 加接口 |
| `input`（Searchables → Controlling） | **PASS**：Searchables PASS；Controlling 前置已满足但 1 条 AT 干净拒绝 |
| `missing-dependency`（只加 Controlling） | **PASS**：`kind=MISSING_DEPENDENCY`，消息带 `searchables [1.0.1,)` |
| `tooltip-at`（Jade） | **PASS**：`kind=ACCESS_TRANSFORMER`（57 条规则 / 39 个运行期目标） |
| `minimap-journeymap` | **PASS**：commonnetworking（JiJ）container 正常；JourneyMap 39 条 AT / 26 个运行期目标拒绝 |
| `hud`（AppleSkin） | **PASS**：`ok=true`、`isLoaded=true` |
| `library`（Balm） | **PASS**：`ok=true`、`isLoaded=true`，`hot-added [kuma_api, balm]`（Balm 也 JiJ 嵌套了一个 mod） |
| `minimap-addon`（World Map/Minimap → XaeroPlus） | 未完成：World Map 在标题屏走自己的 `loadLater` 崩溃；留给 L2 |
| `recipe-at`（JEI） | 已量到 `ACCESS_TRANSFORMER`（嵌套 MezzConfig 0.6.10 有 mods.toml）；expectation 修正后复跑 |

### 2. 分析器两个假阴性（否则流水线会“误报缺前置/没元数据”）

- **hybrid jar loader**：JourneyMap 1.21.1 同时有 `fabric.mod.json`（`journeymap-wrongloader` 哨兵）和
  `META-INF/neoforge.mods.toml`；原顺序先 Fabric，导致 modId/依赖/mixin 全读错。`_detect_loader` 改为 NeoForge 优先。
- **`[[mods]]` 后的子表**：XaeroPlus 的 `[mods."lithium:options"]` 把 `mods` list 覆盖成 dict，modId 变 unknown。
  `tomlmini._navigate` 现在把子表挂到最后一个数组元素。
- 证据：`journeymap` → `journeymap`（不是 wrongloader）、XaeroPlus → `xaeroplus`；analyzer 18 个单测。
- 顺带把“嵌套纯库没有 mods.toml”从错误降为可忽略（JourneyMap 的 pngj）。

### 3. JiJ 嵌套 mod 的 container（“前置”里的硬骨头）

Xaero's Minimap 依赖的 `xaerolib`、JourneyMap 依赖的 `commonnetworking`、Balm 依赖的 `kuma_api`
都不是外部 jar，而是 `META-INF/jarjar` 里的嵌套 **mod**（有 mods.toml）。普通启动由
`JarJarDependencyLocator` 建 `ModContainer`；热加原先只把嵌套 jar 放进 module path，required 依赖因此
`MISSING_DEPENDENCY`。

- 新增 `hotadd/NestedMods`：对每个 `declaresMod` 的嵌套 jar 建 `ModFile`/`ModFileInfo`/`ModInfo`
  （`JarModsDotTomlModFileReader` + identifyMods/identifyLanguage/compileContent/setScanResult），
  嵌套 AT 直接按 `ACCESS_TRANSFORMER` 拒绝；嵌套 mod 的 mixin 配置并入外层注册。
- `HotAddEngine`：先 `ModListInjector.rebuild` 嵌套 containers，再外层；`ModuleInjector` 只把**纯库**当 extraJars，
  嵌套 mod 走 `newFiles`；`ModNotice` 只通知外层。
- `FMLCommonSetupEvent` 改为 `FmlEvents.finishAll`：**所有 container 先同步执行 listener，再统一跑 enqueued 任务**。
  逐 container 跑会让 XaeroLib 在自己的队列里 freeze 包注册表，外层 World Map 再注册就 `IllegalStateException`。
- `ModListInjector` 同步 `LoadingModList`（`modFiles/sortedList/fileById/modDependencies`）：Xaero 在 client setup 里
  `FMLLoader.getLoadingModList().getModFileById("xaeroworldmap")`，不同步就是 NPE。
- `ClientResourcePacks.injectAll`：外层 + 嵌套 `ModFileInfo` 的 pack 一次加入、只重载一次。只注入外层时
  XaeroLib 的 `shaders/core/xaerolib/*.json` 找不到；实测两个 pack 同时选中后
  `Successfully reloaded the XaeroLib shaders!`。

### 4. JBR 玩家指南 + 诊断

- 新 [jbr-support.md](features/jbr-support.md)：决策树、启动器步骤、故障表（flag 加到非 JBR 会让游戏起不来、
  Java 17 vs 21、arch、macOS quarantine、`JAVA_HOME` 不决定启动器 Java）、FAQ、优先级。
- `JbrSupport.diagnose()` + `/modreloader jbr`：打印 `java.home`/vendor/flag/已安装 JBR/下一步；`-PjbrJava=` 可覆盖
  build.gradle 的 JBR 路径。命令仅编译通过，**未真机执行**；真实能力探针仍是待办。
- Mouse Tweaks 的 JBR/noJBR 对照作为文档证据：JBR 下 `ok=true`；无 flag 下 `JBR_REQUIRED` 点名
  `AbstractContainerScreen`，不是“有 mixin 就拒”。

### 5. 边界 / 未做

- L1 只到标题屏：Xaero's World Map 的 `OldFormatSupport.loadVanillaStates` 在标题屏崩（`ok=true` 之后），
  需要 L2 进世界；pipeline 把它判 `INCOMPLETE`，不写成热加失败。
- L2（进世界后的 HUD/按键/配置/截图）未实现；当前 `PASS` 只证明引擎路径 + `isLoaded`，不证明小地图真的画出来。
- 嵌套 mod 的 `data/` 没有单独注入；嵌套 AT 仍拒绝；嵌套 `GAMELIBRARY`（无 mods.toml）没有 container 语义。
- JBR 仍是 vendor+参数启发式；「装进 mods/ 并重启」降级路径未做；真实 JBR 探针未做。
- 并发跑两个 dev client 会抢 `run/`，给 pipeline 加了 `.run.lock` 拒绝。

## 2026-11-17（deepseek-flash）无焦点 L1：自动化不再抢前台

所有者反馈：连续 `runClient` 抢前台焦点，要求做无焦点，并问自动化会不会带来意想不到的 bug。

- **原因**：`-PverifyCompat` 的 L1 每个场景都起一个真实 GLFW 客户端；GLFW 默认
  `GLFW_FOCUS_ON_SHOW=true`，窗口显示时成为前台 App。连续跑 10 个场景就是 10 次抢焦点。
- **方案**：dev-only JVM agent（`src/noFocus/java/dev/modreloader/nofocus/NoFocusAgent`）：
  - `premain` 注册 `ClassFileTransformer`，只处理 `com/mojang/blaze3d/platform/Window`；
  - 在 `<init>` 里**紧跟 `GLFW.glfwDefaultWindowHints()`** 插入
    `GLFW.glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE)`（131084, 0）；
  - `glfwDefaultWindowHints()` 会重置 hint，所以必须在它之后；窗口仍然可见（截图仍有内容）。
  - 不开 `-PnoFocus` 时 agent 根本不加载；不在 mod jar / `mods` block 里。
- **Gradle**：`-PnoFocus=true` → `runClient` 依赖 `noFocusAgentJar`，
  加 `-javaagent:.../modreloader-no-focus-agent.jar`、`-Dmodreloader.noFocus=true`、
  `-Dfml.earlyprogresswindow=false`（early splash 是另一个可能抢焦点的 GLFW 窗口）。
  agent 需要的 ASM 直接用 Gradle 自带 `asm-9.7.jar`，不联网解析（远程 maven 曾让构建卡 3 分钟）。
- **pipeline**：`run` 默认 `-PnoFocus=true`；`--visible` 是交互/焦点敏感场景的逃生口；
  `report.json` 记录 `no_focus` / `window_focused` / `focus_ok`。`CompatCheck` 在标题屏稳定后打印
  `[CompatCheck] window focused=<bool> (noFocus=<bool>)`；如果请求 no-focus 但 `focused=true`，
  整体 verdict 是 `FOCUS_STOLEN`（fail-closed），不会静默认为跑完。
- **证据**（`python3 tools/pipeline/run.py run --scenario hud --mods-dir /tmp/modreloader-corpus`，JBR 21，macOS）：
  `[no-focus] agent active`、`[no-focus] patched com/mojang/blaze3d/platform/Window`、
  `[CompatCheck] window focused=false (noFocus=true)`、`RESULT appleskin ok=true isLoaded=true`、
  `DONE 1/1`、`verdict: PASS`。纯单测 `NoFocusAgentTest` 用合成字节码断言 hint 插在
  `glfwDefaultWindowHints` 之后，不需要游戏/GLFW；`./gradlew noFocusAgentJar` 可单独产出 agent。
- **自动化的风险（写进 [no-focus.md](../features/no-focus.md)）**：
  - 无焦点会改变运行环境：`pauseOnLostFocus` 可能让单人世界暂停；HUD/小地图等可能按焦点跳过渲染；
    真实输入/截图在无焦点下不可靠 → L2/输入/截图用 `--visible`；
  - agent 依赖 `Window` 类/构造器形状；升级 MC 后先跑 no-focus 冒烟，pipeline 有 `FOCUS_STOLEN` 兜底；
  - early splash 的焦点无法从主窗口状态检测（已用 `fml.earlyprogresswindow=false` 关闭）；
  - 只实测 macOS；Windows/Linux 未验；
  - 自动化仍然会起完整游戏：`.run.lock`、超时、一次一个场景仍是必需的。
- **未做**：逐秒前台 App 监测（这次只断言 GLFW `focused=false`）；L2 的可见/无焦点双路径；
  Windows/Linux 冒烟。

## 2026-11-17（deepseek-flash）late AT：AT 不是绝对硬边界，但仍是 JBR-only 实验

所有者问「对待 AT 我们真的无能为力吗」。旧结论（matrix §8）是：AT 只在类加载时由 FML 应用，
晚 AT 量到 0/6 后关闭。这个结论只覆盖了「在类加载时打 AT」；漏掉了 **对已加载类改访问标志** 这条路。

### 1. 先做最小 JVM 证明

合成 `Target`（私有 static final 字段 + 私有方法）与按 public 编译的 `Caller`：

- Temurin 21（stock）：`UnsupportedOperationException: class redefinition failed: attempted to change the schema (add/remove fields)`；
- JBR 21 + `-XX:+AllowEnhancedClassRedefinition`：**接受**；`before field=private static final method=private static final`
  → `after field=public static method=public static`，`Caller.call()` 返回 `hidden:7`。

结论：**JVM 不是不能改访问标志，是 stock JVM 不允许；JBR 允许。**

### 2. 实现 `hotadd/LateAccessTransformers`

`-Dmodreloader.lateAt=true` / Gradle `-PlateAt=true`（默认关闭）：

- 解析 `META-INF/accesstransformer.cfg`；`AccessTransformerRules` 补了 **行尾 `# comment`** 解析
  （JourneyMap 的 `public-f ... NativeImage # NativeImage` 之前会被当成 member `#`）；
- 对每个目标：`Class.forName(name,false,loader)`（只加载不初始化）→ `ClassBytes.of` 捕获**当前字节**
  （保留 NeoForge/Mixin）→ ASM 只改 access/final → `redefineClasses`（在 mod 类加载前）；
- 每目标登记回滚；规则匹配不到成员时跳过并 warn（FML 也容忍旧条目；JourneyMap 的 `heightMap` 已过期）；
- 任一目标 JBR 拒绝 → `FailureKind.ACCESS_TRANSFORMER` 带具体原因，不走「AT 硬边界」文案。

### 3. 实测（L1，macOS，JBR 21，`-PnoFocus=true`）

| jar | 规则/目标 | 结果 |
|---|---|---|
| Jade 15.10.6 | 57 / 39 | 39 applied，0 skipped；`ok=true`、`isLoaded=true`；5 个插件 bootstrap 全成功 |
| JEI 19.70.0.469 | 34 / 16 | 16 applied；`hot-added [mezz_config, jei]` |
| Controlling 19.0.5 | 1 / 1 | 先加 Searchables 后 `ok=true` |
| JourneyMap 6.0.9 | 39 / 26 | 21 applied，5 skipped；AT 不再是拒绝点；随后 `CLIENT_REGISTRATION`（`RegisterShadersEvent`）拒绝 |

回滚：Jade + `-PlateAt=true -PfailAfterModList=true` → 39 个目标全部 `rollback: late AT ... ok`，
`ok=false stage=MODLIST`，没有 `ROLLBACK_PARTIAL`。

### 4. 这意味着什么 / 不意味着什么

- **stock JVM 上 AT 仍是硬边界**；JBR 上 late AT 是 opt-in 实验，默认拒绝行为不变。
- 只覆盖 **access/final**；不改方法体/字段/签名。去 final 不会撤销已编译的常量内联。
- 进程级全局：改的是已加载的 Minecraft/NeoForge 类；成功后不自动还原（AT 语义），失败回滚。
- 目标会提前加载（`initialize=false`），可能有类加载副作用；没做规模浸泡。
- 不解决其它启动期边界：JourneyMap 过了 AT 仍卡在 `RegisterShadersEvent` 注册审计。
- 没有 L2 功能验证（Jade tooltip / JEI 界面 / 真正交互）；Mekanism/AE2/guideme/ExtremeReactors2 未跑；
  Windows/Linux 未测；多 mod AT 冲突、嵌套 AT、连续 add 未测。
- 因此文档/状态改成「AT 不再是绝对硬边界，但仍是 JBR-only 实验」，不要写成「AT 已解决」。

## 2026-11-17（deepseek-flash）JBR 使用指引 + 本机检查脚本

所有者要“如何使用 JBR”的指引。之前的 [jbr-support.md](../features/jbr-support.md) 混合了玩家步骤、技术边界和产品路线，
不适合直接给玩家/整合包作者/服主看，于是拆成两篇：

- 新增 [docs/guides/jbr.md](../guides/jbr.md)：先判断需不需要 JBR；5 分钟快速路径；官方下载与 `jbrsdk`/`jbr` 选择；
  macOS/Windows/Linux 安装；PCL2/HMCL/Prism/官方/Modrinth/CurseForge 的 Java 路径与 JVM 参数位置；
  `-XX:+AllowEnhancedClassRedefinition` 只能加在游戏 JVM、且 stock JVM 加了会起不来；验证（`java -version`、
  `/modreloader jbr`、启动日志）；常见错误表；专用服；撤销；FAQ；以及 JBR 在本项目里具体解锁什么
  （布局变更热加 + 实验性 late AT）和不解决什么（stock AT、一次性事件、注册表回滚等）。
- 新增 `tools/jbr_check.py`：只读地扫常见安装根目录里的 JBR 21，打印 `bin/java`、版本/实现者、下一步；
  `--java <path>` 只检查指定二进制。它看不到启动器实际选的 JVM 和游戏 JVM 参数，所以文档明确要求以启动日志为准。
- 本机实测（macOS，JBR 21.0.11）：脚本找到
  `.../jbr-21.jdk/Contents/Home/bin/java`、`version: 21.0.11, implementor: JetBrains s.r.o.`，返回 0；
  `--java` 指定同一路径也返回 0。
- 文档互链：README 的文档表改为 `[JBR 使用指引]`（步骤）+ `[JBR 技术/路线]`（jbr-support.md）；
  jbr-support.md 顶部指向 guide；AGENTS 目录增加 `docs/guides/`，§2 增加 `python3 tools/jbr_check.py`。
- 没有启动游戏；这轮只是文档 + 只读脚本。

## 2026-11-17（deepseek-flash）GUI：AT 模块的“加载”按钮变灰却不给原因（修）

所有者贴图问「为什么加载按钮不可点击」：弹窗对 Mekanism 10.7.19（`~/Downloads/Mekanism-1.21.1-10.7.19.85.jar`）
显示「贵妃级注册表内容：实验性热加（仅新增）」——听上去可加载——但 `加载` 是禁用的。

- 根因：`ModInspection.canHotLoad` 把 `accessTransformers` 当硬门禁（FML 只在类加载时应用 AT，
  热加无法给已加载/原版成员放宽访问），而 `ModReloaderI18n.blockReason` **没有 AT 分支**，
  于是落到最后的 `preflight.guifei_loadable` 文案。引擎 `VALIDATE` 的拒绝顺序（`HotAddEngine`
  约 216 行）本来就把 AT 放在 tier 之前，`/modreloader why` 也说得对——只有弹窗的文案错了。
- 证据：该 jar 的 `META-INF/accesstransformer.cfg` 有 120 行规则，目标含 `KeyMapping`/`EditBox`/
  `AbstractContainerScreen` 等运行期类（不是 datagen-only），所以 `AccessTransformerRules.datagenOnly()`
  也救不了它。
- 修法：`canHotLoad` / `blockReason` 增加 `allowLateAccessTransformers` 参数，与 `-Dmodreloader.lateAt=true`
  对齐（开着 late AT 时按钮才可用，否则禁用并显示新的 `preflight.access_transformer` 文案，文案直说
  “把 jar 放进 mods/ 后重启”）；`ModReloaderScreen`、`LoadController`、`/modreloader scan` 都传这个开关；
  `FailureAnalyzer.preflight` 对 AT 给出 `failure.access_transformer.suggestion`。
- 验证：新增 `ModInspectionTest`（4 个断言：贵妃+AT 默认拒、lateAt 放行；嫔级同样受 AT 门禁；
  贵妃仍需 REGISTRY_ADAPTATION；alreadyLoaded/HUANGHOU 不受 lateAt 影响）→
  `./gradlew test` BUILD SUCCESSFUL；`LangKeysTest` 3 项仍 PASS（两种语言键/占位符一致）。
- **没有起客户端**：按钮是否变灰、文案是否显示仍未在游戏内看过（按 AGENTS §3.17 不擅自开窗）；
  下次 `-PnoFocus=true` 跑一次 watcher GUI 场景即可收尾。

## 2026-11-17（deepseek-flash）分级校准第二把尺：方向性门禁 + 运行期差分（抓到 JourneyMap 漏估）

所有者问「如何确保对模组分级特点分析正确」。原校准（§3）只比 tier 字符串、只覆盖“跑到了 tier”的 jar。
这轮把“正确”拆成两件可测的事，并各补了一把尺：

- **方向性门禁**（`tools/analyzer/calibrate.py`）：用 `TIER_RANK` 把 mismatch 分成 `under / over / unknown`。
  漏估（对用户说“可加载”然后崩）与 `T?` 永远退出 1；过估默认也失败，`--allow-overestimate` 才放过（CI 用）。
  新增 `tests/test_calibrate.py` 8 个用例（`direction` / `exit_code`）。
- **运行期差分**：同一 jar 过 `inspect/ModInspector` 与 Python 分析器。运行期 tier 取自
  `tools/pipeline/reports/*/report.json` 的 `RESULT ... tier=`（dev harness 打印），不用手写 TSV。
  9 个 jar：修复前 6 一致 / 3 不一致，其中 **JourneyMap 是漏估**（运行期 `FEI`、分析器 `PIN`）。
- 根因是 `mixin` 配置条目**相对 `package`**：JourneyMap 写 `client.GuiMixin` / `server.ChunkMapAccessor`，
  旧代码 `if "." in entry` 就当全限定名 → 26 个 mixin 全落空 → `mixin_class_count=0` → `PIN`。
  修法：`mixins._qualify` 先按 jar 里类**是否真的存在**选候选（`package.entry` 优先、裸 `entry` 次之）。
  新增 `tests/test_mixins_package.py` 4 个用例。修复后 JourneyMap → `HUANGHOU`（26 mixin / 25 target），
  差分 **7 一致 / 2 过估**（Controlling、JourneyMap 的 render-core 策略差异，方向安全，保留并记录）。
- 顺带确认一个结构性问题：`HotAddEngine.VALIDATE` **没有 tier 门禁**，tier 只挡 UI；因此存在「UI 会拒、引擎能加」，
  实例是 Xaero's Minimap（两边都判 `HUANGHOU`，流水线 `minimap` 场景 `ok=true`）。结论：tier 是解释，不是可热加性本身。
- 回归：分析器单测 30 个 PASS；`calibrate.py --allow-overestimate` 全语料
  `labelled 11 / matches 10 / under 0 / over 1`（唯一过估是已知的 ZeroCore2）→ 退出 0；不带 flag 退出 1。
- 文档：`docs/features/analyzer-calibration.md` 新增 §7（差分方法、决策层矩阵缺口、优先级），§5 补退出码语义；
  `status.md` 已知问题 5 补实测数字。

## 2026-11-17（deepseek-flash）托管重启的低门槛收尾：late AT 参数、待重试 jar、实验性警告 + 参数位置 bug

所有者要求「内置降低玩家操作门槛，并加警告」。落地的是 P0 三件事，外加一个修了才成立的 bug：

- **AT 场景的 JBR 重启会开 late AT**：`Relauncher.jbrFlags(true)` 在 `-XX:+AllowEnhancedClassRedefinition` 之外
  加 `-Dmodreloader.lateAt=true`；由 `RelaunchFlags`（纯函数）决定，`RelaunchFlagsTest` 4 个用例钉住组合。
- **重启后不用再找 jar**：失败时把 jar 记为「失败指纹」会让下次会话看不见它，所以重启前
  `WATCHER_CONFIG.clearFailed(jar)` + 写 `modreloader/pending-add.txt`；`ClientBootstrap.resumePending` 在标题屏
  消费该文件、后台扫描、**预选**该 jar 并打开列表（只预选，不自动加载）；jar 已在启动时加载则只弹「无需重试」。
  `PendingAddTest` 3 个用例。
- **警告**：确认屏新增 `relaunch.jbr.late_at_warning`（进程级/全局/不还原、JBR 21 + L1、只对可信 jar、
  其它门禁仍可能拒、仅本次会话），失败屏在按钮上方加 `failure.jbr_late_at_hint`，watcher 列表在 late AT 下的
  AT jar 显示 `preflight.access_transformer_late`（顺序与 `canHotLoad` 对齐，避免又出现「文字说能加、按钮是灰的」）。
- **真 bug**：`saveAndRestart` 原来把新增 JVM 参数追加在命令行**末尾**。JVM 在主类处停止解析选项，所以
  `-XX:+AllowEnhancedClassRedefinition`、`-Dmodreloader.relaunched`、`-D...waitForPid` 全成了程序参数——
  子进程没有 enhanced redefinition，`awaitPreviousProcess()` 也读不到自己的 pid 标记。旧 harness 只看
  脚本/进程能起来 + 命令行里出现标记，所以没抓到。修法：`RelaunchScripts.withJvmFlags` 插到主类之前，
  识别 `-cp/-p/--module-path/--add-modules/--patch-module` 的独立取值与 `-jar/--module` 形态；
  `RelaunchScriptsTest` 新增 4 个命令行用例。
- 回归：`./gradlew build` 通过；`RelaunchFlagsTest` / `PendingAddTest` / `RelaunchScriptsTest` / `LangKeysTest` 全 PASS。
- **未验证**：没有起客户端，所以「点按钮 → 新 JVM 起来 → 自动选中 → late AT 生效」全链路仍未真机跑过
  （按 AGENTS §3.17 不擅自开窗）；Windows/PCL2 的进程树行为也仍未验。

## 2026-11-17（deepseek-flash）没有 JBR 时的游戏内指引

问题：`JbrSupport.hint()` 只有一句“install JBR 21 and select it in the launcher”，没有链接、没有步骤、
也没有“不装 JBR 怎么办”的兜底；而 watcher 里 AT jar 的「加载」是灰的，玩家根本走不到失败屏，等于死路。

- 新增 `ui/JbrGuideScreen`：四步（下载 21.0.x → 启动器指向 `<JBR>/bin/java` → 加
  `-XX:+AllowEnhancedClassRedefinition` → 重启并 `/modreloader jbr` 验证）+「打开下载页」按钮
  （官方 GitHub Releases）+ **兜底**“放 mods/ 重启，按正常模组加载”。同时显示本机检测到的 JBR 路径。
- 入口：① 失败屏在本机无 JBR 时把「用 JBR 重启」换成「如何安装 JBR」（有 JBR 仍是原按钮）；
  ② watcher 列表在“有需要 JBR 的候选 + 本机无 JBR”时，底部多一个「如何安装 JBR」；
  ③ `/modreloader jbr` 的诊断行补下载地址、参数与兜底。
- `FailureAnalyzer.Report` 增加 `jbrHelps`（AT / 布局变更 / FEI 且有 MixinRuntime），避免在不相关的
  失败上弹 JBR 指引；只有 `!available() && installation().isEmpty()` 才显示安装入口。
- 顺手修了失败屏第二排按钮**互相重叠**的布局 bug（「保存并重启」与「用 JBR 重启」原本都在 `y-24`、
  横向叠 40px）：现在并排居中、放不下时上下堆叠。
- 回归：`./gradlew build` 通过，57 个单测 PASS（含 LangKeys 中英键/占位符一致）。
- **未验证**：没有起客户端，指引页/按钮的实际渲染与点击未在游戏内看过（按 AGENTS §3.17 不擅自开窗）。

## 2026-11-17（deepseek-flash）Stage-0：不靠 JBR 自己“实现 AT”的可行性（access-rewrite-poc）

所有者问“能不能实现子集/这些魔法方法”，于是做了独立 fixture（无游戏、无 JBR）：

- 机制：**不改目标类**，改写**调用方**（被热加 mod 的字节码）。`GETFIELD C.f:Desc` → `INVOKESTATIC C.access$N(C)Desc`，
  `NEW; DUP; args; INVOKESPECIAL` → `args; INVOKESTATIC C.access$M(args)C`；访问器描述符与调用点**同栈形状**
  （无装箱、无需重算 StackMapTable），内部是缓存的 `MethodHandle.invokeExact`，用 `privateLookupIn` 解析。
- fixture：`stub/demo/Target`（编译期 AT 展开版）→ `ModCode` 直接访问私有构造器/字段/方法；运行期加载**真身**
  `src/demo/Target`（全 private）+ 改写后的 ModCode。
- 结果（macOS/JDK 21.0.12/ASM 9.10.1，`./build.sh`）：control（不洗字节码）→ `IllegalAccessError`；
  改写后 `run()=7,12,19,7,27`；目标类字段/方法/构造器仍是 private；边界 `Target.Hidden`（package-private 嵌套类）→
  `IllegalAccessError`；热循环 0.59 vs 0.82 ns/call（同量级，`invokeExact` 被内联）。
- 结论：**AT 的字段/方法/构造器子集在 stock JVM 可实现**；**class-level 的 package-private 类型无解**
  （JVM 解析类型时按包检查，改写调用点救不了）。语料 376 条 AT 规则里 337 条（89%）是字段/方法，
  但大模组几乎都带 class-level（Jade 4、AE2 6、Mekanism 8+2 ctor、JourneyMap 7、JEI 2+1 ctor）→ 这些 jar 仍要重启/JBR。
- 未做：接真实模组、`invokedynamic`/方法引用、super 调用、写 final、描述符里的 package-private 类型、未加载目标/module 开放性检测。
- 文档：`experiments/access-rewrite-poc/README.md`（含覆盖/不可覆盖矩阵与下一步）；`AGENTS.md` 目录、`status.md` 已知问题 23 各补一条。

## 2026-11-17（deepseek-flash）决定不做“改写调用方模拟 AT”（ADR-0014）

所有者看完成本/覆盖面后判定「意义不大，大多数模组仍然无法热加载」，据此关闭这条线：

- 新增 [ADR-0014](../decisions/0014-no-caller-rewrite-for-at.md)（Accepted）：Stage-0 已证明字段/方法/构造器子集可行，
  但 class-level（package-private 嵌套类型）无解，而 Jade/AE2/Mekanism/JourneyMap/JEI 都带 class-level ——
  **按 jar 算覆盖面太小**，不足以抵消“改写第三方字节码”的长期风险。
- 实验目录保留为证据（README 顶部标“已否决，不接进运行时”），`AGENTS.md` 目录行改成“已否决（ADR-0014）”；
  `status.md` 已知问题 23 的 Stage-0 段落改为“否决 + 原因”。ADR 索引（`docs/decisions/README.md`）补一行。
- 不变的部分：AT 模组继续走“放 mods/ 重启”（S2）；JBR late AT 仍是 JBR 实例/开发 harness 的实验开关（默认关闭），
  不再作为玩家默认路径；热加的真实覆盖面仍是“无 AT 的嫔/妃 + 贵妃 add-only + datapack + 命令/payload 重放”。

## 2026-11-17（deepseek-flash）人机工作模式：模组白名单分级

所有者定了接下来的协作方式：他拿常用模组在真实实例里热加，遇到阻碍/bug 反馈；agent 负责分析、修复、记录。
分桶（**stock 与 JBR 分开判**）：

- **stock**：一等 T1（可热加、无 bug）/ 二等 T2（可热加、有 bug，优先修，修好升 T1，修不好留 T2 并记录 bug）/
  三等 T3（默认判不可热加、但分析后实际可热加；agent 处理后成功 → 转 T2）/ 黑名单（分析确认结构性不可热加）；
- **JBR**：J1（可热加、无 bug）/ J2（可热加、有 bug）；
- 黑名单是**按 JVM** 的，所以“stock 黑名单 + JBR 一等”是合法组合。

落地：
- 新增 [mod-whitelist.md](features/mod-whitelist.md)：分工、六个桶、流转规则、TSV 字段、**反馈格式**（mod/版本/jar/sha256/
  stock 还是 JBR/触发方式/现象或日志/“能加但功能不对”的具体表现）、为什么这样分；
- 新增数据表 [whitelist/mods.tsv](whitelist/mods.tsv)（目前只有表头，等实测）；
- `AGENTS.md` 加硬规则 18“白名单只记实测”+ 目录两行；`glossary.md` 加六个术语；`status.md` 下一步加一行。
- 已有 harness 证据（EasyLAN、lanserverproperties、ZeroCore2、jecharacters、AppleSkin、众星捧月等）**不直接入表**：
  它们多数跑在 JBR dev client 上，不能当 stock 一等。

## 2026-11-17（deepseek-flash）白名单第一步：stock JVM 自动化首轮

所有者要“恢复非 JBR 环境先自动化跑一跑”。做法与结果：

- 环境：`JAVA_HOME=~/.gradle/jdks/.../eclipse_adoptium-21...` + `-PnoJbr=true -PnoFocus=true`，
  `-PverifyCompat=<jar>[,<jar>...]` 一次启动批量跑；日志确认 `JVM Eclipse Adoptium 21.0.12.1; enhancedRedefinition=false`。
  **注意**：只加 `-PnoJbr=true` 而不换 `JAVA_HOME` 时，JavaExec 仍用 Gradle 守护进程的 JVM（本机是 JBR），
  只是不加 enhanced 参数——不是真正的 stock，已写进 AGENTS §2 与 whitelist 文档 §4.5。
- 首轮（10 个真实 jar，标题屏）：
  - **候选 T1**：EasyLAN（PIN, ok）、AppleSkin（PIN, ok）、Searchables（FEI, ok）；
  - **stock 结构性拒绝（BLACK）**：MouseTweaks（JBR_REQUIRED）、Controlling/Jade/JEI/JourneyMap（ACCESS_TRANSFORMER）、
    xaerominimap/xaeroworldmap/balm（JBR_REQUIRED）；
  - 无结论：XaeroPlus（依赖顺序，地图先被拒）、mezz_config 0.7.1（无 mods.toml 的 GAMELIBRARY，不是普通 mod）。
- 写入 [whitelist/mods.tsv](whitelist/mods.tsv) 11 行：T1 行 `state=candidate`（harness 过，待所有者实例确认），
  BLACK 行 `state=verified` 且 bug 列写结构性原因 + 替代（重启/JBR）。`state` 枚举新增 `candidate`。
- 文档：whitelist 文档加 §4.5“如何复跑”（stock / JBR 两条命令、RESULT 判据、标题屏 vs 世界的差别）；
  `status.md` 白名单行更新。
- 未做：JBR 对照（同批）、世界里加（更严格）、功能级验证（L1 只证明构造/加载）。

## 2026-11-17（deepseek-flash）白名单的人读视图

所有者问“这个白名单怎么读”。原始数据是 TSV（机器友好、人不友好），所以补了视图与校验：

- 新增 `tools/whitelist.py`：
  - `render` 按环境（stock/JBR）+ 桶（T1/T2/T3/J1/J2/BLACK）分组打印 Markdown，带总览计数与“怎么读”图例；
  - `render --write` 生成 `docs/whitelist/README.md`（不手改，避免与 TSV 漂移）；
  - `check` 校验表头/列数/jvm/bucket/state/tier/日期/同 mod 同环境重复，退出码可用于 CI。
- 新增 `tools/tests/test_whitelist.py`（4 个用例：分组顺序、跨环境桶非法、重复行、未知 state）→ 全 PASS。
- 文档互链：mod-whitelist.md §4 指向视图、§4.6 给读法与命令；AGENTS 目录加 `tools/whitelist.py`。
- 当前视图：stock T1×3（EasyLAN/AppleSkin/Searchables，均 candidate）、BLACK×8；JBR 暂无行。

## 2026-11-17（deepseek-flash）白名单 JBR 首轮 + 一个真 bug（Jade J2）

同一批模组在 JBR 上跑两轮：plain（`-PnoFocus=true`）和 late AT（`+ -PlateAt=true`，覆盖 AT 模组）。
多 jar 连加会因前一个 jar 的状态互相影响、且一次崩溃会中断后面的 jar，所以失败/可疑的 jar 都单独重跑。

- **J1（JBR 可热加、harness 未见 bug，全部 candidate）**：Mouse Tweaks（redefined `AbstractContainerScreen`）、
  Xaero's Minimap（含 JiJ xaerolib）、Balm（含 JiJ kuma_api）、Controlling（late AT 1 rule/1 target）、
  JEI（late AT 34 rule，`hot-added [mezz_config, jei]`，嵌套 MezzConfig 正常）。
- **J2**：Jade（late AT 57 rule/39 target，`ok=true`）——多 jar 批次里约 1 秒后 `Rendering overlay` 崩，
  `AssertionError: Missing config translation: config.jade.plugin_*`（crash-2026-11-17_20.13.47-client.txt）；
  **单跑未复现** → 记 J2、`state=fixing`，先查是否 config/资源加载时序。教训：`RESULT ok=true` 不等于没 bug。
- **JBR BLACK**：JourneyMap（`CLIENT_REGISTRATION`，`RegisterShadersEvent` 未重放）、
  Xaero's World Map（`CONSTRUCT`：`Attempted to register a config channel too late` + `XaeroWorldMapCore` CNFE；
  可能是运行时可修的缺口，先记 BLACK + 重启替代）。
- 数据：[whitelist/mods.tsv](whitelist/mods.tsv) 现在 19 行（stock 11 + JBR 8）；视图 `python3 tools/whitelist.py render --write` 重新生成；
  `check` 通过（stock T1=3/T2=0/T3=0/BLACK=8；jbr J1=5/J2=1/BLACK=2）。
- `status.md` 下一步加两条待修：Jade J2 崩溃、Xaero's World Map config channel。

## 2026-11-17（deepseek-flash）白名单扩到 dogfood 常用模组（stock + JBR）

第二轮把 `Ex-Nihilo-Satellitium/run/mods` 里的常用模组也跑了（stock 一批、JBR+lateAt 一批）：

- **stock T1（候选）**：Rhino（PIN, ok）、JECharacters（PIN, ok，coremod 晚注入）。
- **ZeroCore2**：stock `JBR_REQUIRED`（布局 mixin 改已加载类）→ stock BLACK；JBR plain `ok=true` → J1（候选）。
- **CraftTweaker**：stock `ACCESS_TRANSFORMER`（20 条）→ BLACK；JBR+lateAt 在 CONSTRUCT 失败：
  `An error occurred while registering villager trade converters` → `Attempting to instantiate registry
  crafttweaker:transformer_serializer after NewRegistryEvent was fired`（自定义 registry 太晚）→ JBR BLACK（替代：重启）。
- **ExtremeReactors2**：两次都 `MISSING_DEPENDENCY`（需要 ZeroCore2 先热加；两次都没先加载）→ 无结论，未入表。
- 数据：TSV 25 行（stock 15 + JBR 10）；`check`：stock T1=5/T2=0/T3=0/BLACK=10，JBR J1=6/J2=1/BLACK=3；
  视图重新生成。
- 待修/待判：Jade J2 崩溃（多 jar 连加复现、单跑不复现）；Xaero's World Map 的 config channel；
  CraftTweaker 的 `NewRegistryEvent`（看起来是运行时可补的事件重放缺口，不是绝对边界）。

## 2026-11-17（deepseek-flash）修掉 Jade J2：hot-add 的 reload 竞态（白名单里第一个 T2）

Jade 在 JBR+lateAt 下 `RESULT ok=true`，但多 jar 连加约 1 秒后崩：
`AssertionError: Missing config translation: config.jade.plugin_*` → `Rendering overlay`
（crash-2026-11-17_20.13.47-client.txt）。

根因（从 stack trace 读出）：

1. 每个 hot-add 都会 `mc.reloadResourcePacks()`；这些 reload 会**重叠**（Controlling 的 reload 跑了 20 s，
   CompatCheck 3 s 后就加了 Jade）。
2. 旧 reload 完成时，vanilla `LoadingOverlay.render` 会调用 `screen.init(...)` 重新初始化当前 Screen
   （`LoadingOverlay.java:145`）；这会 post `ScreenEvent.Init`。
3. 此时 Jade 的监听器已经注册（构造期），但 Jade 的 `assets/jade/lang/*` 还没被“包含 jade 的那次 reload”加载，
   于是它的配置翻译断言失败，崩在渲染线程。

修法（`client/ClientResourcePacks`）：

- 新增 `requestReload(mc, label)`：reload 在跑时只置 `dirty`，完成后再补一次；**串行化**且合并多次请求；
- 整条链期间把 `mc.screen` 置空（`hiddenScreen`），让 vanilla 的完成回调不 re-init Screen；
- 链的最后一次 reload 完成后再 `setScreen(hidden)`（日志：`screen restored after resource reload: TitleScreen`）。
- 回滚路径也走同一个入口。

验证：复跑原顺序 `Searchables → Controlling → Jade → JEI`（JBR+lateAt）：
**4 个 jar 全 `ok=true`、0 次 `Missing config translation`、0 崩溃**，日志出现
`resource reload already running; queued another for [mod/jade]` 与 `screen restored ... TitleScreen`；
第二次复跑同样干净。白名单 Jade 行从 J2 升 J1（`state=candidate`，等所有者实例复验）。
副作用：hot-add 的资源重载期间当前 Screen 会暂时隐藏（标题屏/暂停屏），重载结束后恢复；世界中本来没有 Screen，无影响。

## 2026-11-17（deepseek-flash）众星捧月进 stock 一等

所有者指示：**众星捧月（Ex Nihilo: Satellitium 1.5.0）加入一等白名单**，JBR 增强稍后自己测。

- 直接按指示入表（owner 实测优先），同时用 stock harness 复核了一遍：
  `-PnoJbr=true` + Temurin 21 → `RESULT exnihilosatellitium-1.21.1-1.5.0.jar predicted=GUIFEI ok=true stage=NONE kind=NONE`。
- 行内容：`stock / T1 / GUIFEI / verified`，证据写“owner 2026-11-17 实际可用 + stock harness ok=true”，
  并注明贵妃级的语义（注册表 add-only、存档会绑定该 mod、不可回滚）。
- 白名单 26 行：stock T1×6 / BLACK×10；JBR J1×7 / J2×0 / BLACK×3。JBR 行等所有者测完再补。

## 2026-11-17（deepseek-flash）GAMELIBRARY 被误报“没有声明任何 mod”

所有者热加独立分发的 `mezz_config-1.21.1-neoforge-0.7.1-standalone.jar`，UI 说“jar 没有声明任何 mod”。

- 事实：这个 jar **没有 mods.toml**，只有 `META-INF/MANIFEST.MF` 里的 `FMLModType: GAMELIBRARY`。
  它是**库**，不是模组；FML 把它当 game library 加载，不建 ModContainer，而热加需要容器
  （module + ModList + 生命周期），所以本质上不可热加（status 已知问题 32 的同一类）。
- 之前的提示有两处误导：字符串“jar declares no mods”（像是一个坏掉的模组），以及 `FailureKind.NOT_A_MOD`
  的“不是 NeoForge 模组（没有 mods.toml）”（也没说清是库）。
- 改法：
  - `ModInspector.isGameLibrary(jar)` 读 manifest 的 `FMLModType`；
  - 预检与 `HotAddEngine` 都走它，返回新的 `FailureKind.LIBRARY`；
  - 新文案 `failure.library.summary/suggestion` 与 `preflight.library`：明确“这是库，不是模组；热加需要 mod 容器，
    放进 mods/ 重启，或由依赖它的模组自带（JiJ）”；
  - `FailureKeys` 映射、`FailureAnalyzer.restartIsTheFix`（库的修复就是重启）与 preflight 的 suggestion 同步。
- 验证：stock harness 复跑 mezz_config 0.7.1 → `kind=LIBRARY`、`msg=library jar (FMLModType: GAMELIBRARY), not a mod`；
  `./gradlew build` 通过（LangKeysTest 覆盖新 key 的中英一致性）。
- 结论给所有者：**这类库直接放 `mods/` 重启**；如果是为了 JEI，不需要单独加（JEI 自带 mezz_config）。

## 2026-11-17（deepseek-flash）JBR 检测：扫游戏目录/父目录，只认 Java 21

所有者把 JBR 解压到和 `.minecraft` 同层，`/modreloader jbr` 却报未找到。

- 根因：`JbrSupport.installRoots()` 只扫 per-OS 固定目录（macOS `~/Library/Java/JavaVirtualMachines`、
  Windows `Program Files/Java` 等），游戏目录与它的父目录不在列表里；`installation()` 本身每次调用都会重算，
  并没有缓存——不是“不实时”，是根本没扫到。
- 改法：
  - `JbrSupport.includeDirectory(dir)`（启动时由 `ModReloader` 调 `FMLPaths.GAMEDIR`）把**游戏目录**和**父目录**
    加入优先扫描根；解压到 `.minecraft` 同层或里面都能找到；
  - 扫描仍每次实时执行，运行中解压也能被下一次 `/modreloader jbr` 看到；
  - 只接受 **Java 21**（release 文件 `JAVA_VERSION=21.x`）：当前 release 页默认是 JBR 25，Minecraft 1.21.1 不认；
    21 与 25 并存时优先 21。
- 测试：新增 `JbrSupportTest` 5 个用例（游戏目录内 / 父目录 / 21 优先 / 只有 25 时忽略 / 无 release 忽略），
  `./gradlew build` 与单测 PASS。
- 文档：guides/jbr.md 第 2 步写明“可解压到 .minecraft 同层/里面，只认 Java 21”。

## 2026-11-17（deepseek-flash）把“JBR 解压到哪”教清楚

检测改成扫游戏目录 + 父目录之后，玩家侧还需要知道“到底放哪”。三个入口同步：

- **玩家指南** [guides/jbr.md](guides/jbr.md) 新增 §1.2「把 JBR 放在哪」：判断标准是“看 `mods/` 在哪”，
  给出“放在实例目录里面 / 旁边”两种放法和官方启动器、PCL2、HMCL、Prism/Modrinth 的实例目录示例，
  明确“不要放进 mods/”“只认 Java 21（25 不行）”“检测是实时的，但启动器仍要重启一次”。
- **游戏内「如何安装 JBR」**：第 2 步改成“解压到游戏目录（有 mods/ 的那一层）里面或它旁边；不要放进 mods/”。
- **`/modreloader jbr`**：未找到时多打印一行 where to put it（游戏目录/旁边，不放进 mods/）。
- 修正 i18n 里的换行转义（`\\n` 被写成了字面量），LangKeysTest 通过；`./gradlew build` 通过。

## 2026-11-17（deepseek-flash）JBR 检测再补：祖辈目录、嵌套解压、显式覆盖

所有者换新构建后仍报未找到。补了三种常见布局：

- **版本隔离**：launcher 的 gameDir 可能是 `.minecraft/versions/<ver>`，而 JBR 放在 `.minecraft` 旁边。
  `includeDirectory` 现在取游戏目录 + 最多 3 层祖辈（不扫文件系统根），并额外加 `user.dir`。
- **zip 多套一层**：`jbr-21.0.11-windows-x64/jbr-21.0.11/release` 这种，扫描会向下找一层
  （只对名字含 `jbr`/`java` 的目录展开，避免扫爆）。
- **显式覆盖**：`-Dmodreloader.jbr=<JBR 目录或 bin/java>`；`/modreloader jbr` 会回显该值。
- `JbrSupportTest` 扩到 8 个用例（游戏目录内/旁、祖辈、嵌套 wrapper、21 优先、25 忽略、显式覆盖、无 release）。
- 新 jar：`build/libs/modreloader-1.21.1-0.1.0.jar`（23:37，sha256 前缀 `5244baeeb1e112c7`）。

## 2026-11-17（deepseek-flash）Windows 上“检测到 JBR 却不能重启”：argv 兜底

所有者反馈“检测到了但什么也不能做”。根因不是检测，而是 **Windows 上
`ProcessHandle.current().info().arguments()` 经常为空**，于是 `Relauncher.blocker()` 报
“the launcher did not expose the game's command line”，失败界面只剩“手动重启”。

- 新增 `Relauncher.currentArguments()`：先走 ProcessHandle；Windows 为空时用
  `powershell Get-CimInstance Win32_Process ... CommandLine`，失败再试 `wmic process ... get commandline`；
  解析用新增的纯函数 `RelaunchScripts.splitWindowsCommandLine`（引号/反斜杠规则），并丢掉 argv[0]（java 路径）。
- `blocker()` 与 `saveAndRestart()` 都改用 `currentArguments()`；Windows 脚本仍走原来的引号规则。
- 单测：`splitWindowsCommandLine` 覆盖带空格路径、`-Dfoo=a b`、转义引号；`./gradlew build` 通过。
- 效果：Windows + JBR 已检测到时，失败界面会重新出现「用 JBR 重启」，点击后带
  `-XX:+AllowEnhancedClassRedefinition`（AT 场景再加 `-Dmodreloader.lateAt=true`）重启，
  并预选待重试的 jar。PowerShell/WMIC 都被禁用时仍回退手动重启。

## 2026-11-17（deepseek-flash）重启失败的真因：.cmd 被 cmd 按 OEM 代码页重读

所有者提供 latest.log：JBR 检测其实成功了（`A JBR 21 install was found at
C:\Dstick\[模组热重载测试]\jbr-21.0.11-windows-x64-b1163.125`），游戏目录是版本隔离的
`.minecraft\versions\1.21.1-NeoForge_21.1.266`，祖先扫描也覆盖到了。

失败在启动方式：生成的 `.cmd` 路径含 `[模组热重载测试]`（中文+方括号），cmd 用系统 OEM 代码页重读，
报 `系统找不到指定的路径`。加 `chcp 65001` 并不能可靠解决所有 cmd 版本。

- 改为**直接 `ProcessBuilder` 启动 JVM**：没有 shell、没有代码页、Unicode 路径原样传递；
  `directory(gameDir)`；Windows 优先 `javaw.exe`（无控制台）；stdout/stderr 写
  `modreloader/relaunch/relaunch-*.log`；子进程照旧等旧 PID（`awaitPreviousProcess`）。
- 成功判定仍靠子进程自己写的 `started-<pid>.txt`（Windows 读不到别人的命令行）。
- `.cmd` 生成保留给 dev harness（`-PverifyRelaunchPlan`），产品路径不再用它。
- 新 jar：`build/libs/modreloader-1.21.1-0.1.0.jar`（23:54，sha256 前缀 `c41d9c07a0c567ad`）。

## 2026-11-17（deepseek-flash）重启后 JVM 报 `--add-opens requires modules to be specified`

所有者截图：子进程 JVM 弹 `Error: --add-opens requires modules to be specified`。

- 根因：PCL 的启动命令行把 `--add-opens java.base/java.lang=ALL-UNNAMED` 写成**空格分隔**；
  `RelaunchScripts.withJvmFlags` 的“带独立取值的选项”清单里没有 `--add-opens`，
  于是把 `java.base/...` 当成主类，把 `-Dmodreloader.*` 插到了选项和它的值之间。
- 修法：清单补 `--add-opens` / `--add-exports` / `--add-reads`（`=` 形式仍是单 token，不受影响）。
- 单测：新增 `jvmFlagsStayAfterAddOpensValue`（空格形式 + `=` 形式）。
- 这也证明直接 `ProcessBuilder` 启动已经生效（JVM 起来了，只是参数被插错），`.cmd`/编码问题不复存在。

## 2026-11-18（deepseek-flash）重启子进程 `InvalidPathException`：命令行里的中文被读成 `?`

所有者给的 `relaunch-*.log`：

```
Error occurred during initialization of boot layer
java.nio.file.InvalidPathException: Illegal char <?> at index 11:
C:\Dstick\[????????????]\.minecraft\libraries/cpw/mods/bootstraplauncher/...
```

- 根因：Windows 兜底用 PowerShell 读 `Win32_Process.CommandLine`，但 PowerShell 的 stdout
  是 OEM 代码页（中文 Windows 为 GBK），我们按 UTF-8 解码 → 中文目录变成 `?`；再用 ProcessBuilder
  传给子 JVM，模块路径就不存在了。
- 修法：PowerShell 命令里强制 `[Console]::OutputEncoding = UTF8`；WMIC 兜底改用
  `native.encoding` / `sun.jnu.encoding` 解码（WMIC 无法强制 UTF-8）。
- 与上一轮的 `--add-opens requires modules to be specified` 是两个独立的参数 bug，均已修。
- 新 jar：`build/libs/modreloader-1.21.1-0.1.0.jar`（00:06，sha256 前缀 `28ca6b2377a67fc7`）。

## 2026-11-18（deepseek-flash）弹窗“AT 不可用”和 JBR 白名单看起来矛盾

所有者截图：stock 下 Jade/JEI 显示“带 access transformer，热加载无法应用”，但白名单里它们是 JBR J1。

- 这不是矛盾，是**环境不同**：白名单的 J1 行证据写的是 `JBR+lateAt`，而当前会话是 Microsoft JDK、
  `enhanced=false`、`lateAt=off`；AT 只在启动时生效，除非显式开实验性 late AT。
- UI 缺陷：弹窗只讲 AT，不讲“当前在哪个环境判的”。修：
  - 弹窗底部加一行 `JVM：<vendor> · 增强重定义：on/off · late AT：on/off`；
  - AT 的拒绝文案补上“或改用 JBR 21 并加 -Dmodreloader.lateAt=true（实验性，仅本次会话）”；
  - 白名单视图的读法补一句“J1/J2 里的 AT 模组需要 JBR + lateAt，只开 enhanced 不够”。
- `./gradlew build` 通过；视图重新生成。

## 2026-11-18（deepseek-flash）玩家侧零折腾（ADR-0015）：删掉玩家路径上的 JBR

所有者定调：装 JBR 已经是权宜之计，不允许再把“让玩家折腾”当作解法；宁可老实重启。

- 新增 [ADR-0015](../decisions/0015-player-side-zero-tinkering.md)（Accepted）。
- 玩家 UI 收口：
  - `LoadFailureScreen` 只保留「保存并重启」；删掉「用 JBR 重启」「如何安装 JBR」以及 JBR 提示行；
  - watcher 列表删掉「如何安装 JBR」入口；
  - 删除 `JbrGuideScreen` 与 `RelaunchConfirmScreen`；
  - `preflight.access_transformer` 文案回到“把 jar 放进 mods/ 后重启”，不再提 JBR/参数。
- JBR/late AT 保留给整合包作者、管理员与 dev harness（`-PlateAt`、`/modreloader jbr` 诊断仍在）；
  `guides/jbr.md` 顶部改为“面向整合包作者/管理员/进阶，普通玩家不需要读”。
- `AGENTS.md` §2 的 JBR 行同步为“玩家侧零折腾”。`status.md` 加“玩家侧原则”小节。
- `./gradlew build` 通过；视图与白名单不受影响。

## 2026-11-18（deepseek-flash）纠正 ADR-0015：不是删能力，而是自动化

所有者指出我把“别让玩家折腾”执行成了“删掉玩家侧 JBR 能力”，这是逃避。已回退：

- 恢复 `JbrGuideScreen`、`RelaunchConfirmScreen` 与失败界面的「用 JBR 重启 / 如何安装 JBR」、
  watcher 列表的 JBR 指引；JBR 重启仍然自动带 `-XX:+AllowEnhancedClassRedefinition`
  和 `-Dmodreloader.lateAt=true`。
- 重写 [ADR-0015](../decisions/0015-player-side-zero-tinkering.md)：玩家侧**零手工**，但能力由运行时
  自动准备（已存在→一键重启；缺失→自动下载/整合包预置；失败才退回重启）；不把装 JBR/填参数写成玩家任务。
- AGENTS/status 同步；`./gradlew build` 通过。
- 下一步（未做）：JBR 自动下载（平台识别、官方来源、校验、解压、失败回滚）——这是“能帮就帮”的真正落点。

## 2026-11-18（deepseek-flash）一键自动准备 JBR（下载 + SHA-512 + 解压 + 重启）

所有者要求“能帮就帮”，不要把装 JBR 推给玩家。实现（未在 Windows 真机跑过下载全链路）：

- `JbrProvisioner`：平台/架构 → 固定 JBR 21.0.11（b1163.125）资产；
  官方 `cache-redirector.jetbrains.com/intellij-jbr/` 下载；SHA-512 校验（值取自官方 `.checksum`）；
  zip 用 `ZipInputStream`、tar.gz 用 `tar -xzf`；解压后定位 `release`+`bin/java`，装到 `gameDir/jbr-21.0.11/`；
  失败清理；Windows 不支持 aarch64 以外的其它平台时明确报错。
- `JbrSetupScreen`：一次点击 → 后台下载（状态行）→ 成功后用新 JBR + `jbrFlags(true)` 重启；
  失败保留游戏并给出重试/手动入口；关闭窗口则不会自动重启。
- 失败界面/监听列表在缺 JBR 时改为「自动准备 JBR」，手动安装降为次级入口。
- 校验：本机下载 macOS aarch64 资产 94,837,906 字节，SHA-512 与固定值一致；
  tar 顶层为 `jbr-21.0.11-osx-aarch64-b1163.125/Contents/Home`，`findHome` 深度 3 可覆盖。
- 单测：`JbrProvisionerTest`（5 个资产各有 128 位 SHA-512、官方 URL、安装目录、平台支持）。
- 未验证：Windows 真机下载/解压/重启全链路（资产哈希来自官方 checksum，未在 Windows 实跑）。

## 2026-11-18（deepseek-flash）白名单扩样：14 个常用模组 stock + JBR

所有者要求继续下载常用热门模组做自动化分类，实例复测稍后。jar 仍只落 `/tmp/modreloader-corpus`。

- 语料：`tools/pipeline/corpus.json` 新增 14 个条目（Modrinth 精确版本 + sha256 锁定）：
  Cloth Config、Architectury、GeckoLib、Curios、Patchouli、Embeddium、ModernFix、FerriteCore、
  Simple Voice Chat、Farmer's Delight、Waystones、Comforts、Moonlight、Supplementaries；
  新增 5 个 scenario（`popular-libs/perf/misc/moonlight/balm`），`check` OK。
- 方法：stock = Temurin 21 + `-PnoJbr=true`（日志 `enhancedRedefinition=false`）；
  JBR = 默认 JBR，AT 批次加 `-PlateAt=true`；全部 `-PnoFocus=true`，每批一次客户端，
  CompatCheck 逐 jar 记 `RESULT`。
- 结果（数据见 [whitelist/mods.tsv](whitelist/mods.tsv)，现 54 行）：
  - stock 新晋 T1：Simple Voice Chat（`ok=true isLoaded=true`）。
  - stock 新增 BLACK：Cloth Config（3 AT）、Architectury（311）、GeckoLib（27）、Curios（16）、
    Embeddium（19）、ModernFix（74）、Farmer's Delight（16）为 AT；Patchouli / FerriteCore /
    Waystones / Comforts 为 `JBR_REQUIRED` 布局 mixin；Moonlight（嵌套 AT）；
    Supplementaries（`JAR_IN_JAR` ClassCastException）。
  - JBR 新晋 J1（`candidate`）：Cloth Config、Curios、Patchouli、Embeddium、Simple Voice Chat、Comforts；
    Balm 复核仍通过。
  - JBR BLACK：Architectury（`RegisterShadersEvent`）、Farmer's Delight
    （`RegisterRecipeBookCategoriesEvent`）、GeckoLib/FerriteCore/ModernFix（redefine `ClassFormatError`）、
    Waystones（post-construct 事件失败）、Moonlight（嵌套 AT）、Supplementaries（JiJ）。
- 新发现（已写入 [status.md](status.md) 已知问题 34–36）：
  ModernFix 失败后残留 method-level mixin，下一 tick `NoClassDefFoundError` 崩溃；
  Waystones 失败但注册表已 +44 方块 / +55 物品；late AT 不覆盖 JiJ 嵌套 AT，
  Supplementaries 的 JiJ 读取仍有 ClassCastException。
- 备注：Waystones/Comforts 的 stock 行是在 `run/mods` 预置 Balm 后测的（Balm 在 stock 不能热加），
  测完已移除预置 jar；JBR 批次按 `Balm → Waystones → Comforts` 顺序热加。

## 2026-11-18（deepseek-flash）玩家文案去参数名（P0-文案）

按 ADR-0015“玩家只感知用 JBR 重启一次，不感知 per-mod 参数”清理玩家可见文案：

- i18n（zh_cn/en_us 各 14 个 key）：`gui.env`、`jbr_guide.body`、`gui.failure.jbr_late_at_hint`、
  `relaunch.jbr.body`、`relaunch.jbr.late_at_warning`、`preflight.jbr`、`preflight.suggestion.jbr`、
  `failure.jbr_required.suggestion`、`preflight.access_transformer`、
  `preflight.access_transformer_late_off`、`preflight.access_transformer_late`、
  `toast.resume.late_at`、`jbr_setup.body`、`failure.access_transformer.suggestion`；
  不再出现 `-XX`、`-Dmodreloader`、`lateAt`，统一说“用 JBR 重启一次 / 自动准备 JBR，所需能力自动启用”。
- 代码：`ModReloaderScreen` 的 `gui.env` 去掉 late AT 开关一列；`JbrSupport.hint()/diagnose()`
  改为指向失败界面的「用 JBR 重启 / 自动准备 JBR」，不再教玩家改启动器或加参数；
  `HotAddEngine` / `LateAccessTransformers` 的 AT 失败消息同步去掉参数名。
- 回归：`LangKeysTest.playerCopyDoesNotExposeJvmFlags` 扫描两份语言文件禁止三个参数名；
  `./gradlew test`、`./gradlew build` 通过。
- 保留：`guides/jbr.md`（包作者/管理员）与本机诊断中的 JVM 路径仍是技术信息；
  `relaunch.jbr.late_at_warning` 保留“改写已加载类访问标志”的风险说明，但不再给开关名。

## 2026-11-18（deepseek-flash）修两个“失败后不干净”的缺口（待真机复跑）

白名单扩样暴露的两个产品失败点，先离线定根因并修：

- **ModernFix 半应用 mixin**：回滚原来先还原类、后摘 mixin config；还原时 config 仍活跃，
  Mixin agent 又把 `onClientTick` 注入写回 `Minecraft`，随后模块回滚让 `ModernFixClient` 不可见，
  下一 tick `NoClassDefFoundError`（`crash-2026-11-18_09.39.40-client.txt`）。
  修法：`MixinSupport.register` 把 config 移除动作放到回滚栈最后入栈（LIFO 先执行），
  再还原类字节；顺序成为“摘 config → 还原类 → 卸 module”。
- **Supplementaries JiJ ClassCastException**：嵌套的 MixinSquared 带 `mods.toml` 但
  `FMLModType: GAMELIBRARY`，`NestedMods` 把它当 mod 强转 `ModFileInfo` 失败。
  修法：`JarInJar` 按 `FMLModType` 把嵌套 GAMELIBRARY 归为库（只进 module path），
  并读取其 manifest `MixinConfigs` 交给 Mixin；`NestedMods` 对非 `ModFileInfo` 给明确错误。
- 单测：`JarInJarTest` 新增 4 个用例（普通 mod / 带 mods.toml 的 GAMELIBRARY / 无元数据 / manifest mixins）；
  `./gradlew test`、`./gradlew build` PASS。
- **未验证**：两处都需要一次真机客户端复跑（ModernFix 回滚后应继续存活；Supplementaries 需
  Moonlight 预置在 run/mods 才能越过依赖检查）。嵌套 AT（Moonlight/codecui）仍未覆盖，
  Waystones 半注册、CraftTweaker 自定义 registry、Xaero's World Map config channel 仍在缺口清单。

## 2026-11-18（deepseek-flash）JBR 玩家路径端到端：macOS dev PASS（PCL2/Windows 未验证）

场景：不懂电脑的玩家、官方 Temurin（stock）、想热加 JBR 一等模组。目标是把“自动准备 JBR →
找到它 → JBR 重启 → 新进程热加”整条产品路径跑通。本机是 macOS arm64，**PCL2 是 Windows 启动器，
无法真实运行**；本次验证的是 launcher-agnostic 路径。

- 新增 dev harness `-PverifyJbrFlow=<jar,...>`（`src/dev/.../JbrPlayerFlowCheck`，分两阶段，
  阶段由当前 JVM 决定，不需要额外开关）：
  - **Phase 1（stock）**：假装机器上没有 JBR（临时改 `user.home`，只扫游戏目录）→ 先加一个 J1 模组，
    确认被 `JBR_REQUIRED` 拒绝 → `JbrProvisioner.install` 真实下载、SHA-512、解压 →
    `JbrSupport.installation()` 找到刚解压的运行时 → 写 `pending-add` 与 jar 清单 →
    `Relauncher.saveAndRestart(..., jbrFlags(true))`。
  - **Phase 2（JBR）**：新进程 `JBR=true, enhancedRedefinition=true` → 逐个热加清单里的 jar，
    写 `run/modreloader/jbr-flow-result.txt`，然后退出。
- 实测（2026-11-18 10:48–10:50）：
  - stock 父进程：Mouse Tweaks 被 `JBR_REQUIRED` 拒绝；下载 90.44 MB，SHA-512 校验、解压
    `run/jbr-21.0.11`（167 MB）均成功；replacement JVM 启动，父进程退出。
  - 子进程：`MouseTweaks ok=true isLoaded=true`、`balm ok=true isLoaded=true`、
    `cloth_config ok=true isLoaded=true`（late AT 3 rule/3 target 应用）；`RESULT: PASS`。
  - 另外用 JDK 21 jshell 验证：`JbrSupport.includeDirectory(run)` 后
    `installation()` 返回 `run/jbr-21.0.11`（Temurin 下也能找到刚解压的 JBR）。
- 证据：`/tmp/jbrflow-parent.log`、`run/modreloader/jbr-flow-result.txt`、
  `run/modreloader/relaunch/relaunch-1794970188766.log`。
- **未覆盖**：PCL2/Windows 的启动器 argv、`javaw`、GUI 按钮点击（harness 调用的是
  `JbrSetupScreen` 背后的同一组方法，没有真的点按钮）、真实 redefine 探针。
  已知问题 37 只更新为“macOS dev 端到端 PASS”，不写成 Windows/PCL2 已验证。

## 2026-11-18（deepseek-flash）复跑两个缺口修复：PASS

命令：`./gradlew runClient -PnoFocus=true -PlateAt=true -PverifyCompat=modernfix,supplementaries`
（不预装 Moonlight，只看 JiJ 是否越过；日志 `/tmp/wl-verify-fixes2.log`）。

- **ModernFix**：回滚顺序已变为 `remove mixin config modernfix... ok` →
  `restore class net.minecraft.client.Minecraft ok`；`RESULT ok=false kind=MIXIN_CONFIG` 仍按预期拒绝，
  但客户端不再崩，继续跑到 `DONE 2/2`。已知问题 34 收口。
- **Supplementaries**：不再 `JAR_IN_JAR`；日志出现
  `jar-in-jar: 2 nested jar(s) -> modules [mixinsquared.forge, sable.companion.common]; containers [sablecompanion]`，
  mixin configs 包含 `mixinsquared.forge.init.mixins.json`；随后在 VALIDATE 返回
  `MISSING_DEPENDENCY`（需要 Moonlight）。JiJ 修复验证通过，已知问题 36 的 JiJ 一半收口。
- Moonlight 预装到 `run/mods` 会触发 dev 启动崩（`codecui.Schema` ClassNotFound，已知问题 39），
  因此本次没有验证 Supplementaries + Moonlight 的完整功能；不据此判定模组不可用。
- 白名单：ModernFix JBR 行改为“回滚已干净、客户端不再崩”；Supplementaries 两行 state=fixing，
  写明“JiJ 已修，待 Moonlight 可用后重测”。视图已重新生成。

## 2026-11-18（deepseek-flash）重放配方书分类事件（Farmer's Delight）

- 新增 `client/RecipeBookCategoryReplay`：`RecipeBookManager` 的三个 map 都是 `HashMap`
  （aggregate view 只是 `unmodifiableMap` 包装），按当前条目复制成
  `ImmutableList` 副本，对新容器发 `RegisterRecipeBookCategoriesEvent`，再 `putAll` 合并。
- 注册审计：`RegisterRecipeBookCategoriesEvent` 从 `crash` 移入 `covered`；`RegAuditCheck`
  的负向夹具改用仍未重放的 `RegisterShadersEvent`；`RegistrationAuditTest` 同步。
- 夹具：`experiments/client-fixture` 增加 `registerBookCategories(CRAFTING, …)`；
  热加夹具实测 `recipe book categories replayed: 1 new, 4 aggregate / 1 type / 0 lookup`，
  `AddCmdCheck RESULT: PASS`。
- Farmer's Delight：注册审计不再拒绝，但在 JBR+lateAt 下改为卡在
  `redefine SecondaryPoiSensor: ClassFormatError: null`（`kind=MIXIN_CONFIG`）。
  这是已知问题 40 的共同阻碍，不是配方书重放的问题；白名单 JBR 行已更新。
- 单测 / 构建 PASS。

## 2026-11-18（deepseek-flash）定位 ClassFormatError：Mixin agent 二次 transform（MixinAgentGuard）

接上一轮：Farmer's Delight 等模组在 JBR+lateAt 下 `redefineClasses` 报
`ClassFormatError: null`。排查过程：

1. 新增 `-PverifyRedefineProbe`：对失败类用纯 ASM 做 identical / 加 public/private/static/final
   字段、public/private 方法、接口、invokedynamic、SourceDebugExtension、注解、synthetic、
   多方法组合，再 `redefineClasses` 并恢复。结果全部 OK → **不是 JBR 加成员的能力问题**。
2. `MixinSupport` 在 redefine 失败时 dump original/mixed 字节到 `run/modreloader/mixin-failures/`；
   对比发现 mixed 加了 MixinExtras 的 bridge 方法、`BootstrapMethods`、`SourceDebugExtension`，
   本身结构校验通过。
3. 对照组：Farmer's Delight 正常装进 `run/mods` 启动无报错 → 问题在热加路径。
4. 打开 `-PmixinDebug=true` 后看到真正的栈：
   `redefineClasses0 → MixinAgent$Transformer.transform → MixinTransformer.transformClassBytes`；
   日志同时有 `Critical injection failure ... Scanned 0 target(s). No refMap loaded.`。
   即：JBR redefine 触发 Mixin agent，把我们**已经手动混入**的字节**再 transform 一次**，
   第二遍 WrapOperation 失败，agent 返回错误字节 → `ClassFormatError`。

修法：新增 `hotadd/MixinAgentGuard`：

- 从 `MixinAgent.instrumentation` 取 Mixin 自己的 `Instrumentation`（我们自附加拿到的实例
  transformer 列表是空的）；
- 在 redefine 窗口内把 agent 的 `MixinAgent$Transformer` 换成 delegating wrapper：
  只对本次目标类在 `classBeingRedefined != null` 时返回 `null`（不再 transform），其它类正常委托；
  结束后移除 wrapper 并恢复原 transformer；
- 第一版恢复时误用了我们自己的 `Instrumentation`，wrapper 残留，导致后续模组仍双重 transform；
  已改为保存 `owner` 并用它恢复。

实测（JBR+lateAt）：

- Farmer's Delight：`ok=true isLoaded=true`，blocks 1060→1192 / items 1333→1518。
- GeckoLib、FerriteCore：`ok=true isLoaded=true`。
- ModernFix 单独：`ok=true isLoaded=true`；MouseTweaks 对照：`ok=true`。
- 同批 `GeckoLib → FerriteCore → ModernFix`：ModernFix 卡在
  `redefine net.minecraft.world.entity.Entity: ClassFormatError` + `ROLLBACK_PARTIAL`
  → 记为已知问题 41（同目标类叠加，单加可绕过）。
- 白名单：Farmer's Delight / GeckoLib / FerriteCore → JBR J1（candidate），
  ModernFix → J2（单加 PASS、同批有 bug，state=fixing）。
- 单测 / `./gradlew test` PASS。

## 2026-11-18（deepseek-flash）同批叠加修复：手动 transform 只保留新 config

`GeckoLib→FerriteCore→ModernFix→MouseTweaks` 批次里 ModernFix 曾失败：
`redefine net.minecraft.world.entity.Entity: ClassFormatError` + `ROLLBACK_PARTIAL`，
而 ModernFix 单独热加 PASS。

- 根因：`MixinSupport` 的 `transformClassBytes` 会应用处理器里**全部 active config**；
  第二次热加时，前面模组的 config 也被套到新目标上，其中一些注入点已不匹配，
  产生坏字节/重复注入。
- 修法：手动 transform 期间把 processor 的 active/pending config 临时过滤为
  **本次新增的配置名**，`applyTargets` 结束后恢复原列表；JBR redefine 期间的
  Mixin agent 仍由 `MixinAgentGuard` 暂停。
- 实测：4 模组批次全部 `ok=true isLoaded=true`，`DONE 4/4`（日志 `/tmp/wl-batch-isolated.log`）。
- 白名单：ModernFix 从 J2 升 JBR J1（candidate）；JBR 计数 J1×17 / J2×0 / BLACK×7。
- 边界：若两个模组真正改同一个类，当前仍以原类字节为基底，尚未专门验证“同目标叠加”。

## 2026-11-18（deepseek-flash）shader 重放 + 类字节缓存（Architectury J1、JourneyMap J2）

- 新增 `client/ShaderReplay`：热加成功后在 `ClientRegistrationReplay` 入队，
  等 `ClientResourcePacks` 的重载结束（shader JSON 在新 pack 里）再发
  `RegisterShadersEvent`，把 shader 填进 `GameRenderer.shaders` 并调用 consumer。
  审计把 `RegisterShadersEvent` 从 `crash` 移入 `covered`；负向夹具改用
  `RegisterSpriteSourceTypesEvent`。
- 新增 `hotadd/ClassBytesCache`：late AT 与 mixin 每次成功 redefine 后缓存实际字节，
  回滚写回；`captureOriginals` 优先用缓存。修掉 JourneyMap 的
  `public-f Screen init` 被 mixin 阶段用原始字节覆盖、`JmUILegacy` override final 失败。
- 实测：Architectury（late AT 311 rule）`ok=true isLoaded=true`；shader 列表为 0。
  JourneyMap 现在 `ok=true isLoaded=true`，但随后资源重载崩：
  `PalettedPermutations` 大量 `trim_palette` 缺失 + `SpriteContents` mip null
  （crash-2026-11-18_15.39.30-client.txt）；单独与批次均复现，shader 非空路径仍未验证。
- 白名单：Architectury → JBR J1（candidate）；JourneyMap → JBR J2（state=fixing）；
  JBR 计数 J1×18 / J2×1 / BLACK×5。
- 单测 / `./gradlew test` PASS。

## 2026-11-18（deepseek-flash）复盘 + 文档/记忆整理（不改产品行为）

所有者要求复盘并整理文档与记忆。本轮只改文档，不改代码行为。

- 新增 [retro/2026-11-18-jbr-saga.md](../retro/2026-11-18-jbr-saga.md)：时间线、我犯的错（把“别让玩家折腾”执行成删 JBR、
  擅自决定、不诊断就换构建、把未验证说成可用、忽视感受）、7 个技术根因与修法、ADR-0015 最终版、未完成项。
- `AGENTS.md` 新增 §7「与所有者协作」五条硬约定（改玩家可见能力/产品方向先问；不交付未验证构建；
  修 bug 可直接做但取舍先给方案；情绪优先；不删除历史），目录树补 `docs/retro/`。
- `status.md`：JBR 行改为「检测 + 一键准备」（官方下载/SHA-512/解压/自动带参数，Windows 全链路未验）；
  托管重启行改为直接 `ProcessBuilder` + 子进程标记 + argv 兜底；玩家侧原则改为“零手工、不删能力”；
  已知问题新增 37（Windows 全链路未验）/38（只覆盖 5 平台、Intel mac 无资产）；下一步 P1-新同步。
- `features/jbr-support.md`：状态/决策树/自动安装章节/能力表/优先级更新；`features/managed-relaunch.md`：
  机制改为直接起 JVM、补 `--add-opens`/编码/标记/冻结，验证清单补 `JbrProvisionerTest` 与 macOS 资产哈希；
  `guides/jbr.md` 顶部改为“玩家点自动准备即可”。
- 验证：`./gradlew build`（含 4 个 `JbrProvisionerTest`）通过；`python3 tools/whitelist.py check` 通过。

## 2026-11-18（deepseek-flash）补充两条产品边界：安装/解压上限 + 统一能力模型

所有者明确：

1. **JBR 可产品化，但安装/解压以外不能再折腾**：玩家最多下载/安装/解压 JBR；之后运行时找到并接管，
   不改启动器 Java、不填参数；能自动下载/解压时连这一步也不要求。
2. **玩家不关心 per-mod 参数**：他只感知“用 JBR 重启后，需要 JBR 的模组就能热加”；
   不区分 `-XX…` 与 `lateAt`，界面不展示开关名。

已落文档：

- `decisions/0015`：决策改为四条（安装/解压上限、统一能力、不删能力、参数只留内部证据），
  后果补“玩家可见文案不得出现开关名”“降级是重启而不是加步骤”；
- `status.md`：玩家侧原则改写，并新增 **P0-文案**（玩家界面去参数名，附待改 key 列表）；
- `features/jbr-support.md`：新增 §1.1 玩家模型；能力表标注“内部视角”；
- `features/managed-relaunch.md`：确认屏只讲“重启一次”，开关名留在内部；
- `features/mod-whitelist.md`：「额外参数」标注为内部证据，不是玩家界面；
- `guides/jbr.md`：顶部加“玩家侧只有一句”，能力表标注包作者视角；
- `AGENTS.md` §2：加产品不变量；复盘补 §4.1。

验证：`./gradlew build` 通过；`python3 tools/whitelist.py check` 通过。**本轮未改玩家可见文案（列为 P0-文案）。**

## 2026-11-18（deepseek-flash）确保自动准备 JBR 可用：校验/解压实测 + 硬化

所有者要求“自动准备 JBR 能力保留，但要确保可用”。本轮做了可验证的部分：

- 资产核对：Windows x64 zip **94,352,754 字节**，SHA-512 前缀 `845fc37fa1af3435…` 与固定值一致；
  macOS aarch64 tar **94,837,906 字节**，SHA-512 前缀 `ecc474537e8e0bc6…` 一致。
- 结构核对：Windows zip 顶层 `jbr-21.0.11-windows-x64-b1163.125/`，含 `release` 与 `bin/java.exe`；
  用真实 zip 跑 `JbrProvisioner.installArchive` → `isRuntimeHome` 通过（临时测试，已删除）。
- 硬化：`installArchive` 拆分（先校验再解压）；`verify` 独立；zip 解压后恢复 `bin/*` 可执行位；
  `isRuntimeLayout`（看布局）与 `isRuntimeHome`（POSIX 再要求 +x；Windows 认 `java.exe` 存在）分离；
  HTTP 连接 20s/请求 10min 超时；下载失败清 `.part`；空下载拒绝；成功后清 archive/unpack 缓存；
  `JbrSetupScreen` 用 `drawWordWrap` 显示错误。
- 新增 `JbrProvisionerArchiveTest`（3 个）：平铺 zip、mac bundle（`Contents/Home`）、SHA-512 匹配/拒绝；
  现有 `JbrProvisionerTest` 4 个；共 7 个。
- **仍未验证**：Windows 真机 `javaw` 启动、PowerShell/WMIC 读取命令行；这是唯一剩下的硬项。

## 2026-11-18（deepseek-flash）产品原则：重启 = 产品失败（切到 JBR 除外）

所有者定调：**玩家为切到 JBR 重启一次不算失败；JBR 就位后仍因某个模组要求重启，是产品失败。**

- `decisions/0015`：决策新增第 4 条（重启=产品失败，切 JBR 除外），第 3 条的“放 mods/ 重启”改为最后手段/失败信号；
- `AGENTS.md` §2 产品不变量补该条；`status.md` 玩家侧原则补该条，并在下一步新增 **P0-新：消除“JBR 后仍需重启”的缺口**；
- `features/mod-whitelist.md`：增加规则 2（只重启即缺口），重排编号；T3→BLACK 不再把“重启”写成正常替代路径；
- `features/jbr-support.md` §2.1 第 4 步标注为最后手段/产品失败；
- `guides/jbr.md` 顶部把“放 mods/ 重启”改为失败信号；
- `retro` 新增 §4.2。
- 未改代码/文案；`./gradlew build` 通过。

## 2026-11-18（JourneyMap 资源重载崩溃：late AT 去「类」final 是 JBR 缺陷）

从已知问题 43 接：JourneyMap 热加 `ok=true` 后资源重载崩（`PalettedPermutations` 全错 +
`SpriteContents$InterpolationData` 的 `byMipLevel` null）。排查结论不是资源重载：

- `latest.log` 里 `late AT applied com.mojang.blaze3d.platform.NativeImage (3 rule(s))`；JourneyMap 的 AT 含
  `public-f NativeImage` / `public-f ModelPart`（类级规则，语义是去掉**类**的 `final`），另有两三条成员规则。
- 崩溃里所有 PNG 解码都返回 null（`BitmapProvider` / `GrassColorReloadListener` / `PalettedPermutations`），
  但 1.21.1 的 `NativeImage.read` 只会抛异常、不会返回 null → 说明类被改坏了，不是资源缺失。
- **独立 JBR 探针（不启动游戏）**：JBR 21.0.11 + `-XX:+AllowEnhancedClassRedefinition` + 一次性 agent + ASM：
  - 只改字段/方法访问（`private→public`）→ `NativeImage.read` 仍 128x128；
  - 去**类** `final`（有 final 实例字段）→ `NativeImage.read` 返回 null；
  - 合成 `final class FinalThing`（含 final 字段）→ redefine 后 `new`/工厂/反射构造全部返回 null；
  - 先剥字段 final 再剥类 final：仍 null；**再 redefine 一次 → SIGSEGV**（`ConstantPoolCacheEntry::clear_entry`）。
  - 没有 final 实例字段的 final 类去 final 正常 → 触发条件是「类 final + final 实例字段」。
- 同一机制解释了此前 `replayRenderers` 的 `PartDefinition.bake` NPE（`ModelPart` 也是类级 `-f`）。

修法：`hotadd/LateAccessTransformers` 在改任何字节**之前**用 `classFinalBlockers` 拒绝
「目标已加载 + 是 final + 规则类级 `-f`」，`FailureKind.ACCESS_TRANSFORMER`，文案要求放 `mods/` 重启让 FML 在加载期应用；
成员级 access/final 照旧。`rewrite` 对非 final 类的类级 `-f` 是空操作（不记 note）；对 final 类保留 final 并记 note（防御）。
单测 `LateAccessTransformersTest`：成员规则照旧、类级规则保留 final + 1 条 note、非 final 类 0 note。
`CompatCheck` 新增 `-PverifyCompatSettle=<ticks>`（默认 40），让热加后的资源重载跑完再退出。

实跑（`runClient -PverifyCompat=<journeymap> -PlateAt=true -PnoFocus=true`）：
- 修复前：`RESULT ok=true`，随后 0.9s 内崩溃（15.39.30 crash report）；
- 修复后：`ok=false stage=VALIDATE kind=ACCESS_TRANSFORMER isLoaded=false`，无崩溃、无残留；
  对照（只保留 final、不加拒绝）时资源重载报 `IncompatibleClassChangeError: ComparableNativeImage cannot inherit from final class`，
  客户端虽然活着但 `Caught error loading resourcepacks, removing all selected resourcepacks` → 拒绝是更诚实的语义。
- 对照里还观察到：JourneyMap 给 `DynamicTexture` 加的 `scaledImageMap` 在启动期已存在的 `LightTexture` 上是 null，
  退出时其 `onClose` 注入抛 NPE（`Shutdown failure!`）→ 记入已知问题 45（mixin 新字段不初始化老实例）。

白名单：JourneyMap `jbr J2 (fixing)` → `jbr BLACK (verified)`，stock 行补“late AT 也不适用”；计数 J1×18 / J2×0 / BLACK×6。
文档：`status.md`（一句话、late AT 行、已知问题 43 结案 + 44/45、下一步）、`features/late-at.md`（§5.1 探针表 + 拒绝语义）、
`AGENTS.md`（命令注释 + 硬规则 14）、`features/jbr-support.md`、`features/hotadd-test-pipeline.md` 中“只覆盖 access/final”改为“成员级”。
验证：`./gradlew build`、`python3 tools/whitelist.py check`（54 行）通过。

## 2026-11-18（ShaderReplay 非空路径：先验出“重复注册”，再修掉）

status 的下一项是“shader 重放只有空列表证据（Architectury 0 个 shader），需要一个真实注册 shader 的夹具”。

- **夹具**：`experiments/client-fixture` 增加 `clientfixture:marker`——`assets/clientfixture/shaders/core/marker.{json,vsh,fsh}`
  （从原版 `position_color` 复制，`vertex/fragment` 写成 `clientfixture:marker`，因为 `ShaderInstance` 对裸露名字默认
  `minecraft:` 命名空间，第一次跑就踩到 `FileNotFoundException: minecraft:shaders/core/marker.vsh`）+
  mod bus 上的 `RegisterShadersEvent` 监听器，consumer 写静态字段（和真实模组一样）。schema 版本 `1 new`。
- **harness**：`CompatCheck` 新增 `-PverifyShaderCount=<n>`：记录首次 add 前 `GameRenderer.shaders` 的 size，DONE 前断言增量；
  配合已有的 `-PverifyCompatSettle` 让热加重载跑完。实测 `shaders RESULT: PASS (56 -> 57, expected +1)`。
- **顺带发现并修掉一个 bug**：日志里夹具的 `registered shader` 出现**两次**——热加资源重载的 `GameRenderer.reloadShaders`
  会给 ModList 里所有容器（含刚热加的那个）fire `RegisterShadersEvent`，而 `afterHotAdd` 之后 `ShaderReplay` 又补了一次；
  同名 `ShaderInstance` 覆盖活实例并把第一个泄漏掉（既浪费 GLSL 编译，也可能让静态字段指向错误实例）。
  修法：`PlatformHooks.injectResourcePacks` 返回“是否触发了资源重载”，`ClientPlatformHooks.afterHotAdd` 在 true 时
  跳过 replay 并打印原因；`ClientResourcePacks.injectAll` 返回该布尔（顺带删掉没有调用方的 `inject(...)` 包装）。
  修后日志：`registered shader` 只出现一次 + `shader replay skipped: the resource reload already ran RegisterShadersEvent...`，
  map 仍 `56 -> 57`。
- **兜底路径**：jar 无 `assets/` 且没注册 reload listener 时不会触发重载，`ShaderReplay` 仍负责补一次；
  这条路径目前没有夹具（已记在 status 下一步）。
- 文档：`status.md`（已验证、已知问题 21 措辞、下一步行与交接）、`features/client-registration-audit.md`（covered 说明与夹具证据）、
  `experiments/client-fixture/README.md`（shader + 期望日志）、`AGENTS.md` §2（两条 harness 命令）。
- 验证：`./gradlew build`、`-PverifyCompat=<clientfixture> -PverifyShaderCount=1` PASS、`python3 tools/whitelist.py check`。

## 2026-11-18（JiJ 嵌套 AT 是误判：空文件 + toml LIBRARY；Moonlight 转自定义注册表）

起点是 status 的“某条建议：让 late AT 覆盖 JiJ 嵌套 jar（Moonlight 的 AT 在 codecui 里）”。先核对语料，结论是**前提错了**：

- 全语料扫描（外层 + `META-INF/jarjar/*.jar`）：**没有一个 jar 有非空的嵌套 AT**；codecui 的
  `META-INF/accesstransformer.cfg` 是 **0 字节**，`accesstransformer1.cfg` 也是 0 字节（FML 本来就只认默认文件）。
- Moonlight 的 126 条 AT 在**外层** jar；它的 `kind=ACCESS_TRANSFORMER` 来自两层误判：
  1. `NestedMods` 把 codecui 当嵌套 **mod**（manifest 没有 `FMLModType`），FML 的 `getAccessTransformers()` 列出空 AT 文件 → “ships 1 access transformer”；
  2. `ModInspector.scanJar` 只看 `META-INF/accesstransformer.cfg` **是否存在**，不看内容。
- FML 的 AT 发现规则（loader 4.0.45 反编译）：toml `[[accessTransformers]] file="…"` 存在时**只用**这些文件，否则默认
  `META-INF/accesstransformer.cfg`；空文件等于没有规则。

修法：
- `AccessTransformerRules.entries(zip)` 按 FML 的规则算入口；`read` 合并这些文件；`hasRules(zip)` 只认**有内容**的条目。
  单测：空 AT、toml 声明替换默认、声明但文件缺失、toml `modType=LIBRARY`（`ModInspectorTest`）。
- `ModInspector.scanJar` 用 `hasRules`；`isGameLibrary` 除 manifest `FMLModType` 外也认 toml `modType = "LIBRARY"`
  （codecui 就是这种）；`NestedMods` 跳过 0 字节 AT。**嵌套 jar 的真 AT 仍不覆盖**。

复跑 Moonlight（`-PverifyCompat=<moonlight> -PlateAt=true -PnoFocus=true`）：越过 MODULE/JiJ，late AT 应用 126 条规则，
随后 `kind=CONSTRUCT`：
`IllegalStateException: Attempting to instantiate registry with name moonlight:villager_trades after NewRegistryEvent was fired!`
（`MoonlightRegistry.<clinit>`）。也就是说新的真实原因是**自定义注册表**，不是嵌套 AT；回滚把 126 个 late AT 目标与两个
mixin config 都恢复了（`remove mixin config ... ok`），但 mixin 已经注入过，日志仍提示重启后稳定。
Supplementaries 依赖 Moonlight，仍缺前置。

记录：status 已知问题 36 更正 + 新增 46（`NewRegistryEvent` 自定义注册表不可热加）；白名单 Moonlight 的 stock/jbr 行原因与证据更正
（库存量仍 BLACK）、Supplementaries 证据补一句；计数 89 单测。
验证：`./gradlew test`（89）、`python3 tools/whitelist.py check`、Moonlight harness。

## 2026-11-18（自定义注册表热加：方案评估，未动代码）

Moonlight 从嵌套 AT 误判里出来后卡在 `kind=CONSTRUCT`：
`IllegalStateException: Attempting to instantiate registry with name moonlight:villager_trades after
NewRegistryEvent was fired!`（`MoonlightRegistry.<clinit>`）。

核实 NeoForge 21.1.265 源码（`neoforge-21.1.265-sources.jar`）：
- 报错来自 `RegistryManager.trackModdedRegistry`——`pendingModdedRegistries` 在启动的 `postNewRegistryEvent()` 末尾被置 null；
  之后 `DeferredRegister.makeRegistry` / `RegistryBuilder.create` 都会失败。
- 启动顺序：construct → `NewRegistryEvent.fill()`（unfreeze/注册/freeze **根注册表**，然后 pending=null）→ `RegisterEvent` 每注册表
  → `GameData.freezeData()`（冻结 root 全部并分配 id）→ `ModifyRegistriesEvent`。
- 现有 `RegistryStage` 已经做了 `unfreeze → RegisterEvent 重放 → freeze`，且 `GameData.getRegistrationOrder()` 与
  `freezeData()` 都遍历**活的**根注册表 → 只要新注册表进了 root，commit/freeze/快照会自动覆盖。
- 因此差距只有三步：临时恢复 pending 集合（构造期）、构造后**只对新容器** fire `NewRegistryEvent` + 调包私有 `fill()`、复刻守卫校验并恢复 null。

评估结论写入 `docs/research-newregistry-hotadd.md`：A（重放）可行、中等工作量，风险在运行时 unfreeze/freeze 根注册表、
`fill()` 无 try/finally、fill 之后失败只能 dirty；B（预检干净拒绝）小改动、先做；C（启动期预注册）等同安装，不建议。
未改任何代码；status 已知问题 46 与下一步已链接该评估。

## 2026-11-18（优先级调整：客户端工具模组 = 内容/贵妃级模组）

所有者明确：**客户端模组与内容级模组优先级相同**（此前 status 的产品锚点只写“缺失的客户端工具模组”）。

- 影响：自定义注册表（`NewRegistryEvent`，Moonlight/CraftTweaker）不再按“收益偏薄、暂缓”处理，回到 P0；
  按 [research-newregistry-hotadd.md](research-newregistry-hotadd.md) 的三步实现，先 fixture spike。
- 保留的结论：方案 B 的**静态**预检拒绝不可取（Architectury/Balm 引用 API 但已 J1 通过），只做运行时异常映射。
- 未改代码；status 的「产品锚点」「已知问题 46」「下一步」已同步。

## 2026-11-18（自定义注册表重放实现：Moonlight 转 J1）

按评估文档的三步实现（`hotadd/CustomRegistries`）：

- `RegistryStage.begin` 时把 `RegistryManager.pendingModdedRegistries` 临时换成一个空集合，`commit`/中止后恢复；
- 构造后、commit 前，**只对新容器** fire `NewRegistryEvent`，然后调用包私有 `fill()` 把注册表注册进根注册表；
- 复刻 `postNewRegistryEvent` 的“创建了但没注册”校验；`fill()` 抛错时**强制 re-freeze 根注册表**（它先 unfreeze 再注册，
  没有 try/finally）。
- `RegisterEvent`/freeze/`takeFrozenSnapshot` 由现有 `RegistryStage` 自动覆盖（`getRegistrationOrder()` 与 `freezeData()`
  都按活的根注册表遍历），无需改 commit。

验证：
- 新夹具 `experiments/registry-fixture`（构造期建 `registryfixture:trades` + 1 条目）：`custom registry RESULT: PASS`、
  commit 90 个注册表；`CompatCheck` 新增 `-PverifyCustomRegistry=<id>`（可选 `Size`）与 `FailureKind.CUSTOM_REGISTRY` + 两语言文案。
- Moonlight（JBR + late AT）：3 个自定义注册表创建、commit 90→92、`ok=true isLoaded=true`、blocks 1060→1061、
  items 1333→1335、stoneId 稳定；白名单 JBR BLACK → **J1（candidate）**。

过程中修了两个 late AT 健壮性问题（都来自“对已混入的类再 redefine”）：
- `LateAccessTransformers` 捕获字节改为 **缓存 → 类资源 → 最后 retransform**（retransform 会让 Mixin agent 二次注入）；
- `MixinAgentGuard` 改为**常驻 wrapper**：第一次安装后不再 remove/add，只维护 `SUPPRESSED` 集合。之前 late AT 与 mixin 阶段
  连续 suspend 时，第二次会找不到 agent、wrapper 留在链上，导致下一次 redefine 二次 transform。

剩余：`fill()` 之后失败只能 dirty；datapack 注册表/`ModifyRegistriesEvent` 未重放；CraftTweaker 未复跑。
Supplementaries 越过 JiJ/依赖后暴露新问题（问题 47）：late AT 对 `Entity`（Moonlight 已混入）仍二次 transform →
`ClassFormatError`，`kind=ROLLBACK_PARTIAL`；Moonlight 单独 add 已 J1。

## 2026-11-18（问题 47 修复：redefine 期间的三次注入路径；Supplementaries 前进到问题 48）

诊断：给 `MixinAgentGuard` 加日志后确认——Moonlight 阶段 guard **确实**拦到了 `Entity`，但 Supplementaries 的 late AT
redefine 仍被 Mixin agent 二次注入。结论：JBR 的 redefine 调用并不总能给出可用的类身份（`beingRedefined`/类名），
按名抑制不可靠；且 Mixin agent 不是唯一的注入路径。

修法：
- `MixinAgentGuard` 改为**包裹全部已注册 transformer 的常驻 wrapper**（安装一次，之后不再 remove/add）；
- 新增 `MixinAgentGuard.blanket()`：在**每一次** `redefineClasses` 期间全局抑制所有 transformer（我们给的字节就是最终字节），
  用 try-with-resources 保证及时恢复；late AT 与回滚 redefine 都使用它；
- 名字抑制仍保留给 mixin 阶段的目标窗口。

复跑 `moonlight → supplementaries`：`transform guard installed (4 transformer(s) wrapped)`，Entity 不再报 `ClassFormatError`；
Moonlight 仍 `ok=true`。Supplementaries 越过 VALIDATE，在 MIXIN 阶段新卡：
`NoClassDefFoundError: com/bawnorton/mixinsquared/MixinSquaredBootstrap`（嵌套 `mixinsquared-forge-0.3.8.jar` 已作为 library
注入 module path，但 Mixin 加载 `mixinsquared.forge.ini` 时的类查找看不到它）→ 记为已知问题 48。

## 2026-11-18（JiJ 递归拆包：问题 48 修复；Supplementaries 前进到模型层）

诊断：`mixinsquared-forge-0.3.8.jar` 里只有一个包（`com/bawnorton/mixinsquared/platform/forge`），
核心 `com.bawnorton.mixinsquared.MixinSquaredBootstrap` 在它**自己再嵌套一层**的 `MixinSquared` jar 里；
`JarInJar` 只拆一层 → bootstrap 不可见 → `register mixinsquared.forge.init.mixins.json` 时 CNFE。

修法：`JarInJar.extract` 改为递归（`MAX_NESTED_DEPTH=3`），每层都物化、建 `SecureJar`、收集 manifest `MixinConfigs`；
`declaredPath` 用 `a!/b` 记录层级。复跑：`jar-in-jar: 3 nested jar(s) ... modules [mixinsquared.forge, MixinSquared,
sable.companion.common]`，MixinSquared 配置可加载，Supplementaries 越过 MIXIN/CONSTRUCT 并注册内容
（blocks 1061→1306、items 1335→1584）。

同时把 `blanket()` 也用到 mixin 阶段的 redefine/回滚（JBR 的 hierarchy update 会顺带重定义子类，只按名抑制不够）。

新卡点（EVENT 阶段，已记录）：
- 问题 49（结构）：`EntityRenderersEvent$CreateSkullModels` → `No model for layer supplementaries:enderman_head#enderman_head`；
  deferred 里 `ItemBlockRenderTypes.setRenderLayer` 被 `Render layers can only be set during client loading!` 拒绝。
  模型层在启动时 bake，`RegisterLayerDefinitions` 未重放；要么重新 bake `EntityModelSet`，要么明确重启。
- 问题 50：失败回滚 `MineshaftPieces$MineShaftPiece` 报 `VerifyError`，`kind=ROLLBACK_PARTIAL`。

## 2026-11-18（模型层重放：问题 49 不是结构边界，Supplementaries 转 J1 candidate）

核实源码（`EntityModelSet` / `ClientHooks` / `LayerDefinitions` / `ItemBlockRenderTypes`）：
- `EntityModelSet.roots` 是可替换字段，每次资源重载由 `LayerDefinitions.createRoots()` 重建，而后者会合并
  `ClientHooks.loadLayerDefinitions`（即 `RegisterLayerDefinitions` 注册的 supplier）。`bakeLayer` 每次查表、无缓存。
- `ItemBlockRenderTypes.checkClientLoading` 只看 `ClientModLoader.isLoading()`（私有静态 `loading`），该 flag 的另一个消费者是
  `Options` 的保存保护；在 FMLClientSetup 重放窗口内临时置 true 风险可控。

实现：
- `ClientRegistrationReplay` 增加 `replayLayerDefinitions(container)`：fire `EntityRenderersEvent.RegisterLayerDefinitions`，
  然后调用 `EntityModelSet.onResourceManagerReload` 重建 roots（日志 `layer definitions replayed: 213 -> 234`）；
- 新增 `replayStaged(containers)`：**全容器先层、后渲染器**。否则嵌套容器（sablecompanion）先重建 `SkullBlockRenderer`，
  外层（supplementaries）的 `enderman_head` 层还没注册 → `No model for layer ...`；
- `ClientPlatformHooks.acceptClientLifecycle` 在 FMLClientSetup + deferred queue 窗口内把 `ClientModLoader.loading` 置 true 再恢复。

复跑 `moonlight → supplementaries`：两者 `ok=true isLoaded=true`，blocks 1061→1306、items 1335→1584、层 213→234，
无 `No model for layer` / `Render layers can only be set...`。白名单 Supplementaries JBR BLACK → **J1（candidate）**。
仍开放：问题 50（失败回滚 VerifyError）；窗口无焦点时 `pauseGame` 会撞隐藏 screen（测试用聚焦窗口跑）。

## 2026-11-18（XaeroLib config channel 窗口：World Map 热加成功，暴露服务端一次性事件）

Xaero 的 “config channel too late” 不在 NeoForge，而在 **XaeroLib 自己的**
`xaero.lib.common.config.channel.register.ConfigChannelRegistry`：有一个 `frozen` 标志，启动后 `freeze()`，之后 `register` 抛
`IllegalStateException`。做法与注册表窗口一致：

- 新增 `hotadd/XaeroLibChannels`：只对**引用该 registry 类**的 jar 生效（`RegistrationAuditScanner.referencedTypes`），
  构造期把 `frozen` 置 false、add 结束（成功或回滚）恢复 true；`INSTANCE`/`frozen` 走 `UnsafeAccess`，找不到只 warn。
- 复跑 World Map（`-PverifyCompat=<xaeroworldmap>`）：`RESULT ok=true isLoaded=true`，此前是 `kind=CONSTRUCT`。

随后暴露两个模组自身问题：
- 标题屏：`XaeroWorldMapCore.onMinecraftRunTick` 抛 `NoSuchElementException`（已知的 `loadLater` 行为，需已有世界/L2）。
- 世界内 add（`-PverifyAddCommand`）：命令返回 1，但 integrated server 玩家 tick NPE —— `WorldMap.serverPlayerTickHandler` 为 null，
  因为它在 `ServerStartedEvent` 里创建，而世界内热加不会再触发该事件 → 记为**问题 52**（服务端一次性事件未重放，ADR-0007）。
白名单 World Map JBR BLACK → **J2（candidate）**；计数 J1×20 / J2×1 / BLACK×3。

## 2026-11-18（问题 52：服务端一次性事件定向重放——已实现，未验证；新挂起问题 54）

实现：
- `EventBusSnapshot.addedSince(snapshot)`：取出本次 add 新增的 game-bus 监听器（快照差值）。
- 新增 `hotadd/ServerLifecycleReplay`：只对这些新监听器调用 `ServerStartingEvent` / `ServerStartedEvent`；
  先按 bus 的 `ListenerList` 取匹配集，若与 owner map 不是同一实例则“逐个试调用、类型不符跳过”；
  服务端实例先 `ServerLifecycleHooks.getCurrentServer()`，为空再反射 `Minecraft#getSingleplayerServer`（集成服）；
  在 add 成功路径的最后调用（注册表/datapack 已就绪，后面没有可失败步骤）。
- `EventBusSnapshot.addedSince` 的首次实现有泛型编译错误，已修。

复跑 `-PverifyAddCommand=<xaeroworldmap>` 两次：都卡在
`Mixin compatibility level set to JAVA_21 after xaerolib.neoforge.mixins.json` 之后（约 10 分钟无日志），
未走到重放；两个客户端 JVM 需 `kill -9`。之前同一场景能跑完（19:43/19:46），所以是间歇/时序问题 —— 记为**问题 54**，
下次先 `jstack` 留线程栈（看 Mixin/transform 锁与常驻 guard）再决定修法。

同时确认了 World Map 的另一个阻碍（问题 53）：`loadLater` 在 `FMLClientSetupEvent` 的 deferred queue 里通过
`Minecraft.getResourceManager()` 读自己的 `vanillaStatesResource`，此时热加资源包还没注入 → `Optional.get()` 抛
`NoSuchElementException`。需要让包在 client setup deferred task 之前可见，或重跑这些 task。

另：`MixinAgentGuard` 的“包裹全部 transformer”在 19:47 的挂起后被收窄回“只包 Mixin agent”（`blanket()` 已覆盖 redefine 路径），
**Supplementaries/问题 47 的修复需要在收窄后复验一次**。

## 2026-11-18（JBR 识别：三态 + 菜单左上角标识 + 复用运行中的 JBR）

所有者反馈：启动器已选择 JBR，但热加 JBR 模组时仍被要求“用 JBR 重启”，运行时似乎不知道当前环境。

排查：`JbrSupport.available()` 要求 **JBR + `-XX:+AllowEnhancedClassRedefinition`**。启动器只负责选 JVM，
不会加我们的 flag；于是“JBR 但缺 flag”被当成“没有 JBR”，文案全在教用户换 JBR。另一个缺口：
`installation()` 只扫安装根，**不含正在运行的 JBR**，启动器自带、扫描根之外的 JBR 会让“用 JBR 重启”按钮不可用。

改动：
- `JbrSupport.status()` 三态（READY / JBR_NO_ENHANCED / NOT_JBR）；`isJbr()` 同时看 vendor/vm.vendor/vm.name/java.home；
  `hasEnhancedFlag()` 抽成可测的纯函数。
- `ModReloaderScreen` 左上角彩色标识：`JBR ✓ · 增强重定义已启用`（绿）/ `JBR ✓ · 增强重定义未启用（用运行时重启一次即可）`（黄）/
  `JBR ✗ · 普通 JVM`（红）；底部原有 JVM 行保留。
- 文案按状态区分：AT/mixin 预检与失败建议新增 `*_jbr_no_flag` / `suggestion.jbr_flag`，不再说“使用 JBR”。
- `installation()` 优先返回**运行中的** JBR home（`runningHome(javaHome, isJbr)`），使“用 JBR 重启”在启动器 JBR 场景可用。
- 单测：`looksLikeJbr`、`hasEnhancedFlag`、`runningHome`；JbrSupportTest 共 9 个。
验证：`-PverifyMenu=true` 截图（绿）、`-PverifyMenu=true -PnoJbr=true` 截图（黄）、
`-PnoJbr=true -PverifyRelaunchPlan=true` → `canRelaunch=true` + JBR spawn PASS；`./gradlew build`/93 单测。
另：`-PverifyCompat=<MouseTweaks> -PnoJbr=true` 的拒绝文案现在明确说
“This JVM is JBR but enhanced redefinition is off; use the failure screen's JBR restart once.”，
不再是“去用 JBR”。

## 2026-11-18（JBR 入口收口：为热加载增强环境做准备）

所有者要求把 JBR 系列收口为一个动作：玩家启动一次游戏，点「为热加载增强环境做准备」，运行时装好正确的 JBR、
自动解压到合适路径，随后自动重启并带齐参数，得到一个可热加载的进程。

- `JbrSetupScreen`：入口标题/正文/按钮改为统一文案；正文显示**安装路径**（`gameDir/jbr-21.0.11`）；
  `start()` 先看 `JbrSupport.installation()`（现在包含正在运行的 JBR），已可用则**跳过下载**直接重启；
  重启使用 `Relauncher.jbrFlags(true)` → `-XX:+AllowEnhancedClassRedefinition` + `-Dmodreloader.lateAt=true`。
- `ModReloaderScreen`：未就绪时**常驻**「准备热加载环境」按钮（不再只在“有需要 JBR 的候选”时出现）；
  截图 `-PverifyMenu=true -PnoJbr=true` 可见黄色标识 + 按钮。
- `LoadFailureScreen`：JBR 相关按钮统一为同一入口/同一文案，不再分“自动准备/用 JBR 重启”。
- 诚实边界（status 问题 55）：参数只能启动时给；启动器每次不带参数则每会话最多一次自动重启；
  跨会话永久生效需要启动器/整合包预设（不默认改玩家配置）或结构方案（classloader 隔离/软重启）。
验证：`./gradlew build`（93 单测）、菜单截图（README/状态）。

## 2026-11-18（late AT 保启动期 AT；Windows 真机 architectury/Jade）

所有者用 PCL2 + JBR 21 真机热加 architectury 13.0.12 与 Jade 15.10.6。上一次（22:54）在 architectury 的
late AT 之后 7 秒崩溃：`IllegalAccessError ... RenderStateShard$TextureStateShard.blur`。根因是无缓存时
late AT 从 class 资源（AT/mixin 之前的原始字节）重写已加载类，把 FML 启动期 AT 一并抹掉。

改动：
- `LateAccessTransformers`：无缓存时先在 `MixinAgentGuard.blanket()` 窗口内用 `ClassBytes.of` 抓活类字节
  （JVM 规范：retransform 以最后一次 defineClass/redefineClasses 的字节为起点），失败才回退 class 资源。
- `MixinSupport.layoutChange`：把字段增减也算布局变更（原只看父类/接口）；JBR 下额外警告
  “旧实例保持 JVM 默认值而不是构造器值，需重启才完全正确”。
- `RelaunchScriptsTest`：两处硬编码 POSIX 路径的断言改为从同一 Path 取 toString，Windows 也 93/93。

验证（Windows 11，PCL2 forced JBR 21.0.11，NeoForge 21.1.266，`-XX:+AllowEnhancedClassRedefinition` +
`-Dmodreloader.lateAt=true`）：
- architectury：23:36:02 开始 → 23:37:05 late AT 311 规则/150 类 → 23:37:11 `hot-added` + `verify isLoaded=true`，
  全程无 `IllegalAccessError`；`TextureStateShard` 等被 late AT 的类渲染正常。整段约 69 秒（UI 阻塞）。
- Jade：23:40:31 → 23:40:58 `hot-added`，redefine `Font`/`Font$StringRenderOutput`/`Entity` 等 5 类，
  keyMappings 34→39，资源包 `mod/jade` 选中并重载；无崩溃。
- 现象 1：Jade 的 `FontMixin` 新增 `glint1/glint2/...` 字段，旧 `Font` 实例字段为 0（应 NaN），文字被压到约 65%
  亮度；重启并正常加载 Jade 即恢复（问题 56）。现象 2：ftb-library 因类级 `-f`（`TextColor`）被前置拒绝，
  符合“类级 AT 需启动期”边界。
- 现象 3（待确认 UI 来源）：热加 architectury（无资源包、无屏幕重建）后左下角计数仍为 4；热加 Jade 触发
  资源重载/标题屏重建后变为 6。ModList 本身在两次 add 后都正确增长；若计数来自启动时缓存的界面文本，
  这是显示层缓存，不是 ModList 未更新。

## 2026-11-18/19（Windows/PCL2 真机联调：构建链、late AT、Jade、Mekanism、监听）

环境：Windows 11 + PCL2（forced JBR 21.0.11）+ NeoForge 1.21.1-266；游戏目录含中文和方括号。

构建链（一次性踩坑，后续可复用）：
- `maven.neoforged.net` 被 DNS 污染（解析到 31.13.x），代理节点到 CDN77 也返回 502；官方后备镜像
  `https://neoforged.forgecdn.net/releases` 可直连。用临时 Gradle init 脚本把所有该源改到镜像。
- 镜像缺 `net.neoforged:minecraft-dependencies:1.21.1`；从 Mojang 1.21.1 version JSON 的 97 个库重建完整 POM，
  放进本地 maven 仓库，`createMinecraftArtifacts` 随即通过。
- Gradle wrapper 8.8 官方源约 22KB/s，改用本机已缓存的 Gradle 8.10；工具链/Gradle JVM 都指向目录里的 JBR 21。
- 中文路径会让测试 worker 的 `@argfile`（UTF-8）按 `sun.jnu.encoding=GBK` 解码，测试类全部 CNFE；
  用 `subst X:` 映射 ASCII 盘跑构建。
- `RelaunchScriptsTest` 两处断言写死 POSIX 路径，Windows 下 `Path.toString()` 是反斜杠；改为从同一 Path 取期望值，
  `build` 全绿、**93/93**。

真机结果（MixinRuntime + Jade/architectury/ftb-library 在 mods，Mekanism 后加入）：
- architectury 13.0.12：23:36:02 → 23:37:11（约 69 秒，UI 阻塞），late AT 311 规则/150 类；
  修复前 22:54 的 `TextureStateShard.blur` `IllegalAccessError` 不再出现，`isLoaded=true`。
- Jade 15.10.6：热加成功（`Font`/`Font$StringRenderOutput`/`Entity` 等 5 类 redefine，keyMappings 34→39）；
  但 `FontMixin` 新增的 4 个 glint 字段在旧 `Font` 实例上是 0（构造器里应为 NaN），文字约 65% 亮度（问题 56）。
- ftb-library：类级 `-f`（`TextColor`）被前置拒绝，符合“类级 AT 需启动期”的边界。
- Mekanism 10.7.19：预检贵妃档；`RegisterRenderers` 早于注册表提交重放，`mekanism:robit` 的
  `DeferredHolder` 未绑定 → 标题屏渲染崩溃（问题 58）。已把层/渲染器重放移到 `commit + finishAll` 之后，
  **修复 jar 未真机复验**。
- 自身 jar 替换：游戏运行时覆盖 modreloader 的 jar，轮询路径没有“已加载 modId”过滤，把它当新候选弹 toast；
  底层 SecureJarHandler 的 jar 被替换，加载未加载类时 `NoClassDefFoundError`（问题 57，已修）。
- 左下角模组数 4→4→6：两次 add 后 ModList 实际都增长；architectury 无资源包不重建标题屏，文本保持旧值，
  Jade 的资源重载重建标题屏后刷新为 6。计数文本的具体 UI 来源尚未确认。

提交（推送前 amend 过，写作时记录的 hash 已失效；以下为 origin/main 上的实际提交）：
`cb7b8a8`（late AT 保 AT）、`bbfa45d`（Windows 测试断言）、`f4ba9d1`（新增字段警告）、
`7568a91`（late AT 复验 / 问题 56 文档）、`788d40f`（渲染器顺序）、`f16d938`（监听过滤）、
`47a06cd`（联调收尾文档）。
未验证：Mekanism 修复后重跑、Jade 重启后字体确认、计数 UI 来源、Windows 下「准备环境」全链路。

## 2026-11-19（渲染/内容/联机一轮：AE2 通过，嵌套 AT 成为主边界）

语料新增 Sodium 0.8.13、Iris 1.8.14、Create 6.0.10、AE2 19.2.20、GuideME 21.1.22、e4mc 6.2.3、
mcwifipnp 1.9.0，并登记 10 个场景（含 lateAt 对照）。全部在 macOS dev + JBR、`--visible`、L1 断言下运行。

结果：
- mcwifipnp：ok=true isLoaded=true（redefine PauseScreen/MinecraftServer/UUIDUtil/PlayerList）。
- e4mc：ok=true isLoaded=true（模块 kaleido+e4mc）；分析器 HUANGHOU 来自内嵌库 native 误判。
- GuideME → AE2（lateAt）：均 ok=true；blocks 1060→1162、items 1334→1698。AE2 的“皇后嫌疑”不成立
  （L1 结论，GUI/物品功能未验）。
- Create / Sodium / Iris：全部 `stage=VALIDATE kind=ACCESS_TRANSFORMER`，原因是**嵌套 mod 的 AT 不在覆盖**：
  Ponder 3 条、sodium-mod 18 条、fabric-block-view-api 1 条；Create 加 `lateAt` 对照仍拒绝（嵌套 AT 检查与 lateAt 无关）。
  Iris 自身另有 2 条类级 `public-f`（OptionInstance、RegistryAccess$RegistryEntry），属 JBR 结构边界。
- 工具：`facts.py` 放行 `META-INF/jars/` 下无 `mods.toml` 的嵌套纯库（e4mc 的 kaleido），否则 check 误阻断。
- no-focus 回归：日志有 `[no-focus] patched Window`，但窗口仍 `focused=true`，首轮 mcwifipnp 判 FOCUS_STOLEN；
  本轮改用 `--visible`。待查（怀疑 MixinRuntime agent 转换顺序或 macOS 27 行为变化）。

证据：`tools/pipeline/reports/20261119-111924-lan-mcwifipnp`（及同批 7 个目录）、`corpus.json` 场景
`sodium-only` / `sodium-iris` / `iris-after-sodium` / `iris-after-sodium-lateat` / `create-only` / `create-lateat` /
`ae2` / `ae2-lateat` / `lan-e4mc` / `lan-mcwifipnp`。
未验证：sodium-iris 同批（sodium 已先拒，未单独跑）、AE2 的 L2 GUI、Sodium/Iris 若支持嵌套 AT 后的行为。

## 2026-11-19（嵌套 mod 的 AT 接入 late AT）

实现：
- `NestedMods.prepare(..., allowLateAt)` 不再对嵌套 AT 无条件抛错；改用 `AccessTransformerRules.read(nested jar)`
  判断是否真有规则（空文件 / 注释-only 不算）。lateAt 关闭时维持原拒绝（`ACCESS_TRANSFORMER`）。
- `HotAddEngine` 在模块注入前，对每个嵌套 AT jar 调用 `LateAccessTransformers.apply`；失败按 `ACCESS_TRANSFORMER` 处理，
  late AT 的类级 `-f` 前置拒绝照旧生效。`NestedMods.Loaded` 新增 `accessTransformers`。

验证（pipeline，JBR，`--visible`，L1）：
- `create-lateat`：越过 VALIDATE（Ponder 3 条嵌套 AT 应用），停在 `stage=CONSTRUCT` —
  `Registry is already frozen (create:arm_interaction_point_type)`（新问题 59）。
- `iris-after-sodium-lateat`：嵌套 AT（fabric-block-view-api 1 条）通过，停在自身 2 条类级 `public-f`
  （`OptionInstance`、`RegistryAccess$RegistryEntry` 在运行期为 final，已知 JBR 边界）。
- `sodium-lateat`：嵌套 18 条 AT 应用后停在 `stage=MODULE` —— 外壳 jar 与内嵌实现同 modId，模块名 `sodium` 冲突
  （已知问题 24 记录的新边界，未适配）。
- `sodium-only` 回归：无 lateAt 仍在 VALIDATE 以 `ACCESS_TRANSFORMER` 拒绝。

构建：`./gradlew build` 绿（93 单测）。
结论：嵌套 AT 不再是 Sodium/Iris/Create 的主因；三者分别卡在同 modId 模块、类级 AT、注册表冻结。

## 2026-11-19（Create 通过：构造期解冻根注册表）

根因：Create 的 `CreateBuiltInRegistries` 在构造期直接 `RegistryBuilder.create()` + `BuiltInRegistries.REGISTRY.register`；
启动期根注册表在 construct 阶段仍可写（`NewRegistryEvent.fill()` 才冻结），运行期是冻结的，于是
`Registry is already frozen (minecraft:root / create:arm_interaction_point_type)`。

改动：`CustomRegistries.open` 用 trusted lookup 调 `BaseMappedRegistry.unfreeze()` 解冻根注册表，
`fill()`/`close()`/回滚时重新冻结；`beforeOpen` 快照改在 open 时取，使构造期直接注册的表也计入 `added`。

验证：
- `create-lateat`：**ok=true**，blocks 1060→1703、items 1333→2032；hot-added [flywheel, ponder, create]，
  6 个 mixin config、约 70 个类 redefine（含 RenderSystem/GlStateManager/Gui）。L1，玩法未验。
- `moonlight-lateat` 回归：Moonlight ok=true（3 个自定义注册表、blocks 1060→1061、items 1333→1335）。
  Supplementaries 随后在 MIXIN 失败：`MapRenderer$MapInstance: MixinTransformerError`，回滚 late AT 成功；
  该目标先被 Moonlight 的 late AT 重定义，属问题 47 同族，与注册表改动无关（问题 60）。
- 构建：`./gradlew build` 绿。

白名单：Create（JBR）→ J1 candidate；计数变为 JBR J1×25 / J2×1 / BLACK×5。

## 2026-11-19（同 modId 嵌套：内嵌实现作主文件；Sodium 热加 ok 但旧渲染对象崩）

背景：Sodium 的发行 jar 是「外壳 + 内嵌实现」且两边都声明 `modId = sodium`；外壳只有 mods.toml 与服务类，
入口、mixin、`assets/` 都在内嵌 jar。启动期 FML 用内嵌文件作为该 mod 的文件；我们的引擎却把外壳当主文件，
两个 ModFile 都叫模块 `sodium`（问题 24 记录）。

改动：
- `NestedMods` 标记 `sharesOuterId`；引擎改用内嵌实现作主文件（容器、mixin、资源包、数据包、注册审计、通知），
  外壳降级为 extra 库模块（模块名来自 manifest 的 `sodium_service`），不再建第二个容器。
- 嵌套 AT 与主文件 AT 去重：主文件是内嵌实现时不再走外层 late AT 分支。

验证：
- `sodium-lateat`：**ok=true isLoaded=true**，同批 4 个 fabric 库进模块，约 64 个类 redefine；
  随后渲染 overlay 抛 NPE：`ShaderInstance.uniformCache` 为 null（旧实例拿不到 mixin 新增字段，问题 45/56 同族），
  退出码 1 → 记 J2（问题 61）。
- `minimap` 回归：PASS（Xaero 的嵌套 mod 逻辑未受影响）。
- 构建：`./gradlew build` 绿。

白名单：Sodium（JBR）BLACK → J2 candidate；计数 JBR J1×25 / J2×2 / BLACK×4。

## 2026-11-19（问题 61：重建 ShaderInstance；问题 51：空白占位 Screen）

改动：
- 新增 `PlatformHooks.rebuildShaderInstances()`（客户端实现）：用 trusted lookup 调包私有的
  `GameRenderer.reloadShaders(ResourceProvider)`，在资源重载与任何渲染之前重建全部 shader 实例，
  让 mixin 后的构造器初始化生效（Sodium 给 `ShaderInstance` 加的 `uniformCache`）。
- `ClientResourcePacks`：重载链期间不再把 `mc.screen` 置空，改用已手动 `init` 的空白 `Screen`
  （`isPauseScreen=false`，不触发 `ScreenEvent.Opening`），重载结束再还原——顺带修复问题 51。

验证：
- `sodium-lateat`：shader overlay NPE 消失；随后 `MinecraftMixin.preRender` 读 `Minecraft.fences`
  （同族：单例上的 mixin 新增字段）→ `runTick` NPE，exit 1。结论 J2：需重启才完全正确，属问题 45/56 边界。
- `minimap`：修 51 前因窗口失焦命中 `PauseScreen` NPE；修后 **PASS**（`shader instances recreated` 正常执行）。
- 构建：`./gradlew build` 绿。

白名单：Sodium 行更新原因与证据（桶不变，J2）；计数不变。

## 2026-11-19（问题 60：跨 add 的 @WrapOperation 冲突 → 预混字节重组）

根因：Moonlight 与 Supplementaries 都对 `MapRenderer$MapInstance.updateTexture` 里的
`DynamicTexture.upload()` 调用点做 `@WrapOperation`。Moonlight 先应用后，原调用点已被包成 wrapper；
Supplementaries 的 config 单独应用到这份已混字节上就 `Scanned 0 target(s)`（MixinExtras 看不到原调用点）。
此前 late AT 改用活类字节后，缓存里保存的就是已混字节，因此暴露。

改动：
- `MixinSupport` 在 transform 失败、且该目标曾被前一次 add/late AT 改写过（`ClassBytesCache` 有记录）时，
  用**类资源原始字节 + 全部活动 configs** 重组（还原启动期顺序，让两个 wrapper 组合），
  再把 live 的类/字段/方法访问标志移植回重组结果（保住 AT 与既有改写）。
- transform 失败的错误信息带完整 cause 链并打印堆栈（原先只有 Mixin 的外层 wrapper）。

验证：
- `moonlight-lateat`：Supplementaries **ok=true**（blocks 1061→1306、items 1335→1584），
  日志有 `recomposed it from pre-mixin bytes with all active configs`。
- `ae2-lateat` 回归：guideme/ae2 均 ok=true。
- 构建：`./gradlew build` 绿。

白名单：Supplementaries 证据补 2026-11-19 复跑（桶不变，J1 candidate）。

## 2026-11-19（问题 54：Mixin 直接 transform 与 ModLauncher 的 ABBA 死锁）

复现：`-PverifyAddCommand=<xaeroworldmap>` 反复跑，第 1 次即挂；日志停在
`Mixin compatibility level set ... xaerolib.neoforge.mixins.json` 之后，60 秒无增长。

jstack：
- Render thread（我们的 add）在 `MixinSupport.transformBytes` 里持有 `MixinTransformer` 与
  `MixinProcessor` 锁；注入点解析（`InjectionPoint.parse`）要加载类，该加载走 ModLauncher
  转换 → `MixinLaunchPluginLegacy.handlesClass` 要 Mixin 插件列表锁（A）。
- worldgen 线程正持 A（transform `RailBlock`），等 `MixinTransformer` 锁（B）。经典 A/B 反转。

修复：新增 `hotadd/LaunchPluginMonitor`，经 `transforming_class_loader` 属性 →
`TransformingClassLoader.classTransformer` → `LaunchPluginHandler.plugins` → 找到
`MixinLaunchPlugin`（继承 `MixinLaunchPluginLegacy`）的 `processors` 监视器；
`MixinSupport.transformBytes` 在调用 `transformClassBytes` 前先 `synchronized` 该监视器，
统一成 ModLauncher 的 A→B 顺序（类加载在 transform 内部同线程重入）。
`Launcher.INSTANCE` 路径在本环境为 null（类加载器分裂），实际走属性路径。

验证：修复前 1/1 挂起；修复后 8/8 次全部走完 mixin（每次 13 个类 redefine，日志有
`Mixin launch-plugin monitor resolved`），不再挂起；随后的失败是已知问题 52
（`WorldMap.serverPlayerTickHandler` null）与 53（`loadLater` NoSuchElement）。

构建：`./gradlew build` 绿。

## 2026-11-19（问题 53 调研：顺序约束与两次被证伪的修法）

启动期实锤（源码）：
- `CommonModLoader.finish` 先 dispatch `FMLClientSetupEvent`（deferred tasks 在事件里立即跑），
  随后才 `NetworkRegistry.setup()`（`RegisterPayloadHandlersEvent`）。
- 但 mod 的 client setup / finish 本身是在**初始资源重载内部**执行的
  （`ClientModLoader.onResourceReload`），所以任务运行时 mod 包已可用。

运行期矛盾：
- client setup tasks 必须在 payload 适配之前（XaeroLib 在任务里冻结 `PacketHandlerRegistry`；
  未冻结时 `iterator()` 直接 `IllegalStateException`）；
- 但热加包要等资源重载后才对 `Minecraft.getResourceManager()` 可见（World Map `loadLater` → `NoSuchElementException`）。

尝试与结果：
- A：把 deferred tasks 整体延后到重载完成 → `minimap` 回归 **FAIL**（`NETWORK_PAYLOAD`，XaeroLib 未冻结）。
- B：按 `TaskInfo` 逐任务执行、失败留待重载重试 → 取到的 `task` 不是用户 runnable
  （计数 1、`ok=true`，但用户代码未执行）。两次均已回滚，仓库保持原行为。

结论：需要**重排阶段**（pack/reload 先于 payload，client setup 任务在 reload 内执行）或把热加做成
可恢复的分段管线；单纯“延后”或“任务级重试”都不可行。

## 2026-11-19（技术债复审 + 渐进式重构第一步）

复审（详见 [tech-debt.md](tech-debt.md)）：严重债集中在「运行时重实现 FML 加载顺序」——
P0-5 生命周期顺序没有单一事实源、P0-6 客户端重放是手写清单；P1-12 不可逆/可回滚混流、
P1-13 反射面 228 处/32 文件、P1-14 并发模型局部补丁、P2-15 测试与文档漂移。

增量：
- 新增 `hotadd/AddStagePlan`：把 10 个阶段的顺序与默认 `FailureKind` 收到一处；
  `HotAddEngine.add` 的 10 处 `stage = ...` 统一走 `stagePlan.enter(...)`（反向跳转直接抛）。
- `AddStagePlanTest` 5 个：顺序固定、可跳过可选阶段、可重复当前阶段、禁止回退、失败种类映射。

验证：`./gradlew build` 绿，单测 **98**。

## 2026-11-19（阶段重排修问题 53；XaeroLib 多注册表窗口）

调研更正：`ReloadableResourceManager.createReload` 在**调用时**就同步关闭旧资源、换入含全部已选包的
新 `MultiPackResourceManager`；`Minecraft.reloadResourcePacks()` 同步走到这一步。启动期
`ClientHooks.initClientHooks`（reload listener 等注册）早于初始重载，`FMLClientSetupEvent` 的 deferred
tasks 在重载内执行——此时 live manager 已含 mod 包。所以 53 只需把顺序排对，不必异步化（推翻了“必须改
异步管线”的结论）。

实现：
- 阶段表改为 `… REGISTRY → EVENT → PACK → CLIENT → DATAPACK`：
  - `EVENT`：capability 重放 + `FMLCommonSetupEvent`；
  - `PACK`：`ClientRegistrationReplay`（含 reload listener 注册）→ 注入包并启动重载 → 可选
    `rebuildShaderInstances`；
  - `CLIENT`：`FMLClientSetupEvent` + deferred tasks → `FMLLoadCompleteEvent` → 层/渲染器重放 →
    payload 适配 → 命令重放。
- `PlatformHooks.registerClientRegistrations` 把注册从 `acceptClientLifecycle` 拆出，保证在
  `createReload` 快照 listener 列表之前完成；`FmlEvents` 拆出 `commonSetupAll` / `sidedSetupAll`。
- `XaeroLibChannels` 从“ConfigChannelRegistry 一个”推广到四个同型 frozen 单例：`PermissionRegistry`、
  `PermissionSystemRegistry`、`PacketHandlerRegistry`。第二个 Xaero 模组构造时
  `BuiltInConfigChannelPermissions → PermissionSystemRegistry.handleModPresence` 抛
  `IllegalStateException`，是同一类一次性注册表。
- `ClientResourcePacks` 若发现原版已有 `pendingReload`，在该次重载后补一次，避免新包被选中却从未加载
  （此时后续任务只保证看到“当前这次”重载的包，日志会 warn）。

证据（JBR 21，`tools/pipeline`，语料 `/tmp/modreloader-corpus`）：
- `minimap-addon`：`xaeroworldmap ok=true`、`xaerominimap ok=true`、`xaeroplus` 干净拒绝
  (`ACCESS_TRANSFORMER`，未开 lateAt)；`verdict: RECORDED`（focus 正常）。
- `minimap`：`MATCH`（FOCUS_STOLEN 为环境问题）；`hud` AppleSkin：`PASS`；`input`
  Searchables `ok` / Controlling 干净拒绝：`MATCH`。
- `moonlight-lateat`：Moonlight、Supplementaries 均 `ok=true`（FOCUS_STOLEN）。
- 客户端夹具（`-PverifyAddCommand=/tmp/clientfixture/... -PverifyGuiLayers -PverifyItemDecorators`）：
  GUI 层与物品装饰 `RESULT: PASS`。
- **payload 回归为既有失败**：`-PverifyPayload`（标题屏加 payloadfix 后建世界）在 HEAD `8e1523d`
  同样 `FAIL`（40s 无 ping；适配日志正常），非本次改动引入；记入 status 验证缺口。

## 2026-11-19（技术债 P0-6：一次性重放单一出口）

病灶：注册审计的 `COVERED` 是第二份手写清单，且已漂移——层定义 2026-11-18 起由
`ClientRegistrationReplay.replayLayerDefinitions` 重放（含 `EntityModelSet` roots 重建），审计却仍把它列在
`BEHAVIOR`，报告与实际能力不一致。

实现：
- `inspect/ReplayCatalog`：21 个启动期事件的唯一列表（纯字符串，专用服也能加载）。
- `hotadd/OneShotReplay.fire(container, expectedEvent, event)`：所有重放调用点（FmlEvents、ClientPlatformHooks、
  ClientRegistrationReplay、GuiLayerReplay、ItemDecoratorReplay、RecipeBookCategoryReplay、ShaderReplay、
  NetworkAdaptation、RegistryStage、CapabilityReplay）统一走这里；helper 校验事件类名，不匹配只 warn
  （失败策略仍留在调用点：生命周期/payload/注册表要 fail，装饰性重放只 warn）。
- `RegistrationAudit` 的 `covered` 改读 catalog；层定义从 BEHAVIOR 移入 covered。

验证：单测 **103**（`ReplayCatalogTest` 3 个 + 审计层定义/自定义注册表各 1 个）；`./gradlew build` 绿；
clientfixture（GUI 层/物品装饰）PASS、`minimap` PASS、`moonlight-lateat` 两个 `ok=true`，三个日志均无
`replay event mismatch`。
剩余：重放仍是逐个事件手写 + 审计兜底，不是让 mod loading 回到真实 reload 上下文；事件类替换靠运行期 warn。

## 2026-11-19（技术债 P1-12：RunState 脏状态；专用服周期告警）

病灶：失败按“能否重试”混在一起——注册表已提交、mixin 已织入、构造器副作用、payload/capability/命令重放之后
失败，回滚只恢复 runtime 的账本，下一条 `add` 仍会继续跑；专用服只有一次失败日志，运维很容易在残留状态下
继续加模组。

实现：
- `hotadd/RunState`：一次 add 记录 registry commit、自定义注册表窗口、构造、mixin 落定、payload、capability、
  命令重放；`reasonFor(Flags)` 是纯函数，按不可逆程度给出原因；置脏是进程级、只有重启清零。
- `HotAddEngine`：`add` 之前 `RunState.isDirty()` → `FailureKind.RUNTIME_DIRTY`（VALIDATE）；所有阶段失败经
  `recordFailure` 汇入状态，并在 `Result.message` 末尾附加脏原因（已有 “runtime is dirty” 时不重复）。
- `hotadd/RunStateWarner`：专用服上按间隔（默认 60s，`-Dmodreloader.dirtyLogSeconds`）在控制台重复告警。
- `FailureKind.RUNTIME_DIRTY` + `FailureKeys` + 中英文案 + `FailureAnalyzer` 重启建议。
- dev：`RunStateCheck`（`-PverifyRunState` / `-PserverRunState`）与 `-PfailAfterRegistry` 注入点。

证据（JBR 21）：
- 客户端 `-PverifyRunState=/tmp/t4fixture/t4fixture-1.0.0.jar -PfailAfterRegistry=true`：
  `first add ok=false ... runtime is dirty: registry content ...`；`second add kind=RUNTIME_DIRTY stage=VALIDATE`；`RESULT: PASS`。
- 专用服 `-PserverRunState=... -PfailAfterRegistry=true -PdirtyLogSeconds=2`：两次 `runtime is still DIRTY`
  （2s 间隔），`RESULT: PASS`，服务器自行 halt。
- 回归：`hud`、`input` 场景无脏日志（成功与变更前拒绝不置脏）。单测 **109**（`RunStateTest` 6）。

## 2026-11-19（P1-12 收尾：脏原因 i18n + 菜单常驻标识）

- `RunState.Reason` 枚举：8 个原因各有稳定 key（`modreloader.dirty.reason.*`）与英文句子；UI 只负责翻译，
  日志/`Result.message` 继续用英文（控制台不本地化）。
- 客户端热加载菜单左上角、JBR 标识下一行常驻红字“运行时不干净：…”，截图
  `run/screenshots/runstate-dirty.png` 实测显示中文原因；失败详情增加“脏状态原因”一行。
- dev harness 拆成服务端安全的 `RunStateCheck`（只引用 server-safe 类型）与 `RunStateClientCheck`
  （客户端 tick/菜单/截图）；此前二合一类在专用服上因引用客户端类 `NoClassDefFoundError`，`ServerDev.install` 失败，
  服务器不会自行 halt。
- 验证：客户端 `-PverifyRunState` PASS 并截图；专用服 `-PserverRunState -PdirtyLogSeconds=2` PASS（2 条重复告警）；
  `hud`/`input` 无脏日志；单测 **110**。`-PverifyMenu` 仍因本机缺 `/tmp/lanmods/EasyLAN...` 样本 jar 报 FAIL
  （环境依赖，非本次改动；菜单本身正常渲染）。

## 2026-11-19（问题 63 结案：过期 fixture，不是产品回归）

现象：`-PverifyPayload` 在标题屏热加 payloadfix 后建世界，40s 收不到 ping；HEAD `8e1523d` 同样失败。

诊断（`PayloadCheck` 新增）：
- `PAYLOAD_REGISTRATIONS` 的 PLAY 列表含 `payloadfix:ping`；`NetworkRegistry.setup=true`。
- 登录后客户端连接与服务端玩家连接的 `PayloadSetup` PLAY 通道都含 `payloadfix:ping`，`hasChannel=true`。
- 独立探针证明 `PlayerLoggedInEvent` 与 `ServerTickEvent.Post` 都能到达游戏总线。
- 但 fixture 自己的两条事件分支完全不打印。

根因：`/tmp/payloadfix/payloadfix-1.0.0.jar` 是 2026-11-15 的旧构建，javap 显示构造函数只注册了
`onPayloads`，`onPing` 是空实现，也没有 `onLogin`/`onServerTick`。用
`experiments/server-fixture/build-payload.sh` 重建后：`sending ping → PING RECEIVED → marker`，PASS。

结论：问题 63 结案；`PayloadCheck` 保留诊断输出（注册表 / 两侧 setup / 总线探针），以后 payload 问题能直接分辨
“注册/协商”还是“监听器/处理”层。

## 2026-11-19（问题 50 已修：late AT 回滚改为尽力而为）

复现（JBR 21，`-PlateAt=true`）：
- Moonlight 成功后热加 Supplementaries；新增 `-PfailAfterModListModId` / `-PfailAfterConstructModId`
  让双 jar 场景只让第二个 add 失败。
- 构造前失败：`late AT ...MineshaftPieces$MineShaftPiece ok`。
- 构造后失败：`redefineClasses` 写回被 JBR 拒绝，抛裸 `VerifyError: null` → 旧代码把它计入 rollback errors，
  `kind=ROLLBACK_PARTIAL`。

修法：`LateAccessTransformers` 的回滚动作 catch 所有 Throwable，warn “late AT stays applied” 并保留当前
（加宽后的）字节；访问加宽是加法，不会破坏已加载代码，且比把整个回滚判成 partial 更好。`ClassBytesCache`
保持指向 live 字节，后续 add 的捕获仍然正确。

证据：构造后失败不再有 `rollback errors`（`kind=UNKNOWN`，RunState 因构造/ mixin 置脏）；成功路径
`moonlight-lateat` 仍为两个 `ok=true`。构造后失败仍会因 Supplementaries 构造器注册的 Moonlight 动态资源回调
崩在后续 reload —— 属问题 35/7 的构造器副作用边界，不在本条范围。

## 2026-11-19（问题 35 查明：提交前失败不留内容；直接注册会被检测）

- Waystones 用 `DeferredRegister`，条目只在 `RegistryStage.commit` 的 `RegisterEvent` 里注册。
  `-PfailAfterConstruct` + `-PfailAfterConstructModId=waystones`：`blocks 1060->1060 / items 1333->1333`，
  `abort` 打印 “no entries were added … ids restored”。
- 构造器里**直接** `Registry.register` 才会在提交前留下条目。给 t4-fixture 加 `-PdirectRegister=true`
  （构造器里注册一个 block + item）复现：`abort` 报 `block 1060->1061, item 1333->1334`，置
  `RegistryStage.residue`；`RunState` 的原因从 CONSTRUCT/CUSTOM_REGISTRY 收紧为 REGISTRY，后续 add 被拒。
- 正常路径回归：t4fixture `ok=true`；Balm→Waystones `ok=true`（+44/+55 只发生在成功提交后）。
- 边界仍存在：注册表没有删除路径（删条目会移动 id），恢复手段是重启；但现在不会被静默忽略。

## 2026-11-19（包依赖分层：五条反向边清零 + 架构护栏）

清理前的反向边（源码扫描）：`client → server`、`diagnose → ui`、`command → ui`、`ui → client`、
`capability → hotadd`。全部靠“共用服务下沉”消除，而不是加白名单：

- `hotadd/{UnsafeAccess,JvmAccess}` → `reflect/`；`hotadd/MixinRuntimeApi` → `inspect/`；
- `ui/ModReloaderI18n` → `i18n/`；`server/ServerCommands` → `command/`；
- `client/{PendingAdd,Relauncher,RelaunchScripts,RelaunchFlags,JbrProvisioner}` → `launch/`
  （对应 5 个单测一起搬到 `src/test/.../launch`，因为它们用到包私有的内部函数）。

新增 `ArchitectureTest`：扫描 `src/main/java` 的 `package`/`import`，按
`reflect → inspect → capability/i18n/launch/watch/command → hotadd → client/server → ui/diagnose`
检查禁止方向，越层即失败。`./gradlew test` **111**；`-PverifyRelaunchPlan=true` PASS（重启脚本/spawn 路径）、
`hud` MATCH、`./gradlew build` 绿。

## 2026-11-19（P1-13 第一步：Mixin 锁序语义探针）

- `LaunchPluginMonitor` 从 `hotadd` 下沉到 `reflect`（纯反射/加载器内省），新增 `probe()`：
  解析 launcher 插件列表中的 `MixinLaunchPluginLegacy.processors` 监视器，失败时回退到
  `transforming_class_loader`，并给出失败原因。
- `Capabilities.checkMixinHotAdd` 增加语义判定：解析不到锁对象 → `mixin_hotadd=missing`，
  `applyMixins` 在 transform 前以 `CAPABILITY` 拒绝。这是把“54 修好的前提”从注释变成启动期可验证的事实。
- 回退路径不再引用 `net.minecraft.client.Minecraft.class`（专用服上会链接失败），改为从自身 classloader
  向上找 `TransformingClassLoader`。
- 证据：client `minimap` PASS（日志 `Mixin lock-order monitor: resolved`、`mixin_hotadd=ok`）；
  专用服 RunState harness PASS（同样 resolved/ok）；单测 111、build 绿。

## 2026-11-19（P1-13 第二步：Mixin agent 语义探针）

- 新增 `reflect/AgentTransformers`：只读扫描 Mixin agent 的 `Instrumentation`（`MixinAgent.instrumentation`，
  回退调用方实例）→ 两个 manager 字段 → `mTransformerList` → `MixinAgent$Transformer`，返回
  “无 agent / 找到 N 个 / agent 在但 transformers 不可读或无目标”三种可区分结果。
- `MixinAgentGuard` 改用它安装；`safeToRedefine()` = 本侧无 agent 或已包上。两处在 redefine 前拒绝：
  `LateAccessTransformers`（总是重定义已加载类）与 `MixinSupport`（目标已加载时），错误信息带扫描原因。
- 证据：`minimap` PASS（`mixin agent guard installed (1 transformer(s) wrapped)`）；`tooltip-lateat`
  Jade `ok=true`；build 绿、单测 111。

## 2026-11-19（P2-15 harness 抽共用；P1-12 菜单角标；CI 复核）

- `dev/TestWorld`：真实 create-world 屏 → 按 “Create New World” → 等 level/player → settle；
  `PayloadCheck` 与 `AddCommandCheck` 迁入（payload 端到端 PASS；clientfixture 的 GUI 层/物品装饰 PASS）。
- 主菜单/暂停屏 Logo 按钮：dirty 时右上角红点 + tooltip 带原因（`modreloader.gui.entry_dirty`）。
- CI：`api.github.com` 查到 36 次 GitHub Actions run，最新 `4557e7b` 为 `success`；P2-10 的“未远端验证”结案。
- 问题 29 归入结构性边界（chunk 重镶归原版；串行化后未复现）；P1-14 的线程/锁序表写进 design.md。
- 单测 111；build 绿。

## 2026-11-19（P2-15 收口：全部建世界 harness 迁到 TestWorld）

- `TestWorld` 已覆盖 `PayloadCheck`、`AddCommandCheck`、`AtSpikeCheck`、`CapabilityCheck`、
  `CreativeTitleCheck`（warm-up 用第二个实例做第二次建世界）与 `LanScreenCheck`。
- 实测：payload PASS、clientfixture PASS、AtSpike DONE、capabilities PASS、creative-at-title(+warm) PASS；
  `LanScreenCheck` 因本机缺 `lanserverproperties` 未跑（代码路径与其余一致）。
- 剩余：`TestConnection` 未抽（RemotePayloadCheck / MpRegistry*）。

## 2026-11-19（技术债账本收口）

- `TestConnection` 抽取：RemotePayloadCheck（E3 跨进程实测 PASS）、MpRegistry/MpFei/MpNotice 迁入。
- 技术债复审：P0-5/P0-6/P1-12/P1-13（高风险路径语义化）/P1-14（线程锁序规则）/P2-10（CI 实测 success）/
  P2-15（TestWorld+TestConnection）/包分层，均标 ✅；剩余项改写为“结构性/产品”清单：
  真实 reload 上下文重写、约 226 处逐特性反射、并发自动测试、L2/noFocus。
- 事实表刷新：27,083 行 / 150 文件；单测 111。

## 2026-11-20（问题 61：A 预检标注 J2 + B Sodium 适配实验）

- A：`MixinSupport` 在“给已加载类新增实例字段”时检测目标类是否有**活的单例**（静态自指字段且非空）。
  `Minecraft`（static instance）与 `BlockEntityType`（static FURNACE）命中 → 成功消息标 `J2` 并列出目标；
  Jade 的 `Font` 不是单例，仍只 warn（不误标）。证据：`sodium-lateat` 的消息含两条 J2，`tooltip-lateat` 无。
- B（opt-in，`-Dmodreloader.sodiumBootstrap=true`）：初始化 `Minecraft.fences`，并重放 Sodium 的内存初始化缺口
  （`SodiumClientMod.onInitialization`、`ConfigLoaderForge.collectConfigEntryPoints`、
  `ConfigManager.registerConfigsEarly/Late`、`ResourcePackScanner.checkIfCoreShaderLoaded`）。
  实测越过 `fences` NPE 与 “Config not yet available”，随后仍崩在 `SpriteContents$Ticker.parent`（旧精灵 ticker
  的新字段为 null）。说明这是“已有对象批量回填”问题，不是单字段可补；B 保持 opt-in，未默认启用。
- 分类：按所有者决定 **Sodium 保持 J2**（事实可热加，重启前存在致命级 bug）；白名单 TSV 备注与证据已更新。

## 2026-11-20（问题 61 B 实验进展：标题屏不崩，进世界卡在 BlockColors）

- B 新增 `SodiumBackfill`：回填旧 `SpriteContents$Ticker/InterpolationData.parent`（56）、
  `BlockEntityType.sodium$renderPredicates`（44）、活 `LevelRenderer.renderer`（`new SodiumWorldRenderer(mc)`，
  并在 managed GL 上下文里 `setLevel`）。
- 结果：标题屏热加 + 15s settle 无 NPE；**进世界热加**仍崩在 `BlockColors.sodium$getProviders`
  （`blocksToColor`/`overridenBlocks` 为 null，属构造态语义）。
- 结论不变：Sodium J2（可热加，重启前致命 bug）；B 保持 opt-in，不再继续堆 per-object 适配，除非决定做
  “Sodium 专用兼容层”（需要重建其颜色/渲染状态，风险高于收益）。

## 2026-11-20（问题 2 结案：AE2 L2 PASS）

- 新 harness `Ae2L2Check`（`-PverifyAe2L2=<guide>,<ae2>`）：标题屏热加 GuideME → AE2（lateAt），
  `TestWorld` 建世界，服务端直接放入 `ae2:guide` / `ae2:wireless_terminal`（创造世界无作弊，/give 会被拒），
  右键打开界面。
- 结果：GuideME `GuideScreen` 与 AE2 `MEStorageScreen` 均打开，无崩溃；证明菜单屏、物品模型与资源在热加后可用。
- 白名单：AE2/GuideME J1 由 candidate 转 verified；status 问题 2 移入已修，actionable 只剩 61。

## 2026-11-20（晚 AT 改为“准备环境”默认能力）

- 问题：玩家点一次「准备环境」并重启后，lateAt 仍依赖第二个属性 `-Dmodreloader.lateAt`；启动器只给
  `-XX:+AllowEnhancedClassRedefinition` 时 AT 模组仍被拒——这是非技术性阻塞。
- 改法：`LateAccessTransformers.enabled()` 在属性未显式设置时随增强 JBR 自动开启；
  `-Dmodreloader.lateAt=false` 可关，`-PlateAt`/属性仅 dev 覆盖。`Relauncher.jbrFlags()` 无参重载也改为带 lateAt。
- 验证：JBR 默认（不带 `-PlateAt`）Jade 57 条 AT 自动应用、`ok=true`；Temurin stock `late AT: off`、干净
  `ACCESS_TRANSFORMER` 拒绝；单测 114（新增 `LateAtDecisionTest`）。
- 文档：AGENTS / status / late-at.md 的“默认关闭/opt-in”表述同步更新。

## 2026-11-20（MixinRuntime 作为明确前置的 UI 化）

- 所有者明确：MixinRuntime 由用户/整合包提供，发布件不打包、不代下载。
- UI：热加载菜单右上角常驻红字“未安装 MixinRuntime (mr)：Mixin/妃级热加不可用”；准备环境页
  也注明“准备环境不包含它”。预检原本就拦 FEI/GUIFEI+mixins，现在准备阶段即可见。
- 文档：AGENTS 规则 12、status JBR 行、guides/jbr.md 增加前置说明。

## 2026-11-20（更正：MixinRuntime 是可选扩展，不是前置）

所有者澄清：`MixinRuntime` 不是启动前置，而是**可选扩展**——缺它时运行时照常启动（嫔级/普通热加不受影响），
只是妃级/Mixin 能力不可用。menu/预检/准备页的文案与 AGENTS/status/jbr 指南已从“前置”改回“可选扩展”；
`mods.toml` 的 `optional` 声明不变。

## 2026-11-20（0.1.0 release notes 草案）

- 新增 [releases/0.1.0.md](releases/0.1.0.md)：只承诺 `state=verified` 的行，candidate/fixing 明确“待复测”；
  按“是什么/安装/能力分层/支持矩阵/已知限制/回滚/报障材料”组织，存档不可逆与多人踢人写在限制里。
- 数据口径以 `tools/whitelist.py check` 为唯一校验入口：61 行，stock T1=7/BLACK=23；jbr J1=25/J2=2/BLACK=4；
  verified 3 行（stock T1 的 dogfood，JBR J1 的 GuideME、AE2）。所有者决定 J2 维持成功消息标注、不硬阻断。
- 状态源 `status.md` 的交接与最近变更同步到 2026-11-20；草案定稿仍等所有者真实复测。

## 2026-11-21（Mekanism 真机复验：问题 58 修复生效，暴露方法级 `-f` 崩溃）

环境：Windows 11 / PCL2 / NeoForge 21.1.266 / JBR 21.0.11（增强重定义）；`mods/` 只有 MixinRuntime + modreloader，
Mekanism 10.7.19.85 在标题屏经 `/modreloader add` 加入。共跑 4 次（默认 JIT、`-ShowHiddenFrames`、`-Xint`、
`-Xint + -Xlog:exceptions`），崩溃可稳定复现。

结果：
- **问题 58 修复 PASS**：late AT 94 规则/43 类；注册表 93 个重放，blocks 1060→1260、items 1333→1714、
  states 26684→29164、stoneId 稳定；自定义注册表 `chemical/module/robit_skin_serializer/chemical_ingredient_type`；
  层定义 213→224、GUI 层 25→29、物品装饰 0→14、payloads 58 个；`isLoaded=true container=true`；
  **不再出现 `mekanism:robit` 未绑定 NPE**。
- **新问题 64**：热加成功后约 2.1–2.3 秒，标题屏渲染在 `ClientHooks.drawScreenInternal` 的
  `Screen.renderWithTooltip` 调用点抛 NPE。该帧无行号、无消息；`-Xint` 仍复现；`-Xlog:exceptions=info` 显示
  `drawScreenInternal` 的 **BCI 为负值**（277.948s `-2951640`、278.449s `-2952208`），随后同异常经
  `drawScreen`(bci 27) → `GameRenderer.render`(bci 744) 传播。崩溃前 2 秒另有
  `NoSuchMethodError: java.lang.invoke.DirectMethodHandle$Holder.invokeSpecial` 等 MethodHandle 链接错误。
  说明不是业务空指针，而是 **JBR redefine 后已解析调用点/方法元数据失效**。
- **定位**：Mekanism 的 AT 有两条**方法级** `-f`：`public-f Screen renderWithTooltip(...)`、
  `public-f AbstractWidget render(...)`；它的 `GuiMekanism`（declares `public final renderWithTooltip`）与
  `GuiElement` 等重写了这两个方法，规则是类能否加载的前提。对照组 architectury 13.0.12（311 条 AT、
   **无**方法级 `-f`）在 11-18 同机 PASS，指向该差异。类级 `-f` 早已按问题 44 拒绝；这里是**方法级**的新边界。
- 工具：用 ASM 写了临时反汇编/扫描器（挂在 /tmp）确认重写者；`-Xlog:exceptions` 是这次定位的关键手段
  （直接给出抛出方法 + BCI，且不依赖 JIT）。
- jar 已复位：Mekanism 回到 `modreloader/staging/`，`mods/` 恢复为 MixinRuntime + modreloader；后续需要把
  PCL 的诊断参数（`-Xint`、`-ShowHiddenFrames`、`-Xlog`）去掉再跑正常场景。
- 状态源：58 转已修（hot-add 级 PASS），64 记为结构性边界，待产品决策（`VALIDATE` 拒绝/标 J2 或 workaround）。

## 2026-11-21（包装 jar：MezzConfig standalone 可热加）

白名单复测时 JEI 19.71 被预检拦下（缺 `mezz_config`）。所有者指出 JEI 自带 mezz，且本机
`mezz_config-...-standalone.jar` 并不是“不可热加的库”：它是**发行包装**——外层 manifest
`FMLModType: GAMELIBRARY`、没有 mods.toml，唯一载荷是 `META-INF/jarjar/mezz_config-...jar`，
内层才是真 mod（modId=`mezz_config`，234 个类）。启动期 FML 的 jarjar locator 会自动拆开它
（日志：`Found mod file "mezz_config-....jar" [parent: ...standalone.jar]`），热加路径之前只看外层，
于是按 `FailureKind.LIBRARY` 拒绝。

改动：
- 新增 `inspect/WrapperJars`：纯 zip 判定（外层无 mods.toml + jarjar 内层有 `[[mods]]`），按 path/size/mtime
  缓存；`nestedModIds` 供依赖检查使用（JEI 这类**自带**前置的外层 jar 不再误报 missing）。
- 新增 `hotadd/WrapperJar.resolve`：命中包装时用 `JarInJar`（content-addressed）抽出内层 mod，add 管线
  转到内层文件上执行；日志 `wrapper X: hot-adding the nested mod Y`。
- `ModInspector`：包装 jar 返回内层 mod 的预料（tier 由嵌套 mods.toml 推断、未扫类）；外层 mod 的
  jar-in-jar modId 计入依赖满足并写 `bundled (jar-in-jar)` 备注。
- 测试：`WrapperJarsTest` 4 个（包装/自带 mod/非 mod 内层/无 jarjar）；**118 单测**，build 绿。

待真机：把 standalone 从 `mods/` 移除后启动，标题屏 `/modreloader add mezz_config-...-standalone.jar`
应成功注册 `mezz_config`；随后 JEI 能否热加取决于其自身是否真的内嵌 mezz（jar 需放回本机核对）。

## 2026-11-21（包装拆包真机 PASS；JEI 依赖链打通）

- `mezz_config-...-standalone.jar`（外层 GAMELIBRARY + 无 mods.toml，内层 `META-INF/jarjar/mezz_config-....jar`
  才是真 mod）在标题屏热加成功：日志 `wrapper mezz_config-...: hot-adding the nested mod META-INF/jarjar/...`，
  `verify mezz_config: isLoaded=true, container=true`。
- 紧接着 JEI 19.71.0.475 热加：`verify jei: isLoaded=true, container=true`，keyMappings 39→79，无崩溃。
- 结论：JEI 这个 jar **不含** mezz（无 jarjar/metadata.json、无 net/mezzdev/config 类），mods.toml 要求
  `mezz_config [0.6.10,1.0.0)` 独立安装；拦截本身正确。产品侧改为支持“先热加前置”，并把缺依赖文案改成
  “该前置需要单独安装 / 并非所有发行包都自带”。
- 白名单：JEI（J1）转 verified（19.71.0.475，依赖链证据）。

## 2026-11-21（ModernUI 热加崩溃：自带 UI 线程，post-construct 失败不可回滚）

现象：`[现代化 UI] ModernUI-NeoForge-1.21.1-3.13.0.1-universal.jar` 标题屏热加；preflight 判
`guifei · mixins · registry`（静态扫描没看到 native/渲染线程）；add 在 post-construct 阶段失败
（`RuntimeException: null`，消息为空），引擎回滚（Screen/KeyboardHandler/StringSplitter/Window/GameRenderer
类、module layer、late AT 尽力还原），随后游戏崩溃：`Exception on UI thread`，
`NullPointerException: UI recording context has not been created yet, or creation failed`
（ModernUI 的 Looper/Choreographer 线程）。

原因与边界：ModernUI 是 render-core，构造/初始化期会**启动自己的 UI 线程**；这类副作用无法回滚（问题 7），
线程在 module/类被回滚后继续绘制 → 崩。不是单点 bug，而是结构性不可热加。
处置：白名单新增 ModernUI（jbr）BLACK（需重启正常加载）；问题 7 补实例。静态 tier 低估“自带线程/render core”
仍是已知局限（问题 1）。

## 2026-11-21（ModernUI opt-in 适配：复刻渲染线程初始化）

根因（字节码确认）：ModernUI 的 `Core.initOpenGL()` 只由 `MixinRenderSystem.onInitRenderer` 注入
`RenderSystem.initRenderer`（TAIL）在**启动期**调用；热加不会重跑该点，`Core.sRenderThread` 恒为 null，
其客户端 deferred task 抛 `The render thread has not been initialized yet`。构造期它还启动了自己的
UI 线程（Looper/Choreographer），回滚后继续绘制 → `UI recording context has not been created yet` 崩溃。

实现（opt-in，`-Dmodreloader.modernuiAdapter=true`）：
- `hotadd/ModernUIAdapter`：反射执行 `Core.initialize()` → `new ContextOptions()`，按 mixin 同样的字段
  （`mUseStagingBuffers`/`mAllowGLSPIRV`/`mDriverBugWorkarounds`，来自 `ModernUIClient` 的 bootstrap 属性）
  赋值 → `Core.initOpenGL(options)`；必须在渲染线程（chat 触发时经 `Minecraft.execute` 跳线程）。
- 在 `HotAddEngine.add()` 的 construct 之后、注册表提交之前执行（窗口尽量早，减少 UI 线程空转）。
- fail-safe：失败不在半初始化进程里回滚继续，直接 `Relauncher.saveAndRestartCurrentClient`（托管重启）；
  重启不可用才返回失败。
- 顺带修引擎时序：`rebuildShaderInstances` 从“资源包注入前”移到“注入后”，修 `modernui:shaders/...`
  在旧 manager 里找不到的 `FileNotFoundException`（提交 c2d35f3）。
- 构建绿（118 单测、ArchitectureTest 通过），提交 6036507；待真机。

## 2026-11-21（ModernUI opt-in 适配 PASS；Create L2 崩溃定位）

- ModernUI（3.13.0.1）：六轮真机后，适配链路定型并 PASS——
  1) 构造 **前** `Core.initialize()` + `Core.initOpenGL(options)`（其 UI 线程在构造期启动，必须已有 GPU device）；
  2) 构造后回填 **`Minecraft.font` 和 `Minecraft.fontFilterFishy`** 的 `modernUI_MC$textRenderer`
     （ChatScreen 的 EditBox 用后者；此前只填 font，按 `/` 即崩）；
  3) shader 重建后 `TextRenderType.preloadShaders()`（config reload 早于 shader 注册，current SDF shader 为 null）；
  4) 每 tick + 每个屏幕 Init.Post 兜底回填。
  日志：`modernui adapter: Core.initialize + Core.initOpenGL...`、`backfilled ... Minecraft.font/fontFilterFishy`、
  `TextRenderType.preloadShaders()...`、`hot-added [modernui]`；打开聊天框无崩溃。默认关闭，
  `-Dmodreloader.modernuiAdapter=true` 启用；白名单 ModernUI → J2。
- Create（6.0.10，含 ponder/flywheel 嵌套）：
  - **世界内 add** 崩于 `AllConfigs.server()` 为 null（SERVER 配置被跳过）。已让 `ModConfigs.load` 在有世界时
    从 `<world>/serverconfig` 加载 SERVER 配置（标题屏仍跳过并提示），已部署待复验。
  - **标题屏 add 成功、进世界崩**：`ponder` 的 `RenderTypeAccessor` 是 `@Mixin(RenderType.class)` 接口，
    静态 `@Invoker` 在“未被 Mixin 应用”时是直接抛 `AssertionError("Mixin application failed!")` 的桩；
    热加只 transform 了目标类，**接口自身没有被 Mixin 改写**，`PonderRenderTypes.<clinit>` 首次世界渲染即断言。
    记问题 65（待解决），白名单 Create → J2（能加，进世界不可用）。

### 更正：SERVER 配置不是“重启才生效”

2026-11-21 复盘发现，早先 `ModConfigs` 把 SERVER 配置一律跳过并提示“重启才能应用”是错误理解：
FML 的 `ConfigTracker.loadConfigs(Type.SERVER, …)` 在**世界启动**时遍历所有已注册配置（实测 18:33:21
`Loading configs type SERVER`），所以**标题屏热加**的模组的 SERVER 配置会在进世界时自动加载。
真正缺的只有“世界已经在跑、之后再热加”的场景——现在引擎在 `ModConfigs.load` 里用
`ServerLifecycleHooks.getCurrentServer().getServerDirectory()/serverconfig` 立即加载；标题屏则只记 INFO
（“will load when a world starts”），不再误导为需要重启。

## 2026-11-21（专用服一等目标：Create 在 dedicated server 热加 PASS）

所有者指出“SERVER 配置跳过”是客户端中心思维后，按专属服路径做了实测与修复：

- 本机 dev dedicated server（`runServer`，JBR/enhanced，`-x downloadAssets` + 手写
  `build/moddev/minecraft_assets.properties` 指向现有 `.minecraft/assets` 绕开 PKIX 资产下载）：
  `-PserverAddCommand=<create-6.0.10>` → **RESULT: PASS**，`verify create isLoaded=true`，
  `flywheel/ponder/create` 均 registered；`mixin config ... side=SERVER platform=server`。
- 修复（均为服务器侧真实缺口）：
  1. `MixinAgentGuard`：`MixinAgent` 类存在但 transformer 未注册、且无 MixinRuntime 时，
     旧逻辑按“不安全”拒绝所有 late AT（服务端首次实测即失败）；现视为安全，只有活跃 agent 才需要 guard。
  2. `LateAccessTransformers`：AT 目标在本次 side 因 `RuntimeDistCleaner` 拒绝加载（客户端类）时，
     旧逻辑让整批失败；现按 side 跳过该规则（日志 `not loadable on this side`）。
  3. `ModConfigs`：专用服不再加载 CLIENT 配置（FML 启动期也不加载）；世界内 SERVER 配置从
     `<world>/serverconfig` 加载，标题屏则由 FML 在世界启动时加载。实测 Create 服务端加载
     `create-common.toml + create-server.toml`（client 已跳过）。
- 客户端侧结论不变：标题屏 add 后进世界仍崩于 Ponder accessor 接口（问题 65），Create 保持 J2；
  专用服结论已单独写入白名单 evidence。
- 契约：`AGENTS.md` 12.5「专用服务器是一等目标」（提交 e9cd16c）。

## 2026-11-21（专用服回归批次：Create/Mekanism/architectury/runstate/why 全 PASS）

- `-PserverAddCommand`：Create（flywheel/ponder/create）PASS；Mekanism PASS（`registry commit: 93`，
  blocks 1060→1260、items 1333→1714，客户端 AT 目标 `not loadable on this side` 跳过）；architectury PASS。
- `-PserverRunState=mekanism -PfailAfterRegistry=true`：PASS（dirty、周期告警 2 次、第二次 add 拒绝）。
- `-PserverWhy`：PASS。首跑暴露判词不一致——`why` 只说“AT 拒绝”，而 add 在增强 JBR 下用 late AT 成功；
  已让 `HotAddWhy` 与 `LateAccessTransformers.enabled()` 同规则（显式属性优先，否则增强 JBR），
  现在输出 `note: access transformers via late AT` 且判词与 add 一致。
- 环境备注：dev dedicated server 需要 `-x downloadAssets` 并手写 `build/moddev/minecraft_assets.properties`
  （`assets_root` + `asset_index`）指向现有 `.minecraft/assets`，绕开本机 PKIX 资产下载；`run/mods` 需放 MixinRuntime。

### 服务器命令门禁（2026-11-21）

专用服上 `/modreloader` 原先没有权限要求（Brigadier 默认 permission 0），任何能打命令的玩家都能热加 jar。
`ReloaderCommands.registerServer` 现在在根节点加 `.requires(source -> source.hasPermission(2))`：控制台（4）与 OP（2+）
可用，普通玩家不可。客户端命令（`RegisterClientCommandsEvent`）不受影响，单机/本地照旧。
验证：`ServerAddCmdCheck` 新增断言——permission-0 命令源执行 `modreloader ping` 被拒（`denied=true`），
控制台 `serverAddCommand` 仍 `RESULT: PASS`。

## 2026-11-21（问题 65：热加 Mixin 的 accessor/invoker 接口从未被改写）

- 现象（上一批真机）：Create 标题屏热加 `ok=true`，进世界首次渲染 `PonderRenderTypes.<clinit>` 调
  `RenderTypeAccessor.catnip$create` 抛 `AssertionError: Mixin application failed!`；崩溃栈里该接口带
  `re:mixin` 标记但方法体仍是注解处理器生成的桩。
- 机制（Mixin 0.8.7 / sponge-mixin 0.16.4）：accessor 接口属于 loadable mixin，`MixinProcessor.applyMixins`
  对每个类先跑 `coprocessors.process`；`MixinCoprocessorAccessor` 在接口 define 时用
  `MixinInfo.getClassNode(0)` 生成静态代理（`invokestatic <target>.<generated>`）并 `Bytecode.replace`。
  它靠 `onPrepare` 学习接口名，而 listener 是 `MixinProcessor.prepareConfigs` 调
  `MixinConfig.addListener(coprocessor)` 挂上的。热加路径只调 `MixinConfig.prepare(extensions)`，
  没有 listener → `accessorMixins` 为空 → `couldTransformClass=false` → 原字节加载。
- 修法（`MixinSupport`）：
  - `attachConfigListeners`：prepare 前把 processor 的 4 个 coprocessor（passthrough / synthetic-inner /
    accessor / nest-host）与 `MixinInheritanceTracker.INSTANCE` 挂到该 config（每 config 只挂一次）。
  - prepare 前后快照 accessor registry，新出现的接口记日志；
  - `processLoadedAccessors`：已被早先 add 加载的接口用 `transformClassBytes` 重算并 redefine（带原字节回滚）。
- 回归夹具：`experiments/mixin-fixture` 新增 `AccessTarget`（私有静态 `hidden()`）与
  `mixin/accessor/AccessTargetInvoker`（静态 `@Invoker`，桩体同 ponder）；`MixinFixtureCheck` 在 add 后加载接口并调用。
  构建脚本改为编译 `src` 下全部 java。
- 证据（dev client，`-PnoJbr=true -PverifyMixinFixture=/tmp/mixinfixture/mixinfixture-1.0.0.jar`）：
  `mixin config mixinfixture.mixins.json: attached 4 processor listener(s)` →
  `accessor/invoker interface mixin(s) ... [mixinfixture.mixin.accessor.AccessTargetInvoker]` →
  `AccessTargetInvoker.mixinfixture$hidden() = reached-target` → `RESULT: PASS`。
- 部署：`build/libs/modreloader-1.21.1-0.1.0.jar` 已覆盖到 PCL
  `.minecraft/versions/1.21.1-NeoForge_21.1.266/mods/`（2026-11-21 20:57），并用 `javap` 确认部署包含新方法。
- 仍缺：Create 真机进世界复验；白名单 Create 暂留 J2。

### 问题 65 后续：专用服回归 + 影响面扫描（同日）

- 专用服复跑：`runServer -PserverAddCommand=[机械动力] create-1.21.1-6.0.10.jar` → `verify create: isLoaded=true`,
  `RESULT: PASS`；6 个 mixin config 各自 `attached 4 processor listener(s) for on-load interface rewriting`，
  说明 coprocessor listener 注册对服务端路径无副作用。
- 只读影响面扫描（本机 34 个 jar，含 `META-INF/jarjar` 嵌套；条件：类字节含
  `Mixin application failed!` 且含 `@Invoker`/`@Accessor`）：仅 Create→ponder 的 `RenderTypeAccessor` 命中，
  已安装模组与 `modreloader/staging`（Mekanism/ftb-library/architectury）均无静态 invoker 桩。
- 夹具侧只做常量提取（`INVOKER`），产品 jar 与 20:57 部署版本一致；`compileJava`/`compileDevJava` 绿。
- 尚未覆盖：`processLoadedAccessors`（接口在 add 前已加载）没有夹具路径——夹具 jar 在 add 前不在模块路径上，
  无法在不污染 loader 的前提下预加载；保留该分支用于“构造后失败回滚再重试”等场景。

### Create 专用检查（2026-11-21 晚）：PonderRenderTypes 的静态 invoker 路径 PASS

- 新增 dev harness `-PverifyCreatePonder=<jar>`（`CreatePonderCheck`）：标题屏热加 Create，20 tick 后反射
  `Class.forName("net.createmod.catnip.render.PonderRenderTypes", true)` 并调用 `fluid()` / `outlineSolid()`；
  这正是 18:33 崩溃的 `<clinit>`（`RenderTypeAccessor.catnip$create`）。
- 实测（dev client + 增强 JBR）：add `ok=true`（flywheel/ponder/create 三容器），
  accessor registry 登记 **52** 个接口（含 `RenderTypeAccessor`、flywheel/ponder/create 的其余 accessor）；
  `fluid() = RenderType[ponder:fluid:...]`、`outlineSolid() = RenderType[ponder:outline_solid:...]`、`RESULT: PASS`。
- 同批布局警告（J2，问题 45/56 同族）：`ItemStack` +`create$CLIPBOARD_ID`、`BlockEntityType` +flywheel visualizer/
  sodiumPredicate、`EntityType` +visualizer、`MapItemSavedData`/`ArmorTrim`/`PoseStack`/`LevelRenderer` 也新增字段；
  活实例保持 JVM 默认值。进世界是否可见错误留给真机观察。
- 仍未验证：PCL 真机进世界（手动）；白名单 Create 暂留 J2。

### 问题 66：数据包注册表未重放（2026-11-21，真机暴露后修复）

- 真机进度：问题 65 修复后 **Create 能进世界**；打开第一个机械动力创造栏（渲染马铃薯炮的装饰器）崩：
  `IllegalStateException: Registry create:potato_projectile/type not found`
  （`crash-reports/crash-2026-11-21_21.31.03-client.txt`）。另一个 Create 页签不触发该路径，故不崩。
- 根因：Create `CreateRegistriesImpl.registerDatapackRegistries` 用 `DataPackRegistryEvent.NewRegistry` 注册
  数据包注册表；热加只重放 `NewRegistryEvent`，数据包 codec 没进 `DataPackRegistriesHooks`，世界加载时不会建表。
- 修法：新增 `hotadd/DatapackRegistries`（construct 后对新容器发事件 + `process()`；重复 key 拒绝），
  接入 `ReplayCatalog`（23 项）。记录两个活视图：`getDataPackRegistries()`（WorldLoader）与
  `SYNCHRONIZED_REGISTRIES`（网络同步），标题屏 add → 下一个世界生效；已打开世界需重启。
- 过程中修掉一个 JDK 21 细节：`Unsafe.objectFieldOffset` 拒绝 record 字段（`DataPackRegistryData`），
  改走 trusted `findGetter` 读 `loaderData`。
- 证据：`-PverifyCreatePonder` 升级为 L2（TestWorld 建世界）→ `entries=25`、`getAmmo()` 返回 null（装饰器判空）、
  Ponder 渲染类型正常 → `RESULT: PASS`；`./gradlew build` 绿。部署 jar sha256 `a51bacb1…6335`（21:43）。
- 仍未验证：PCL 真机再开同一个创造栏；白名单 Create 暂留 J2。

### 问题 67（记录）：启动期 AT 在热加 redefine 时被原始字节覆盖（2026-11-21 真机）

- 真机：Create 热加、进世界后手持/穿戴铜潜水头盔右键方块 →
  `IllegalAccessError: class BlockBehaviour$BlockStateBase tried to access protected method
  UseOnContext.<init>(Level,Player,InteractionHand,ItemStack,BlockHitResult)`
  （`crash-reports/crash-2026-11-21_21.57.01-client.txt`）。
- 证据链：NeoForge 的 `META-INF/accesstransformer.cfg` 有
  `public net.minecraft.world.item.context.UseOnContext <init>(...)V`（启动期 AT）；Create 的 AT 里没有这条
  （`LateAccessTransformers` 40 规则/29 应用，列表无 UseOnContext）。21:55:54 日志显示
  `redefined net.minecraft.world.item.context.UseOnContext with mixins`。
  即：`captureOriginals` 从类资源拿到 protected 原始字节，transform + redefine 把它写回，启动期 AT 被抹掉；
  随后 `BlockStateBase`（也被 mixin redefine）首次执行 `useItemOn` 的调用点时访问检查失败。
- 影响面：所有「启动期 AT 加宽 + 该成员在热加目标类里被调用」的组合；失败回滚同样会把 AT 还原成原始访问。
- 计划修法：`MixinSupport` 增加“从活类移植访问标志”（反射 modifiers → 类/字段/方法），
  应用到 transform 后字节与回滚字节；回归断言 `UseOnContext.<init>` 仍 public，并真实触发 `useItemOn`。
- 当前处理：**只记录，未改代码**（按所有者要求先记录并推送今日改动）。Create 白名单暂留 J2。

## 2026-11-21（拉取 Windows 批次并同步文档）

- 快进拉取 38 个提交（`7867463`）：问题 64–67、ModernUI opt-in 适配、包装 jar、datapack 注册表重放、
  专用服一等目标与命令权限 2、Create/Mekanism/architectury 回归。
- 构建：`./gradlew build` 成功，新 jar `build/libs/modreloader-1.21.1-0.1.0.jar`（479,858 B，sha256 `e80b92d3…`）。
- 白名单实际 61 行（stock T1=7/BLACK=23；jbr J1=22/J2=5/BLACK=4）；同步 release notes 矩阵、
  verified 列表（Architectury/Controlling/JEI）与 J2 缺陷（Create/ModernUI），并修掉 status.md 的损坏行。

### 问题 67 修复：redefine 时保留加载期 AT（2026-11-21 晚）

- 修法：`MixinSupport.transplantLiveAccess` 从活类的 `getModifiers()` 取可见性/final，覆盖到
  `captureOriginals`/`processLoadedAccessors` 读出的类资源字节上（只动可见性/final，其它标志保持原样）；
  回滚基线同样带 AT。dev 跑出 `preserved 1 load-time access flag(s) on BlockEntityType`，机制生效。
- 关键限制：dev 的 `neoforge-...-merged.jar` 构建期已应用 AT，`UseOnContext.<init>` 资源本身就是 public，
  **dev 无法复现 67**；`-PverifyCreatePonder` 增加了构造器可见性断言与真实
  `gameMode.useItemOn(...)`（`PASS`），但真机右键方块才是唯一判据。
- 部署：PCL 与 server-kit 同步更新，sha256 `80e08684…dcf5`；server-kit 内含 Windows JBR 步骤。
- 专用服边界（问题 66 的延伸）：专用服世界先于控制台可用的时间点加载，add 后当前世界看不到新的
  数据包注册表；已记录为下一步「世界加载前热加」。

### 问题 68（记录）：in-world add 的 mixin→construct 竞态（AE2 真机）

- 真机：单人世界内热加 AE2 19.2.20，集成服务器 tick 崩
  `NPE: AEConfig.instance() is null`（`crash-reports/crash-2026-11-21_23.17.58-server.txt`）。
- 时间线（`logs/2026-11-21-7.log.gz`）：23:17:14 开始 add → 23:17:51 `ChunkMap` redefine（注入生效）→
  23:17:58 服务器 tick 崩 → 23:18:00 mixin 阶段结束 → 23:18:02 配置加载（construct 内）→ 23:18:08 add 完成。
- 根因：AE2 在 `AppEngBase` 构造器里 `AEConfig.register(...)` 创建单例；in-world add 先对已加载类 redefine
  mixin、后 construct，服务器线程在窗口内继续 tick，导致注入代码读到未初始化静态状态。标题屏 add 无 tick，故未暴露。
- 状态：只记录 + 三个方案（A 服务器 tick 屏障 / B 调整顺序 / C 拒绝 in-world mixin），待所有者拍板。

### 问题 67 真机确认（2026-11-21 23:13 会话）

- 真机日志 `logs/2026-11-21-7.log.gz`：热加 Create 后出现 4 条
  `preserved 1 load-time access flag(s) on ...`（`UseOnContext` / `DispenserBlock` / `BlockEntityType` / `RenderType`），
  整段 **0 次 `IllegalAccessError`**；21:57 会话（旧 jar）的 `BlockStateBase.useItemOn` 崩溃未再出现。问题 67 结案。

### 问题 68 实验：服务器 tick 屏障（2026-11-21 晚，失败，默认关闭）

- 实现 `ServerTickBarrier`：首个 redefine 前用 `server.execute` 的 latch 停住服务器线程，construct + common setup +
  payload 适配后释放；`ServerThreadHop` 在屏障期间 inline；`-Dmodreloader.tickBarrier=true` 才启用（默认 false）。
- 结果：Create 世界内 add（`-PverifyAddCommand` + `pauseOnLostFocus=false`）冻结 26s 后 PASS；
  **AE2 世界内 add（先 GuideME）两次都在 `VM_RedefineClasses` 里 JVM 崩溃**
  （`hs_err_pid14388.log`/`hs_err_pid22700.log`，`EXCEPTION_ACCESS_VIOLATION`，Server thread `_thread_blocked`）；
  关掉屏障两次都 PASS（AE2 的 AEConfig NPE 是概率性的，这次没撞上）。
- 结论：A 在当前 JBR 上不安全，默认关闭；下一步 B（in-world 先 construct 再 mixin）或 C（拒绝 in-world mixin）。
- 顺带：release 早于 payload 适配会让 Create 的 `clientbound_chain_conveyor` 被连接拒绝
  （`may not be sent to the client`）；释放点已移到 `runSidedStartup` 末尾。
- harness：新增 `-PverifyAddCommandPre`（AE2 需 GuideME）与 `pauseOnLostFocus=false`。

## 2026-11-21 深夜交接

- 真机链路：Create 进世界通过 → 66 数据包注册表修复确认（创造栏不再崩）→ 67 启动期 AT 修复确认
  （4 条 `preserved`、0 次 IllegalAccessError）→ 世界内热加 AE2 暴露 68（mixin→construct 竞态）。
- 68 方案 A（tick 屏障）实验失败：AE2 两次 JBR VM 崩溃，默认关闭；下一步 B（in-world 先 construct 再 mixin）。
  新增 harness `-PverifyAddCommandPre`、`pauseOnLostFocus=false`、`-PtickBarrier=true`。
- 部署：PCL jar sha `80e08684…dcf5`（65/66/67）；`server-kit.zip` 已同步（Windows JBR 步骤）。
- Git：本地已推送至 `origin/main`（尖端见 status 交接）；代理 7897 TLS 失败，直连重试成功。

## 2026-11-22（0.1.0-beta 发布：许可证改为 GPLv3）

- 所有者指示：许可证由 MIT 改为 GPLv3，并发布 0.1.0 beta。
- `LICENSE` 换为 GPLv3 全文 + 版权声明；`gradle.properties` / `neoforge.mods.toml` / README 同步为
  `GPL-3.0-only`；新增 ADR-0016。旧 `v0.1.0` tag 保持 MIT，不追溯。
- 版本 `0.1.0-beta`；发布 jar 内含 `META-INF/LICENSE`；`./gradlew build` 成功，118 单测。
- 产物：`build/libs/modreloader-1.21.1-0.1.0-beta.jar`，497,608 B，sha256
  `5ba576ca75be5a8e3065e58f428458417344eca2f12b415e6f38cf64a00434fd`；tag `v0.1.0-beta`。

## 2026-11-22（late join 专用服自动化：服务端先热加 → 客户端重启后加入）

- 新增 `dev/MpLateJoinServerCheck` + `dev/MpLateJoinClientCheck`，Gradle 开关
  `-PmpLateJoinServer="<jar>|<modId>"` / `-PverifyMpLateJoin="<jar>|<modId>"`。
- 服务端在 `ServerStartedEvent` 后无玩家时热加，写 `run/mp-latejoin-server.txt` 与 ready marker；
  客户端把同一 jar 放进 `run/mods` 启动，加入后比较全量 id 并保持 10s；服务端结果后 30s 自动停。
- 证据（t4fixture，2026-11-22 09:49–09:50）：服务端 `ok=true`、blocks 1060→1061/items 1333→1334；
  客户端 `isLoaded=true`、`noticeCount=0`、`ids match ... (29080 lines, blocks=1061, items=1334)`、
  `still connected=true ... after 10s`、`RESULT: PASS`；服务端 `RESULT: PASS` 后自动停，`runServer` exit 0。
- 结论：已装 jar 的 late joiner 由 NeoForge 登录同步带新注册表，无需额外步骤；未装 jar 的 late joiner
  仍会在登录期被 unknown-keys 拒绝且没有友好通知（已知 UX 缺口）。

## 2026-11-22（late join 加 L2 动作断言）

- `MpLateJoinServerCheck` 在客户端加入后准备 (0,120,0) 平台、把玩家传送到 (0.5,121,0.5)、
  给 `t4fixture:test_block`；客户端对 (1,120,0) 顶面执行 `useItemOn`，放置到 (1,121,0)。
- 服务端轮询权威 block state，客户端轮询本地 block state；两边都确认后才 PASS。
- 证据（2026-11-22 09:59–10:01）：客户端 `prepared to place ...`、
  `useItemOn(...) = SUCCESS`、`client placed ... (local block state confirmed)`；
  服务端 `client placed ... (server block state confirmed)`、`RESULT: PASS`；
  20s 后自动停，`runServer` exit 0。
- 夹具仍先由服务端无玩家热加；客户端 jar 在 `run/mods` 启动加载，登录同步 29,080 行 id 一致。
