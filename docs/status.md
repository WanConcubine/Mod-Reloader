# 项目状态

> **唯一状态源。** 每次会话先读这里；改状态只改这里，不要在各处复制。过程记录见 [history.md](history.md)。
> 最后更新：2026-11-18

## 一句话

只读分析器 v0 可用；热加引擎在 NeoForge 1.21.1 dev 环境验证到四档里的前三档——**嫔级**（无 mixin/注册表）、
**妃级**（mixin + 布局变更；JBR 只在「改已加载类的布局」时需要，其它情况免 JBR）、**贵妃级 add-only**（方块/物品 + 资源包/创造栏 + datapack + capability），
含真实 dogfood 众星捧月 1.5.0；皇后级只提示重启。热加 jar 的 **coremod（`ICoreMod`）现在会晚注入生效**——JEI 拼音搜索（jecharacters）已功能验证。
**AT 边界收窄（2026-11-17）**：stock JVM 上 AT 仍是硬边界，但 **JBR 的 enhanced redefinition 可以改已加载类的访问标志**；
实验性 late AT 让 Jade（57/57 规则、39/39 目标）、JEI（34/34、16/16）、Controlling（1/1）L1 PASS，Jade 回滚 39/39 ok。
它是 **JBR-only、opt-in、只覆盖 access/final、只到 L1**，不要写成“通用 AT 支持”（[late-at.md](features/late-at.md)）。
其余硬边界：一次性启动事件的重放（审计只能报告/拒绝）、删除/更新（datapack 删除实测不可能）、软重启未开始。**JBR 边界收窄**：只有「已加载类 + 布局变更」必须 JBR；
coremod 只补 class 级变换。世界生成注册表已收窄到「标题屏热加 + 新建世界可用」。
失败回滚覆盖 post-ModList / post-pack / **post-construct 的游戏总线监听器**；构造器的其它副作用与 post-construct 之后的重试仍不行。
**2026-11-17**：兼容性测试流水线 v0（`tools/pipeline`，L0 静态 + L1 标题屏报告）；**JiJ 嵌套 mod 会建 container 与资源包**——Xaero's Minimap（自带 XaeroLib）L1 **PASS**；`/modreloader jbr` 诊断 + [JBR 玩家指南](features/jbr-support.md)；**无焦点 L1**（`-PnoFocus=true` dev agent，窗口不抢前台，pipeline 默认）。
入口：`/modreloader add|why|jbr`、mods/inbox 监听 + 预检 + toast/弹窗；完整命令见 [AGENTS §2](../AGENTS.md)。

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
| 分析器 | **18 个单测**；`run/mods` 9 个 → 嫔 2 / 妃 1 / 贵妃 4 / 皇后 2；**真机校准 11 个有标注 jar：10 个 tier 一致、1 个已知过估**（ZeroCore2，安全方向）；NeoForge **AT 检测**（`at_rules`/`at_targets` + 表格 `AT` 列），6 个 AT jar 规则数与游戏内独立测量逐个相同（57/21/75/94/47/1） | `cd tools/analyzer && python3 -m unittest discover -s tests -v`；`python3 tools/analyzer/calibrate.py --ground-truth tools/analyzer/calibration/ground-truth.tsv <jars>`；[analyzer-calibration.md](features/analyzer-calibration.md) |
| **A0（嫔级）** | EasyLAN：`isLoaded/container=true`、资源包 `mod/easylan` 选中重载、keyMappings 34→34 | `-PautoAdd=<EasyLAN>`；[a0-hot-add.md](features/a0-hot-add.md) |
| **A1.2（妃级）** | 4 mixin；`UUIDUtil`/`AbstractWidget` redefine + `ShareToLanScreen`/`PlayerList` 首载织入；接口合并；LAN 屏 port=25565；专用服同路径 `redefined Slot` + `hot-added` | `-PautoAdd=<lsp> -PverifyLan=true`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)、[server-hotadd.md](features/server-hotadd.md) §4 |
| **兼容性测试流水线** | 11 个 pinned jar / 15 场景（含 3 个 `late_at`），L0 `check` 绿；L1 **PASS**：`minimap`（Xaero's + JiJ XaeroLib：6 mixin config、20 类 redefine、key 34→57、HUD 25→26、两个资源包）、`mouse-gestures`、`hud`、`input`（Searchables PASS + Controlling 干净 AT）、`missing-dependency`（消息带 `searchables [1.0.1,)`）、`tooltip-at`（Jade 57 条 AT）、`minimap-journeymap`（39 条 AT）、`recipe-at`（JEI 34 条 AT）、`library`（Balm + JiJ `kuma_api`）；`input-lateat`/`tooltip-lateat`/`recipe-lateat` 见 late AT 行；`mouse-gestures-nojbr` 干净 `JBR_REQUIRED`（点名 `AbstractContainerScreen`） | `python3 tools/pipeline/run.py check/run --scenario <id> --mods-dir /tmp/modreloader-corpus`；[hotadd-test-pipeline.md](features/hotadd-test-pipeline.md) |
| **无焦点自动化** | `-PnoFocus=true`：dev-only agent 在 `Window.<init>` 的 `glfwDefaultWindowHints` 后插 `GLFW_FOCUS_ON_SHOW=false`；关 early splash；pipeline 默认 no-focus，`--visible` 逃生。实测 `[no-focus] patched` + `window focused=false` + `hud` PASS；抢焦点时 verdict=`FOCUS_STOLEN` | `./gradlew runClient -PverifyCompat=<jar> -PnoFocus=true`；`NoFocusAgentTest`；[no-focus.md](features/no-focus.md) |
| **JiJ 嵌套 mod + 资源包** | Xaero's → `xaerolib` 有 ModContainer、依赖满足、mixin 配置合并、`FMLCommonSetupEvent` 全 container 先同步再跑队列、`LoadingModList` 同步、嵌套 pack 一次加入 | 同上；`hotadd/NestedMods`、`ModListInjector`、`ClientResourcePacks.injectAll` |
| **JBR 检测 + 一键准备** | `/modreloader jbr` 打印 JVM/参数/已安装 JBR；检测覆盖游戏目录/祖辈/嵌套/`-Dmodreloader.jbr`；缺 JBR 时「自动准备 JBR」= 官方 JBR 21.0.11 下载（SHA-512 校验）→ 解压到 `gameDir/jbr-21.0.11/` → 自动带 enhanced + lateAt 重启。 | 单测 7；macOS/Windows 资产 SHA-512 与解压实测一致；**Windows 真机启动未验证**；[jbr-support.md](features/jbr-support.md) |
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
| **托管重启（A′/B）** | 失败界面「保存并重启游戏」（同 JVM）与「用 JBR 重启」（仅本次会话）：保存世界/options → **直接 `ProcessBuilder` 起 JVM**（Windows 用 `javaw`，cwd=gameDir；不再生成 `.cmd`）→ 子进程写 `started-<pid>.txt` 标记 → 旧进程看到标记才退出；argv 由 ProcessHandle 或 PowerShell/WMIC 读取（UTF-8/native 解码），JVM 参数插在主类之前，`--add-opens` 等带值选项不拆开；不碰 `mods/`。 | dev 验证：plan/spawn（macOS）；`RelaunchScriptsTest` + `JbrProvisionerTest`；**Windows/PCL2 全链路未验证** |
| **客户端启动期注册重放** | menu screens / renderers（+ 重建 dispatcher）/ 颜色 / 客户端扩展 / reload listener / tooltip 工厂 / item→model / **HUD 层（25→31，顺序不变，渲染 60 tick）** / **物品装饰（diamond 0→1）**；各事件「为什么不做」逐个写在 §7.1 | `-PverifyAddCommand=<appleskin> -PverifyGuiLayers=true`、`-PverifyAddCommand=<clientfixture> -PverifyItemDecorators=true`；[client-registration-audit.md](features/client-registration-audit.md) §7.1 |
| **capability 重放** | 众星捧月热加后五项全 present（桶 item/fluid、坩埚 fluid、机器 item/energy）；`-PskipCapabilityReplay=true` 时五项全 MISSING（反证）；`item 16/48、fluid 24/27、energy 4/4` | `-PverifyCapabilities=<jar>`；[dogfood-gameplay.md](features/dogfood-gameplay.md) §1.1 |
| **late AT（实验性）** | JBR 21 + flag：stock 拒绝改访问标志，JBR **接受** `private→public` / 去 `final`；Jade **57 规则/39 目标全应用**、`ok=true`/`isLoaded=true`、5 个插件 bootstrap；JEI 34/16、`hot-added [mezz_config, jei]`；Controlling 1/1（先加 Searchables）；JourneyMap 21/26 应用后被 `CLIENT_REGISTRATION`（shader 事件）挡住，不是 AT；`-PfailAfterModList` 下 39/39 `rollback ... ok` | `-PlateAt=true`；`LateAccessTransformers`；[late-at.md](features/late-at.md) |
| **AT 晚注入判定（历史）** | 295 条规则 / 151 目标类；**旧“类加载时晚 AT”0/6** 仍成立，但**不能推出“AT 不能晚到”**：JBR 可以对已加载类改访问标志（见上一行） | `-PverifyAtSpike=<jars>`；[matrix](features/hotadd-compatibility-matrix.md) §8 / §8.1 |
| **失败回滚** | post-ModList / post-pack 恢复 ModList/module/config/类/资源包（post-ModList 重试成功）；post-construct 游戏总线监听器按实例摘除（`Capabilities.EVENT_BUS_ROLLBACK`） | `-PverifyRollback=true -PfailAfterModList=true` / `-PfailAfterPack=true` / `-PfailAfterConstruct=true` |
| **工程底座** | `capabilities` **10 组**全 ok（`…event_bus_rollback=ok`，server `resource_packs=n/a`）；无 `libs/` 可 `clean build`；**42 个无游戏 Java 单测**；i18n 两语言各 150 key | `./gradlew build`/`test`；[tech-debt.md](tech-debt.md) |

## 验证状态

### 已验证

- **late AT（实验性）**：JBR 21 + `-XX:+AllowEnhancedClassRedefinition` 下，`LateAccessTransformers` 捕获已加载类的当前字节、只改 AT 的 access/final、在 mod 类加载前 redefine。实测 Jade（57 规则/39 目标全应用，插件 bootstrap 全部成功）、JEI（34/34，MezzConfig 嵌套 container）、Controlling（1/1）；`-PfailAfterModList` 下 Jade 39 个目标全部 `rollback ... ok`，无 `ROLLBACK_PARTIAL`。合成 JVM 测试：stock 拒绝、JBR 接受 `private→public` + 去 final，调用方成功。
- **无焦点 L1**：`-PnoFocus=true` 启动（JBR 21，macOS）→ `[no-focus] agent active`、`[no-focus] patched com/mojang/blaze3d/platform/Window`、`[CompatCheck] window focused=false (noFocus=true)`，`hud` 场景仍 `ok=true`/`DONE 1/1`/PASS。纯单测 `NoFocusAgentTest` 用合成字节码断言 hint 插在 `glfwDefaultWindowHints` 后（不需要游戏）。
- **热加测试流水线 v0**：`fetch` 只按 pinned version 下载并 pin sha256；`check` 校验 required 依赖闭包/顺序/AT 与 expectation；`run` 解析 `[CompatCheck]` 写 JSON/MD。**L1 实测 PASS**：Xaero's Minimap 26.6.0（JiJ XaeroLib container/mixin/HUD/按键/两个资源包）、Mouse Tweaks（JBR；`-PnoJbr=true` 干净 `JBR_REQUIRED`）、Searchables→Controlling（前置满足但 Controlling AT）、单独 Controlling（`MISSING_DEPENDENCY`）、AppleSkin、Jade（AT）、JourneyMap（JiJ 前置 + 39 条 AT）。report 在 `tools/pipeline/reports/`。
- **分析器**：合成 jar、公开 7-jar 语料、Ex Nihilo 1.5.0、`run/mods` 都能跑；JSON/Markdown 输出；18 个单测；首轮真机校准。**2026-11-17 修两个假阴性**：hybrid jar 优先 NeoForge 元数据（JourneyMap/XaeroPlus 的 `fabric.mod.json` wrongloader 哨兵）、`[[mods]]` 后的子表不再覆盖 list。

- **自附加 + 类重定义**（JDK 17，macOS arm64）：不依赖 `-javaagent` 拿到 `Instrumentation`；方法体替换 0.7–5 ms；新增方法被 JVM 拒绝。见 [PoC](../experiments/self-attach-poc/README.md)。
- **热加四档中的三档**：嫔级 / 妃级 / 贵妃级 add-only，含客户端资源包与创造栏、capability、datapack、WORLDGEN（新世界）。
- **双端化 + 服务端**：dedicated server 加载/热加/命令重放/payload E1-E3、`runServer` 接 JBR、服务端+客户端同时妃级。
- **真实 dogfood（众星捧月 1.5.0）**：注册层 + datapack + 玩法 + 五个客户端 bug 全 PASS，另四条创造栏路径回归。
- **兼容性矩阵**：baseline AT 变更前拒绝、缺依赖前置拒绝、JiJ 内嵌库抽取；旧「类加载时晚 AT」0/6，但 **JBR late AT 已让 Jade/JEI/Controlling L1 PASS**（实验性）。
- **注册审计 + 客户端重放 + capability 重放 + 失败回滚**：各有 harness；整条验收清单 2026-11-16 全跑（见 §下一步）。

### 未验证

- **EasyLAN 的真实 GUI 按钮**：`ScreenEvent.Opening` 监听已注册，但没人工点过；`keyMappings` 未增加。
- **生产 jar / 第三方启动器**：只在 dev client 测过。
- **无焦点 L2/输入/截图**：只验证了 L1 标题屏；进世界后的 `pauseOnLostFocus`、HUD 渲染、真实输入/截图没有在 no-focus 下验证，计划用 `--visible` 或显式关闭 pause。Windows/Linux 的 GLFW 行为未实测。
- **late AT 的 L2/功能/平台**：Jade/JEI 只到构造、插件 bootstrap、`isLoaded`；tooltip/配方界面/交互未验证；Mekanism/AE2/guideme/ExtremeReactors2 未跑；Windows/Linux 未测；多 mod AT 冲突、嵌套 AT、连续多次 add/失败重试未验证。**不要把它写成“AT 已解决”。**
- **`-PlateAt` 的默认 UX**：默认关闭；游戏内 `/modreloader add` 需要 JVM 参数才走 late AT，UI 预检仍按 AT 拒绝；`LateAccessTransformers` 失败时只回退到拒绝。
- **流水线 L2**：进世界后的 HUD/按键/配置/截图断言还没有；L1 的 PASS 只说明引擎路径与 `isLoaded`，不说明小地图真的画出来。
- **Xaero's World Map 的 L1 标题屏**：引擎 `ok=true`，随后它自己的 `OldFormatSupport.loadVanillaStates` 在标题屏抛 `NoSuchElementException`（`DONE` 前退出）；需 L2 进世界复测，不能写成热加失败。
- **`input-nojbr` / `minimap-addon`**：未跑；`input-nojbr` 的边界已由 Mouse Tweaks 的 JBR/noJBR 对照组覆盖，
  `minimap-addon` 先被 Xaero's World Map 的标题屏 `loadLater` 挡住（L2 场景）。
- **`/modreloader jbr` 与 JBR 能力探针**：命令编译通过但没在游戏里执行；`JbrSupport` 仍是 vendor+参数的启发式，没有真实 redefine 探针。
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
5. **Python 分析器 vs Java 运行时**：规则两套实现，需要抽成声明式配置避免漂移。**注册轴已对齐**（2026-11-16）：两边都要求「`register*` 调用（`Registry` / `*BuiltInRegistries` / `*RegisterEvent` / 任意 `*DeferredRegister`）或 `DeferredRegister` 类型引用」，Java 侧抽成可单测的 `inspect/RegistrySignal`；tier 的其它轴（native/JNI、render-core mixin）仍不一致。**2026-11-17 差分实测**（9 个 jar，运行期 tier vs 分析器）：7 一致 / 2 过估（Controlling、JourneyMap 的 render-core 策略），并抓到并修掉一个**漏估**：JourneyMap 的 mixin 条目写成 `client.Foo`（相对 `package` 的子包名），旧代码把带点的条目当全限定名 → 26 个 mixin 算成 0 → 判 `PIN`；现按 jar 内类是否存在解析（`mixins._qualify`），JourneyMap 变 `HUANGHOU`。另外 `VALIDATE` 没有 tier 门禁（只有 UI `canHotLoad` 有），所以「UI 会拒、引擎能加」存在实例（Xaero's Minimap，流水线 `ok=true`）；决策层校准见 [analyzer-calibration.md](features/analyzer-calibration.md) §7。
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
21. **客户端启动期注册事件仍是手写清单**：NeoForge 1.21.1 的 mod bus 上有一批一次性事件（44 个顶层实现 + 若干嵌套），我们只重放其中十几个。已重放：FMLClientSetup / key mappings / menu screens / renderers / 颜色 / 客户端扩展 / reload listener / tooltip 工厂 / 配方书分类 / shader / HUD 层 / 物品装饰 / 粒子 provider + item→model 快照；**未重放**：`RegisterLayerDefinitions`（模型层已 bake）、`ColorResolvers`。漏一个就是一类 bug，所以有**注册审计**（一类崩溃事件变更前拒绝），逐个结论见 [client-registration-audit.md](features/client-registration-audit.md) §7.1。2026-11-18：`RegisterRecipeBookCategoriesEvent` 已由 `client/RecipeBookCategoryReplay` 重放（夹具实测 `1 new`）；`RegisterShadersEvent` 已由 `client/ShaderReplay` 在热加资源重载完成后重放，Architectury 已 `ok=true`；JourneyMap 不再因 shader 被拒，转为 late AT final 问题（见已知问题 42）。
22. ✅ **专用服妃级热加（已修）**：`build.gradle` 原来只给 `runClient` 配 JBR，ZeroCore2 在专用服 redefine `Slot` 失败。2026-11-16 同一个 helper 用到 `runServer`，实测成功。教训：客户端能过的 mixin 操作，服务端要单独验证一次。
23. **AT：stock JVM 是硬边界；JBR late AT 是实验性可行**：AT 只在类加载时由 FML 应用；依赖它的 mod 构造时 `IllegalAccessError`。旧「晚注入」量测（0/6）只证明“类加载时应用”不可行；2026-11-17 发现 **JBR 可以 redefine 已加载类的 access/final 标志**，`hotadd/LateAccessTransformers` 在 mod 类加载前应用并保留 NeoForge/Mixin 字节。**默认仍拒绝**（`FailureKind.ACCESS_TRANSFORMER` + UI 预检；2026-11-17 修了弹窗文案缺口：`blockReason` 原来没有 AT 分支，Mekanism 显示“实验性热加”却禁用「加载」，现在按 `-Dmodreloader.lateAt=true` 开关与 `canHotLoad` 一致地报“带 access transformer，需重启”）；`-PlateAt=true` 才走实验路径。**2026-11-17 托管重启补了玩家路径**：AT 场景的「用 JBR 重启」会自动带 `-Dmodreloader.lateAt=true`、记住并预选待重试的 jar、确认屏显示实验性警告；同时修了 JVM 参数被追加在主类之后（等于程序参数）的 bug，见 [managed-relaunch.md](features/managed-relaunch.md) §3.1。**2026-11-17 Stage-0 实验后否决**（[ADR-0014](decisions/0014-no-caller-rewrite-for-at.md)，`experiments/access-rewrite-poc`）：改写调用方（静态访问器 + `MethodHandle`）在 stock JVM 上能实现 AT 的字段/方法/构造器子集，fixture 四组断言 PASS；但 **class-level（package-private 嵌套类型）无解**，而 Jade/AE2/Mekanism/JourneyMap/JEI 都带 class-level —— 按 jar 算覆盖面太小，决定**不接进运行时**；AT 模组仍走“重启”，late AT 仅保留为 JBR 实例/开发 harness 的实验开关。本机没有 JBR 时，失败屏/watcher 列表给「自动准备 JBR」（官方下载 + SHA-512 + 解压 + 重启），手动安装页只是兜底；`/modreloader jbr` 也会打印诊断。边界：JBR-only、access/final only、进程级全局、目标提前加载、无 L2/多 mod/平台验证；注册审计等仍会挡（JourneyMap 卡在 `RegisterShadersEvent`）。见 [late-at.md](features/late-at.md)。
24. **JiJ 与 coremod 的剩余边界**：带 `mods.toml` 的嵌套 **mod** 2026-11-17 起会建 `ModFile`/`ModContainer`（`hotadd/NestedMods`；Xaero's → XaeroLib 实测），嵌套资源包也一起注入；**嵌套 GAMELIBRARY（JEI 的 MezzConfig，无 mods.toml）仍没有 container**，所以 JEI 先撞 `MISSING_DEPENDENCY`；嵌套 mod 的 AT 仍拒绝、`data/` 不单独注入；**注册审计只扫外层 jar**，嵌套 mod 的启动期事件引用没有单独审计；`isObfuscated: true` 跳过。**coremod 不再是「不运行」**：热加时在独立 module layer 里加载并晚注入（jecharacters 拼音搜索已功能验证），但只覆盖 class 级 `ITransformer`（`ClassNode`/`byte[]`）——method/field 级、目标在别的 class loader 的情况只报告 skipped；真机只跑了 at-load 路径（redefine 路径未实测）。见 [matrix](features/hotadd-compatibility-matrix.md) §3.2。
25. **JBR 只对「已加载类的布局变更」是硬边界**：JBR 的 enhanced redefinition 可以给已加载类加接口/字段/方法；没有它才以 `attempted to change superclass or interfaces` 失败。**2026-11-16 收窄**：删掉了「任何 mixin 都要 JBR」的前置门禁——未加载的目标由 Mixin 在 define 时织入（免 JBR），已加载目标逐目标判定布局；只有后者在没有 JBR 时拒绝（`FailureKind.JBR_REQUIRED`，消息点名目标与具体变更）。Mouse Tweaks 属于后者（accessor 给 `AbstractContainerScreen` 加接口），在 JBR 下可热加。
26. ✅ **热加 mod 的 `RegisterCapabilitiesEvent` 从未重放（已修）**：`hotadd/CapabilityReplay` 在 registry commit 后、common setup 前只投新容器，临时复位 `CapabilityHooks.initFinished`；失败只 warn。审计把该事件移入 `covered`。剩余：`GuiLayers`/`ItemDecorations`/模型层（前两个已补）。
27. **「主菜单热加后创造栏没出现」无法复现（未定位）**：所有者在其机器上用 1.4.2 报告，本机四条路径全 PASS（页签都在第 2/2 页、182 项），1.4.2 与 1.5.0 内容一致。失败日志没拿到；若再现看 `creative tabs sorted at the title screen` 与 `hot-add ok=true`。见 [dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1.1。
28. ✅ **in-world 热加与区块发送的竞态（已修）**：根因是 `RegistryStage.commit` 在调用线程（render 线程）清 `BLOCKSTATE_TO_ID_MAP` 再 freeze，而区块序列化在服务器线程。`ServerThreadHop` 把「清 id + freeze」投到服务器线程，连跑 3 次 PASS。**剩余**：单人两端共享同一张表，窗口只是被挪走并缩小（[t4-registry-hotadd.md](features/t4-registry-hotadd.md) §11.5）。
29. **in-world 热加的资源重载与 chunk 重镶并发（一次性，未定位）**：无 assets 的客户端夹具热加时 worker 线程 `FluidSpriteCache` NPE（`Tesselating liquid`），第二次跑 PASS。缓解：无 `assets/`、无 `jarfile`、无新 reload listener 时**跳过整次资源重载**。真串行化未做。
    **2026-11-17 补**：`ClientResourcePacks.requestReload` 现在把 hot-add 的 reload 串行化（reload 在跑时只置 dirty，完成后补跑一次），并在整条链期间把 `mc.screen` 置空、最后恢复——否则旧 reload 完成触发 vanilla 的 `screen.init`，新 mod 的 ScreenEvent 会在其语言文件加载前跑（Jade 崩）。这是“遮住重入”的修法，不是把 chunk 重镶也串行化。
31. **L1 标题屏测不了“需要世界”的模组**：Xaero's World Map 在标题屏的 `loadLater` 会读旧格式状态并抛 `NoSuchElementException`（引擎已 `ok=true`）。pipeline 现在把“没到 `DONE`”判为 `INCOMPLETE`，不当作 PASS；这类模组必须走 L2（进世界）而不是 L1。
32. **无 `mods.toml` 的嵌套 GAMELIBRARY 不是 container**：`NestedMods` 只处理有 mods.toml 的嵌套 mod。JEI **实际不受影响**（它 JiJ 的 MezzConfig 0.6.10 有 mods.toml，container/依赖正常，拒绝点是 AT）；只有独立分发的 MezzConfig 0.7.1（`FMLModType: GAMELIBRARY`、无 mods.toml）这类 jar 还没有 container 语义。
    **2026-11-17**：预检/引擎现在会读 `META-INF/MANIFEST.MF` 的 `FMLModType`，对 GAMELIBRARY 返回新的 `FailureKind.LIBRARY` 与文案（“这是一个库，不是模组：热加需要 mod 容器，放进 mods/ 重启或由依赖它的模组自带”），不再误报“jar 没有声明任何 mod”。
    **2026-11-18**：嵌套的 GAMELIBRARY（即使带 `mods.toml`，如 MixinSquared）也不再当 mod 读 `ModFileInfo`；`JarInJar` 把它归为库注入 module path，并收集 manifest `MixinConfigs`。
33. **流水线 L1 的“干净拒绝”不等于“功能正常”**：`classify` 只看 `ok`/`isLoaded`/`kind`；一个 mod 可以在 `RESULT ok=true` 之后崩溃（Xaero World Map），所以 `overall_verdict` 要求 `DONE n/n` 且 exit 0，否则是 `INCOMPLETE`。
34. **失败的热加可能留下半应用状态（2026-11-18）**：JBR+lateAt 下 ModernFix 在 redefine 阶段失败（`MIXIN_CONFIG` / `ClassFormatError`），但 `Minecraft` 上的 method-level mixin 已织入；下一 tick `NoClassDefFoundError: org/embeddedt/modernfix/ModernFixClient` 直接崩溃（`crash-2026-11-18_09.39.40-client.txt`）。“失败即干净拒绝”不成立：mixin 应用先于 setup 失败，没有回滚。
    **2026-11-18 修复（已复跑）**：根因是回滚顺序——先还原类、后移除 mixin config，还原时 config 仍活跃，Mixin agent 把注入又写回去。`MixinSupport.register` 现在把 config 移除放到回滚栈最后入栈（LIFO 先执行），再还原类；`JarInJarTest`/单测覆盖辅助逻辑。复跑日志：`rollback: remove mixin config modernfix... ok` 在 `rollback: restore class net.minecraft.client.Minecraft ok` 之前，客户端不再崩，继续跑到 `DONE 2/2`。
35. **post-construct 失败仍会留下注册表内容（2026-11-18）**：JBR 下 Waystones 在 Balm 先热加成功后、构造期事件失败（`kind=EVENT`），但 `BuiltInRegistries` 已 +44 方块 / +55 物品，只能重启清理。与已知问题 18（注册表不可回滚）同源，但失败路径需要更早的校验点。
36. **late AT 不覆盖 JiJ 嵌套 jar；JiJ 嵌套读取仍有 ClassCastException（2026-11-18）**：Moonlight 的 AT 在嵌套 `codecui` 里，`-PlateAt=true` 仍以 `ACCESS_TRANSFORMER` 拒绝；Supplementaries 在 MODULE 阶段 `DefaultModFileInfo cannot be cast to ModFileInfo`（`JAR_IN_JAR`）。
    **2026-11-18 修复 JiJ 一半（已复跑）**：根因是嵌套的 MixinSquared 带 `mods.toml` 但 `FMLModType: GAMELIBRARY`，被当成 mod 去读 `ModFileInfo`。`JarInJar` 现在按 `FMLModType` 把这类 jar 归为库（只进 module path），并收集其 manifest `MixinConfigs` 交给 Mixin；`JarInJarTest` 覆盖 4 种情况。复跑：Supplementaries 越过 MODULE/JiJ，转为 `MISSING_DEPENDENCY`（Moonlight）。**嵌套 AT 仍不覆盖**（Moonlight 仍需重启/后续扩展 late AT）。
37. **自动准备 JBR / 一键重启的 Windows 全链路未验证（2026-11-18）**：下载 zip、解压、`javaw` 重启、PowerShell/WMIC 读取命令行只在 macOS 验证了资产哈希与 plan/spawn；**资产 SHA-512 与 zip/tar 解压已实测一致**；Windows 的 `javaw` 启动与 PowerShell/WMIC 读取仍未在真机跑。失败会停在具体阶段并保留游戏。
    **2026-11-18 macOS dev 端到端 PASS**：`-PverifyJbrFlow` 在 stock Temurin 下真实下载 90.44 MB → 校验 → 解压 `run/jbr-21.0.11` → `installation()` 找到 → JBR 重启 → 子进程热加 Mouse Tweaks/Balm/Cloth Config 全部 `ok=true`。**PCL2/Windows 的启动器 argv、javaw、GUI 按钮仍未覆盖**。
38. **自动下载只覆盖 5 个平台资产（2026-11-18）**：Windows x64/arm64、macOS arm64、Linux x64/arm64；Intel macOS 没有匹配的 JBR 21 资产，会明确报错，需手动/整合包预置。下载 95–200 MB，需要网络。
39. **dev client 预装 Moonlight 启动崩（2026-11-18，环境问题）**：把 `moonlight-1.21.1-3.8.3` 放进 `run/mods` 后，Moonlight 的 `ExtraCodecs` mixin 引用 `net.mehvahdjukaar.codecui.Schema`，crash report 阶段 `ClassNotFoundException`；ModList 里 Moonlight/CodecUI 都已加载。只在 dev（MixinRuntime agent）观察到，正常实例未验证；这阻塞了 Supplementaries 的完整功能复跑，不应据此判定 Supplementaries 不可用。
40. **JBR redefine 的 ClassFormatError 已定位并修复：Mixin agent 二次 transform（2026-11-18）**：GeckoLib、FerriteCore、ModernFix、Farmer's Delight 的失败不是 JBR 不能加成员（`RedefineProbe` 对同一批类加 public/private/static/final 字段、方法和接口都成功），而是 JBR 的 `redefineClasses` 会触发 Mixin agent，对**已经手动混入的字节再 transform 一次**；MixinExtras `@WrapOperation` 在第二遍注入检查失败，agent 返回错误字节 → `ClassFormatError: null`。修法：`hotadd/MixinAgentGuard` 通过 `MixinAgent.instrumentation` 找到 agent transformer，在 redefine 窗口内换成“目标类返回 null、其它类委托”的 wrapper，结束后恢复原 transformer；redefine 失败时把 original/mixed 字节 dump 到 `run/modreloader/mixin-failures/`。结果：Farmer's Delight、GeckoLib、FerriteCore、ModernFix（单独）、MouseTweaks（对照）均 `ok=true isLoaded=true`。**注意**：guard 依赖 JDK `InstrumentationImpl` 与 `MixinAgent` 的内部字段；找不到时只打印 transformer 清单并 no-op（旧错误会回来），不是可移植 API。每次 remove/add 也会改变 transformer 顺序，长期应改成常驻 wrapper + volatile 抑制集合；目前无回归。
41. **同批连续热加时同目标类冲突（2026-11-18，已修）**：ModernFix 单独热加 `ok=true`；在同批 `GeckoLib → FerriteCore → ModernFix` 中曾失败于 `redefine net.minecraft.world.entity.Entity: ClassFormatError` + `ROLLBACK_PARTIAL`。根因：手动 `transformClassBytes` 默认会应用**所有已激活 config**，把前面模组的 mixin 又套到新目标上；其中还可能包含已不匹配的注入点。修法：`MixinSupport` 在手动 transform 期间只保留**本次新增**的 active/pending config，完成后恢复全部。复跑批次 `GeckoLib→FerriteCore→ModernFix→MouseTweaks` 全部 `ok=true`、`DONE 4/4`。边界：若两个热加模组真正改同一个类，当前仍以原类字节为基底，后续需要专门测“同目标叠加”。
42. **late AT 结果被后续 mixin 重定义覆盖（2026-11-18，已修）**：JourneyMap 的 `public-f Screen init` 已被 late AT 应用（JBR 探针确认能移除 final），但 mixin 阶段用**原始类资源**重新定义 Screen，把 final 又写了回去，构造时 `JmUILegacy overrides final method Screen.init`。修法：新增 `hotadd/ClassBytesCache`，late AT 与 mixin 每次成功 redefine 后缓存实际字节，回滚写回；`captureOriginals` 优先用缓存，再退回类资源。JourneyMap 现在 `ok=true isLoaded=true`。
43. **JourneyMap 资源重载崩溃（2026-11-18，待定位）**：热加成功后（`RESULT ok=true`）客户端在资源重载期间崩：`PalettedPermutations` 大量 `Couldn't load texture minecraft:trims/color_palettes/trim_palette` + `SpriteContents$InterpolationData` mip null（`crash-2026-11-18_15.39.30-client.txt`）；单独与批次均复现。发生在 shader 重放之前，所以 `ShaderReplay` 的非空 shader 路径尚未验证（Architectury 的 shader 列表为 0）。下一步：查热加资源重载与 sprite atlas ticking 的生命周期/ pack_format 9 的 JourneyMap pack。
30. **客户端「保存→退标题→重开」harness 会卡死（环境相关，路径已删）**：两次卡在 `Minecraft.disconnect → runTick → glfwSwapBuffers`（同会话有 `GLFW: Failed to locate a primary monitor`）。不把会卡死的自动化留在仓库，存档回读改由专用服路径覆盖。

## 玩家侧原则（ADR-0015，2026-11-18 最终）

- **安装/解压是玩家动作的上限**：JBR 可产品化；玩家最多自己下载/解压，之后由运行时找到并接管（不改启动器、不填参数）。能自动时连这一步也不要求。
- **统一能力模型**：JBR 重启一次性带 `-XX` + `lateAt`；玩家只感知“用 JBR 重启后，需要 JBR 的模组就能热加”，不按模组区分参数，界面不出现开关名。
- **能力不删**：已存在 JBR → 一键重启；不存在 → 自动下载（官方 + SHA-512）或整合包预置。
- **重启 = 产品失败（切到 JBR 本身除外）**：JBR 就位后仍要求玩家重启才能加载的模组，是缺口（bug/待修），要记录并消除；「放 mods/ 重启」只能是最后手段，不能当成正常路径。
- 手填参数/手动安装只给包作者、管理员、开发者（guides/jbr.md），不是玩家路径；**删玩家侧能力不是“简化”，是逃避**（复盘）。

## 下一步

> **当前交接（2026-11-18，commit `dbb57cf`，已推送）**。下一会话从这里接：
>
> - 已完成：JBR 自动准备端到端（macOS dev PASS）；白名单 54 行；配方书与 shader 事件重放；
>   `MixinAgentGuard`、手动 transform 新 config 隔离、`ClassBytesCache`；Architectury/GeckoLib/
>   FerriteCore/Farmer's Delight/ModernFix 已转 JBR J1（candidate）。
> - JBR 计数：**J1×18 / J2×1 / BLACK×5**（最新数据见 [whitelist/mods.tsv](whitelist/mods.tsv)）。
> - 下一项优先级：
>   1. **已知问题 43**：JourneyMap 热加成功但资源重载崩（`PalettedPermutations trim_palette` /
>      `SpriteContents` mip null；单独与批次均复现）。
>   2. **ShaderReplay 非空路径**：Architectury 的 shader 列表为 0；需要一个真实注册 shader 的夹具。
>   3. 剩余 JBR BLACK：CraftTweaker、Xaero's World Map、Moonlight、Supplementaries 等（逐个看是否可修）。
>   4. PCL2/Windows 全链路仍未验证（已知问题 37）。
> - 证据：`/tmp/wl-*.log` 可能被系统清理；结论已写入 history/status。JBR 在 `run/jbr-21.0.11`（gitignored），
>   语料在 `/tmp/modreloader-corpus`。
> - 风险：`MixinAgentGuard` 依赖 JDK/Mixin 内部字段（找不到会 no-op）；`ClassBytesCache` 不覆盖
>   “eager loaded（未 redefine）”的目标。
>
> ⚠️ 本机 dev client 显示层不稳（曾连续两次卡在 `glfwSwapBuffers` / `Failed to find a primary monitor`）：
> 优先做不需要 GL 的活；必须起客户端时一次一个场景。
> 所有 harness 命令见 [AGENTS §2](../AGENTS.md)；**热加类 harness 要求 `run/mods/` 里没有众星捧月 jar**（备份 `/tmp/dogfood-runmods-backup.jar`）。

| 优先 | 任务 | 关键点 / 落点 | 验收 |
|---|---|---|---|
| **P1-3** | Git 收尾 | 代码已推送（`6569599` / `dbb57cf`），只剩观察 Actions 是否绿、需要时打 tag | Actions 绿 |
| **P0-新** | 流水线 L2 | 新 `-PverifyScenario`：标题屏热加 → 建世界 → N tick → HUD/按键/截图；先覆盖 minimap/mouse-gestures | 断言 + 截图证据 |
| **P0-新** | 语料扩到 20–30 个常用模组 | 小地图/JEI/HUD/输入/前置库；把 `check` 放进 PR | 报告 + 矩阵 |
| **P0-新** | 模组白名单实测（所有者驱动） | **2026-11-18 扩样后 54 行**：stock T1×7 / BLACK×23；**JBR J1×18 / J2×1 / BLACK×5**。新增 14 个常用模组全部入表；ModernFix、GeckoLib、FerriteCore、Farmer's Delight、Architectury 已由 harness 转 J1（candidate），JourneyMap J2（资源重载崩溃，见已知问题 43）。见 [whitelist/mods.tsv](whitelist/mods.tsv) 与视图 [whitelist/README.md](whitelist/README.md)；规则 [mod-whitelist.md](features/mod-whitelist.md) | 所有者复测后 candidate → verified |
| **P0-新** | ✅ Jade J2 崩溃（已修） | 根因：旧 reload 完成时 vanilla 会 re-init 当前 Screen，而新 mod 的 ScreenEvent 在语言文件加载前触发 → Jade 断言缺翻译。修法：`ClientResourcePacks.requestReload` 串行化 reload（跑着就排队），链期间隐藏 Screen、最后一次 reload 后恢复；4-jar 复跑 0 崩溃、屏幕恢复 TitleScreen | owner 实例复验后 candidate → verified |
| **P0-新** | Xaero's World Map：config channel 注册太晚 | JBR 下 `kind=CONSTRUCT`（`Attempted to register a config channel too late` + `XaeroWorldMapCore` CNFE）；可能是运行时可修缺口 | 修或确认只能重启 |
| **P0-新** | late AT 硬化 | L2 功能（Jade tooltip/JEI 界面）、Mekanism/AE2、多 mod AT 冲突、嵌套 AT、Windows/Linux、默认 UX | [late-at.md](features/late-at.md) |
| **P0-新** | JourneyMap 资源重载崩溃 | 已知问题 43：`PalettedPermutations trim_palette` / `SpriteContents` mip null；先查热加 reload 与 sprite atlas 生命周期、`pack_format: 9` 的 pack | 单独热加后不再崩 |
| **P0-新** | ShaderReplay 非空路径 | 给客户端夹具加一个真实 shader（复用原版 shader JSON 或最小自定义），断言 `shaders replayed: 1 new` | 夹具 PASS |
| **P0-新** | 消除“JBR 后仍需重启”的缺口 | 把 JBR BLACK / 只能重启的模组（Xaero's World Map、CraftTweaker、ModernFix 半应用等）列成缺口逐个修；重启不作为可接受结果（JBR 切换除外）。**2026-11-18 已修并复跑**：ModernFix 半应用（回滚顺序）、Supplementaries JiJ ClassCast（越过 JiJ，转 MISSING_DEPENDENCY）、配方书分类（夹具 `1 new`）、Mixin agent 二次 transform（`MixinAgentGuard`）+ 手动 transform 只保留新 config + `ClassBytesCache` → Farmer's Delight / GeckoLib / FerriteCore / ModernFix / Architectury 转 J1；JourneyMap 从 shader 拒绝转为 J2（资源重载崩溃，见 43）；嵌套 AT（Moonlight）仍待处理 | 缺口清零或写明结构性不可行 |
| **P0-文案** | ✅ 玩家文案去参数名（2026-11-18） | 玩家界面不再出现 `lateAt`/`-XX`/`-D`：`preflight.access_transformer`、`gui.failure.jbr_late_at_hint`、`relaunch.jbr.late_at_warning`、`jbr_setup.body`、`toast.resume.late_at`、`jbr_guide.body` 等 14 个 key 改成“用 JBR 重启一次/自动准备”；`gui.env` 去掉 late AT 开关显示；`JbrSupport.hint()/diagnose()` 不再教玩家加参数；AT 失败消息同步去掉参数名 | `LangKeysTest.playerCopyDoesNotExposeJvmFlags` + `./gradlew test` / `build` PASS |
| **P1-新** | JBR 端到端 + 探针 | ✅ **2026-11-18 macOS dev 端到端 PASS**（`-PverifyJbrFlow`）：stock Temurin → 真实下载 90.44 MB / SHA-512 / 解压到 `run/jbr-21.0.11` → `JbrSupport.installation()` 找到 → JBR 重启 → Mouse Tweaks/Balm/Cloth Config 全部 `ok=true isLoaded=true`。剩余：Windows/PCL2 真机、GUI 按钮点击、真实 redefine 探针、「装进 mods/ 并重启」 | 见 [jbr-support.md](features/jbr-support.md) §6 |
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

- **2026-11-18（JBR 自动准备 + 复盘）**：`JbrProvisioner`（官方 JBR 21.0.11 / 5 平台 / SHA-512 / zip+tar.gz）与 `JbrSetupScreen`（一次点击 → 下载 → 用新 JBR 重启）；失败界面/监听列表在缺 JBR 时改为「自动准备 JBR」。同时修复 Windows argv（PowerShell/WMIC）、`.cmd` 编码、`--add-opens` 参数位置、子进程标记误判；复盘见 [retro/2026-11-18-jbr-saga.md](retro/2026-11-18-jbr-saga.md)，协作约定进 AGENTS §7；所有者补充两条产品边界：安装/解压是玩家动作上限、JBR 重启统一带两个开关（玩家不感知参数）。**Windows 真机启动未验证（资产/解压已验）。**

- **2026-11-17（JBR 使用指引 + 本机检查脚本）**：新增 [guides/jbr.md](guides/jbr.md)（下载/分系统安装/PCL2-HMCL-Prism-官方启动器/参数/验证/故障表/服务端/撤销/FAQ）与 `tools/jbr_check.py`（只读找本机 JBR 21、检查指定 `bin/java`，不启动游戏）；README/AGENTS/JBR 技术文档互链。
- **2026-11-17（late AT：AT 不再是绝对硬边界）**：合成测试证明 stock 拒绝、JBR 接受改访问标志；新增 `hotadd/LateAccessTransformers`（捕获当前字节、ASM 只改 access/final、加载前 redefine、逐目标回滚）与 `-PlateAt=true`；修正 `AccessTransformerRules` 的行尾注释解析。实测 Jade 57/57 规则、39/39 目标 → `ok=true`；JEI 34/34 → `hot-added [mezz_config, jei]`；Controlling 1/1；JourneyMap 21/26 后被注册审计挡住；Jade `-PfailAfterModList` 回滚 39/39 ok。仍然是 JBR-only、opt-in、L1。
- **2026-11-17（无焦点 L1）**：`src/noFocus` dev JVM agent 在 `Window.<init>` 的 `glfwDefaultWindowHints()` 后插 `GLFW_FOCUS_ON_SHOW=false`，`-PnoFocus=true` 时由 `runClient` 加载并关 early splash；pipeline `run` 默认 no-focus，`--visible` 逃生；`CompatCheck` 打印 `window focused=`，pipeline 在请求 no-focus 却 `focused=true` 时判 `FOCUS_STOLEN`。证据：`hud` → `focused=false`、PASS；单测 `NoFocusAgentTest`。
- **2026-11-17（测试流水线 + 嵌套 mod/资源包 + 小地图/鼠标手势/前置 L1）**：`tools/pipeline` v0（pinned corpus、required 依赖闭包/顺序、`-PverifyCompat` 报告、run lock）；`hotadd/NestedMods` 为 JiJ 嵌套 mod 建 container，`ModListInjector` 同步 `LoadingModList`，`ClientResourcePacks.injectAll` 一次注入外层+嵌套资源包；`FMLCommonSetupEvent` 改为全 container 先同步再跑队列。L1 PASS：`minimap`（Xaero's + XaeroLib）、`mouse-gestures`/`-nojbr`、`input`、`missing-dependency`、`tooltip-at`、`minimap-journeymap`、`recipe-at`、`hud`、`library`（Balm + kuma_api）。证据在各 `tools/pipeline/reports/*/report.json`。
- **2026-11-17（分析器两个假阴性）**：hybrid jar 优先 NeoForge 元数据；`[[mods]]` 后的子表挂到最后一个元素。证据：JourneyMap `journeymap`（不是 wrongloader）、XaeroPlus `xaeroplus`（不是 unknown）、18 个单测。
- **2026-11-17（JBR 玩家指南/诊断）**：`/modreloader jbr`、`-PjbrJava=`、[jbr-support.md](features/jbr-support.md)（决策树/启动器步骤/故障表/优先级）；命令仅编译通过，未真机执行。

- **2026-11-16（粒子 provider 重放：Balm 的假拒绝）**：`RegisterParticleProvidersEvent` 从 `crash` 移入 `covered`——新增 `ClientRegistrationReplay#replayParticleProviders`，按**新容器定向重放**（不是全局 post；实测 Balm 把 listener 挂在消费方 mod 的 bus 上，定向重放才命中）。证据：`-PverifyCompat=/tmp/balm.jar` → `particle providers replayed: 0 new, 109 total`、`isLoaded=true`、`RESULT ok=true`（Balm 是库，自己不注册粒子，但消费方热加时它的 registrar 会走这条路）；审计计数 71 covered / 1 crash。
- **2026-11-16（tier 注册信号修正：贵妃误判）**：`ModInspector` 原来只要 jar 里出现 `Registry`/`Registries`/`BuiltInRegistries`/`DeferredRegister`/`NeoForgeRegistries`/`RegisterEvent` 任一类名就算「贵妃」，读取注册表（tooltip/GUI/配方展示）也被算成内容。改为 `inspect/RegistrySignal`：只有 `register*` 调用（`Registry` / `*BuiltInRegistries` / `*RegisterEvent` / 任意 `*DeferredRegister`）或 `DeferredRegister` 类型引用才算。实测 9 个真实 jar：Jade 贵妃→妃（0 次注册调用），MekanismGenerators 嫔→贵妃（它自己的 `BlockDeferredRegister#register` 以前漏了），其余不变；与 analyzer 一致，5 个单测钉住「读≠注册」。
- **2026-11-16（late coremod + JBR 收窄）**：新增 `hotadd/LateCoremod`——扫外层 + `META-INF/jarjar` 里的 `ICoreMod` 服务，在独立 module layer（先于 module 注入创建，避免同包解析冲突）里加载，已加载目标 `redefineClasses`、未加载目标用 `ClassFileTransformer` 在 define 时变换，失败可回滚。证据：`-PverifyPinyin` → `RESULT: PASS`（4 个目标变换、80 deferred、`matches("tieding","铁锭")` false→true）。同时删掉「有 mixin 就必须 JBR」的门禁：JBR 只对已加载类的布局变更必需。证据：Mouse Tweaks JBR 下 `ok=true`、`-PnoJbr=true` 下点名 `AbstractContainerScreen` 干净拒绝；mixin fixture 未加载目标免 JBR PASS。
- **2026-11-16（服务端+客户端同时妃级：P2-4 收口）**：`-PmpFeiServer` + `-PverifyMpFei` 两个 harness，ZeroCore2 两侧各自热加（服务端 `redefined Slot`、客户端 `Slot/GuiGraphics/ModelBakery`），两边 `ok=true`、玩家一直在线 → 双方 `RESULT: PASS`。至此 P2-4 四项全部有 harness。
- **2026-11-16（构造后失败回滚：游戏总线监听器按实例摘除）**：先量出 38→41 的泄漏，再加 `hotadd/EventBusSnapshot`（快照 owner→监听器实例，回滚按实例摘除 + 修剪 owner 表，`Capabilities.EVENT_BUS_ROLLBACK` 守卫）；修后 38→38（移除 2 个），状态检查全过。边界：post-construct 之后重试仍失败（FML `ModLoadingException`）。
- **2026-11-16（从 P0-2 到 P2-4 的一整轮）**：HUD 层/物品装饰重放 + 首个客户端夹具、AT 晚注入判死、WORLDGEN 改写为「需新世界」、datapack 删除不可行、`/modreloader why`、分析器真机校准 + AT 维度、解掉 `command↔client` 循环。每项证据见 [history.md](history.md)。
