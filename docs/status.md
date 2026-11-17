# 项目状态

> **唯一状态源。** 每次会话先读这里；改状态只改这里，不要在各处复制。过程记录见 [history.md](history.md)。
> 最后更新：2026-11-16

## 一句话

只读分析器 v0 可用；热加引擎在 NeoForge 1.21.1 dev 环境验证到四档里的前三档——**嫔级**（无 mixin/注册表）、
**妃级**（mixin + 布局变更；JBR 只在「改已加载类的布局」时需要，其它情况免 JBR）、**贵妃级 add-only**（方块/物品 + 资源包/创造栏 + datapack + capability），
含真实 dogfood 众星捧月 1.5.0；皇后级只提示重启。热加 jar 的 **coremod（`ICoreMod`）现在会晚注入生效**——JEI 拼音搜索（jecharacters）已功能验证。
**硬边界**：access transformer（6/9 真实模组，「晚注入」已逐 jar 判死 0/6）、一次性启动事件的重放（审计只能报告/拒绝）、
删除/更新（datapack 删除实测不可能）、软重启未开始。**JBR 边界收窄**：只有「已加载类 + 布局变更」必须 JBR；
coremod 只补 class 级变换。世界生成注册表已收窄到「标题屏热加 + 新建世界可用」。
失败回滚覆盖 post-ModList / post-pack / **post-construct 的游戏总线监听器**；构造器的其它副作用与 post-construct 之后的重试仍不行。
入口：`/modreloader add|why`、mods/inbox 监听 + 预检 + toast/弹窗；完整命令见 [AGENTS §2](../AGENTS.md)。

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

| 能力 | 关键结果 | 命令 / 来源 |
|---|---|---|
| 分析器 | **14 个单测**；`run/mods` 9 个 → 嫔 2 / 妃 1 / 贵妃 4 / 皇后 2；**真机校准 11 个有标注 jar：10 个 tier 一致、1 个已知过估**（ZeroCore2，安全方向）；NeoForge **AT 检测**（`at_rules`/`at_targets` + 表格 `AT` 列），6 个 AT jar 规则数与游戏内独立测量逐个相同（57/21/75/94/47/1） | `cd tools/analyzer && python3 -m unittest discover -s tests -v`；`python3 tools/analyzer/calibrate.py --ground-truth tools/analyzer/calibration/ground-truth.tsv <jars>`；[analyzer-calibration.md](features/analyzer-calibration.md) |
| **A0（嫔级）** | EasyLAN：`isLoaded/container=true`、资源包 `mod/easylan` 选中重载、keyMappings 34→34 | `-PautoAdd=<EasyLAN>`；[a0-hot-add.md](features/a0-hot-add.md) |
| **A1.2（妃级）** | 4 mixin；`UUIDUtil`/`AbstractWidget` redefine + `ShareToLanScreen`/`PlayerList` 首载织入；接口合并；LAN 屏 port=25565；专用服同路径 `redefined Slot` + `hot-added` | `-PautoAdd=<lsp> -PverifyLan=true`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)、[server-hotadd.md](features/server-hotadd.md) §4 |
| **late coremod** | jecharacters 的 `JechCoreMod` 在独立 module layer 里加载；热加时变换 4 个目标（JEI 搜索/过滤器/前缀解析 + 原版 `SearchTree`），80 个未安装目标 deferred；`ElementSearchLowMem.matches("tieding","铁锭")` 变换前 **false**、变换后 **true** | `-PverifyPinyin=<jecharacters>`（JEI 需在 `run/mods`）；[matrix](features/hotadd-compatibility-matrix.md) §3.2 |
| **JBR 边界收窄** | 只有「已加载类的布局变更」要 JBR：Mouse Tweaks 在 JBR 下 `ok=true`；`-PnoJbr=true`（关掉 enhanced redefinition，等价 stock 路径）下 `stage=MIXIN kind=JBR_REQUIRED`，点名 `AbstractContainerScreen` 加接口、状态不变；**未加载目标**的 mixin 免 JBR（fixture `Probe.value()` 1→2，`RESULT: PASS`） | `-PverifyCompat=<MouseTweaks>`、`-PnoJbr=true -PverifyMixinFixture=<fixture>`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) §10.4 |
| **A1 产品化四项** | 第二个妃级模组（同会话，`SECOND ADD RESULT: PASS`）；构造后**游戏总线监听器回滚**（修前 38→41、修后 **38→38 (2 removed)**）；**服务端+客户端同时妃级**（ZeroCore2 两侧 `ok=true`、连接存活）；存档回读（专用服内容路径 t4-fixture 重启读档）。边界：构造器其它副作用不可回滚、post-construct 重试仍失败 | `-PverifyAddCommand2` / `-PfailAfterConstruct` / `-PmpFeiServer`+`-PverifyMpFei`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) §10 |
| **命令路径** | in-world `/modreloader add` 返回 1、`isLoaded=true`（缺 mr 时 0 且状态干净）；专用服**短名** + TAB 补全 + `scan` 行尾 `→ add <名字>`；`/modreloader why` 只读诊断（tier/AT/依赖/审计/结论） | `-PverifyAddCommand`、`-PserverAddCommand`、`-PserverWhy`；[server-hotadd.md](features/server-hotadd.md) §3/§5 |
| **专用服热加** | 命令重放 93→94、tick 到达、payload E1/E2/E3（含跨进程）、dist 门禁拦截并干净回滚、`resource_packs=n/a`、`enhancedRedefinition=true` | [server-hotadd.md](features/server-hotadd.md) |
| **GUI / 监听** | EasyLAN 检测→加载、坏 jar 失败屏、贵妃级实验性加载、LSP 回滚屏、ignore→restore、世界内 toast、放入→删除→拷回 全 PASS | [gui-mod-watcher.md](features/gui-mod-watcher.md) |
| **贵妃级 add-only** | 服务端 blocks 1060→1061 / items 1333→1334 / states 26684→26685，stoneId 1→1 稳定；放置 `(0,100,0)` 存盘、重启读档；客户端资源包选中重载、`rebuilt 14 creative tab(s)` | [t4-registry-hotadd.md](features/t4-registry-hotadd.md) |
| **datapack 热加载** | 众星捧月 recipes 0→164 / loot 0→75 / tags 2+1、真实 3×3 合成与掉落 PASS；专用服 fixture 热加 + 重启后数据仍在；**标题屏热加 + 新建世界可用维度**（`wgfixture:wgflat registered=true level=true`）；**删除/更新实测不可行**（required `mod_data` 会把子包加回来） | [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §9/§10、`-PverifyDatapack`、`-PverifyDatapackRemove`、`-PverifyWorldgen` |
| **dogfood 玩法验收** | `/exnihilo tables` 七表全非空；锤/筛/合成真实命中；`beRenderers 6/6`、`fluidExtensions=3`、`clientReloadListeners=6`、图标 `181/181`、config `1/1`；创造栏四条路径页签都在**第 2/2 页**、182 项、渲染 40 tick | [dogfood-gameplay.md](features/dogfood-gameplay.md) §1–§2.1 |
| **多人** | 两端相反顺序热加 → 快照应用 → `ids match the server exactly (29475 lines)`；**告知层**：未装 jar 的客户端先收到通知（聊天+toast），随后被 datapack 同步踢掉、重连被 `server-with-unknown-keys` 拒，客户端 ack 落日志 | [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §9/§10、`-PmpRegistryServer`+`-PverifyMpRegistry`、`-PmpNoticeServer`+`-PverifyMpNotice` |
| **注册审计**（[ADR-0012](decisions/0012-registration-audit-severity.md)） | 16 个真实 jar / **129 处引用**：**71 covered** / 24 recurring / 6 datagen / **16 silent** / 10 behavior / **1 crash** / 1 base；`RegisterParticleProvidersEvent` 2026-11-16 移入 covered（2 处引用：AE2、Mekanism），剩下的 crash 只有 Mekanism 的 shader；合成 jar 证明 crash 事件在 module 注入前被 `CLIENT_REGISTRATION` 拒绝 | `-PverifyRegAudit=<jars>`；[client-registration-audit.md](features/client-registration-audit.md) §5 |
| **tier 注册信号（runtime）** | 从「出现过注册表类名」改为「注册调用 / `*DeferredRegister` 引用」：`jade` 贵妃→**妃**（12 个类只读注册表、0 次注册调用）、`mekanismgenerators` 嫔→**贵妃**（`mekanism/common/registration/impl/BlockDeferredRegister#register`）、ZeroCore2/AE2/Mekanism/ExtremeReactors2/JEI 仍是贵妃；与 analyzer 的注册信号一致 | `RegistrySignalTest`（5 个单测）+ 离线 ASM 扫描 9 个真实 jar；[analyzer-calibration.md](features/analyzer-calibration.md) |
| **托管重启（A′/B）** | 失败界面「保存并重启游戏」（同 JVM）与「用 JBR 重启」（显式确认，仅本次会话）：保存世界/options → 写脚本（等 2s → cd → exec 原参数）→ `setsid`/`start` 启动 → **扫描带标记的 JVM 确认新进程存在**才退出；不碰 `mods/`。dev 验证：plan 12 args + spawn `java -version` exit 0 + JBR spawn exit 0（`-PverifyRelaunchPlan`，macOS） | [managed-relaunch.md](features/managed-relaunch.md)、`RelaunchScriptsTest`；**真实新窗口端到端未验（Windows/PCL2）** |
| **客户端启动期注册重放** | menu screens / renderers（+ 重建 dispatcher）/ 颜色 / 客户端扩展 / reload listener / tooltip 工厂 / item→model / **HUD 层（25→31，顺序不变，渲染 60 tick）** / **物品装饰（diamond 0→1）**；各事件「为什么不做」逐个写在 §7.1 | `-PverifyAddCommand=<appleskin> -PverifyGuiLayers=true`、`-PverifyAddCommand=<clientfixture> -PverifyItemDecorators=true`；[client-registration-audit.md](features/client-registration-audit.md) §7.1 |
| **capability 重放** | 众星捧月热加后五项全 present（桶 item/fluid、坩埚 fluid、机器 item/energy）；`-PskipCapabilityReplay=true` 时五项全 MISSING（反证）；`item 16/48、fluid 24/27、energy 4/4` | `-PverifyCapabilities=<jar>`；[dogfood-gameplay.md](features/dogfood-gameplay.md) §1.1 |
| **AT 晚注入判定** | 295 条规则 / 151 目标类（排除 datagen 后 146 运行期）；**晚 AT 可行 0/6**（一个已加载目标就否掉）；ExtremeReactors2 的 AT 只服务 datagen → 拒绝文案带组成 | `-PverifyAtSpike=<jars>`；[matrix](features/hotadd-compatibility-matrix.md) §8 / §8.1 |
| **失败回滚** | post-ModList / post-pack 恢复 ModList/module/config/类/资源包（post-ModList 重试成功）；post-construct 游戏总线监听器按实例摘除（`Capabilities.EVENT_BUS_ROLLBACK`） | `-PverifyRollback=true -PfailAfterModList=true` / `-PfailAfterPack=true` / `-PfailAfterConstruct=true` |
| **工程底座** | `capabilities` **10 组**全 ok（`…event_bus_rollback=ok`，server `resource_packs=n/a`）；无 `libs/` 可 `clean build`；**29 个无游戏 Java 单测**；i18n 两语言各 150 key | `./gradlew build`/`test`；[tech-debt.md](tech-debt.md) |

## 验证状态

### 已验证

- **分析器**：合成 jar、公开 7-jar 语料、Ex Nihilo 1.5.0、`run/mods` 都能跑；JSON/Markdown 输出；14 个单测；首轮真机校准。
- **自附加 + 类重定义**（JDK 17，macOS arm64）：不依赖 `-javaagent` 拿到 `Instrumentation`；方法体替换 0.7–5 ms；新增方法被 JVM 拒绝。见 [PoC](../experiments/self-attach-poc/README.md)。
- **热加四档中的三档**：嫔级 / 妃级 / 贵妃级 add-only，含客户端资源包与创造栏、capability、datapack、WORLDGEN（新世界）。
- **双端化 + 服务端**：dedicated server 加载/热加/命令重放/payload E1-E3、`runServer` 接 JBR、服务端+客户端同时妃级。
- **真实 dogfood（众星捧月 1.5.0）**：注册层 + datapack + 玩法 + 五个客户端 bug 全 PASS，另四条创造栏路径回归。
- **兼容性矩阵**：AT 变更前拒绝、缺依赖前置拒绝、JiJ 内嵌库抽取；AT 晚注入逐 jar 判死（0/6）。
- **注册审计 + 客户端重放 + capability 重放 + 失败回滚**：各有 harness；整条验收清单 2026-11-16 全跑（见 §下一步）。

### 未验证

- **EasyLAN 的真实 GUI 按钮**：`ScreenEvent.Opening` 监听已注册，但没人工点过；`keyMappings` 未增加。
- **生产 jar / 第三方启动器**：只在 dev client 测过。
- **`/modreloader gui` / `scan` / 失败重试**：已注册，未自动验证（`add`、`why`、世界内 toast、主菜单入口与忽略恢复已有 harness）。
- **忽略/失败 JSON 跨重启持久化**：GUI 内 ignore→restore 已自动验证，重启后是否仍记住未验证。
- **已加载后删除 jar**：行为已知（mod 留在 ModList，没有卸载路径），没跑过专门检查。
- **客户端内「保存 → 重开同一存档」**：harness 两次卡死已移除（已知问题 30）；内容路径的存档可读由专用服 t4-fixture 覆盖。
- **datapack 失败语义**：`Stage.DATAPACK` 失败返回 `FailureKind.DATAPACK` + dirty 文案，但没有夹具触发过。
- **机器 GUI 交互 / 手动玩法项**：桶、坩埚、堆肥、网耐久、机器界面只有清单，没有自动断言（[dogfood-gameplay.md](features/dogfood-gameplay.md) §3）。
- **分析器**：Fabric 语料完全没校准（intermediary 名退化）、置信度数值没校准、3 个语料未标注。
- **coremod 的 redefine 路径**：目标类在热加前已加载时走 `redefineClasses`，只有代码路径，真机只跑了「at-load」路径；JEI 拼音用私有 `matches` 断言，没开界面手动输入。
- **托管重启的端到端**：只验证了 plan/脚本/spawn 路径（macOS dev）；真实“新窗口起来、旧进程退出”没自动跑，Windows/PCL2 的进程树行为尤其未知。
- **多版本 / 多加载器**：1.21.1 之外未测试。

## 已知问题 / 坑

1. **静态分析仍可能漏/误判**：`mekanismgenerators` 以前被标成嫔级（它通过 Mekanism 自己的 `BlockDeferredRegister#register` 注册内容）——**2026-11-16 修了**：运行时 inspector 现在按「注册调用 / `*DeferredRegister` 引用」判定，不再按「出现过注册表类名」判定，MekanismGenerators 变贵妃、Jade 从贵妃回到妃级（见 `RegistrySignalTest`）。但反射/跨模组代理注册这类形状仍抓不到。
2. **皇后级可能过保守**：AE2 因渲染 model mixin 被判皇后级；是否真的必须完整重启需要运行时验证。
3. **Fabric 生产 jar 是 intermediary 名**：`net.minecraft.class_310`，核心类识别退化。
4. **JiJ 只拆一层**：更深的 bundle 不展开；运行时的 `JarInJar` 同样只处理第一层（剩余边界见 24）。
5. **Python 分析器 vs Java 运行时**：规则两套实现，需要抽成声明式配置避免漂移。**注册轴已对齐**（2026-11-16）：两边都要求「`register*` 调用（`Registry` / `*BuiltInRegistries` / `*RegisterEvent` / 任意 `*DeferredRegister`）或 `DeferredRegister` 类型引用」，Java 侧抽成可单测的 `inspect/RegistrySignal`；tier 的其它轴（native/JNI、render-core mixin）仍不一致。
6. **LAN 模组是妃级布局变更**：给 `MinecraftServer`/`PauseScreen`/`ShareToLanScreen` 加字段；需要 JBR；`online-mode` 可能要重启集成服务器。见 [use-cases.md](use-cases.md)。
7. **A1 的剩余边界**：依赖 JBR + `-XX:+AllowEnhancedClassRedefinition`；回滚覆盖 post-ModList/post-pack/post-construct 监听器，但**构造器其它副作用**（静态状态、全局注册、线程）不可撤销；JVM 不能 undefine module；被移除的 mixin config 保留但 inactive；**post-construct 之后重试仍失败**（FML `ModLoadingException`）→ 语义是「干净回滚 + 重启」；存档回读只有专用服内容路径。
8. **Git 收尾一半**：`main`/`origin` 已建，`.github/workflows/ci.yml` 与 tag `v0.1.0` 已加，截图已入 `docs/features/images/`；**没 push，CI 绿未证明**。
9. **没有完整人工标注集**：分析器首轮只校准了 11 个有真机结论的 jar；Fabric 与置信度未校准。
10. **没有删/更新路径**：只实现 add；已加载类尤其织入 mixin 的无法靠删 jar 卸载，需要 classloader 隔离或软重启；datapack 删除实测不可能（[t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §9）。
11. **GUI/监听初版限制**：只有 `zh_cn`/`en_us`；异常消息/技术值仍是英文；最多直接显示 7 个候选；`gui`/`scan`、失败重试未自动验证；皇后级只提示重启。
12. **MixinRuntime 是妃级第三方前置（可选依赖 + 反射）**：mr 为 `type="optional"`，缺它时嫔级照常、妃级被拦并给人话提示；`MixinRuntimeApi` 调用时解析 10 个方法，`ClassBytes` 接管读字节——**构建不再需要 `libs/`**。
13. **`mods.toml` 的 side 字段不可信**：EasyLAN 声明 `side="BOTH"` 却在 `@Mod` 构造器注册 `ScreenEvent`，专用服直接抛 `invalid dist`。现在 `ModInspector` 扫 `net/minecraft/client/**` 引用并**只 warn**（静态分不出「双端模组的客户端代码」与「启动路径就用」），兜底是 dist 门禁 + 回滚。
14. **服务端命令重放/payload 适配的边界**：重放 `RegisterCommandsEvent` 是全局事件，所有已加载 mod 的监听器会再跑一遍；payload 通过反射解冻 `NetworkRegistry.setup` 注册并能补进已建立连接的 `PayloadSetup`（E2/E3 验证），但仍缺握手（两端必须同一个 jar，人工保证）、锁 1.21.1、`ServerStartedEvent` 之类一次性事件。
15. **客户端重放 `RegisterCommandsEvent` 的副作用**（与 14 同源）：集成服务器每次热加都会让所有命令监听器再跑一遍；标题屏热加（无集成服务器）不重放。
16. **Capabilities 只查名字，不查行为**：锚点存在且签名对上，不等于语义没变；价值是框架漂移时立刻给人话失败，不能代替 harness（锚点清单自己也写错过，被 harness 抓到）。
17. **裸 unfreeze/freeze 会移动 BlockState id**：`BlockCallbacks.onBake` 用 `IdMapper.add()` 重建，不清空的话 stone 从 1 变 26685。必须先清 `BLOCKSTATE_TO_ID_MAP` 再 freeze。见 [research-t4-registry.md](research-t4-registry.md)。
18. **贵妃级只到 add-only**：客户端不装 jar 的多人场景仍不行——会被 datapack 配方同步直接踢掉，装回同一 jar 前连不回来（[t4-registry-hotadd.md](features/t4-registry-hotadd.md) §10）；能同步的只有 id 映射；实体类 post-register 事件未重放、删除/更新未做、提交后不可回滚；「有玩家在线时是否默认拒绝注册表内容热加」仍未决（[ADR-0011](decisions/0011-hotadd-must-inform-clients.md)）。
19. **datapack 的边界**：只覆盖 `RegistryLayer.RELOADABLE`；WORLDGEN 需新世界（标题屏热加可用，已存在的世界/专用服启动期不行）；存档会记录 `mod/<modId>`，重启时 jar 也在 `mods/` 会出现两次（按 id 归并、无报错）；失败文案必须强调「注册表已提交、dirty、请重启」。
20. ✅ **创造页签排序非幂等（已修，留作坑）**：`sortTabs()` 每次追加 HOTBAR/SEARCH/OP_BLOCKS/INVENTORY 且不清空 → 4→8 → 创造界面列 7/8 越界崩。修法：`afterRegistryContent()` 先清 `DEFAULT_TABS`；回归 `-PverifyCreative`。
21. **客户端启动期注册事件仍是手写清单**：NeoForge 1.21.1 的 mod bus 上有一批一次性事件（44 个顶层实现 + 若干嵌套），我们只重放其中十几个。已重放：FMLClientSetup / key mappings / menu screens / renderers / 颜色 / 客户端扩展 / reload listener / tooltip 工厂 / HUD 层 / 物品装饰 / 粒子 provider + item→model 快照；**未重放**：`RegisterLayerDefinitions`（模型层已 bake）、`ColorResolvers`。漏一个就是一类 bug，所以有**注册审计**（三类崩溃事件变更前拒绝），逐个结论见 [client-registration-audit.md](features/client-registration-audit.md) §7.1。
22. ✅ **专用服妃级热加（已修）**：`build.gradle` 原来只给 `runClient` 配 JBR，ZeroCore2 在专用服 redefine `Slot` 失败。2026-11-16 同一个 helper 用到 `runServer`，实测成功。教训：客户端能过的 mixin 操作，服务端要单独验证一次。
23. **access transformer 是硬边界**：AT 只在类加载时由 FML 应用；依赖它的 mod 构造时 `IllegalAccessError`，对已加载类做 AT 式 redefine 会丢 NeoForge patch。现在变更前拒绝（`FailureKind.ACCESS_TRANSFORMER` + UI 预检）。「晚注入」两轮量测后**彻底关闭**（0/6）；AT 组成写进拒绝文案（[matrix](features/hotadd-compatibility-matrix.md) §8.1）。
24. **JiJ 与 coremod 的剩余边界**：带 `mods.toml` 的嵌套 **mod** 只有 module、没有 `ModContainer`（`@Mod` 构造器不跑）；`isObfuscated: true` 跳过。**coremod 不再是「不运行」**：热加时在独立 module layer 里加载并晚注入（jecharacters 拼音搜索已功能验证），但只覆盖 class 级 `ITransformer`（`ClassNode`/`byte[]`）——method/field 级、目标在别的 class loader 的情况只报告 skipped；真机只跑了 at-load 路径（redefine 路径未实测）。见 [matrix](features/hotadd-compatibility-matrix.md) §3.2。
25. **JBR 只对「已加载类的布局变更」是硬边界**：JBR 的 enhanced redefinition 可以给已加载类加接口/字段/方法；没有它才以 `attempted to change superclass or interfaces` 失败。**2026-11-16 收窄**：删掉了「任何 mixin 都要 JBR」的前置门禁——未加载的目标由 Mixin 在 define 时织入（免 JBR），已加载目标逐目标判定布局；只有后者在没有 JBR 时拒绝（`FailureKind.JBR_REQUIRED`，消息点名目标与具体变更）。Mouse Tweaks 属于后者（accessor 给 `AbstractContainerScreen` 加接口），在 JBR 下可热加。
26. ✅ **热加 mod 的 `RegisterCapabilitiesEvent` 从未重放（已修）**：`hotadd/CapabilityReplay` 在 registry commit 后、common setup 前只投新容器，临时复位 `CapabilityHooks.initFinished`；失败只 warn。审计把该事件移入 `covered`。剩余：`GuiLayers`/`ItemDecorations`/模型层（前两个已补）。
27. **「主菜单热加后创造栏没出现」无法复现（未定位）**：所有者在其机器上用 1.4.2 报告，本机四条路径全 PASS（页签都在第 2/2 页、182 项），1.4.2 与 1.5.0 内容一致。失败日志没拿到；若再现看 `creative tabs sorted at the title screen` 与 `hot-add ok=true`。见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1.1。
28. ✅ **in-world 热加与区块发送的竞态（已修）**：根因是 `RegistryStage.commit` 在调用线程（render 线程）清 `BLOCKSTATE_TO_ID_MAP` 再 freeze，而区块序列化在服务器线程。`ServerThreadHop` 把「清 id + freeze」投到服务器线程，连跑 3 次 PASS。**剩余**：单人两端共享同一张表，窗口只是被挪走并缩小（[t4-registry-hotadd.md](features/t4-registry-hotadd.md) §11.5）。
29. **in-world 热加的资源重载与 chunk 重镶并发（一次性，未定位）**：无 assets 的客户端夹具热加时 worker 线程 `FluidSpriteCache` NPE（`Tesselating liquid`），第二次跑 PASS。缓解：无 `assets/`、无 `jarfile`、无新 reload listener 时**跳过整次资源重载**。真串行化未做。
30. **客户端「保存→退标题→重开」harness 会卡死（环境相关，路径已删）**：两次卡在 `Minecraft.disconnect → runTick → glfwSwapBuffers`（同会话有 `GLFW: Failed to locate a primary monitor`）。不把会卡死的自动化留在仓库，存档回读改由专用服路径覆盖。

## 下一步

> 下一会话从 **P1-3 收尾**（push CI）或 **P3** 开始；P0/P1/P2 项已全部完成或已收口。
> ⚠️ 本机 dev client 显示层不稳（曾连续两次卡在 `glfwSwapBuffers`）：显示恢复前优先做不需要 GL 的活（分析器、单测、`runServer`、文档）。
> 所有 harness 命令见 [AGENTS §2](../AGENTS.md)；**热加类 harness 要求 `run/mods/` 里没有众星捧月 jar**（备份 `/tmp/dogfood-runmods-backup.jar`）。

| 优先 | 任务 | 关键点 / 落点 | 验收 |
|---|---|---|---|
| **P1-3** | Git 收尾 | workflow/tag/截图已就绪，只剩 push 上去让 CI 真跑一次 | Actions 绿 |
| **P3-1** | GUI/watcher Phase 3/4 | 主菜单自动弹窗、失败面板完善、多候选/依赖排序、同 mod id 冲突、`-PverifyGui` 端到端点击 | 见 [gui-mod-watcher.md](features/gui-mod-watcher.md) §Phase 3/4 |
| **P3-2** | 产品化下一步 | 托管重启端到端（Windows/PCL2、开机自检）、嵌套 mod 容器、可分发形态、coremod target 报告 | [managed-relaunch.md](features/managed-relaunch.md) 的未验证项 |
| **P3-3** | 分析器 Fabric 校准 | intermediary 名 → 核心类识别；置信度校准；更多标注语料 | 校准表扩充 + tier 规则修正 |
| **tech-debt** | P1-5 `OneShotReplay` 统一出口、P1-6 harness 样板抽取 | 见 [tech-debt.md](tech-debt.md) | 构建绿 + 相关 harness 回归 |

### 验收清单（给自己/所有者）

```bash
./gradlew build && ./gradlew test        # 29 passed
# 确认 run/mods/ 里没有众星捧月 jar（它必须可热加）
./gradlew runClient -PverifyCapabilities=<dogfood.jar>                              # 五项 present，RESULT: PASS
./gradlew runClient -PverifyCapabilities=<dogfood.jar> -PskipCapabilityReplay=true  # 五项 MISSING，RESULT: FAIL（反证）
./gradlew runClient -PverifyAddCommand=<dogfood.jar> -PverifyAddCommandModId=exnihilosatellitium -PverifyGameplay=true
#   -> GameplayCheck RESULT: PASS，capability providers 五项 present
./gradlew runClient -PverifyRegAudit=<16 jars>          # 71 covered / 1 crash / 10 behavior，RESULT: PASS
./gradlew runClient -PverifyCreativeAtTitle=<dogfood.jar>  # 创造栏路径之一：RESULT: PASS
```

**整条清单在 2026-11-16 21:3x 全跑过一遍**：build ✅ / test ✅（29）/ capabilities ✅ / 反证 ❌（正确）/ gameplay ✅ / regaudit ✅ / creative ✅。
已知问题 27 仍需要那台机器的 `logs/latest.log`，不阻塞任何一项。更多已完成项的细节见 [history.md](history.md)。

## 最近变更

> 逐步过程与证据见 [history.md](history.md)；这里只留最近三条。

- **2026-11-16（粒子 provider 重放：Balm 的假拒绝）**：`RegisterParticleProvidersEvent` 从 `crash` 移入 `covered`——新增 `ClientRegistrationReplay#replayParticleProviders`，按**新容器定向重放**（不是全局 post；实测 Balm 把 listener 挂在消费方 mod 的 bus 上，定向重放才命中）。证据：`-PverifyCompat=/tmp/balm.jar` → `particle providers replayed: 0 new, 109 total`、`isLoaded=true`、`RESULT ok=true`（Balm 是库，自己不注册粒子，但消费方热加时它的 registrar 会走这条路）；审计计数 71 covered / 1 crash。
- **2026-11-16（tier 注册信号修正：贵妃误判）**：`ModInspector` 原来只要 jar 里出现 `Registry`/`Registries`/`BuiltInRegistries`/`DeferredRegister`/`NeoForgeRegistries`/`RegisterEvent` 任一类名就算「贵妃」，读取注册表（tooltip/GUI/配方展示）也被算成内容。改为 `inspect/RegistrySignal`：只有 `register*` 调用（`Registry` / `*BuiltInRegistries` / `*RegisterEvent` / 任意 `*DeferredRegister`）或 `DeferredRegister` 类型引用才算。实测 9 个真实 jar：Jade 贵妃→妃（0 次注册调用），MekanismGenerators 嫔→贵妃（它自己的 `BlockDeferredRegister#register` 以前漏了），其余不变；与 analyzer 一致，5 个单测钉住「读≠注册」。
- **2026-11-16（late coremod + JBR 收窄）**：新增 `hotadd/LateCoremod`——扫外层 + `META-INF/jarjar` 里的 `ICoreMod` 服务，在独立 module layer（先于 module 注入创建，避免同包解析冲突）里加载，已加载目标 `redefineClasses`、未加载目标用 `ClassFileTransformer` 在 define 时变换，失败可回滚。证据：`-PverifyPinyin` → `RESULT: PASS`（4 个目标变换、80 deferred、`matches("tieding","铁锭")` false→true）。同时删掉「有 mixin 就必须 JBR」的门禁：JBR 只对已加载类的布局变更必需。证据：Mouse Tweaks JBR 下 `ok=true`、`-PnoJbr=true` 下点名 `AbstractContainerScreen` 干净拒绝；mixin fixture 未加载目标免 JBR PASS。
- **2026-11-16（服务端+客户端同时妃级：P2-4 收口）**：`-PmpFeiServer` + `-PverifyMpFei` 两个 harness，ZeroCore2 两侧各自热加（服务端 `redefined Slot`、客户端 `Slot/GuiGraphics/ModelBakery`），两边 `ok=true`、玩家一直在线 → 双方 `RESULT: PASS`。至此 P2-4 四项全部有 harness。
- **2026-11-16（构造后失败回滚：游戏总线监听器按实例摘除）**：先量出 38→41 的泄漏，再加 `hotadd/EventBusSnapshot`（快照 owner→监听器实例，回滚按实例摘除 + 修剪 owner 表，`Capabilities.EVENT_BUS_ROLLBACK` 守卫）；修后 38→38（移除 2 个），状态检查全过。边界：post-construct 之后重试仍失败（FML `ModLoadingException`）。
- **2026-11-16（从 P0-2 到 P2-4 的一整轮）**：HUD 层/物品装饰重放 + 首个客户端夹具、AT 晚注入判死、WORLDGEN 改写为「需新世界」、datapack 删除不可行、`/modreloader why`、分析器真机校准 + AT 维度、解掉 `command↔client` 循环。每项证据见 [history.md](history.md)。
