# 项目状态

> **唯一状态源。** 每次会话先读这里；改状态只改这里，不要在各处复制。过程记录见 [history.md](history.md)。
> 最后更新：2026-11-18

## 一句话

只读分析器 v0 可用；热加引擎在 NeoForge 1.21.1 dev 环境验证到四档里的前三档——**嫔级**（无 mixin/注册表）、
**妃级**（mixin + 布局变更；JBR 只在「改已加载类的布局」时需要）、**贵妃级 add-only**（方块/物品 + 资源包/创造栏 + datapack + capability），
含真实 dogfood 众星捧月 1.5.0；皇后级只提示重启。热加 jar 的 **coremod（`ICoreMod`）会晚注入**（jecharacters 拼音搜索已功能验证）。
**AT**：stock 是硬边界；JBR late AT **只覆盖成员级 access/final**（Jade 57/57、JEI 34/34、Controlling 1/1 L1 PASS）；
**类级 `public-f`（去类的 final）不可用**——JBR redefine 后该类的构造返回 null，再来一次 redefine SIGSEGV，runtime 改字节前就拒绝
（JourneyMap 因 `ComparableNativeImage extends NativeImage` 只能重启，见已知问题 43/44）。
其余硬边界：一次性启动事件、删除/更新（datapack 删除实测不可能）、软重启未开始；世界生成注册表只在「标题屏热加 + 新建世界」可用。
失败回滚覆盖 post-ModList / post-pack / post-construct 监听器；构造器的其它副作用与 post-construct 之后的重试仍不行。
**2026-11-18 补**：`RegisterShadersEvent` 的常规路径由热加资源重载自己完成（`GameRenderer.reloadShaders` 给所有容器 fire），
`ShaderReplay` 只在没触发重载时兜底；兼容性流水线 v0（`tools/pipeline`）与无焦点 L1（`-PnoFocus=true`）可用。
入口：`/modreloader add|why|jbr`、mods/inbox 监听 + 预检 + toast/弹窗；命令见 [AGENTS §2](../AGENTS.md)。

## 当前目标

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| 分析器 | Fabric + NeoForge，语料与校准锁 1.21.1 |
| 运行时首发 | NeoForge 1.21.1 |
| 首个 dogfood | `../Ex-Nihilo-Satellitium`（贵妃级内容模组） |
| 身份 | **runtime，不是 mod**：热加载第三方模组，不替代它们的功能（ADR-0005） |
| 产品锚点 | **客户端工具模组与内容/贵妃级模组同优先级**（2026-11-18 所有者）；LAN 是第一个场景（[use-cases.md](use-cases.md)） |
| 论文 | 短期不做（ADR-0004） |

原因见 [ADR-0001](decisions/0001-target-1.21.1-neoforge.md)：作者自有项目是 NeoForge 1.21.1；其 dev 整合包 9 个模组里 6 个是贵妃/皇后级。

## 表尺（可复现）

| 能力 | 关键结果 | 命令 / 来源 |
|---|---|---|
| 分析器 | **18 单测**；`run/mods` 9 → 嫔 2 / 妃 1 / 贵妃 4 / 皇后 2；真机校准 11 个标注 jar：10 一致 / 1 已知过估（ZeroCore2，安全方向）；AT 检测 6 个 jar 与游戏内一致（57/21/75/94/47/1） | `cd tools/analyzer && python3 -m unittest discover -s tests -v`；[analyzer-calibration.md](features/analyzer-calibration.md) |
| **A0（嫔级）** | EasyLAN：`isLoaded/container=true`、`mod/easylan` 选中重载、keyMappings 34→34 | `-PautoAdd=<EasyLAN>`；[a0-hot-add.md](features/a0-hot-add.md) |
| **A1.2（妃级）** | 4 mixin；`UUIDUtil`/`AbstractWidget` redefine + 首载织入；接口合并；LAN 屏 port=25565；专用服 `redefined Slot` | `-PautoAdd=<lsp> -PverifyLan=true`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) |
| **兼容性流水线** | 11 pinned jar / 15 场景（3 个 `late_at`），L0 `check` 绿；L1 PASS（PASS = 复现了场景期望，含“按预期拒绝”）：minimap（JiJ XaeroLib）/ mouse-gestures / hud / input / tooltip-at / recipe-at / library / missing-dependency（拒绝）/ minimap-journeymap（拒绝）/ `mouse-gestures-nojbr`（干净 `JBR_REQUIRED`） | `python3 tools/pipeline/run.py check/run --scenario <id> --mods-dir /tmp/modreloader-corpus`；[hotadd-test-pipeline.md](features/hotadd-test-pipeline.md) |
| **无焦点自动化** | `Window.<init>` 后插 `GLFW_FOCUS_ON_SHOW=false`；实测 `focused=false` + `hud` PASS；抢焦点时 `FOCUS_STOLEN` | `-PnoFocus=true`；`NoFocusAgentTest`；[no-focus.md](features/no-focus.md) |
| **JiJ 嵌套 mod + 资源包** | Xaero's → `xaerolib` 有 container、依赖满足、mixin 合并、嵌套 pack 一次加入；`FMLCommonSetupEvent` 全 container 先同步；**2026-11-18 递归 3 层**（Supplementaries → `mixinsquared-forge` → `MixinSquared`，模块与 manifest mixin config 都收齐） | `hotadd/NestedMods`、`ClientResourcePacks.injectAll`；[hotadd-test-pipeline.md](features/hotadd-test-pipeline.md) |
| **JBR 环境（2026-11-18 收口）** | 菜单左上角三态标识（✓ 绿 / ✓ 黄「增强未启用」/ ✗ 红）；单一动作「为热加载增强环境做准备」：复用已装/运行中的 JBR，缺失才官方下载（SHA-512）→ 解压到 `gameDir/jbr-21.0.11` → 自动重启带齐 enhanced + lateAt；`/modreloader jbr` 打印诊断。**Windows 真机测试中（所有者）**；参数只能启动时给，每会话最多一次自动重启（问题 55） | 单测 9；[jbr-support.md](features/jbr-support.md) |
| **late coremod** | jecharacters 在独立 module layer 加载；变换 4 目标（含原版 `SearchTree`），80 deferred；`matches("tieding","铁锭")` false→true | `-PverifyPinyin`（JEI 需在 `run/mods`）；[matrix](features/hotadd-compatibility-matrix.md) §3.2 |
| **JBR 边界收窄** | 只有「已加载类的布局变更」要 JBR：Mouse Tweaks JBR 下 `ok=true`；`-PnoJbr=true` 干净 `JBR_REQUIRED`；未加载目标免 JBR（fixture PASS） | `-PverifyCompat=<MouseTweaks>`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) §10.4 |
| **A1 产品化四项** | 第二个妃级模组同会话 PASS；构造后监听器回滚 38→38（2 removed）；服务端+客户端同时妃级连接存活；专用服存档回读 | `-PverifyAddCommand2` / `-PfailAfterConstruct` / `-PmpFeiServer`；[a1](features/a1-mixin-hotadd.md) §10 |
| **命令路径** | `/modreloader add` 返回 1；专用服短名 + TAB + `scan` 行尾 `→ add`；`why` 只读诊断 | `-PverifyAddCommand`、`-PserverAddCommand`、`-PserverWhy`；[server-hotadd.md](features/server-hotadd.md) §3/§5 |
| **专用服热加** | 命令 93→94、tick 到达、payload E1/E2/E3（含跨进程）、dist 门禁 + 回滚、`resource_packs=n/a` | [server-hotadd.md](features/server-hotadd.md) |
| **GUI / 监听** | EasyLAN 检测→加载、坏 jar 失败屏、贵妃级实验加载、LSP 回滚屏、ignore→restore、世界内 toast、删除→拷回 | [gui-mod-watcher.md](features/gui-mod-watcher.md) |
| **贵妃级 add-only** | blocks 1060→1061 / items 1333→1334 / states +1、stoneId 稳定；放置存盘 + 重启读档；创造栏 `rebuilt 14 tab(s)` | [t4-registry-hotadd.md](features/t4-registry-hotadd.md) |
| **自定义注册表** | `NewRegistryEvent` 重放：构造期窗口 + 只对新容器 fire + `fill()`；夹具 `registryfixture:trades` `custom registry RESULT: PASS`；Moonlight 建 3 个注册表、commit 90→92、`ok=true` | `-PverifyCompat=<jar> -PverifyCustomRegistry=<id>`；[research-newregistry-hotadd.md](research-newregistry-hotadd.md) |
| **datapack 热加载** | dogfood recipes 0→164 / loot 0→75、真实合成与掉落；标题屏 + 新世界可用维度；**删除/更新实测不可行** | [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §9/§10；`-PverifyDatapack`、`-PverifyWorldgen` |
| **dogfood 玩法** | 七表非空；锤/筛/合成命中；`beRenderers 6/6`、图标 `181/181`；创造栏第 2/2 页、182 项 | [dogfood-gameplay.md](features/dogfood-gameplay.md) |
| **多人** | 相反顺序热加 → `ids match ... (29475 lines)`；未装 jar 的客户端先收通知再被 datapack 同步踢；ack 落日志 | [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §9/§10 |
| **注册审计**（[ADR-0012](decisions/0012-registration-audit-severity.md)） | 16 jar / 129 引用：71 covered / 24 recurring / 6 datagen / 16 silent / 10 behavior / **1 crash**（Mekanism shader）/ 1 base；crash 在 module 注入前拒绝 | `-PverifyRegAudit=<jars>`；[client-registration-audit.md](features/client-registration-audit.md) §5 |
| **tier 注册信号** | 按「注册调用 / `*DeferredRegister` 引用」：Jade 贵妃→妃、mekanismgenerators 嫔→贵妃；与 analyzer 一致 | `RegistrySignalTest`（5 单测） |
| **托管重启（A′/B）** | 「保存并重启」与「用 JBR 重启」：`ProcessBuilder` 直接起 JVM（Windows `javaw`）、子进程标记、argv 用 ProcessHandle/PowerShell；**Windows/PCL2 未验证** | `RelaunchScriptsTest`、`JbrProvisionerTest` |
| **客户端注册重放** | menu screens / renderers（+dispatcher）/ 颜色 / 扩展 / reload listener / tooltip / item→model / HUD 层 25→31 / 物品装饰 diamond 0→1 / 配方书 `1 new` / **层定义 213→234（staged）**；逐个结论见 §7.1 | `-PverifyAddCommand=<clientfixture> -PverifyGuiLayers=true -PverifyItemDecorators=true`；[client-registration-audit.md](features/client-registration-audit.md) §7.1 |
| **shader 重放** | 热加资源重载本身 fire `RegisterShadersEvent`（所有容器）；夹具 `clientfixture:marker` 实测 `shaders 56 -> 57`，只注册一次；`ShaderReplay` 仅无 reload 时兜底 | `-PverifyCompat=<clientfixture> -PverifyShaderCount=1 -PverifyCompatSettle=240`；[client-registration-audit.md](features/client-registration-audit.md) |
| **capability 重放** | 五项全 present；`-PskipCapabilityReplay=true` 时五项 MISSING（反证） | `-PverifyCapabilities=<jar>`；[dogfood-gameplay.md](features/dogfood-gameplay.md) §1.1 |
| **late AT（实验性）** | Jade 57/39、JEI 34/16、Controlling 1/1；Jade 回滚 39/39 ok；**类级 `public-f` 拒绝**（`stage=VALIDATE kind=ACCESS_TRANSFORMER`） | `-PlateAt=true`；[late-at.md](features/late-at.md) |
| **失败回滚** | post-ModList / post-pack 恢复 ModList/module/config/类/资源包；post-construct 监听器按实例摘除 | `-PverifyRollback` / `-PfailAfterModList` / `-PfailAfterPack` / `-PfailAfterConstruct` |
| **工程底座** | `capabilities` 10 组全 ok；无 `libs/` 可 `clean build`；**93 个无游戏 Java 单测**；i18n 两语言**各 197 key** | `./gradlew build`/`test`；[tech-debt.md](tech-debt.md) |

## 验证状态

### 已验证

- **late AT + 类级 `-f` 边界**：Jade/JEI/Controlling L1 PASS、Jade 回滚 39/39；独立 JBR 21.0.11 探针证明去**类** final 后
  `NativeImage.read` 与合成 `final class` 的构造全部返回 null，第二次 redefine SIGSEGV；`LateAccessTransformers` 改字节前拒绝，
  JourneyMap 实跑 `ok=false stage=VALIDATE kind=ACCESS_TRANSFORMER`（无崩溃、无残留）。细节 [late-at.md](features/late-at.md) §5.1。
- **JiJ 嵌套元数据误判（2026-11-18）**：空 AT 文件、以及 toml `modType = "LIBRARY"` 的嵌套库不再当成“有 AT 的 mod”；
  `AccessTransformerRules` 按 FML 的规则读 AT（toml `[[accessTransformers]]` 优先，否则默认文件），单测覆盖空文件/自定义路径。
  Moonlight 复跑：越过 MODULE + late AT（126 条），转为 `kind=CONSTRUCT`（自定义注册表，见 46）。
- **XaeroLib config channel 窗口（2026-11-18）**：`ConfigChannelRegistry.frozen` 在构造期临时放开、add 后恢复；
  只对引用该 registry 的 jar 生效。World Map 复跑 `ok=true isLoaded=true`（此前 `kind=CONSTRUCT`）。
  剩余：它自己的服务端初始化在 `ServerStartedEvent` 里，进世界热加后 `serverPlayerTickHandler` 为 null（问题 52）。
- **统一环境准备动作（2026-11-18）**：`JbrSetupScreen` 收口为「为热加载增强环境做准备」——已安装/运行中的 JBR 直接复用，
  缺失时下载（官方、SHA-512）并解压到 `gameDir/jbr-21.0.11`，随后自动重启并带齐 enhanced + lateAt；
  热加载菜单在未就绪时**常驻**该按钮（截图验证），失败屏与菜单使用同一文案/同一入口。
- **JBR 环境识别与菜单标识（2026-11-18）**：`JbrSupport.status()` 三态（READY / JBR_NO_ENHANCED / NOT_JBR），
  检测同时看 vendor/vm.vendor/vm.name/java.home；热加载菜单**左上角**显示彩色标识（✓ 绿 / ✓ 黄「增强重定义未启用」/ ✗ 红）。
  预检与失败文案按状态区分：已运行 JBR 只缺能力时不再说“使用 JBR”，而是“用运行时重启一次”。
  `installation()` 现在**优先复用正在运行的 JBR home**，启动器自带、扫描根之外的 JBR 也能用“用 JBR 重启”。
  验证：`-PverifyMenu=true` 截图（READY 绿）与 `-PverifyMenu=true -PnoJbr=true` 截图（JBR_NO_ENHANCED 黄）；
  `-PnoJbr=true -PverifyRelaunchPlan=true` → `canRelaunch=true`、JBR spawn PASS。
- **模型层重放（2026-11-18）**：`RegisterLayerDefinitions` 已重放并在之后重建 `EntityModelSet` roots；层与 renderer 重建按“全容器先层后渲染器”排序；
  `ClientModLoader.loading` 在 FMLClientSetup 窗口内临时为 true（`setRenderLayer` 门禁）。Supplementaries 实测 `layer definitions ... 213 -> 234`、
  `ok=true isLoaded=true`，同批 Moonlight 也 `ok=true`。
- **自定义注册表（2026-11-18）**：夹具 `registryfixture:trades`（构造期 `DeferredRegister.makeRegistry`）→ `custom registry RESULT: PASS`；Moonlight 复跑 `ok=true`（`moonlight:custom_map_data_types` / `world_saved_data_type` / `villager_trades`，blocks 1060→1061、items 1333→1335、stoneId 稳定）。实现：`CustomRegistries` + `RegistryStage` 窗口；
  顺带把 late AT 的字节捕获改成 **缓存 → 类资源 → retransform**，并把 `MixinAgentGuard` 改成常驻 wrapper（只增删目标集合）。
  仍开放：`fill()` 之后失败只能 dirty；datapack 注册表与 `ModifyRegistriesEvent` 不重放。
- **shader 重放**：夹具 `clientfixture:marker`（JSON 在夹具包里）→ 重载自己 fire 事件，`shaders 56 -> 57`、只注册一次；
  同时修掉 `ShaderReplay` 在 reload 后再补一次（同名实例被覆盖并泄漏）。
- **无焦点 L1**：`[no-focus] patched` + `focused=false` + `hud` PASS；`NoFocusAgentTest` 钉住字节码插入点。
- **流水线 v0**：pinned 下载/`check` 依赖闭包/L1 报告；L1 PASS 列表见上表；report 在 `tools/pipeline/reports/`。
- **分析器**：合成 jar、公开语料、Ex Nihilo 1.5.0、`run/mods` 可跑；JSON/Markdown；18 单测；hybrid jar 与 `[[mods]]` 子表两个假阴性已修。
- **三档热加 + 双端 + dogfood**：嫔/妃/贵妃 add-only（资源包、创造栏、capability、datapack、WORLDGEN）；专用服命令/payload；
  众星捧月注册层 + datapack + 玩法全 PASS（细节见 [history.md](history.md)）。
- **注册审计 + capability + 回滚**：各有 harness；整条验收清单 2026-11-16 全跑过一次。
- **late AT 保 AT + Jade 真机（2026-11-18，Windows/PCL2/JBR）**：architectury 真机热加 311 规则/150 类后，
  `RenderStateShard$TextureStateShard` 重定义不再丢 FML 启动期 AT（`blur` 保持 public），未再出现
  `IllegalAccessError`；Jade 15.10.6 热加成功（Font/Font$StringRenderOutput 等 5 类 redefine）。
  同一会话暴露两类边界：Jade 的 `FontMixin` 给已加载 `Font` 新增 4 个实例字段，旧实例字段为 0 而非构造器里的
  `NaN`，文字被压到约 65% 亮度（问题 56）；ftb-library 因类级 `-f`（`TextColor`）被前置拒绝，符合设计。

### 未验证

- **EasyLAN 真实 GUI 按钮**（`ScreenEvent.Opening` 未人工点过）；**生产 jar / 第三方启动器**；1.21.1 之外。
- **无焦点 L2/输入/截图**：只到标题屏；Windows/Linux 的 GLFW 行为未测。
- **late AT 的 L2/平台**：Jade tooltip / JEI 界面未验证；Mekanism/AE2 未跑；多 mod AT 冲突、嵌套 AT、Windows/Linux；`-PlateAt` 仍是 opt-in。
- **`ShaderReplay` 的兜底路径**（无 reload 时）没有夹具。
- **流水线 L2**：进世界后的 HUD/按键/截图断言还没有（L1 PASS 不说明画面正确）。
- **Xaero's World Map 的 L1**：引擎 `ok=true` 后它自己的 `loadLater` 在标题屏抛异常，需 L2；`input-nojbr` / `minimap-addon` 未跑。
- **`/modreloader jbr` 与 JBR 能力探针**：命令未在游戏里执行；`JbrSupport` 仍是启发式，没有真实 redefine 探针。- **`/modreloader gui` / `scan` / 失败重试 / 忽略持久化 / 加载后删 jar**：未自动验证或未覆盖。
- **存档与失败语义**：客户端「保存→重开同一存档」harness 已删（问题 30），存档回读只有专用服路径；datapack 失败语义无夹具。
- **机器 GUI / 手动玩法项**（桶、坩埚、堆肥、网耐久）：只有清单。
- **分析器**：Fabric 语料、置信度数值未校准；3 个语料未标注。
- **coremod 的 redefine 路径**：真机只跑 at-load；JEI 拼音用私有 `matches` 断言，未开界面输入。
- **托管重启端到端**：只验证 plan/脚本/spawn（macOS dev）；Windows/PCL2 进程树未知。

## 已知问题 / 坑

1. **静态分析仍会漏/误判**：反射/跨模组代理注册抓不到；注册轴已按「注册调用 / `*DeferredRegister` 引用」对齐（`RegistrySignalTest`）。
2. **皇后级可能过保守**：AE2 因渲染 model mixin 判皇后，是否真需完整重启未验。
3. **Fabric 生产 jar 是 intermediary 名**（`class_310`），核心类识别退化。
4. **JiJ 递归拆包（2026-11-18）**：`JarInJar` 现在递归展开到 3 层（Supplementaries → mixinsquared-forge → MixinSquared core），嵌套库进 module path 并收集 manifest `MixinConfigs`；更深的 bundle 仍不展开，**嵌套 jar 的真 AT 仍不覆盖**（见 24/36）。
5. **Python 分析器 vs Java 运行时**：注册轴已对齐，其它轴（native/JNI、render-core mixin）仍不一致；`VALIDATE` 没有 tier 门禁（UI 才有）。
6. **LAN 模组是妃级布局变更**：需要 JBR；`online-mode` 可能要重启集成服务器。
7. **A1 剩余边界**：构造器其它副作用（静态状态/全局注册/线程）不可回滚；不能 undefine module；post-construct 重试仍失败（干净回滚 + 重启）。
8. **Git 收尾一半**：`main`/`origin`/CI/tag 已建但**没 push**，CI 绿未证明。
9. **没有完整人工标注集**：只校准 11 个有真机结论的 jar；Fabric 与置信度未校准。
10. **没有删/更新路径**：已加载（尤其织入 mixin）的类无法卸载；datapack 删除实测不可能。
11. **GUI/监听初版限制**：只有 `zh_cn`/`en_us`；最多显示 7 个候选；`gui`/`scan`、失败重试未自动验证；皇后级只提示重启。
12. **MixinRuntime 是可选第三方前置**（`type="optional"` + 反射）：缺它时嫔级照常、妃级被拦；构建不需要 `libs/`。
13. **`mods.toml` 的 side 字段不可信**：静态只 warn（分不清「双端模组的客户端代码」与「启动路径就用」），兜底是 dist 门禁 + 回滚。
14. **命令重放的边界**：`RegisterCommandsEvent` 是全局事件（所有监听器再跑一遍）；payload 能补进已建立连接，但缺握手（两端必须同一 jar）、锁 1.21.1、一次性事件不支持。
15. **客户端重放命令的副作用**（与 14 同源）：集成服务器每次热加都重跑所有命令监听器；标题屏不重放。
16. **Capabilities 只查名字不查行为**：锚点匹配 ≠ 语义未变，不能代替 harness。
17. **裸 unfreeze/freeze 会移动 BlockState id**：必须先清 `BLOCKSTATE_TO_ID_MAP` 再 freeze（[research-t4-registry.md](research-t4-registry.md)）。
18. **贵妃级只到 add-only**：客户端不装 jar 的多人仍不行（被 datapack 同步踢，装回前连不回）；实体 post-register 未重放；提交后不可回滚。
19. **datapack 边界**：只覆盖 `RELOADABLE`；WORLDGEN 需新世界；存档记录 `mod/<modId>`，重启且 jar 在 `mods/` 会合并；失败文案必须强调 dirty/重启。
20. ✅ **创造页签排序非幂等（已修）**：`sortTabs()` 追加不清空 → 4→8 → 越界崩；`afterRegistryContent()` 先清 `DEFAULT_TABS`。
21. **客户端启动期注册仍是手写清单**：已重放 FMLClientSetup/key/menu/renderers/颜色/扩展/reload listener/tooltip/配方书/**shader**/HUD/物品装饰/粒子+item→model；未做 `ColorResolvers`；`RegisterLayerDefinitions` 2026-11-18 起重放（含 `EntityModelSet` roots 重建与 staged 顺序）。漏一个就是一类 bug，所以有注册审计。
22. ✅ **专用服妃级（已修）**：`runServer` 也用 JBR + enhanced；ZeroCore2 `redefined Slot` 成功。
23. **AT：stock 硬边界；JBR late AT 实验性可行**：成员级 access/final 可改；**类级 `-f` 见 44**。默认拒绝（`ACCESS_TRANSFORMER`），`-PlateAt=true` 才走实验路径；托管重启会自动带开关。ADR-0014 否决“改写调用方”。
24. **JiJ/coremod 剩余边界**：嵌套 GAMELIBRARY 无 container（独立分发的 MezzConfig 类）；嵌套 mod 的 AT 仍拒绝、`data/` 不单独注入；审计只扫外层；coremod 只覆盖 class 级 `ITransformer`，redefine 路径未实测。
25. **JBR 只对「已加载类的布局变更」必需**：数据/方法级 mixin 在 JBR 下可 redefine；未加载目标免 JBR；缺 JBR 时干净 `JBR_REQUIRED` 并点名目标。
26. ✅ **`RegisterCapabilitiesEvent` 重放（已修）**：commit 后、common setup 前只投新容器；审计移入 covered；模型层未重放。
27. **「主菜单热加后创造栏没出现」无法复现**：本机四条路径 PASS；需要那台机器的 `latest.log`（[dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1.1）。
28. ✅ **in-world 热加与区块发送竞态（已修）**：`ServerThreadHop` 把「清 id + freeze」投到服务器线程；单人两端共享同一张表，窗口只是缩小。
29. **资源重载与 chunk 重镶并发（一次性）**：无 assets 时跳过整次重载；reload 已串行化 + 链期间隐藏 Screen（Jade 修复），但 chunk 重镶未串行化。
30. **客户端「保存→退标题→重开」harness 会卡死**（`glfwSwapBuffers` / 无主显示器）：路径已删，存档回读由专用服覆盖。
31. **L1 标题屏测不了“需要世界”的模组**：Xaero World Map 的 `loadLater` 会抛；pipeline 把“没到 `DONE`”判 `INCOMPLETE`。
32. **无 `mods.toml` 的嵌套 GAMELIBRARY 没有 container 语义**（独立分发的 MezzConfig）；GAMELIBRARY manifest 现在返回 `FailureKind.LIBRARY`；带 `mods.toml` 的嵌套 GAMELIBRARY 归为库 + 收集 `MixinConfigs`。
33. **L1 的“干净拒绝”不等于“功能正常”**：`overall_verdict` 要求 `DONE n/n` 且 exit 0。
34. ✅ **失败热加留下半应用状态（已修）**：回滚改为 LIFO（先摘 mixin config 再还原类）；ModernFix 复跑不再崩。
35. **post-construct 失败仍留注册表内容**：Waystones 在 Balm 之后失败，`BuiltInRegistries` 已 +44/+55，只能重启；与 18 同源，需要更早的校验点。
36. **JiJ 嵌套 AT/元数据的误判（2026-11-18 已修）**：codecui 的 `META-INF/accesstransformer.cfg` 是**空文件**，且 `modType="LIBRARY"` 写在 toml（manifest 没有 `FMLModType`）；旧预检把“空 AT”和“没有 manifest 标记的库”都当成真 mod/真 AT。现在 AT 只认 FML 会应用且**有内容**的文件（含 toml `[[accessTransformers]]`），`isGameLibrary` 也认 toml `modType=LIBRARY`；`NestedMods` 跳过空 AT。Moonlight 因此越过 MODULE + late AT（126 条规则应用并回滚 ok），改卡在自定义注册表（见 46）。**嵌套 jar 的真 AT 仍不覆盖**（late AT 只读外层）。
37. **JBR 自动准备/一键重启的 Windows 全链路未验证**：macOS dev 端到端 PASS（真实下载/校验/解压/重启/热加）；PCL2/Windows 的 argv、`javaw`、GUI 按钮未覆盖。
38. **自动下载只覆盖 5 个平台资产**：Intel macOS 无 JBR 21 资产，需手动/预置；下载 95–200 MB。
39. **dev client 预装 Moonlight 启动崩（环境问题）**：`ExtraCodecs` 引用 `codecui.Schema` 时 CNFE；只在 dev 观察到，阻塞 Supplementaries 完整复跑。
40. **JBR redefine 的 `ClassFormatError`（已修）**：根因是 JBR 触发 Mixin agent 二次 transform；`MixinAgentGuard` 在 redefine 窗口内暂停目标类，失败 dump 字节。guard 依赖内部字段，找不到时 no-op。
41. ✅ **同批热加同目标类冲突（已修）**：手动 transform 只保留本次新增 config；`GeckoLib→FerriteCore→ModernFix→MouseTweaks` 4/4 PASS。真正改同一个类的叠加仍未测。
42. ✅ **late AT 结果被后续 mixin 覆盖（已修）**：`ClassBytesCache` 记住实际字节；`captureOriginals` 优先用缓存。
43. ✅ **JourneyMap 资源重载崩溃（已修）**：根因是 late AT 去**类** final 把 `NativeImage`/`ModelPart` 的构造打成 null；现在前置拒绝（见 44）。
44. **类级 AT 去 final 是 JBR 结构性边界**：实例构造返回 null，后续 redefine SIGSEGV；“AT 覆盖 access/final”只指**成员级**；需启动期类变换或 JBR 修复。
45. **mixin 新增实例字段不初始化已存在对象**：JourneyMap 给 `DynamicTexture` 加字段，老 `LightTexture` 上为 null，退出时 `onClose` 注入 NPE（只影响退出）。
46. ✅ **自定义注册表（`NewRegistryEvent`）已支持（2026-11-18）**：构造期临时恢复 `pendingModdedRegistries`，构造后只对新容器 fire `NewRegistryEvent` 并调用包私有 `fill()`；`RegisterEvent`/freeze/快照由现有 `RegistryStage` 动态覆盖。夹具 + Moonlight 验证；失败语义：`fill()` 之后失败只能 dirty。datapack 注册表、`ModifyRegistriesEvent` 仍不重放。见 [research-newregistry-hotadd.md](research-newregistry-hotadd.md)。
47. ✅ **late AT 对已混入的类二次 transform（2026-11-18，已修）**：Supplementaries 的 AT redefine `Entity`（Moonlight 已混入）时，Mixin agent 仍二次注入 → `ClassFormatError`。修法：`MixinAgentGuard` 改为常驻 wrapper（2026-11-18 晚已收窄为只包 Mixin agent，靠 `blanket()` 覆盖其它路径，待复验），并在每次 `redefineClasses` 期间用 `blanket()` **全局抑制**（JBR 该路径不一定传类名，按名匹配不可靠）。复跑日志：`transform guard installed (4 transformer(s) wrapped)`、Moonlight `ok=true`，Entity 不再报错。同类问题（40/41）的第三份。
48. ✅ **嵌套 `MixinSquared` bootstrap（2026-11-18，已修）**：`JarInJar` 递归拆包后，Supplementaries 的 3 个嵌套库（`mixinsquared.forge` / `MixinSquared` / `sable.companion.common`）全部进入 module path，`mixinsquared.forge.init.mixins.json` 能加载其 `MixinSquaredBootstrap`；Supplementaries 越过 MIXIN/CONSTRUCT，注册了 blocks 1061→1306、items 1335→1584。
49. ✅ **模型层/Skull 模型（2026-11-18，已修）**：`RegisterLayerDefinitions` 现在会被重放，随后调用 `EntityModelSet.onResourceManagerReload` 重建 roots（`LayerDefinitions.createRoots()` 会合并 `ClientHooks` 的静态表）；层回放与“重建 renderer”拆成两阶段、**全容器先层后渲染器**（否则嵌套容器先重建，外层层的模型还没注册）。`ItemBlockRenderTypes.setRenderLayer` 的 client-loading 门禁在 FMLClientSetup 重放窗口内临时放开（`ClientModLoader.loading`，消费者只有该门禁和 options 保存保护）。实测 Supplementaries 层 213→234、无 `No model for layer`、`ok=true`。
50. **失败回滚对 late-AT 重定义过的类可能 VerifyError（2026-11-18）**：Supplementaries 失败后回滚 `MineshaftPieces$MineShaftPiece` 报 `VerifyError: null`，`kind=ROLLBACK_PARTIAL`。回滚写回的是 late AT 之前的字节，但该类的后续加载/重定义可能已经改变了校验状态；需要专门复现，失败时至少保证“可重启的干净状态”并明确提示。
51. **无焦点 + 热加重载隐藏 screen 会撞 `PauseScreen` NPE（2026-11-18，测试环境）**：`-PnoFocus=true` 时窗口失焦，`GameRenderer.render` 调 `pauseGame`；而 `ClientResourcePacks` 在 reload 链期间把 `mc.screen` 置空，`PauseScreen.createPauseMenu` 读到 `minecraft.player == null` → NPE（crash-2026-11-18_19.18.27）。**多 add/重载场景用聚焦窗口跑**（不带 `-PnoFocus`）；无焦点只用于单 add/短重载。
55. **「已选 JBR 仍要重启」是产品失败（2026-11-18 所有者）**：玩家已经在启动器里选了 JBR，却因为缺 `-XX:+AllowEnhancedClassRedefinition` 被要求再重启一次——把实现细节（JVM 启动参数）转嫁给玩家，不是可接受的路径。该参数**只能启动时给**，运行时无法补；因此产品修法是**运行时自己负责**：检测到“JBR 但缺能力”时不弹教学屏，而是记住待加载的 jar、自动用同一 JBR 重启一次并在新进程里完成热加（玩家零决策、最多一次自动重启）；若启动器每次都不带参数，则每次会话需要一次自动重启——这仍是缺口，不能写成“已解决”。界面上的三态标识与准确文案（问题 51 同批改动）只是止血，不是修复。55. **「已选 JBR 仍要重启」是产品失败（2026-11-18 所有者）**：玩家已在启动器选了 JBR，却因缺 `-XX:+AllowEnhancedClassRedefinition` 被要求再重启——把实现细节转嫁给玩家，不可接受。**2026-11-18 收口为单一动作「为热加载增强环境做准备」**：菜单（未就绪时常驻）与失败屏都指向同一入口；已安装/正在运行的 JBR 直接复用，不再下载；缺失时官方下载 → SHA-512 → 解压到 `gameDir/jbr-21.0.11`；随后用该 JBR 自动重启并带齐 `-XX:+AllowEnhancedClassRedefinition` + `-Dmodreloader.lateAt=true`。**仍余缺口**：参数只能启动时给，启动器每次不带参数的话，每次会话最多一次自动重启；跨会话永久生效需要启动器/整合包预设（不默认改玩家配置）或 classloader 隔离/软重启（长期）。
52. **服务端一次性事件定向重放：已实现，未验证（2026-11-18）**：新增 `hotadd/ServerLifecycleReplay`，用 `EventBusSnapshot.addedSince` 取出本次 add 新增的 game-bus 监听器，只对这些监听器调用 `ServerStartingEvent`/`ServerStartedEvent`（`ListenerList` 不保证与 owner map 同一实例，故有“逐个试调用、类型不符即跳过”的兜底）；服务端实例先取 `ServerLifecycleHooks.getCurrentServer()`，为空再反射 `Minecraft#getSingleplayerServer`。**未验证**：进入世界复跑时，客户端两次卡在 xaerolib mixin 阶段（见 54），没走到重放日志。
53. **client setup 的 deferred task 早于热加资源包注入（2026-11-18）**：World Map 的 `loadLater` 在 `FMLClientSetupEvent` 的 deferred queue 里读自己的 `vanillaStatesResource`（`Minecraft.getResourceManager()`），此时热加包还没注入 → `Optional.get()` 抛 `NoSuchElementException`。启动期该顺序成立是因为包在 setup 前已注册；运行期需要“包先可用”或把这类 deferred task 放到 资源重载之后重跑。
54. **xaerolib mixin 阶段挂起（2026-11-18，待查）**：两次 `-PverifyAddCommand=<xaeroworldmap>` 都停在 `Mixin compatibility level set to JAVA_21 after xaerolib.neoforge.mixins.json` 之后，10 分钟无日志；两个客户端 JVM 都需 `kill -9`。之前同一场景能跑到 add 返回 1，所以是间歇/时序问题；下次先 `jstack` 留线程栈（重点看 Mixin/transform 锁与我们的 guard），再决定修法。
56. **Mixin 新增实例字段不初始化旧实例（2026-11-18 真机确认）**：Jade 的 `FontMixin` 给已加载的 `Font` 增加
    `glint1/glint2/glint1Strength/glint2Strength`（构造器里 `glint1/2 = NaN` 表示“无闪烁”）。热加只 redefine 类、
    不会重跑构造器，旧 `Font` 实例拿到的字段是 `0.0f`，闪烁分支常开，所有文字约 65% 亮度（真机截图：字体变暗）。
    这是问题 45（新增字段不初始化）的第二个实例；`layoutChange` 现在把字段增减也算作布局变更：无 JBR 前置拒绝，
    JBR 下照常尝试并额外打印“旧实例保持 JVM 默认值，需重启才完全正确”的警告。Jade 的变暗不是崩溃，
    实测重启并让 Jade 正常加载即可恢复；是否把 Jade 记为“需重启才正确渲染”由所有者按白名单规则决定。

## 玩家侧原则（ADR-0015，2026-11-18 最终）

- **安装/解压是玩家动作的上限**：JBR 可产品化；之后由运行时找到并接管（不改启动器、不填参数）。能自动时连这一步也不要求。
- **统一能力模型**：JBR 重启一次性带 `-XX` + `lateAt`；玩家只感知“用 JBR 重启后，需要 JBR 的模组就能热加”，界面不出现开关名。
- **能力不删**：已存在 JBR → 一键重启；不存在 → 自动下载（官方 + SHA-512）或整合包预置。
- **重启 = 产品失败（切到 JBR 本身除外）**：JBR 就位后仍要求重启的模组是缺口，要记录并消除；「放 `mods/` 重启」只能是最后手段。
- 手填参数/手动安装只给包作者、管理员、开发者（[guides/jbr.md](guides/jbr.md)），不是玩家路径。

## 下一步

> **当前交接（2026-11-18，已推送 `e75d56e`）**：当前状态：自定义注册表、JiJ 递归、模型层重放、Xaero config channel、
> JBR 环境收口（三态/标识/单一动作）都在；**所有者转 Windows 真机测试**（环境准备、MouseTweaks 布局热加）。
> JBR 计数 **J1×20 / J2×1 / BLACK×3**。下一项：① Windows 实测反馈；② 问题 54（xaerolib mixin 挂起，先 jstack）
> 与 53（client setup 早于热加包注入）；③ 问题 52/50（服务端事件重放未验证、回滚 VerifyError）；④ CraftTweaker 复跑；
> ⑤ 类级 `-f` 启动期替代与流水线 L2。
> 证据在 [history.md](history.md)；JBR 在 `run/jbr-21.0.11`（gitignored），语料 `/tmp/modreloader-corpus`。
> 风险：`MixinAgentGuard` 依赖内部字段（找不到 no-op）；`ClassBytesCache` 不覆盖 eager loaded 目标。
> ⚠️ 本机 dev client 显示层不稳：优先不需要 GL 的活，一次只起一个客户端场景；harness 命令见 [AGENTS §2](../AGENTS.md)，
> **热加类 harness 要求 `run/mods/` 里没有众星捧月 jar**。

| 优先 | 任务 | 关键点 / 落点 | 验收 |
|---|---|---|---|
| **P1-3** | Git 收尾 | 本轮及 JBR 收口已推送（`e75d56e`）；观察 Actions、需要时打 tag | Actions 绿 |
| **P0-新** | 流水线 L2 | 标题屏热加 → 建世界 → N tick → HUD/按键/截图；先 minimap/mouse-gestures | 断言 + 截图证据 |
| **P0-新** | 语料扩到 20–30 个常用模组 | 小地图/JEI/HUD/输入/前置库；`check` 进 PR | 报告 + 矩阵 |
| **P0-新** | 模组白名单实测（所有者驱动） | 54 行：stock T1×7 / BLACK×23；JBR J1×18 / BLACK×6；candidate 由所有者复测转 verified | TSV + 视图同步 |
| **P0-新** | ✅ Xaero's World Map config channel | `XaeroLibChannels` 窗口（`ConfigChannelRegistry.frozen`），仅对引用该类的 jar 生效；World Map `ok=true isLoaded=true` | 已转 J2 |
| **P0-新** | 服务端一次性事件（问题 52） | `ServerLifecycleReplay` 已实现（定向调用新增监听器 + integrated server 兜底），**未验证**：进入世界复跑卡在 54 | World Map J1 |
| **P0-新** | xaerolib mixin 阶段挂起（问题 54） | 先 `jstack` 留线程栈再杀；怀疑 Mixin/transform 锁或常驻 guard | 复跑不挂 |
| **P1-新** | 资源顺序（问题 53） | 让热加包在 client setup deferred task 之前可见，或重跑这些 task | World Map 不再 `NoSuchElement` |
| **P0-新** | 类级 AT 的启动期替代 | 启动扫描队列里的 AT，在**加载期**去 final（覆盖 `NativeImage` 这类启动即加载的类） | 夹具/真实 jar 复跑 |
| **P0-新** | ✅ 自定义注册表（`NewRegistryEvent`） | `CustomRegistries` + `RegistryStage` 窗口；夹具 `custom registry RESULT: PASS`，Moonlight `ok=true`（3 个注册表）。剩余：`fill()` 后失败 dirty、datapack 注册表/`ModifyRegistriesEvent` 不重放、CraftTweaker 未复跑 | Moonlight J1（candidate） |
| **P0-新** | ✅ late AT 对已混类二次 transform（问题 47） | 常驻 wrapper 包裹全部 transformer + 每次 redefine 的 `blanket()` 全局抑制；Moonlight 复跑 `ok=true`，Entity 不再报错 | 批次复跑 |
| **P0-新** | ✅ 嵌套 MixinSquared bootstrap（问题 48） | `JarInJar` 递归 3 层拆包；3 个嵌套库进 module path，MixinSquared config 可加载。Supplementaries 越过 MIXIN/CONSTRUCT | 批次复跑 |
| **P0-新** | ✅ 模型层/Skull 模型（问题 49） | `RegisterLayerDefinitions` 重放 + `EntityModelSet` roots 重建；全容器先层后渲染器；FMLClientSetup 窗口内放开 client-loading 门禁。Supplementaries 层 213→234、`ok=true` | 批次复跑 |
| **P1-新** | 回滚 VerifyError（问题 50） | 失败回滚 late-AT 类时 `VerifyError`；保证失败后是可重启的干净状态并明确提示 | 复跑 |
| **P0-新** | late AT 硬化 | L2 功能（Jade tooltip/JEI 界面）、Mekanism/AE2、多 mod 冲突、嵌套 AT、Windows/Linux、默认 UX | [late-at.md](features/late-at.md) |
| **P0-新** | 消除“JBR 后仍需重启”的缺口 | JBR BLACK 逐个修；**不得**把重启当正常路径（ADR-0015）。结构性的（JourneyMap 类级 AT）写明原因 | 缺口清零或写明结构性不可行 |
| **P1-新** | JBR 端到端 + 探针 | 剩余 Windows/PCL2 真机、GUI 按钮、真实 redefine 探针 | [jbr-support.md](features/jbr-support.md) §6 |
| **P3-1** | GUI/watcher Phase 3/4 | 主菜单弹窗、失败面板、多候选/依赖排序、同 mod id 冲突 | [gui-mod-watcher.md](features/gui-mod-watcher.md) |
| **P3-2** | 产品化下一步 | 托管重启端到端、嵌套 mod 容器、可分发形态、coremod target 报告 | [managed-relaunch.md](features/managed-relaunch.md) 未验证项 |
| **P3-3** | 分析器 Fabric 校准 | intermediary 名 → 核心类识别；置信度校准 | 校准表扩充 |
| **tech-debt** | `OneShotReplay` 统一出口、harness 样板抽取 | [tech-debt.md](tech-debt.md) | 构建绿 + 回归 |

## 最近变更

> 逐步过程与证据见 [history.md](history.md)；这里只留最近三条。

- **2026-11-18（late AT 保 AT，真机 PASS）**：late AT 改为优先抓活类字节（抑制 transformer 后 retransform），
  architectury 真机热加 311 规则/150 类不再触发 `TextureStateShard.blur` 的 `IllegalAccessError`；
  同批 Jade 暴露“新增字段不初始化旧实例”的变暗问题（问题 56，已加警告）。
- **2026-11-18（统一「准备热加载环境」动作）**：JBR 入口收口为一个按钮：复用已装/运行中的 JBR，缺失才下载解压到
  `gameDir/jbr-21.0.11`，重启带齐 enhanced + lateAt；菜单未就绪时常驻入口，失败屏同文案。菜单截图验证。
- **2026-11-18（JBR 识别三态 + 菜单标识）**：修“启动器已选 JBR 却被要求用 JBR 重启”；菜单左上角彩色标识，
  文案按状态区分，`installation()` 优先复用运行中的 JBR。截图与 relaunch plan 验证。
