# 项目状态

> **唯一状态源。** 每次会话先读这里；改状态只改这里，不要在各处复制。过程记录见 [history.md](history.md)。
> 最后更新：2026-11-16

## 一句话

只读分析器 v0 可用；热加引擎在 NeoForge 1.21.1 dev 环境验证到四档里的前三档——**嫔级**（无 mixin/注册表）、
**妃级**（mixin + 布局变更，需 JBR + MixinRuntime）、**贵妃级 add-only**（方块/物品注册 + 资源包/创造栏 + datapack `/reload` 语义，
含真实 dogfood 众星捧月 1.5.0）；皇后级只提示重启。
2026-11-16 五个新进展：**JiJ 内嵌库抽取**（jecharacters 从失败转 PASS）、**多人连接后的注册表 id 同步**
（运行时自有 play 通道，跨进程两端 29475 行 id 一致；前提是两端热加同一批 jar）、**MixinRuntime 反射化（构建不再需要 `libs/`）**、
**注册审计**（16 个真实 jar 的启动期事件清单；已知崩溃四类变更前拒绝，不再靠手写枚举碰运气）、
**capability 重放**（`RegisterCapabilitiesEvent` 只投新容器；众星捧月桶/坩埚/机器的 item/fluid/energy provider 热加后可用）。
同日收尾：in-world add 的 **id 重建竞态**（`No value with id -1`，已知问题 28）已修——「清 id + freeze」改在服务器线程执行，3 次回归 PASS；
**HUD 层（`RegisterGuiLayersEvent`）与物品装饰（`RegisterItemDecorationsEvent`）已重放**
（AppleSkin 25→31 层 / 顺序不变 / 渲染 60 tick；新夹具 `experiments/client-fixture` 断言两者）；
**「晚注入 AT」按 jar 逐条判死**（6 个真实 jar 在两个时机都是 0/6 可行，见 status 表尺）。
**硬边界**：access transformer（6/9 真实模组；晚注入已判死）、一次性启动事件的**重放**（审计只能报告/拒绝）、删除/更新、软重启未开始。
世界生成注册表已收窄：**标题屏热加 + 新建世界**可用（维度实测可进），已存在的世界/专用服启动期仍要重启。
失败回滚只覆盖 post-ModList / post-pack。按 [§6 契约](../AGENTS.md) 尚不算「完成」的项见文末「未验证」。

## 当前目标

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| 分析器 | Fabric + NeoForge，语料与校准锁 1.21.1 |
| 运行时首发 | NeoForge 1.21.1 |
| 首个 dogfood | `../Ex-Nihilo-Satellitium`（贵妃级内容模组） |
| 身份 | **runtime，不是 mod**：热加载第三方模组，不替代它们的功能（ADR-0005） |
| 产品锚点 | 热加载缺失的客户端工具模组；LAN 是第一个场景（[use-cases.md](use-cases.md)） |
| 论文 | 短期不做（ADR-0004） |

原因见 [ADR-0001](decisions/0001-target-1.21.1-neoforge.md)：作者自有项目是 NeoForge 1.21.1；其 dev 整合包 9 个模组里 6 个是贵妃/皇后级；妃级工具价值有限。

## 表尺（可复现）

一行一项能力；细节与边界都在对应的 feature 文档里。

| 能力 | 关键结果 | 命令 / 来源 |
|---|---|---|
| 分析器 | **14 个单测**；合成 jar 妃级 0.65；`run/mods` 9 个 → **嫔 2 / 妃 1 / 贵妃 4 / 皇后 2**；**真机校准 11 个有标注 jar：10 个 tier 一致、1 个已知过估**（ZeroCore2 GUIFEI vs 实际 fei，安全方向） | `cd tools/analyzer && python3 -m unittest discover -s tests -v`；`python3 tools/analyzer/calibrate.py --ground-truth tools/analyzer/calibration/ground-truth.tsv <jars>`；[analyzer-calibration.md](features/analyzer-calibration.md) |
| **分析器：AT 维度**（2026-11-16） | 新增 `META-INF/accesstransformer.cfg` 解析（`at_rules` / `at_targets`）+ 默认表格 `AT` 列；**6 个 AT jar 的规则数与运行期 `-PverifyAtSpike` 的独立测量逐个相同**（Jade 57 / JEI 21 / AE2 75 / Mekanism 94 / guideme 47 / ER2 1）；顺带纠正：MekanismGenerators **没有 AT**，它是缺依赖被拒 | 同上一行的 `calibrate.py`；[analyzer-calibration.md](features/analyzer-calibration.md) §4.1 |
| **A0（嫔级）热加** | EasyLAN：`isLoaded/container=true`、资源包 `mod/easylan` 选中并重载、keyMappings 34→34 | `-PautoAdd=<EasyLAN>`；[a0-hot-add.md](features/a0-hot-add.md) |
| **A1.2（妃级）全流水线** | 4 mixin；`UUIDUtil`/`AbstractWidget` redefine + `ShareToLanScreen`/`PlayerList` 首载织入；接口合并；LAN 屏 port=25565 | `-PautoAdd=<lsp> -PverifyLan=true`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) |
| **A1.2 失败回滚** | post-ModList / post-pack 均恢复 ModList/module/config/类/资源包，post-ModList 重试成功；构造后 listener 不可注销 | `-PverifyRollback=true -PfailAfterModList=true` / `-PfailAfterPack=true` |
| **世界内 `/modreloader add`** | 成功返回 1、`isLoaded=true`；缺 mr 时返回 0、状态干净、错误写明 `MixinRuntime (mr)` | `-PverifyAddCommand=…`（负向加 `-PverifyAddCommandExpectFailure=true`） |
| **专用服控制台 `/modreloader add`** | **短名可用：`/modreloader add t4fixture-1.0.0.jar`（先当前目录、再 `mods/`、再 inbox）→ `Loaded: hot-added [t4fixture]`、返回 1、`isLoaded=true`；TAB 补全 `[MixinRuntime-…jar, t4fixture-1.0.0.jar]`；未知名字返回 0 并打印「No jar found for '…'. Looked in: […]」；`scan` 行尾带 `→ /modreloader add <名字>`** | 2026-11-16 11:17：`runServer -PserverAddCommand=<t4fixture>`；见 [server-hotadd.md](features/server-hotadd.md) §3 |
| **专用服热加** | 嫔级夹具、命令重放 93→94、tick 到达、payload E1/E2/E3、dist 门禁拦截并干净回滚、`resource_packs=n/a` | [server-hotadd.md](features/server-hotadd.md) |
| **专用服妃级热加（JBR）** | **`enhancedRedefinition=true`；ZeroCore2 `redefined net.minecraft.world.inventory.Slot with mixins (8261 bytes)`、`hot-added [zerocore]`、payload 6 个适配；同一条 add 在此前（无 JBR 参数）是 `ROLLBACK_PARTIAL`** | 2026-11-16 10:58：`runServer -PautoAdd=<ZeroCore2>`；见 [server-hotadd.md](features/server-hotadd.md) §4 |
| **GUI / 监听** | EasyLAN 检测→加载、坏 jar 失败屏、贵妃级实验性加载、LSP 回滚屏、ignore→restore、世界内 toast、放入→删除→拷回 全 PASS | [gui-mod-watcher.md](features/gui-mod-watcher.md) |
| **贵妃级 add-only（服务端闭环）** | blocks 1060→1061 / items 1333→1334 / states 26684→26685，stoneId 1→1 稳定；放置 `(0,100,0)` 存盘；重启读档 `isT4Block=true` | [t4-registry-hotadd.md](features/t4-registry-hotadd.md) |
| **贵妃级客户端路径** | in-world 命令与 watcher 两条路：`mod/t4fixture` 资源包选中重载、`rebuilt 14 creative tab(s)`、物品进 `building_blocks` | 同上；截图 [t4-registry-prompt.png](features/images/t4-registry-prompt.png) |
| **datapack 热加载** | 众星捧月 **recipes 0→164 / loot 0→75 / tags 2+1**、真实 3×3 合成与战利品掉落 PASS；标题屏走 `already live` 短路；专用服 fixture 热加 + 重启后数据仍加载 | [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) |
| **dogfood 玩法验收** | `/exnihilo tables` 七表全非空；锤/筛/合成真实命中；`beRenderers 6/6`、`fluidExtensions=3`、`clientReloadListeners=6`、图标 `181/181`、config `1/1`；创造栏四条路径（进世界 add / 标题屏 add 冷 / 标题屏 add 热缓存 / 正常安装）页签 `exnihilosatellitium:main` 都在**第 2/2 页**、`>` 翻页可达、182 项、选中后菜单 45–46 项并渲染 40 tick；「翻页也找不到」未复现（已知问题 27） | [dogfood-gameplay.md](features/dogfood-gameplay.md) §1、§2.1；`-PverifyCreativeAtTitle`（`Warm`/`Installed`） |
| **多人连接后 id 同步** | 两端**相反顺序**热加：暂态 push 被正确拒绝 → `applied registry snapshot … 30 registries, 5043 entries` → `ids match the server exactly (29475 lines, blocks=1141, items=1515)` PASS | [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §9；`-PmpRegistryServer` + `-PverifyMpRegistry` |
| **热加告知在线客户端** | **未装该 jar 的客户端会先收到通知（聊天 + toast：mod 名/版本/jar/sha256 + 「会被断开且装回前进不来」），随后被 datapack 同步踢掉（`Failed to decode packet 'clientbound/minecraft:update_recipes'`）、重连被 `server-with-unknown-keys` 拒绝；客户端 ack 让服务端日志显示 `Dev has [] of the hot-added mod id(s)`** | 2026-11-16 11:48：`runServer -PmpNoticeServer=<t4fixture>` + `runClient -PverifyMpNotice=true`（客户端**故意不装**该 jar）；见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §10 |
| **AppleSkin 悬停食物崩溃（已修）** | **崩溃报告 `IllegalArgumentException: Unknown TooltipComponent` @ `ClientTooltipComponent.create`；根因：`RegisterClientTooltipComponentFactoriesEvent` 从未重放。修后 `tooltip component factories replayed: 1 new, 1 total`、`isLoaded=true`；**悬停渲染那一步还没复测**（需要鼠标）** | 2026-11-16 14:56（崩溃）/ 14:57（修复）：`run/crash-reports/crash-2026-11-16_14.56.09-client.txt` + `-PverifyCompat=<appleskin>` |
| **JiJ 内嵌库抽取** | jecharacters `ok=true`、`modules [jecharacters, PinIn, JustEnoughCharacters.coremod.neoforge]`、资源包重载；ZeroCore2 + jecharacters 同进程回归 PASS | `-PverifyCompat=<jecharacters>`；[matrix](features/hotadd-compatibility-matrix.md) §5 |
| **热加兼容性矩阵（首轮）** | 9 个真实模组：**2 PASS**（ZeroCore2 含 mixin、jecharacters）/ 6 个 AT 变更前拒绝 / 1 个缺依赖 | `-PverifyCompat=<jar>[,<jar>]`；[matrix](features/hotadd-compatibility-matrix.md) |
| **注册审计（启动期事件）**（[ADR-0012](decisions/0012-registration-audit-severity.md)） | 16 个真实 jar、**129 处事件引用**：**69 covered** / 24 recurring / 6 datagen / **16 silent** / 10 behavior / **3 crash** / 1 base（2026-11-16 复跑，HUD 层与物品装饰移入 covered）；3 处 crash 全在 AE2/Mekanism（本就被 AT 拒）→ 没有任何当前 PASS 的模组变成被拒；合成 jar 证明 crash 事件在 module 注入前被 `CLIENT_REGISTRATION` 拒绝；专用服上纯客户端事件记为 `other-side` | `-PverifyRegAudit=<jars>`；[client-registration-audit.md](features/client-registration-audit.md) §5 |
| **客户端重放：HUD 层 + 物品装饰** | `client/GuiLayerReplay`（live `GuiLayerManager` 层表**副本**重放再整表换入）+ `client/ItemDecoratorReplay`（live `DECORATOR_LOOKUP` 的每 item 列表副本追加再换回 rebuilt map）；`acceptEvent` 只投新容器。AppleSkin **25 → 31 层、6 个 `appleskin:*`、顺序不变**、渲染 60 tick；夹具 `clientfixture`（`experiments/client-fixture`）**层 +1、`minecraft:diamond` 装饰 0 → 1**。审计把两事件从 `silent` 移入 `covered`（**69 covered / 16 silent**） | 2026-11-16 19:47 / 20:0x：`-PverifyAddCommand=<appleskin> -PverifyGuiLayers=true`、`-PverifyAddCommand=<clientfixture> -PverifyGuiLayers=true -PverifyItemDecorators=true`、`-PverifyRegAudit=<16 jars>`；[client-registration-audit.md](features/client-registration-audit.md) §5、[client-fixture](../experiments/client-fixture/README.md) |
| **capability 重放（`RegisterCapabilitiesEvent`）** | 众星捧月热加后五项全部 present：桶 item/fluid、坩埚 fluid、机器 item/energy；A/B：`-PskipCapabilityReplay=true` 时五项全 MISSING（FAIL），重放后 PASS；provider 计数 `item 16/48、fluid 24/27、energy 4/4`（本模组/总数） | `-PverifyCapabilities=<jar>`；[dogfood-gameplay.md](features/dogfood-gameplay.md) §1.1 |
| **AT「晚注入」逐 jar 判定（只读，**已关闭**）** | 6 个带 AT 的 jar 共 295 条规则 / 151 个目标类（排除 datagen 后 146 个运行期目标）；**晚 AT 可行：0/6**（标题屏与进世界各一次；一个已加载目标就足够否掉）；ExtremeReactors2 的 AT **只服务 datagen**（0 运行期目标）→ 拒绝文案现在写清组成 | `-PverifyAtSpike=<jars>`；[matrix](features/hotadd-compatibility-matrix.md) §8.1 |
| **注册表 id 重建的线程**（修已知问题 28） | commit/abort 的「清 `BLOCKSTATE_TO_ID_MAP` + `freezeData`」投到服务器线程（日志 `registry freeze: hopping from the thread 'Render thread' to the server thread`），`RegisterEvent` 重放仍留调用线程；**连跑 3 次** `-PverifyAddCommand` + `-PverifyGameplay`：3/3 `AddCmdCheck`/`GameplayCheck RESULT: PASS`、capability 五项 present、`stoneId 1 -> 1 (stable)`、0 处 `No value with id`；专用服 guifei commit 在 `[Server thread]` 直跑（`RESULT: PLACED` + datapack PASS）、嫔级 add 回归 PASS | 2026-11-16 19:27–19:31：`-PverifyAddCommand=<dogfood> -PverifyAddCommandModId=exnihilosatellitium -PverifyGameplay=true` / `runServer -PautoAdd=<t4fixture> -Pt4fixtureSelftest=true` / `-PverifyAddCommand=<EasyLAN>`；[t4-registry-hotadd.md](features/t4-registry-hotadd.md) §11 |
| **WORLDGEN 边界（维度）** | 标题屏热加 `wgfixture`（数据夹具：1 个超平坦维度）→ **新建世界** → `dimension wgfixture:wgflat registered=true level=true`、`worldgen RESULT: PASS`（修前同一路径 `registered=false level=false`）；机制：`server == null` 时把包预注册进 `ResourceLoader.modResourcePacks`，world load 时随 `mod_data` 一起加载；**已存在的世界仍需重启**，专用服启动期仍不行 | 2026-11-16 20:1x：`-PverifyAddCommand=/tmp/wgfixture/wgfixture-1.0.0.jar -PverifyAddCommandModId=wgfixture -PverifyDatapackAtTitle=true -PverifyWorldgen=wgfixture:wgflat`；[t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §10 |
| **第二个妃级模组（同一会话）** | 先 in-world add 众星捧月（贵妃级），再 add `lanserverproperties`（妃级：4 mixin + 布局变更）：两边 `isLoaded=true`、第一个不受影响，`SECOND ADD RESULT: PASS` | 2026-11-16 20:2x：`-PverifyAddCommand=<dogfood> … -PverifyAddCommand2=<lsp> -PverifyAddCommand2ModId=lanserverproperties`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) §10 |
| **`/modreloader why` 只读诊断** | `inspect/HotAddWhy` 按引擎 VALIDATE 的顺序汇总 tier/内容形状/AT 组成/依赖/注册审计/结论；控制台命令返回 1（诊断成功，不因「拒绝」返回 0）。实测三形态：Jade → `refused (access transformers …)` 57/39；t4fixture → `experimental add-only`；EasyLAN → `hot-add` + **warning: references client classes**（静态扫描不据此拒绝，兜底仍是 dist 门禁 + 回滚） | 2026-11-16 20:5x：`runServer -PserverWhy="<jade>\|access transformer,<t4>\|experimental add-only,<easylan>\|references client classes"` → `[ServerWhy] RESULT: PASS`；[server-hotadd.md](features/server-hotadd.md) §5 |
| **工程底座** | `capabilities: module_injection … registry_sync … resource_packs` 全 ok（server `resource_packs=n/a`）；**无 `libs/` 可 `clean build`**；**21 个无游戏单测** | `./gradlew test`；[tech-debt.md](tech-debt.md) |
| **JDK / JBR 21** | 编译用 Temurin 21 toolchain；**`runClient` 与 `runServer` 都**用 JBR 21.0.11 + `-XX:+AllowEnhancedClassRedefinition`（`enhancedRedefinition=true`） | 启动日志 `[ModReloader] JVM … enhancedRedefinition=` |

## 验证状态

### 已验证

- **分析器**：合成 jar、公开 7-jar 语料、Ex Nihilo 1.5.0、`run/mods` 都能跑；JSON / Markdown 输出可用；9 个单测。
- **自附加 + 类重定义**（JDK 17，macOS arm64）：不依赖 `-javaagent` 拿到 `Instrumentation`；方法体替换 0.7–5 ms；新增方法被 JVM 拒绝。见 [PoC](../experiments/self-attach-poc/README.md)。
- **热加四档中的三档**：嫔级 [a0-hot-add.md](features/a0-hot-add.md)、妃级 [a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)、贵妃级 add-only [t4-registry-hotadd.md](features/t4-registry-hotadd.md)；
  客户端资源包/创造栏、客户端启动期注册重放（menu screens / BE+Entity 渲染器 / 染色 / 客户端扩展 / reload listener / item→model / config）见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §2。
- **贵妃级数据层**：datapack 注入 + `/reload` 语义 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md)；多人连接后的 id 同步 [同文 §9](features/t4-registry-hotadd.md)，决策见 [ADR-0010](decisions/0010-post-connect-registry-sync.md)（Proposed）。
- **双端化 + 工程底座**：dedicated server 加载/热加/命令重放/payload E1-E3 [server-hotadd.md](features/server-hotadd.md)；
  引擎与两端解耦（`PlatformHooks`）、[Capabilities](../src/main/java/dev/modreloader/capability/Capabilities.java) 八组锚点守卫、`dev/` 移出发布 jar、21 个无游戏单测 [tech-debt.md](tech-debt.md)。
- **真实 dogfood（众星捧月 1.5.0 / 1.4.2）**：注册层 + datapack + 玩法验收 + 五个客户端 bug（创造页签崩溃、机器 GUI、物品图标、内部内容不渲染、config 未加载）全部 PASS；
  创造栏另有四条路径回归（含主菜单热加与热缓存），页签都在第 2/2 页，见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1。
- **兼容性矩阵首轮 + 两个缺口的处理**：AT 变更前拒绝（含 UI 预检）、缺依赖前置拒绝、JiJ 内嵌库抽取、AT 晚注入量测与否定结论，见 [hotadd-compatibility-matrix.md](features/hotadd-compatibility-matrix.md)。
- **注册审计**：纯规则 9 个单测；`-PverifyRegAudit` 扫 16 个真实 jar（129 处引用）+ 合成致命 jar 断言 `VALIDATE` 拒绝、零副作用；真实 `-PverifyCompat=<AppleSkin>` 仍 PASS。见 [client-registration-audit.md](features/client-registration-audit.md)。
- **capability 重放**：`hotadd/CapabilityReplay` 在 registry commit 之后、common setup 之前只对新容器重放 `RegisterCapabilitiesEvent`；众星捧月五项 provider 在主菜单热加与进世界 add 两条路径都 present，`-PskipCapabilityReplay=true` 反证全 MISSING。见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §1。
- **注册表 id 重建的线程**：`hotadd/ServerThreadHop` 把 commit/abort 的「清 `BLOCKSTATE_TO_ID_MAP` + `freezeData`」投到服务器线程（已在服务器线程时直跑，超时回退 + warn，原子 claim 防重复）；`-PverifyAddCommand + -PverifyGameplay` 连跑 3 次全 PASS、无 `No value with id -1`。见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §11。

### 未验证

- **EasyLAN 的真实 GUI**：`@Mod` 构造器已注册 `ScreenEvent.Opening` 监听器，但没有人工点过界面按钮；`keyMappings` 未增加。A1.2 的 LAN 屏验证只覆盖 `lanserverproperties`。
- **生产 jar / 第三方启动器**：只在 dev client 测过。
- **`/modreloader gui` / `scan` 命令**：已注册，未自动验证（`/modreloader add`、世界内 toast、主菜单入口与忽略恢复已有 harness）。
- **忽略/失败 JSON 跨重启持久化**：GUI 内 ignore → restore 已自动验证，重启客户端后是否仍记住未验证。
- **已加载后删除 jar**：行为已知（mod 仍留在 ModList，没有卸载路径，不会再成为候选），但没有跑过专门检查。
- **贵妃级（注册表内容）**：删除/更新未做、提交后不可回滚；**客户端不装 jar 的多人场景**不支持（见已知问题 18）；世界生成注册表：标题屏热加 + 新建世界已实测可用（见已知问题 16 与 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §10），已存在的世界/专用服启动期热加仍不行。
- **datapack 热加载的极端组合**：标题屏热加 + 「世界加载前 pack 缓存已经建好」会走与世界内相同的注入路径，但没有单独 harness；实测的两个场景是「世界内注入」与「标题屏 + 已 live 短路」。
- **机器 GUI 交互**：screen 工厂已注册、界面能开，但放取物品/按钮没有自动断言；桶/坩埚内部内容渲染已修（`beRenderers 6/6`），但没有逐项视觉断言。
- **众星捧月手动玩法项**：桶 / 坩埚 / 堆肥 / 网耐久 / 机器界面只有清单，没有自动断言（见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §3）。
- **datapack 失败语义**：`Stage.DATAPACK` 失败返回 `FailureKind.DATAPACK` 并标注「注册表已提交、dirty」，但没有夹具触发过（需要 mod 的 reload listener 抛异常；坏 recipe/loot 只是逐条跳过）。
- **Tier 预测 vs 人工标注**：首轮校准已做（2026-11-16，11 个有真机结论的 jar：10 一致 / 1 过估，见
  [analyzer-calibration.md](features/analyzer-calibration.md)）。仍缺：只有 3 个语料未标注（Searchables / Controlling / mcwifipnp）、
  **Fabric 语料完全没校准**（intermediary 名导致 tier 规则退化）、置信度数值本身没校准。
- **Fabric intermediary 映射**：`class_XXX` 目标识别仍退化。
- **多版本 / 多加载器**：1.21.1 之外未测试。
- **存档一致性**：还没有写操作，谈不上验证。

## 已知问题 / 坑

1. **静态分析会漏**：`mekanismgenerators` 被标成嫔级，但它通过 Mekanism API 注册内容——跨模组注册抓不到。
2. **皇后级可能过保守**：AE2 因渲染 model mixin 被判皇后级；是否真的必须完整重启，需要运行时验证。
3. **Fabric 生产 jar 是 intermediary 名**：`net.minecraft.class_310`，核心类识别退化。
4. **JiJ 只拆一层**：更深的 bundle 不展开；运行时的 `JarInJar` 同样只处理 `META-INF/jarjar` 第一层。
5. **Python 分析器 vs Java 运行时**：规则两套实现，需要抽成声明式配置避免漂移。
6. **LAN 模组是妃级布局变更**：会给 `MinecraftServer` / `PauseScreen` / `ShareToLanScreen` 加字段；需要 JBR 或 sidecar；`online-mode` 可能要重启集成服务器才生效。详见 [use-cases.md](use-cases.md)。
7. **A1.2 仍未产品化**：依赖 JBR + `-XX:+AllowEnhancedClassRedefinition`；失败回滚只覆盖 post-ModList / post-pack——构造后的构造器和事件监听器无法注销；JVM 不能 undefine module（Module 对象留在缓存供重试）；被移除的 mixin config 保留但 inactive。存档回读、第二个妃级模组、真实 jar 启动仍未验证。
8. **Git 仓库已建立**（2026-11-15）：`main`、`origin` → `github.com/WanConcubine/Mod-Reloader`；尚未加 CI、tag、release，证据截图仍只在 `run/`（被忽略）。
9. **没有人工标注集**：规则准确率、混淆矩阵都还没有。
10. **没有删/更新路径**：只实现了 add；运行时删除/卸载/更新未实现、未测试。已加载的类（尤其已织入 mixin 的）无法靠删 jar 卸载；需要 classloader 隔离或贵妃级软重启。
11. **GUI/监听初版限制**：只有 `zh_cn` / `en_us`；异常消息/技术值仍是原始英文（标签已本地化）；最多直接显示 7 个候选；`/modreloader gui`/`scan`、失败重试未自动验证；**皇后级只提示重启**（贵妃级已放行，带实验性警告）。
12. **MixinRuntime 是妃级的第三方前置：可选依赖 + 只通过反射调用**：`mr` 是 `type="optional"`，缺它时游戏能启动、嫔级照常，由我们给出人话提示（toast + 日志 + 预检 + 失败屏，统一 `MixinRuntime (mr)`）；`required` 时 FML 只打印原始 modId 并直接终止，我方无法提示。`hotadd/MixinRuntimeApi` 在调用时解析 mr 的 10 个方法（`probe()` 驱动 `Capabilities`），`hotadd/ClassBytes` 接管了「读已加载类字节」，因此**构建不再需要 `libs/`**（fresh clone 可编）。`libs/` 目录已无引用，可删。
13. **`mods.toml` 的 side 字段不可信**（2026-11-15 实测）：EasyLAN 声明 `side="BOTH"`，但 `@Mod` 构造器注册了 `ScreenEvent` 监听，在专用服上直接抛 `Attempted to load class ... for invalid dist DEDICATED_SERVER`。NeoForge 在服务端是**主动拒绝**客户端类。判断"能否上服务端"必须扫字节码里的 `net/minecraft/client/**` 引用（含 provider 类本身），不能只看 metadata；扫描也只能减少误判，仍需运行期兜底（已有回滚）。
14. **服务端：命令靠重放生效，payload 可适配但有边界**：重放 `RegisterCommandsEvent` 后命令进了 dispatcher（93→94），重复触发的 `ServerTickEvent` 也能到达。**副作用**：重放是全局总线事件，所有已加载 mod 的监听器会再跑一遍（Brigadier 合并节点所以实测幂等，但有副作用的监听器会执行两次）。payload 通过反射解冻 `NetworkRegistry.setup` + 重放 `RegisterPayloadHandlersEvent` 注册，E2 起还能把新 channel 补进已建立连接的 `PayloadSetup`；E3 跨进程验证。**仍缺**：① 握手（两端必须是同一个 jar，人工保证）；② 与 `@ApiStatus.Internal` 对着干，锁 1.21.1；③ `ServerStartedEvent` 之类一次性事件。注册表内容已由贵妃级路径 + [ADR-0010](decisions/0010-post-connect-registry-sync.md) 的 id 同步补上。
15. **客户端重放 `RegisterCommandsEvent` 的副作用**（2026-11-15）：补上单人游戏命令注册的同时，集成服务器每次热加都会让**所有**已加载模组的命令监听器再跑一遍（与 14 同源）。热加发生在标题屏（无集成服务器）时不重放。
16. **Capabilities 只查名字，不查行为**：字段/方法/构造器存在且签名对上，不等于语义没变。价值是「框架漂移时立刻给人话失败而不是静默跳过」，不能代替游戏内 harness；锚点清单自己也会写错（第一版把 `createPackForMod(IModFileInfo)` 写成 `ModFileInfo`，被 harness 抓到）。
17. **贵妃级的第一个坑：裸 unfreeze/freeze 会移动 BlockState id**（2026-11-15 实测）：`GameData.unfreezeData()` → `freezeData()` 之后 `stone` 的 state id 从 1 变成 26685，因为 `BlockCallbacks.onBake` 用 `IdMapper.add()` 重建而 `add()` 无条件分配新 id。适配路线必须先清空 `BlockCallbacks.BLOCKSTATE_TO_ID_MAP` 再 freeze。细节见 [research-t4-registry.md](research-t4-registry.md)。
18. **贵妃级只到 add-only**：能热加方块/物品并保证存盘/重启可读，单人客户端资源包与创造栏、datapack、**多人连接后的 id 同步**都已验证（[t4-registry-hotadd.md](features/t4-registry-hotadd.md) §9）；但**客户端不装 jar 的多人场景**仍不行——而且比预想的硬：实测它会被 datapack 的配方同步直接踢下线，装回同一 jar 之前**连不回来**（§10）。jar / 资源 / payload channel 都无法从服务端同步，能同步的只有 id 映射。现在有告知层（[ADR-0011](decisions/0011-hotadd-must-inform-clients.md)）：通知 + 客户端 ack + 服务端 WARN；「有玩家在线时是否默认拒绝注册表内容热加」仍未决。实体类 post-register 事件未重放、删除/更新未做、提交后不可回滚。
19. **datapack 热加载的边界**：只覆盖 `RegistryLayer.RELOADABLE`（战利品/附魔/伤害类型/纹饰/物品修饰器…）；维度/生物群系/结构属于 WORLDGEN 层，要世界加载才生效——
**2026-11-16 实测**：标题屏热加时把包预注册进 `modResourcePacks`，**下一个新世界**确实能拿到维度（`-PverifyWorldgen`：`wgfixture:wgflat registered=true level=true`）；
已存在的世界仍要重启，专用服启动期（`modResourcePacks` 还没初始化）热加仍不可用。存档会记录 `mod/<modId>` 这个 data pack，重启时若 jar 也在 `mods/` 里该包会出现两次（实测按 id 归并、计数不变、无报错，但不优雅）。失败文案必须强调「注册表已提交、运行时 dirty、请重启」。见 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §7。
20. **创造页签排序非幂等（已修，留作坑）**：`CreativeModeTabRegistry.sortTabs()` 每次调用都往 `DEFAULT_TABS` 追加 HOTBAR/SEARCH/OP_BLOCKS/INVENTORY 且不清空，热加后再调一次 4→8，创造界面列 7/8 越界崩（`renderTabButton` 只有 7 张贴图）。修法：`afterRegistryContent()` 调 `sortTabs()` 前先清空 `DEFAULT_TABS`（失败只 warn）；回归 `-PverifyCreative`。
21. **客户端启动期注册事件：仍然是一张手写清单（结构性问题）**：2026-11-16 实测 AppleSkin 悬停食物崩溃（`Unknown TooltipComponent`），根因是 `RegisterClientTooltipComponentFactoriesEvent` 没重放——第六次同类缺口。NeoForge 1.21.1 的 mod bus 上有一批一次性事件（sources 里 44 个顶层 `IModBusEvent` 实现 + 若干嵌套），我们只重放其中十几个，**漏一个就是一类 bug**，失败形态从静默到崩溃不等。已重放：`FMLClientSetupEvent` / `RegisterKeyMappingsEvent` / `RegisterMenuScreensEvent` / `EntityRenderersEvent.RegisterRenderers`（+ 重建两个 dispatcher）/ `RegisterColorHandlersEvent.Block/.Item` / `RegisterClientExtensionsEvent`（临时 map 合并）/ `RegisterClientReloadListenersEvent`（临时 manager + 按类名去重），并补齐 `ItemRenderer` 的 item→model 快照、`RegisterClientTooltipComponentFactoriesEvent`（tooltip 组件工厂）与 `RegisterGuiLayersEvent`（HUD 层，`GuiLayerReplay`）与 `RegisterItemDecorationsEvent`（物品装饰，`ItemDecoratorReplay`）。
**仍未重放**：`RegisterLayerDefinitions`（模型层启动时已 bake，要重建 `EntityModelSet`）与 `ColorResolvers`（群系着色）——
逐个事件的「为什么不做 / 成本在哪」写在 [client-registration-audit.md](features/client-registration-audit.md) §7.1。见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §4。
    **2026-11-16 起有注册审计**（`-PverifyRegAudit`）：`VALIDATE` 打印每个 jar 的 covered/recurring/datagen/crash/behavior/silent 清单；
    已知「一用就崩」的四类（粒子 / 配方书 / shader / sprite source）像 AT 一样**变更前拒绝**（`FailureKind.CLIENT_REGISTRATION`），
    其余只 warn。清单仍然手写，但**漏了哪个现在是可报告、可排期的数据**，见 [client-registration-audit.md](features/client-registration-audit.md)。
22. ✅ **专用服妃级热加（已修，留作坑）**：`build.gradle` 原来只给 `runClient` 配 JBR，于是 ZeroCore2 在专用服上 redefining `Slot` 会失败（`UnsupportedOperationException: attempted to change superclass or interfaces`，`ROLLBACK_PARTIAL`）。2026-11-16 把同一个 helper 用到 `runServer`（JBR + `-XX:+AllowEnhancedClassRedefinition`，JBR 缺失时 warn），实测 `redefined net.minecraft.world.inventory.Slot` + `hot-added [zerocore]`。教训：**客户端能过的 mixin 操作，服务端要单独验证一次**——JVM 参数不一致会让同一条路径表现不同。
23. **access transformer 是热加的硬边界**：9 个真实模组里 6 个带 `META-INF/accesstransformer.cfg`；AT 只在类加载时由 FML 应用，热加不应用 → 依赖它的 mod 在构造时 `IllegalAccessError`（Jade 实测），而对已加载原版类做 AT 式 redefine 会丢 NeoForge patch。现在变更前拒绝（`FailureKind.ACCESS_TRANSFORMER` + UI 预检）。「晚注入」已量过两轮（[matrix](features/hotadd-compatibility-matrix.md) §8 / §8.1）：第一轮 151 个目标类里标题屏 19%、进世界 8% 未加载；
第二轮按「**每个 jar 是否所有运行期目标都未加载**」逐条判（排除 datagen）——**6 个 jar 在标题屏与进世界都是 0/6 可行**，
所以「晚 AT」**彻底关闭**（不是「先量着看」）。附带：ExtremeReactors2 的 AT 只放宽 `RecipeProvider`（datagen 类），
属于「AT 只服务 datagen」的误伤——放行仍不在结论内，但拒绝文案现在会打印 AT 组成（`inspect/AccessTransformerRules`）。
24. **JiJ 的剩余边界**（内嵌库已能读到）：带 `mods.toml` 的嵌套 **mod** 只有 module、没有 `ModContainer`（`@Mod` 构造器不跑）；`isObfuscated: true` 跳过；coremod（`FMLCorePlugin`）**不会运行** → 类能加载但字节码变换不生效（jecharacters 的拼音搜索没有验证）。见 [matrix](features/hotadd-compatibility-matrix.md) §3.2。
25. **已加载类的布局变更 / 无 JBR 的妃级热加（2026-11-16 实测修正）**：JBR 的 enhanced redefinition **可以**给已加载类加接口
    （`EditBox` 经 Searchables、`AbstractWidget` 经 LSP 都成功），所以「已加载 + 改接口」不是布局边界；
    真正的边界是**没有 JBR**——那次会话的提示屏就写着需要 JBR，`redefineClasses` 于是以
    `attempted to change superclass or interfaces` 失败。现在：`HotAddEngine` 在 MIXIN 阶段前置检查 JBR
    （`FailureKind.JBR_REQUIRED`），布局检测只在 JBR 不可用时拒绝、可用时只 warn 并照常尝试。
    同时修掉两个「读取/变换原始字节」的副作用：`ClassBytes` 的 retransform 会应用已准备的 mixin，
    MixinRuntime 的 `transform(Class)` 会自己 redefine——现在引擎用类文件资源取原始字节 + 自调
    `IMixinTransformer#transformClassBytes` + 自己 `redefineClasses`（见 history 2026-11-16）。
26. ✅ **热加 mod 的 `RegisterCapabilitiesEvent` 从未重放（已修 2026-11-16）**：审计首次指出的缺口——众星捧月给桶（item/fluid）、
    坩埚（fluid）、机器（item/energy）注册 provider，热加后不登记，所以「桶倒不进去 / 机器不动」。现在 `hotadd/CapabilityReplay`
    在 registry commit 之后、common setup 之前用 `container.acceptEvent(new RegisterCapabilitiesEvent())` **只投新容器**，
    并临时把 `CapabilityHooks.initFinished` 置 false 让 `setProxyable` 可用（finally 恢复）；never fail the add（provider 是 additive）。
    证据：主菜单热加 → 建世界 → 放桶/坩埚/机器，五项 capability 全 present；`-PskipCapabilityReplay=true` 时五项全 MISSING（反证）。
    审计把该事件从 `behavior` 移入 `covered`（66 covered / 10 behavior）。剩余：`GuiLayers` / `ItemDecorations` / 模型层仍未重放。
27. **「主菜单热加后创造栏没出现」报告无法复现（2026-11-16，未定位、未改代码）**：所有者在其机器上用众星捧月 **1.4.2**
    看到「主菜单热加 → 进世界 → 创造栏翻页也找不到页签」。本机四条路径（进世界 add / 标题屏 add 冷 / 标题屏 add 热缓存 / 正常安装）全 PASS，
    页签都在第 2/2 页、`>` 可达、182 项；1.4.2 与 1.5.0 发布 jar 内容一致（只差 MANIFEST/mods.toml 的版本号，`ModCreativeTabs.class` 哈希相同），
    版本解释不了差异。失败那次的日志没拿到，所有者放弃复现；四条 harness 留作回归。若再现：看那台 `logs/latest.log` 有没有
    `creative tabs sorted at the title screen`（没有 = 旧构建）以及 `hot-add` 是否 `ok=true`。详见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1.1。
28. ✅ **in-world 热加与区块发送的竞态（2026-11-16 18:50 复现 / 19:28 修，留作坑）**：进世界后热加众星捧月时，客户端收到一个 chunk 包报
    `IllegalArgumentException: No value with id -1`（`LinearPalette.read`），随后服务端以「网络协议错误」断开玩家。根因：`RegistryStage.commit`
    在**调用线程**（dev harness 是 render 线程）做「清 `BLOCKSTATE_TO_ID_MAP`（就是 `Block.BLOCK_STATE_REGISTRY`）→ `freezeData()`」，
    而区块序列化在服务器线程上——清空到 bake 重建之间 `getId` 一律返回 -1，服务器把 -1 写进 palette。
    修法：新增 `hotadd/ServerThreadHop`，把「清 id + freeze」（commit 与 abort 两处）投到服务器线程，已在服务器线程/还没有服务器时直跑；
    超时回退 + warn，原子 claim 防止执行两次；`RegisterEvent` 重放仍留调用线程。
    证据：`-PverifyAddCommand + -PverifyGameplay` 连跑 3 次全 PASS、capability 五项 present、无 `No value with id`。
    **剩余**：单人两端共享同一张表，窗口只是被挪到服务器线程并缩小（详细边界见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §11.5）。

29. **in-world 热加的资源重载与 chunk 重镶并发（一次性，2026-11-16 19:53，未定位）**：热加**没有 assets 的**客户端夹具时，
    `ClientResourcePacks` 仍选中一个空 mod 包并 `reloadResourcePacks()`；随后渲染 worker 线程报
    `NullPointerException: … "pk" is null`（`FluidSpriteCache.getFluidSprites` → `Map.getOrDefault(null, …)`），
    崩溃描述 `Tesselating liquid in world`（`SectionCompiler` → `LiquidBlockRenderer`）。同一条命令**第二次跑 PASS**，
    所以是竞态而不是本次改动的确定性后果；`FluidSpriteCache` 自己的注释就写着它是为了「chunk 在资源重载时被重镶」而存在。
    **已做的缓解**：`ClientResourcePacks.inject` 在「jar 既没有 `assets/` 也没有 `META-INF/jarjar`，且本次 add 没有新增
    client reload listener」时**跳过资源包注入与整次重载**（既省掉几秒，也去掉了无意义重载与 chunk 重镶的竞争面）。
    **未做**：真正的串行化（重载期间暂停/清空 chunk 重建任务），需要稳定复现再动。

30. **客户端「保存 → 退到标题 → 重开同一存档」的自动化会卡死（2026-11-16，环境相关，未定位）**：为验证 AGENTS §6 的
    「存档可读」，给 `AddCommandCheck` 加了 `-PverifySaveReadback`：放方块 → `saveEverything` → `mc.disconnect(...)` →
    重开存档再断言。**连续两次运行都卡死**：jcmd 线程转储为 Render thread 停在
    `Minecraft.disconnect → runTick → Window.updateDisplay → glfwSwapBuffers`（`disconnect` 内部会跑一次 `runTick`），
    同会话早前还出现过 `GLFW: Failed to locate a primary monitor` —— 更像显示环境不可用，而不是热加逻辑问题。
    处理：**该 harness 路径已删除**（不把会卡死的自动化留在仓库里），存档回读的既有证据仍是专用服 t4-fixture 的重启读档。
    要在无显示环境覆盖同一件事，应该走专用服（无 GL）路径。

## 下一步

> 下一会话从 **P2-4 剩余两项**（构造后 listener 回滚 / 服务端+客户端同时妃级）或 **P3** 开始；
> P1-2/P1-3/P2-1/P2-2/P2-3 已完成或已收口。
> ⚠️ **本机 dev client 显示层不稳**（2026-11-16 20:2x 连续两次卡在 `glfwSwapBuffers`、一次 `GLFW: Failed to locate a primary monitor`）：
> 在显示恢复前，优先做**不需要 GL 的活**（分析器校准、纯 Python/Java 单测、`runServer` 路径、文档），
> 客户端 harness 跑之前先确认窗口能正常交换缓冲。所有 harness 命令见 [AGENTS §2](../AGENTS.md)，细节在对应 feature 文档。
> **热加类 harness 要求 `run/mods/` 里没有众星捧月 jar**（已加载就不能再热加）；备份在 `/tmp/dogfood-runmods-backup.jar`。

| 优先 | 任务 | 关键点 / 落点 | 验收 |
|---|---|---|---|
| **P1-2** | ~~WORLDGEN 边界实验~~ **已完成 2026-11-16** | `experiments/worldgen-fixture`（只有数据：1 个维度）+ `-PverifyWorldgen`；**结论：「需重启」→「需新世界」**（标题屏热加 + 新建世界维度可进），已存在的世界仍要重启、专用服启动期仍不可用 | harness PASS + [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §10 |
| **P1-3** | Git 收尾（**部分完成 2026-11-16**） | `.github/workflows/ci.yml`（analyzer + `./gradlew build`）已加；6 张截图从 `run/screenshots/` 复制进 `docs/features/images/` 并把文档/README 链接改过去；tag 见下 | 剩：把 workflow 推上去跑绿（本机不 push，未证明）；tag 在本地 |
| **P2-1** | 剩余一次性事件 | ~~`RegisterItemDecorationsEvent`~~ **已完成**（`ItemDecoratorReplay` + `client-fixture`）；剩下的是逐个判死的清单：`RegisterLayerDefinitions`（要重建 `EntityModelSet`）、`ColorResolvers`、`ModifyDefaultComponentsEvent` 等，结论已写进 [client-registration-audit.md](features/client-registration-audit.md) §7.1 | 已有 harness 的必须 PASS；其余在 §7.1 写明为什么不做 |
| **P2-2** | ~~剩余 behavior 事件~~ **已完成 2026-11-16** | 11 个 behavior + 剩下的 silent 逐个给了结论：`RegisterItemDecorationsEvent`/`RegisterGuiLayersEvent` 变 covered；其余写进 [client-registration-audit.md](features/client-registration-audit.md) §7.1（框架启动后关闭、没有 subject、改已注册对象风险等） | 每个要么变 covered，要么在 §7.1 写清为什么不做（已满足） |
| **P2-3** | 贵妃级下一层（**删除/更新结论已完成 2026-11-16**） | datapack 删除：**实测不可能**（deselect 后 id 从 selected 消失，但 required `mod_data` 聚合包把子包重新加回来，`selectedPacks` 仍有它、recipes 164 / loot 75 仍在）；更新：不做（聚合包 children 启动期固定 + `SecureJar` 已绑定，等价 managed relaunch）→ [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §9 + 回归 `-PverifyDatapackRemove`。剩：WORLDGEN 与 P1-2 合并 | 结论已进 feature 文档；`-PverifyDatapackRemove` PASS |
| **P2-4** | A1 产品化（**部分完成 2026-11-16**） | ✅ **第二个妃级模组**（同一会话，`-PverifyAddCommand2`，PASS）；存档回读：内容路径已有 t4-fixture 重启读档 PASS，**客户端内「保存→退标题→重开」的 harness 两次卡死已移除**（已知问题 30，建议改走专用服）；仍缺：**构造后 listener 回滚**（NeoForge 无注销接口，需 bus 快照/恢复）、**服务端+客户端同时妃级** | 每一项 harness PASS + 文档；剩两项未做 |
| **P3** | GUI/watcher Phase 3/4、~~分析器校准~~（首轮已完成，见 [analyzer-calibration.md](features/analyzer-calibration.md)）、~~`/modreloader why`~~（已完成 2026-11-16，`-PserverWhy`）、B fallback、嵌套 mod 容器 | 见 [tech-debt.md](tech-debt.md) 与对应 feature 文档 | 各自文档的判据 |

### 验收清单（给自己/所有者）

```bash
./gradlew build && ./gradlew test        # 21 passed
# 确认 run/mods/ 里没有众星捧月 jar（它必须可热加）
./gradlew runClient -PverifyCapabilities=<dogfood.jar>                              # 五项 present，RESULT: PASS
./gradlew runClient -PverifyCapabilities=<dogfood.jar> -PskipCapabilityReplay=true  # 五项 MISSING，RESULT: FAIL（反证）
./gradlew runClient -PverifyAddCommand=<dogfood.jar> -PverifyAddCommandModId=exnihilosatellitium -PverifyGameplay=true
#   -> GameplayCheck RESULT: PASS，capability providers 五项 present
#   -> 连跑 3 次（id 重建竞态回归）：3/3 PASS，日志 0 处 "No value with id"
./gradlew runClient -PverifyRegAudit=<16 jars>          # 66 covered / 10 behavior，RESULT: PASS
./gradlew runClient -PverifyCreativeAtTitle=<dogfood.jar>  # 创造栏路径之一：RESULT: PASS
```

已知问题 27（1.4.2 创造栏缺失）仍需要那台机器的 `logs/latest.log` 才能继续，不阻塞上面任何一项。

已完成（保留参考，细节见 history 与对应 feature 文档）：**第二个妃级模组（同一会话，in-world 连续两次 add）**；**WORLDGEN 边界（标题屏热加 + 新世界可用维度）**；**datapack 删除/更新结论**；**客户端重放：HUD 层 + 物品装饰（含首个 client fixture）**；**AT 晚注入逐 jar 判死（0/6）**；**注册表 id 重建的线程（服务器线程 hop）**；**多人连接后注册表 id 同步**；**MixinRuntime 反射化（构建不再需要 `libs/`）**；
**AT 晚注入量测（结论：不做，P1-1 复核）**；**JiJ 内嵌依赖抽取**；**datapack 热加载**；**dogfood 玩法验收 + 五个客户端 bug**；
**兼容性矩阵首轮 + AT/缺依赖的变更前拒绝**；**注册审计**；**capability 重放**。

## 最近变更

> 逐步过程与证据见 [history.md](history.md)；这里只留最近几条。

- **2026-11-16（解掉 `command ↔ client` 包循环 / tech-debt P1-4）**：`ReloaderCommands` 不再 import `ClientCommands`；
  共享树 `tree()` 改为 public，新增 `remember(dispatcher)`（dev harness 仍要拿到客户端 dispatcher），
  客户端在自己的入口 `client/ClientCommands#register` 里做 `register(withGui(tree()))`，由 `ClientBootstrap` 订阅。
  依赖方向变成 **client → command** 单向：`command` 包里不再有任何客户端类型，专用服加载这条链时不会碰到它们。
  验证（2026-11-16 21:0x）：`./gradlew build` 通过；`runClient -PverifyAddCommand=<clientfixture> -PverifyGuiLayers=true
  -PverifyItemDecorators=true` → `dispatcher has /modreloader=true`（说明 `remember` 生效）、层 25→26、装饰 0→1、
  `AddCmdCheck RESULT: PASS`。见 [tech-debt.md](tech-debt.md) §4。
- **2026-11-16（`/modreloader why`：把「为什么」收成一条只读命令）**：新增 `inspect/HotAddWhy`（按引擎 VALIDATE 的顺序：
  tier/内容形状 → AT 组成 → 注册审计 → 缺失依赖 → HUANGHOU/GUIFEI/FEI/PIN 结论），`/modreloader why <jar>` 共用短名解析、
  控制台与客户端都能用；返回 1 表示**诊断跑了**（不因结论是拒绝而返回 0）。
  顺带修了一个真实的误判：`ModInspector` 原来只信 `clientSideOnly` 元数据，**看不到字节码里的客户端类引用**——
  EasyLAN 声明 `side=BOTH` 却在 `@Mod` 构造器注册 `ScreenEvent`，专用服上必崩（已知问题 13）；现在扫描
  `net/minecraft/client/**` 与 `com.mojang.blaze3d/**` 引用并写进 notes，但**只 warn 不拒绝**（Jade 这类双端模组的
  客户端代码从不在服务端加载，静态无法区分；兜底仍是 dist 门禁 + 回滚）。
  证据（2026-11-16 20:5x，专用服 `-PserverWhy`）：Jade → `refused (access transformers …)` 57 规则/39 目标；
  t4fixture → `experimental add-only`；EasyLAN → `hot-add` + `warning: references client classes`；
  控制台命令 `modreloader why <jade>` 返回 1；`[ServerWhy] RESULT: PASS`。文档：[server-hotadd.md](features/server-hotadd.md) §5、AGENTS §2。
- **2026-11-16（分析器首轮真机校准 + AT 维度：11 个标注 jar 10 个对，修掉一个危险方向的 bug）**：
  新增 `tools/analyzer/calibrate.py` + `calibration/ground-truth.tsv`（只存 jar 文件名，不提交 jar），把 tier 预测和
  status 表尺/兼容性矩阵里的真机结论对上：**11 个有标注 jar → 10 个 tier 一致、1 个已知过估**（ZeroCore2：分析器 GUIFEI、
  实际只需 fei；过估只多一条警告，方向安全，暂不修）。
  两处实质改进：① **AT 现在能看见了**——之前只认 Fabric 的 `accessWidener`，NeoForge 的
  `META-INF/accesstransformer.cfg` 完全没解析，而 AT 是运行期第一拒绝原因；现在解析规则/目标、默认表格加 `AT` 列，
  6 个 AT jar 的规则数与游戏内 `-PverifyAtSpike` 的独立测量**逐个相同**（57/21/75/94/47/1），两条代码路径互证。
  ② **修掉一个漏估**：`DeferredRegister$Blocks/$Items` 这类**嵌套子类**上的 `register*` 之前不匹配
  （旧规则 `endswith("/DeferredRegister")`），导致 t4fixture 被预测成 PIN、而运行期其实要开注册表窗口（GUIFEI）；
  现在改成按类名含 `DeferredRegister` + 方法名 `register*`，加单测钉住。漏估会让用户以为能热加、构造时崩，方向危险。
  顺带纠正一个口口相传的假设：MekanismGenerators 没有 AT，它在矩阵里是**缺依赖**被拒。
  文档：[analyzer-calibration.md](features/analyzer-calibration.md)、[tools/analyzer/README.md](../tools/analyzer/README.md)、AGENTS §2。
- **2026-11-16（A1 产品化的两小步：第二个妃级模组 PASS；客户端内「保存→重开」卡死并撤掉）**：
  `AddCommandCheck` 新增 `-PverifyAddCommand2` / `-PverifyAddCommand2ModId`：先 in-world add 众星捧月（贵妃级），再用同一条命令路径
  加 `lanserverproperties`（妃级），两边 `isLoaded=true`、第一个不受影响 → `SECOND ADD RESULT: PASS`（20:2x，干净的复跑）。
  另外试了 `-PverifySaveReadback`（放方块 → 保存 → `mc.disconnect` 退标题 → 重开存档 → 断言模组/方块还在）：
  **两次都卡死在 `Minecraft.disconnect → runTick → glfwSwapBuffers`**（jcmd 转储；同会话还出现过
  `GLFW: Failed to locate a primary monitor`），判定为显示环境问题、但连续卡死不可接受 → **该路径已从 harness 删除**，
  记为已知问题 30；存档可读的既有证据仍是 t4-fixture 的专用服重启读档。P2-4 还剩构造后 listener 回滚与服务端+客户端同时妃级。
- **2026-11-16（WORLDGEN 边界：从「需重启」改写为「需新世界」）**：新增 `experiments/worldgen-fixture`（只有数据：一个超平坦维度
  `wgfixture:wgflat`）与 `-PverifyWorldgen=<维度 id>`。**修前**标题屏热加 → 新建世界：`registered=false level=false`
  （注入点在 `ServerStartedEvent`，世界已经加载完，WORLDGEN 层补不上）。**修法**：`DatapackStage` 在 `server == null` 时先把
  `ResourcePackLoader.createPackForMod(fileInfo)` 放进 `modResourcePacks`（`preregister`），下一次 world load 的
  `populatePackRepository` 就会把它作为 `mod_data` 的子包带上，维度随世界注册；`defer` 仍留作兜底。
  **修后**：`dimension wgfixture:wgflat registered=true level=true`、`worldgen RESULT: PASS`。
  附带效果：标题屏热加众星捧月现在在 world load 就 **already live**（recipes=164 / loot=75），省掉世界启动后那次多余的整包重载。
  回归：标题屏 add + `-PverifyDatapack` PASS、进世界 `-PverifyGameplay` PASS。
  边界：已存在的世界仍需重启；专用服启动期热加（`modResourcePacks` 未初始化）仍不可用。见 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §10。
- **2026-11-16（datapack 删除/更新：实测把「大概不可行」变成「机制上不可能」）**：新增 `-PverifyDatapackRemove`
  （挂在 `AddCommandCheck`）：热加众星捧月 → recipes 164 / loot 75 → 从 `PackRepository` 的 selected 移除
  `mod/exnihilosatellitium` → `reloadResources` → 再读。结果：`selected ids=[vanilla, mod_data]` 但
  `selected packs=[vanilla, mod_data, mod/mr, …, mod/exnihilosatellitium]`、**recipes 仍 164 / loot 仍 75**。
  原因可引用：NeoForge 把所有 mod 数据包作为 **required** 聚合包 `mod_data` 的子包（`makePack` 的
  `PackSelectionConfig(required=true…)` + `withChildren`），`PackRepository.rebuildSelected` 每次都把 required 包的
  children 加回来。更新同样不做（children 启动期固定 + 包资源绑在 `SecureJar` 上，等于 managed relaunch）。
  结论写进 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §9，harness 留作"边界仍然成立"的回归。
- **2026-11-16（Git 收尾的一半：CI workflow + 截图入仓）**：新增 `.github/workflows/ci.yml`：`analyzer` job 跑
  `python3 -m unittest discover -s tests -v`；`runtime` job 用 Temurin 21 + `gradle/actions/setup-gradle` 跑 `./gradlew build`
  （AGENTS §6 的「完成」定义：单测 + dev harness 编译），60 分钟超时 + 上传测试报告。**没在远端跑过**（本机不 push），
  所以「CI 绿」仍未验证，诚实记为未完成。截图：`a1-lan-screen.png` / `watch-prompt{,-en,-t4}.png` / `watch-failure.png` /
  `pending-screen.png` 复制到 `docs/features/images/`，`a1-mixin-hotadd.md`、`gui-mod-watcher.md`、`README.md` 里
  指向 `run/screenshots/` 的链接全部改过去（`run/` 是 gitignore 的，之前文档里的图在仓库里根本不存在）。
- **2026-11-16（物品装饰重放 + 第一个客户端夹具：`RegisterItemDecorationsEvent`）**：新增 `client/ItemDecoratorReplay`
  （NeoForge 把事件烘成静态 `ItemDecoratorHandler.DECORATOR_LOOKUP`，所以读 live → 每个 item 的列表拷副本 → 追加 → 重建 handler → 换回；
  handler 的私有构造器走 trusted lookup）。同时补了**第一个客户端注册夹具** `experiments/client-fixture`
  （1 个 HUD 层 + `minecraft:diamond` 上 1 个装饰）：在此之前这两条重放没有任何真实 subject，改坏了所有 harness 也照样 PASS。
  证据：`-PverifyAddCommand=<clientfixture> -PverifyGuiLayers=true -PverifyItemDecorators=true` →
  `GUI layers: 25 -> 26`、`item decorations: minecraft:diamond 0 -> 1`、RESULT: PASS（20:0x 复跑；19:53 那次撞上已知问题 29 的竞态崩了）；
  审计复跑 **69 covered / 24 recurring / 6 datagen / 16 silent / 10 behavior / 3 crash / 1 base**，RESULT: PASS。
  顺带：`ClientResourcePacks` 对「无 assets、无 jarjar、无新 reload listener」的 jar **跳过整次资源重载**（已知问题 29 的缓解）。
  剩余事件的逐个结论见 [client-registration-audit.md](features/client-registration-audit.md) §7.1（P2-2 一半）。
- **2026-11-16（HUD 层重放：`RegisterGuiLayersEvent`，审计 silent 桶里第一个被补的）**：新增 `client/GuiLayerReplay`。
  这个事件不能像别的重放那样投进空 sink——它的 API 是 `registerBelow/Above(VanillaGuiLayers.HOTBAR, …)`，
  必须能看见已有层才能排序。做法：拿 live `GuiLayerManager` 的层表**副本**重放（`container.acceptEvent` 只跑新模组的监听器，
  已加载模组不会二次注册），再把副本整表换入；事件只能插入/包裹，所以「副本 = live + 新层」。
  从服务器线程触发时（聊天命令）把换入投到客户端线程，避免与 HUD 渲染并发。
  审计里 `RegisterGuiLayersEvent` 从 `silent` 移入 `covered`（66→68 covered、19→17 silent；16 jar / 129 引用不变）。
  证据：`-PverifyAddCommand=<appleskin> -PverifyAddCommandModId=appleskin -PverifyGuiLayers=true` →
  `GUI layers: 25 -> 31 layer(s); 6 new, 6 in namespace appleskin`、`order preserved`、`60 ticks rendered`、RESULT: PASS；
  `-PverifyRegAudit=<16 jars>` RESULT: PASS（合成 silent jar 的示例事件换成 `RegisterItemDecorationsEvent`）；
  众星捧月 `-PverifyGameplay -PverifyCreative` 回归 PASS。22+6 个单测。
- **2026-11-16（AT「晚注入」逐 jar 判死：0/6，这条关闭）**：`dev/AtSpikeCheck` 现在逐 jar 输出「**所有运行期 AT 目标**是否都未加载」，
  datagen / gametest 目标排除（`inspect/AccessTransformerRules`，纯解析 + 6 个单测）。6 个真实 jar 在标题屏与进世界**都是 0/6 可行**
  （Jade 32/39、JEI 9/12、Mekanism 38/45、AE2 26/33、guideme 16/17 已加载；ExtremeReactors2 的 AT 只针对 `RecipeProvider`，0 运行期目标）。
  顺带把「AT 组成」写进拒绝文案（`57 rule(s) over 39 target class(es) (39 runtime, 0 datagen-only); e.g. […]`），
  让「AT 只服务 datagen」的误伤变成可见事实（放行仍不在结论内）。表见 [matrix](features/hotadd-compatibility-matrix.md) §8.1。
- **2026-11-16（in-world add 的 id 重建竞态修掉了：`No value with id -1`）**：18:50 一次性复现的客户端 chunk 解码崩溃，
  根因是 `RegistryStage.commit` 在调用线程（dev harness 里是 render 线程）做「清 `BLOCKSTATE_TO_ID_MAP`（= `Block.BLOCK_STATE_REGISTRY`）→ `freezeData()`」，
  而区块序列化在服务器线程上，清空到 bake 重建之间 `getId` 返回 -1，服务器把 -1 写进 palette。
  新增 `hotadd/ServerThreadHop`：commit 与 abort 的「清 id + freeze」都投到服务器线程（`isSameThread`/无服务器时直跑，
  否则 `execute` + 等完成；超时回退 + warn；原子 claim 防重复执行），`RegisterEvent` 重放仍留调用线程。
  证据：`-PverifyAddCommand=<dogfood> … -PverifyGameplay=true` 连跑 3 次（19:27:07 / 19:27:53 / 19:28:32），3/3 PASS、capability 五项 present、
  `stoneId 1 -> 1 (stable)`、0 处 `No value with id`。剩余边界（单人两端共享同一张表，窗口只是被挪到服务器线程并缩小）写在
  [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §11.5。见已知问题 28、[tech-debt.md](tech-debt.md) §4。
- **2026-11-16（capability 重放：桶/坩埚/机器终于有 item/fluid/energy provider 了）**：注册审计指出的第一个真实缺口。
  新增 `hotadd/CapabilityReplay`：在 registry commit 之后、common setup 之前，用 `container.acceptEvent(new RegisterCapabilitiesEvent())`
  **只投新容器**，并临时复位 `CapabilityHooks.initFinished` 让 `setProxyable` 可用；失败只 warn，不让 add 失败。
  `dev/CapabilityCheck`（`-PverifyCapabilities`）做 A/B：主菜单热加 → 建世界 → 放桶/坩埚/机器 →
  五项全 present；`-PskipCapabilityReplay=true` 时五项全 MISSING。进世界 add 的 `GameplayCheck` 也加了同样断言并 PASS。
  审计把 `RegisterCapabilitiesEvent` 从 `behavior` 移入 `covered`（重跑 16 jar：66 covered / 10 behavior / 129 引用）。
  见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §1、已知问题 26。
- **2026-11-16（众星捧月创造栏：补「模组自己的页签」+ 四条路径回归）**：原来的 `-PverifyCreative` 只测「开界面不崩 + defaults=4 +
  某个 tab 里有该 namespace 的物品」，没测模组自己的页签。现在 `GameplayCheck` 断言页签注册、内容非空、在界面的分页里、
  `selectTab` 后菜单被填充并渲染 40 tick；`dev/CreativeTitleCheck`（`-PverifyCreativeAtTitle`，含 `Warm` 热缓存 / `Installed` 正常安装对照）
  覆盖**主菜单热加**路径（按界面真实的 `>` 翻页按钮）。四条路径都 PASS：页签在第 2/2 页、182 项、45–46 个非空菜单项。
  用户报的「翻页也找不到」未复现（1.4.2 与 1.5.0 发布 jar 内容一致）→ 已知问题 27。见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1。
- **2026-11-16（注册审计：漏了哪个启动期事件，现在是数据而不是运气）**：`inspect/RegistrationAudit`（纯分级规则，8 个单测）+
  `inspect/RegistrationAuditScanner`（ASM + `Class.forName`）接进 `VALIDATE`：打 covered/crash/behavior/silent 清单，
  四个「一用就崩」事件变更前拒绝（`CLIENT_REGISTRATION`），其余只 warn。`-PverifyRegAudit` 扫 16 个真实 jar（129 处引用）+
  合成致命 jar 验证拒绝发生在注入之前。审计首次指出众星捧月的 `RegisterCapabilitiesEvent` 从未重放 → 已知问题 26。
  见 [client-registration-audit.md](features/client-registration-audit.md)、[ADR-0012](decisions/0012-registration-audit-severity.md)。
- **2026-11-16（AppleSkin 悬停崩溃 = 漏了一个启动期事件）**：`Unknown TooltipComponent` @ `ClientTooltipComponent.create`；
  `RegisterClientTooltipComponentFactoriesEvent` 从未重放，已补（临时 map → 合并进 `ImmutableMap`）。同时修正 Searchables 的误判
  （JBR 可以给已加载类加接口；那次失败是没有 JBR），并修掉两个「读原始字节其实在改类」的 bug。
- **2026-11-16（热加告知层）**：新增 `server_mod_notice` / `mod_notice_ack`，在注册表提交与 datapack 重载**之前**通知在线客户端
  （mod 名/版本/jar/sha256/是否有注册表内容）；未装 jar 的客户端文案写明「会被断开、装回同一 jar 前连不回来」。
  实测：通知先到 → 被 `update_recipes` 同步踢掉 → 重连被 `server-with-unknown-keys` 拒。见 [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §10、[ADR-0011](decisions/0011-hotadd-must-inform-clients.md)。
- **2026-11-16（专用服控制台的短名 add + TAB 补全）**：`command/JarPathResolver`（绝对路径 → 当前目录 → `mods/` → inbox）+
  `add`/`probe` 补全 + `scan` 行尾 `→ /modreloader add <名字>`；6 个单测，专用服 `-PserverAddCommand` PASS。见 [server-hotadd.md](features/server-hotadd.md) §3。
- **2026-11-16（`runServer` 接 JBR）**：JBR 配置抽成 `useJbr` helper，`runServer` 也用 JBR 21 + `-XX:+AllowEnhancedClassRedefinition`；
  ZeroCore2 在专用服 `redefined Slot` + `hot-added`（此前 `ROLLBACK_PARTIAL`）。见 [server-hotadd.md](features/server-hotadd.md) §4。
