# 项目状态

> **唯一状态源。** 每次会话先读这里；改状态只改这里，不要在各处复制。过程记录见 [history.md](history.md)。
> 最后更新：2026-11-21

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
| **JiJ 嵌套 mod + 资源包** | Xaero's → `xaerolib` 有 container、依赖满足、mixin 合并、嵌套 pack 一次加入；`FMLCommonSetupEvent` 全 container 先同步；**2026-11-18 递归 3 层**（Supplementaries → `mixinsquared-forge` → `MixinSquared`，模块与 manifest mixin config 都收齐）；**包装 jar**（无 mods.toml 的 GAMELIBRARY 外形，如 MezzConfig standalone）拆到内层 mod 热加（2026-11-21 真机 PASS：`wrapper ... hot-adding the nested mod` → `mezz_config isLoaded=true`，随后 JEI 19.71 `isLoaded=true`、键位 39→79） | `hotadd/NestedMods`、`hotadd/WrapperJar`、`inspect/WrapperJars`、`ClientResourcePacks.injectAll`；[hotadd-test-pipeline.md](features/hotadd-test-pipeline.md) |
| **JBR 环境（2026-11-18 收口）** | 菜单左上角三态标识（✓ 绿 / ✓ 黄「增强未启用」/ ✗ 红）；单一动作「为热加载增强环境做准备」：复用已装/运行中的 JBR，缺失才官方下载（SHA-512）→ 解压到 `gameDir/jbr-21.0.11` → 自动重启带齐 enhanced + lateAt；`/modreloader jbr` 打印诊断。**MixinRuntime (mr ≥ 1.0.2) 是可选扩展，不是前置**：不打包/不代下载；缺失时运行时照常启动，妃级/Mixin 能力不可用，菜单右上角红字、预检拦下。**Windows 真机测试中（所有者）**；参数只能启动时给，每会话最多一次自动重启（问题 55） | 单测 9；[jbr-support.md](features/jbr-support.md)、[guides/jbr.md](guides/jbr.md) |
| **late coremod** | jecharacters 在独立 module layer 加载；变换 4 目标（含原版 `SearchTree`），80 deferred；`matches("tieding","铁锭")` false→true | `-PverifyPinyin`（JEI 需在 `run/mods`）；[matrix](features/hotadd-compatibility-matrix.md) §3.2 |
| **JBR 边界收窄** | 只有「已加载类的布局变更」要 JBR：Mouse Tweaks JBR 下 `ok=true`；`-PnoJbr=true` 干净 `JBR_REQUIRED`；未加载目标免 JBR（fixture PASS） | `-PverifyCompat=<MouseTweaks>`；[a1-mixin-hotadd.md](features/a1-mixin-hotadd.md) §10.4 |
| **A1 产品化四项** | 第二个妃级模组同会话 PASS；构造后监听器回滚 38→38（2 removed）；服务端+客户端同时妃级连接存活；专用服存档回读 | `-PverifyAddCommand2` / `-PfailAfterConstruct` / `-PmpFeiServer`；[a1](features/a1-mixin-hotadd.md) §10 |
| **命令路径** | `/modreloader add` 返回 1；专用服短名 + TAB + `scan` 行尾 `→ add`；`why` 只读诊断 | `-PverifyAddCommand`、`-PserverAddCommand`、`-PserverWhy`；[server-hotadd.md](features/server-hotadd.md) §3/§5 |
| **专用服热加** | 命令 93→94、tick 到达、payload E1/E2/E3（含跨进程）、dist 门禁 + 回滚、`resource_packs=n/a` | [server-hotadd.md](features/server-hotadd.md) |
| **GUI / 监听** | EasyLAN 检测→加载、坏 jar 失败屏、贵妃级实验加载、LSP 回滚屏、ignore→restore、世界内 toast、删除→拷回 | [gui-mod-watcher.md](features/gui-mod-watcher.md) |
| **贵妃级 add-only** | blocks 1060→1061 / items 1333→1334 / states +1、stoneId 稳定；放置存盘 + 重启读档；创造栏 `rebuilt 14 tab(s)` | [t4-registry-hotadd.md](features/t4-registry-hotadd.md) |
| **大内容热加（Create）** | JBR+lateAt 下 ok=true；blocks 1060→1703、items 1333→2032；同批 flywheel/ponder、6 个 mixin config、约 70 类 redefine（L1，玩法未验） | pipeline `create-lateat`；[status 问题 59](status.md) |
| **自定义注册表** | `NewRegistryEvent` 重放：构造期窗口 + 只对新容器 fire + `fill()`；夹具 `registryfixture:trades` `custom registry RESULT: PASS`；Moonlight 建 3 个注册表、commit 90→92、`ok=true` | `-PverifyCompat=<jar> -PverifyCustomRegistry=<id>`；[research-newregistry-hotadd.md](research-newregistry-hotadd.md) |
| **数据包注册表** | `DataPackRegistryEvent.NewRegistry` 重放（只对新容器）→ 下一个世界/连接可见；Create 建世界后 `create:potato_projectile/type entries=25`、`PotatoCannonItem.getAmmo()` 不抛 | `-PverifyCreatePonder=<create>`；[status 问题 66](status.md) |
| **datapack 热加载** | dogfood recipes 0→164 / loot 0→75、真实合成与掉落；标题屏 + 新世界可用维度；**删除/更新实测不可行** | [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md) §9/§10；`-PverifyDatapack`、`-PverifyWorldgen` |
| **dogfood 玩法** | 七表非空；锤/筛/合成命中；`beRenderers 6/6`、图标 `181/181`；创造栏第 2/2 页、182 项 | [dogfood-gameplay.md](features/dogfood-gameplay.md) |
| **多人** | 相反顺序热加 → `ids match ... (29475 lines)`；未装 jar 的客户端先收通知再被 datapack 同步踢；ack 落日志 | [t4-registry-hotadd.md](features/t4-registry-hotadd.md) §9/§10 |
| **注册审计**（[ADR-0012](decisions/0012-registration-audit-severity.md)） | 16 jar / 129 引用：71 covered / 24 recurring / 6 datagen / 16 silent / 10 behavior / **1 crash**（Mekanism shader）/ 1 base；crash 在 module 注入前拒绝。**2026-11-19 起 `covered` 由 `inspect/ReplayCatalog` 单一维护**（层定义由 behavior 纠正为 covered；数字待下次审计刷新） | `-PverifyRegAudit=<jars>`；[client-registration-audit.md](features/client-registration-audit.md) §5 |
| **tier 注册信号** | 按「注册调用 / `*DeferredRegister` 引用」：Jade 贵妃→妃、mekanismgenerators 嫔→贵妃；与 analyzer 一致 | `RegistrySignalTest`（5 单测） |
| **托管重启（A′/B）** | 「保存并重启」与「用 JBR 重启」：`ProcessBuilder` 直接起 JVM（Windows `javaw`）、子进程标记、argv 用 ProcessHandle/PowerShell；**Windows/PCL2 未验证** | `RelaunchScriptsTest`、`JbrProvisionerTest` |
| **客户端注册重放** | menu screens / renderers（+dispatcher）/ 颜色 / 扩展 / reload listener / tooltip / item→model / HUD 层 25→31 / 物品装饰 diamond 0→1 / 配方书 `1 new` / **层定义 213→234（staged）**；逐个结论见 §7.1 | `-PverifyAddCommand=<clientfixture> -PverifyGuiLayers=true -PverifyItemDecorators=true`；[client-registration-audit.md](features/client-registration-audit.md) §7.1 |
| **shader 重放** | 热加资源重载本身 fire `RegisterShadersEvent`（所有容器）；夹具 `clientfixture:marker` 实测 `shaders 56 -> 57`，只注册一次；`ShaderReplay` 仅无 reload 时兜底 | `-PverifyCompat=<clientfixture> -PverifyShaderCount=1 -PverifyCompatSettle=240`；[client-registration-audit.md](features/client-registration-audit.md) |
| **capability 重放** | 五项全 present；`-PskipCapabilityReplay=true` 时五项 MISSING（反证） | `-PverifyCapabilities=<jar>`；[dogfood-gameplay.md](features/dogfood-gameplay.md) §1.1 |
| **late AT（实验性）** | Jade 57/39、JEI 34/16、Controlling 1/1；Jade 回滚 39/39 ok；**类级 `public-f` 拒绝**（`stage=VALIDATE kind=ACCESS_TRANSFORMER`）；**方法级 `-f` 重定义后调用点崩（问题 64）**；**嵌套 mod AT 已接入**（2026-11-19：Create/Iris 越过嵌套 AT）；**增强 JBR 下自动启用**（`-Dmodreloader.lateAt=false` 可关） | `-PlateAt=true` 仅 dev 覆盖；[late-at.md](features/late-at.md) |
| **失败回滚** | post-ModList / post-pack 恢复 ModList/module/config/类/资源包；post-construct 监听器按实例摘除 | `-PverifyRollback` / `-PfailAfterModList` / `-PfailAfterPack` / `-PfailAfterConstruct` |
| **工程底座** | `capabilities` 10 组全 ok；无 `libs/` 可 `clean build`；**118 个无游戏 Java 单测**；i18n 两语言**各 197 key** | `./gradlew build`/`test`；[tech-debt.md](tech-debt.md) |
| **ModernUI 适配（opt-in 实验）** | 真机 PASS（2026-11-21）：构造前 `Core.initialize + initOpenGL`（UI 线程据此建 recording context）→ 构造后回填 `Minecraft.font`/`fontFilterFishy` 的 `modernUI_MC$textRenderer` → shader 重建后 `TextRenderType.preloadShaders()`；每 tick/每屏兜底回填。`-Dmodreloader.modernuiAdapter=true` 才启用 | `hotadd/ModernUIAdapter`；白名单 J2 |
| **SERVER 配置** | 世界内热加：引擎立即从 `<world>/serverconfig` 加载；标题屏热加：FML 在**世界启动**时自动加载；**专用服不加载 CLIENT 配置**。实测：Create 专用服热加 `loaded 2 config(s): create-common.toml, create-server.toml` | `hotadd/ModConfigs` |
| **专用服热加（一等目标）** | 2026-11-21 本机 dev dedicated server：Create `serverAddCommand` **PASS**（registry? 否；flywheel/ponder/create，configs common+server）；Mekanism **PASS**（registry 93、blocks→1260、items→1714、AT 客户端目标跳过）；architectury **PASS**；`serverRunState`（failAfterRegistry）**PASS**（dirty + 周期告警 + 第二次 add 拒绝）；`serverWhy` **PASS**（判词按 late AT 修正后与 add 一致）；`/modreloader` 服务端要求 permission 2（控制台/OP），permission-0 源被拒（harness 实测）；**问题 65 修复后复跑 Create 仍 PASS**（6 个 config 各 `attached 4 processor listener(s)`、`verify create: isLoaded=true`） | `runServer -PserverAddCommand/-PserverRunState/-PserverWhy`；[mod-whitelist](features/mod-whitelist.md) |

## 验证状态

### 已验证

- **Create L2（问题 65 + 66，2026-11-21 dev client + 增强 JBR）**：`-PverifyCreatePonder=<create-6.0.10>` 在标题屏
  热加 Create（flywheel/ponder/create 三容器、52 个 accessor 接口、`datapack registries registered:
  [create:potato_projectile/type]`），再用真实建世界流程进入世界并断言：`create:potato_projectile/type entries=25`、
  `PotatoCannonItem.getAmmo()` 不再抛（返回 null，装饰器判空跳过）、`PonderRenderTypes.fluid()/outlineSolid()`
  正常 → `RESULT: PASS`。仍未自动打开创造栏 GUI（断言的是同一条 lookup/decorator 代码路径），PCL 真机待复验。
  同批 J2 警告：`ItemStack`（`create$CLIPBOARD_ID`）/`BlockEntityType`/`EntityType`（flywheel visualizer）
  给已加载类新增实例字段（问题 45/56 同族），活实例保持 JVM 默认值。

- **accessor/invoker 接口在热加后被 Mixin 改写（问题 65，2026-11-21 dev client，`-PnoJbr=true`）**：
  `MixinSupport` 在 prepare 前把 processor 的 4 个 coprocessor 与 `MixinInheritanceTracker` 挂到新 config
  （日志 `attached 4 processor listener(s) for on-load interface rewriting`），accessor 接口在 define 时由
  `MixinCoprocessorAccessor` 写入静态代理；夹具 `mixinfixture.mixin.accessor.AccessTargetInvoker`（桩体抛
  `AssertionError("Mixin application failed!")`）在 add 后加载并调用返回 `reached-target`，
  `-PverifyMixinFixture` → `RESULT: PASS`。已加载过的接口由 `processLoadedAccessors` 做
  transform + redefine（带回滚）。**Create 真机进世界尚未复验。**

- **专用服 Create 热加（2026-11-21，本机 dev dedicated server）**：`runServer -PserverAddCommand=<create-6.0.10>`
  在控制台路径 `RESULT: PASS`，`verify create isLoaded=true`。过程中修掉三个**服务器侧**缺口：
  `MixinAgentGuard` 把“agent 类存在但无 transformer”误判为不安全（无 MixinRuntime 时）、
  `LateAccessTransformers` 对 dist 拒绝的客户端目标整批失败（改为按 side 跳过）、
  `ModConfigs` 在服务端加载了 CLIENT 配置（改为跳过）。SERVER 配置按 `<world>/serverconfig` 正常加载。

- **包装 jar 热加 + JEI 依赖链（2026-11-21，Windows/PCL2 + 增强 JBR）**：`mezz_config-...-standalone.jar`
  （外层 `FMLModType: GAMELIBRARY`、无 mods.toml，内层才是 mod）经 `WrapperJar` 拆包后热加成功
  （日志 `wrapper mezz_config-...: hot-adding the nested mod ...`、`isLoaded=true container=true`）；
  随后 JEI 19.71.0.475 热加 `isLoaded=true`、keyMappings 39→79，required 依赖链完整。
- **Mekanism 热加复验（问题 58，2026-11-21，Windows/PCL2 + 增强 JBR）**：标题屏热加 Mekanism 10.7.19 成功
  （late AT 43 类、注册表 93 个、blocks→1260 / items→1714、层→224、GUI 层→29、物品装饰→14、payloads 58、
  `isLoaded=true container=true`），`robit` NPE 消失。随后在 `Screen.renderWithTooltip` 调用点出现
  **方法级 `-f` 引发的 JVM 元数据崩溃**（问题 64；`-Xint` 仍复现，xlog 该帧 BCI 为负值）。
  证据：`crash-reports/crash-2026-11-21_00.13.03-client.txt`、游戏目录 `xlog-exceptions.txt`。

- **harness 共用建世界（P2-15，2026-11-19）**：抽 `dev/TestWorld`，`PayloadCheck`/`AddCommandCheck` 已迁入；
  两种 harness 均 PASS（payload 端到端、clientfixture 的 GUI 层/物品装饰）。主菜单/暂停屏的 Logo 按钮在 dirty 时
  显示红色角标并更新 tooltip。
- **CI（2026-11-19 复核）**：GitHub Actions 36 次 run，最新 `4557e7b` success。
- **Mixin agent 语义探针（P1-13，2026-11-19）**：`reflect/AgentTransformers` 只读扫描 Mixin agent 自己的
  Instrumentation/transformer；`MixinAgentGuard` 据此安装，并在 late AT 与“重定义已加载目标”前检查：
  agent 存在但包不上时拒绝，避免 `ClassFormatError`（问题 40）。实测 minimap、Jade late AT 均
  `mixin agent guard installed (1 transformer(s) wrapped)`。
- **Mixin 锁序语义探针（P1-13，2026-11-19）**：`LaunchPluginMonitor.probe()` 在启动时真正解析出 issue 54
  依赖的锁对象并写日志；解析失败会让 `mixin_hotadd` 变为 missing，引擎在 transform 前拒绝（避免死锁）。
  客户端与专用服实测 `Mixin lock-order monitor: resolved`、`mixin_hotadd=ok`；同时去掉了 hotadd 里唯一的
  客户端类字面量。
- **包依赖分层（2026-11-19）**：`reflect → inspect → capability/i18n/launch/watch/command → hotadd →
  client/server → ui/diagnose` 的反向边全部消除（`UnsafeAccess`/`JvmAccess`、`MixinRuntimeApi`、
  `ModReloaderI18n`、`ServerCommands`、`launch/*` 下沉）；`ArchitectureTest` 扫描源码钉住规则。
  回归：`-PverifyRelaunchPlan` PASS、`hud` MATCH、build 绿。
- **注册表残留检测（问题 35，2026-11-19）**：构造后失败（提交前）不留内容；构造器直接注册会被
  `RegistryStage.abort` 计数并置 `residue`，RunState 记为 REGISTRY、后续 add 被拒。
  证据：`-PverifyCompat=<t4fixture> -PfailAfterConstruct -PdirectRegister=true` 报告
  `block 1060->1061, item 1333->1334`；正常 t4fixture、Balm→Waystones 均 `ok=true`。
- **late AT 回滚（问题 50，2026-11-19）**：构造后失败时 JBR 可能拒绝把 AT 类写回（内嵌类裸 `VerifyError`）；
  回滚改为尽力而为：被拒则保留加宽并 warn，`rollbackErrors` 为空。构造前失败仍正常还原。
  证据：Moonlight→Supplementaries 双 jar，`-PfailAfterModList -PfailAfterModListModId=supplementaries`
  （内嵌类回滚 ok）与 `-PfailAfterConstruct -PfailAfterConstructModId=supplementaries`（ATT 保留 + 无 ROLLBACK_PARTIAL）。
- **AE2 L2（问题 2，2026-11-20）**：`-PverifyAe2L2=<guideme>,<ae2>` 在标题屏热加两 jar、建世界、
    服务端直接放入 `ae2:guide`/`ae2:wireless_terminal`，右键后分别打开 `GuideScreen` 与 `MEStorageScreen`；
    证明菜单屏/物品模型/资源在热加后可用。AE2/GuideME 白名单 J1 verified。
- **payload 标题屏路径（2026-11-19 复核）**：`-PverifyPayload` 用重建后的 fixture PASS；诊断日志会打印
  `PAYLOAD_REGISTRATIONS`、登录后客户端/服务端连接的 `PayloadSetup` 与 `hasChannel`。
- **脏状态与拒绝（RunState，技术债 P1-12，2026-11-19）**：注册表提交、自定义注册表窗口、构造、mixin 落定、
  payload/capability/命令重放等任一不可逆操作之后再失败，`RunState` 置脏；后续 `add` 在 `VALIDATE`
  以 `FailureKind.RUNTIME_DIRTY` 拒绝，文案区分客户端/专用服。失败 `Result` 末尾附加脏原因。
  专用服由 `RunStateWarner` 按间隔持续在控制台告警（默认 60s，`-Dmodreloader.dirtyLogSeconds` 可调）。
  脏原因有稳定 i18n key（`modreloader.dirty.reason.*`），热加载菜单左上角常驻红字（JBR 标识下一行），
  失败详情里也有“脏状态原因”一行。
  证据：`-PverifyRunState=<t4fixture> -PfailAfterRegistry=true` 客户端 PASS（第二次 add `RUNTIME_DIRTY`，
  截图 `run/screenshots/runstate-dirty.png` 显示中文红字）；`-PserverRunState=... -PdirtyLogSeconds=2` 专用服 PASS
  （窗口内 2 条重复告警）；`hud`/`input` 回归无脏日志；单测 **110**。
- **一次性重放单一出口（技术债 P0-6，2026-11-19）**：`inspect/ReplayCatalog` 列 21 个会被重放的启动期事件；
  所有调用点走 `OneShotReplay.fire(container, catalog 常量, event)`，helper 运行期校验“声明名 == 实际事件类”；
  `RegistrationAudit.covered` 直接读该列表。顺带纠正层定义被审计误标为 behavior（2026-11-18 起其实已重放）。
  证据：`ReplayCatalogTest` 3 + 审计层定义/自定义注册表测试；clientfixture、minimap、moonlight-lateat 均无 mismatch 日志。
- **资源顺序（问题 53，2026-11-19）**：阶段改为 `… EVENT → PACK → CLIENT → DATAPACK`；先注册 client reload listener，
  再注入包并启动重载（`createReload` 同步换入新 manager），然后跑 `FMLClientSetupEvent` 任务、层/渲染器重放、
  payload、命令。`minimap-addon`：World Map + Minimap 同会话 `ok=true`（此前 World Map 必失败），XaeroPlus 干净拒绝 AT；
  `minimap`/`hud`/`input` MATCH；`moonlight-lateat` 两个 `ok=true`；客户端夹具 GUI 层/物品装饰 PASS。
  同会话第二个 Xaero 模组的 `PermissionRegistry`/`PermissionSystemRegistry`/`PacketHandlerRegistry` frozen 由
  `XaeroLibChannels` 一并开窗（问题 62）。
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
- **Windows 构建链（2026-11-18/19）**：Gradle 8.10（wrapper 8.8 官方源约 22KB/s，改用本机缓存）+ JBR 21 toolchain，
  `build` 绿、**93/93 单测**；`maven.neoforged.net` DNS 污染期间用官方 `neoforged.forgecdn.net` 镜像 +
  本地重建的 `minecraft-dependencies:1.21.1` POM；中文路径用 `subst X:` 绕过 Gradle worker argfile 的 GBK 解码。
- **渲染器重放顺序（2026-11-18，已修待复验）**：层/渲染器重放从 construct 之后移到注册表提交 + 生命周期之后；
  Mekanism 10.7.19 真机首次复现 `mekanism:robit` 未绑定 NPE，修复后的 jar 尚未重跑（用户时间到）。
- **监听轮询已加载过滤（2026-11-18，已修）**：轮询路径原先没有“modId 已加载”过滤；游戏运行时替换自身 jar
  被当成新候选并弹 toast，底层 jar 被替换导致 `NoClassDefFoundError`。现在两路径都跳过并要求重启（问题 57）。

### 未验证

- **EasyLAN 真实 GUI 按钮**（`ScreenEvent.Opening` 未人工点过）；**生产 jar / 第三方启动器**；1.21.1 之外。
- **无焦点 L2/输入/截图**：只到标题屏；Windows/Linux 的 GLFW 行为未测。
- **late AT 的 L2/平台**：Jade tooltip / JEI 界面未验证；Mekanism 已复验 hot-add PASS（见 58），进世界未验，且
  方法级 `-f` 崩溃（64）未解决；AE2 已在 macOS L2 PASS；多 mod AT 冲突、其余平台仍缺。
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

> 追加式账本，**编号稳定**（引用不变）。共 67 条：待解决 2 / 验证缺口 4 / 结构性边界 18 / 历史与注记 15 / 已修 28。
> **当前 actionable（2）**：61 Sodium 单例 mixin 字段、67 启动期 AT 被 redefine 覆盖（Create 方块交互崩，已定位待修）。
> **64 是待拍板的结构性边界**（方法级 `-f`）。**65/66 已修**（accessor 接口改写、数据包注册表重放；66 真机已越过创造栏）。

### 待解决（需改代码）

67. **热加后方块交互 `IllegalAccessError`：启动期 AT 在 redefine 时被原始字节覆盖（2026-11-21 真机）**：
    Create 热加、进世界后手持/穿戴铜潜水头盔右键方块，`BlockBehaviour$BlockStateBase.useItemOn` 抛
    `IllegalAccessError: ... tried to access protected method UseOnContext.<init>(...)`
    （`crash-reports/crash-2026-11-21_21.57.01-client.txt`）。
    **分析**：`UseOnContext.<init>` 的 `public` 来自 **NeoForge 启动期 AT**（
    `META-INF/accesstransformer.cfg`：`public ... UseOnContext <init>(...)V`），不在 Create 的 AT 里，所以
    `LateAccessTransformers` 没碰它（日志 40 规则/29 应用，无 UseOnContext）。`MixinSupport.captureOriginals`
    从**类资源**读原始字节（protected），热加又用这份字节 transform + redefine 了 `UseOnContext`（21:55:54），
    随后 `BlockStateBase` 也被 mixin redefine，首次执行 `useItemOn` 链接调用点时该构造器已是 protected → 访问检查失败。
    同一机制还会让**失败回滚**把 AT 加宽还原成原始访问。
    **修复方向**：把 live 类的访问标志（类/字段/方法，反射 `getModifiers()`）移植到 transform 后的字节上，
    并以同样方式处理回滚字节（类似现有 `transplantAccess`，但来源是活类而不是类资源）；
    回归断言：热加后 `UseOnContext.<init>(Level,Player,InteractionHand,ItemStack,BlockHitResult)` 仍为 public，
    并真正走一次 `BlockStateBase.useItemOn`。**属 late AT/加载期 AT 的通用缺口，不只 Create。**

61. **Sodium 热加后旧渲染对象崩溃（2026-11-19；2026-11-20 分析）**：`sodium-lateat` 中 Sodium ok=true（含 4 个
    fabric 库、约 64 类 redefine）。已修 `ShaderInstance.uniformCache` NPE（add 前重建 shader 实例）。
    **A（已实现）**：`MixinSupport` 检测“给有活单例的类新增实例字段”，成功消息标 `J2` 并列出目标
    （本次为 `Minecraft`/`BlockEntityType`）。
    **B（opt-in 实验，`-PsodiumBootstrap=true`）**：依次修复/回填
    ① `Minecraft.fences`；② Sodium 配置引导（`onInitialization`/`registerConfigsEarly|Late`/core-shader 检查）；
    ③ 旧 `SpriteContents$Ticker/InterpolationData.parent`（56 个）；④ `BlockEntityType.sodium$renderPredicates`
    （44 个）；⑤ 活 `LevelRenderer.renderer`（含 managed GL 上下文与 `setLevel`）。
    标题屏热加已不再崩；**进世界热加**仍停在 Sodium 的 `BlockColors` 状态（`blocksToColor`/`overridenBlocks`
    未初始化 → `sodium$getProviders` NPE）。这是重建 Sodium 构造态语义，不是单字段回填；适配需要逐对象/逐状态。
    **按所有者决定保持 J2**（事实可热加，但重启前存在致命级 bug），B 未默认启用。

### 验证缺口（已实现/已修，缺证据）

27. **「主菜单热加后创造栏没出现」无法复现**：本机四条路径 PASS；需要那台机器的 `latest.log`（[dogfood-gameplay.md](features/dogfood-gameplay.md) §2.1.1）。

37. **JBR 自动准备/一键重启的 Windows 全链路未验证**：macOS dev 端到端 PASS（真实下载/校验/解压/重启/热加）；PCL2/Windows 的 argv、`javaw`、GUI 按钮未覆盖。

38. **自动下载只覆盖 5 个平台资产**：Intel macOS 无 JBR 21 资产，需手动/预置；下载 95–200 MB。

52. **服务端一次性事件定向重放：已实现，未验证（2026-11-18）**：新增 `hotadd/ServerLifecycleReplay`，用 `EventBusSnapshot.addedSince` 取出本次 add 新增的 game-bus 监听器，只对这些监听器调用 `ServerStartingEvent`/`ServerStartedEvent`（`ListenerList` 不保证与 owner map 同一实例，故有“逐个试调用、类型不符即跳过”的兜底）；服务端实例先取 `ServerLifecycleHooks.getCurrentServer()`，为空再反射 `Minecraft#getSingleplayerServer`。**未验证**：进入世界复跑时，客户端两次卡在 xaerolib mixin 阶段（见 54），没走到重放日志。

### 结构性边界（长期限制，不是待修 bug）
29. **资源重载与 chunk 重镶并发（保留为边界，2026-11-19）**：无 assets 时跳过整次重载；重载链已串行化 +
    链期间隐藏 Screen。chunk 重镶由原版在重载完成后的 `allChanged()` 调度，引擎从不直接碰 chunk；串行化后那次
    `FluidSpriteCache` NPE 未再复现。线程/锁序规则见 [design.md](design.md)「线程、锁序与阶段契约」。


6. **LAN 模组是妃级布局变更**：需要 JBR；`online-mode` 可能要重启集成服务器。

7. **A1 剩余边界**：构造器其它副作用（静态状态/全局注册/线程）不可回滚；不能 undefine module；post-construct 重试仍失败（干净回滚 + 重启）。**2026-11-21 实例**：ModernUI（现代化 UI）构造后失败（`RuntimeException: null`），回滚后其启动的 UI 线程继续绘制并崩（`UI recording context has not been created yet`），游戏退出；已记 JBR BLACK（见白名单）。

10. **没有删/更新路径**：已加载（尤其织入 mixin）的类无法卸载；datapack 删除实测不可能。

12. **MixinRuntime 是可选第三方扩展**（`type="optional"` + 反射）：缺它时运行时照常启动、嫔级照常，妃级/Mixin 能力不可用；构建不需要 `libs/`。

14. **命令重放的边界**：`RegisterCommandsEvent` 是全局事件（所有监听器再跑一遍）；payload 能补进已建立连接，但缺握手（两端必须同一 jar）、锁 1.21.1、一次性事件不支持。

15. **客户端重放命令的副作用**（与 14 同源）：集成服务器每次热加都重跑所有命令监听器；标题屏不重放。

18. **贵妃级只到 add-only**：客户端不装 jar 的多人仍不行（被 datapack 同步踢，装回前连不回）；实体 post-register 未重放；提交后不可回滚。

19. **datapack 边界**：只覆盖 `RELOADABLE`；WORLDGEN 需新世界；存档记录 `mod/<modId>`，重启且 jar 在 `mods/` 会合并；失败文案必须强调 dirty/重启。

21. **客户端启动期注册仍是手写清单**：已重放 FMLClientSetup/key/menu/renderers/颜色/扩展/reload listener/tooltip/配方书/**shader**/HUD/物品装饰/粒子+item→model；未做 `ColorResolvers`；`RegisterLayerDefinitions` 2026-11-18 起重放（含 `EntityModelSet` roots 重建与 staged 顺序）。漏一个就是一类 bug，所以有注册审计。

23. **AT：stock 硬边界；JBR late AT 实验性可行**：成员级 access/final 可改；**类级 `-f` 见 44**。stock 默认拒绝（`ACCESS_TRANSFORMER`）；增强 JBR 会话下自动走 late AT（`-Dmodreloader.lateAt=false` 可关）；托管重启自动准备环境。ADR-0014 否决“改写调用方”。

24. **JiJ/coremod 剩余边界**：嵌套 GAMELIBRARY 无 container（独立分发的 MezzConfig 类）；**嵌套 mod 的 AT 自 2026-11-19 起在 lateAt 下走 late AT**（增强 JBR 默认开；stock 仍拒绝），嵌套 **GAMELIBRARY** 的 AT 与 `data/` 仍不单独注入；审计只扫外层；coremod 只覆盖 class 级 `ITransformer`，redefine 路径未实测。Sodium 式「外壳 jar 与内嵌实现同 modId」已适配（2026-11-19：内嵌实现作主文件，外壳降级为库模块）。

25. **JBR 只对「已加载类的布局变更」必需**：数据/方法级 mixin 在 JBR 下可 redefine；未加载目标免 JBR；缺 JBR 时干净 `JBR_REQUIRED` 并点名目标。

32. **无 `mods.toml` 的嵌套 GAMELIBRARY 没有 container 语义**（独立分发的 MezzConfig）；GAMELIBRARY manifest 现在返回 `FailureKind.LIBRARY`；带 `mods.toml` 的嵌套 GAMELIBRARY 归为库 + 收集 `MixinConfigs`。**2026-11-21 补**：**顶层发行包装**（外层无 mods.toml、jarjar 内层是真 mod）已会被拆到内层 mod 热加（`WrapperJars`/`WrapperJar`，真机 PASS：mezz standalone → `mezz_config`）；仍不支持的是“顶层就是一个纯库、没有任何 mod”。

44. **类级 AT 去 final 是 JBR 结构性边界**：实例构造返回 null，后续 redefine SIGSEGV；“AT 覆盖 access/final”只指**成员级**；需启动期类变换或 JBR 修复。

45. **mixin 新增实例字段不初始化已存在对象**：JourneyMap 给 `DynamicTexture` 加字段，老 `LightTexture` 上为 null，退出时 `onClose` 注入 NPE（只影响退出）。

56. **Mixin 新增实例字段不初始化旧实例（2026-11-18 真机确认）**：Jade 的 `FontMixin` 给已加载的 `Font` 增加
    `glint1/glint2/glint1Strength/glint2Strength`（构造器里 `glint1/2 = NaN` 表示“无闪烁”）。热加只 redefine 类、
    不会重跑构造器，旧 `Font` 实例拿到的字段是 `0.0f`，闪烁分支常开，所有文字约 65% 亮度（真机截图：字体变暗）。
    这是问题 45（新增字段不初始化）的第二个实例；`layoutChange` 现在把字段增减也算作布局变更：无 JBR 前置拒绝，
    JBR 下照常尝试并额外打印“旧实例保持 JVM 默认值，需重启才完全正确”的警告。Jade 的变暗不是崩溃，
    实测重启并让 Jade 正常加载即可恢复；**2026-11-21 所有者 Windows/PCL2 实例复测复现**（`keyMappings 34→39`、
    late AT 57 rule），按白名单规则 Jade 的 JBR 行转 **J2**（bug 列：「热加后字体变暗，重启后正常」）。

64. **方法级 `-f`（去已加载方法的 final）在 JBR 下不可用（2026-11-21 真机复现）**：Mekanism 的 AT 含
    `public-f Screen renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` 与
    `public-f AbstractWidget render(Lnet/minecraft/client/gui/GuiGraphics;IIF)V`（它的 `GuiMekanism`/`GuiElement`
    重写了这两个方法，规则是加载前提）。late AT 通过 JBR redefine 去掉 final 后**热加本身成功**，
    但约 2 秒后标题屏渲染在 `ClientHooks.drawScreenInternal` 的 `Screen.renderWithTooltip` 调用点抛 NPE；
    `-Xint` 纯解释执行仍复现，`-Xlog:exceptions` 显示该帧 **BCI 为负值**且无消息（方法元数据/CP 解析已失效），
    不是业务空指针；崩溃前另有 `DirectMethodHandle$Holder` 的 `NoSuchMethodError`。对照组 architectury 13.0.12
    （311 条 AT、**无**方法级 `-f`）同机 PASS（2026-11-21 复跑仍 PASS：311 rule/150 类、2 mixin config、
    `isLoaded=true`，热加后 3 分钟无崩溃），指向「已解析过的 final 方法被 redefine」。**需产品决策**：
    按问题 44 的同类边界在 `VALIDATE` 拒绝/标 J2 并提示重启，或继续找 workaround（重定义调用方消 CP 缓存、
    换 JBR 版本）。证据：`crash-reports/crash-2026-11-21_00.13.03-client.txt`、游戏目录 `xlog-exceptions.txt`。

### 历史与注记（分析器/环境/过期）

1. **静态分析仍会漏/误判**：反射/跨模组代理注册抓不到；注册轴已按「注册调用 / `*DeferredRegister` 引用」对齐（`RegistrySignalTest`）。

3. **Fabric 生产 jar 是 intermediary 名**（`class_310`），核心类识别退化。

4. **JiJ 递归拆包（2026-11-18）**：`JarInJar` 现在递归展开到 3 层（Supplementaries → mixinsquared-forge → MixinSquared core），嵌套库进 module path 并收集 manifest `MixinConfigs`；更深的 bundle 仍不展开，嵌套 mod 的 AT 自 2026-11-19 起在 lateAt 下覆盖（见 24/36）；**GAMELIBRARY 嵌套 AT 仍不覆盖**。

5. **Python 分析器 vs Java 运行时**：注册轴已对齐，其它轴（native/JNI、render-core mixin）仍不一致；`VALIDATE` 没有 tier 门禁（UI 才有）。

8. **Git 收尾**：`main`/`origin` 持续 push（最近 `aa21f68`）；CI 仍未观察、tag 未打。

9. **没有完整人工标注集**：只校准 11 个有真机结论的 jar；Fabric 与置信度未校准。

11. **GUI/监听初版限制**：只有 `zh_cn`/`en_us`；最多显示 7 个候选；`gui`/`scan`、失败重试未自动验证；皇后级只提示重启。

13. **`mods.toml` 的 side 字段不可信**：静态只 warn（分不清「双端模组的客户端代码」与「启动路径就用」），兜底是 dist 门禁 + 回滚。

16. **Capabilities 只查名字不查行为**：锚点匹配 ≠ 语义未变，不能代替 harness。

17. **裸 unfreeze/freeze 会移动 BlockState id**：必须先清 `BLOCKSTATE_TO_ID_MAP` 再 freeze（[research-t4-registry.md](research-t4-registry.md)）。

30. **客户端「保存→退标题→重开」harness 会卡死**（`glfwSwapBuffers` / 无主显示器）：路径已删，存档回读由专用服覆盖。

31. **L1 标题屏测不了“需要世界”的模组**：Xaero World Map 的 `loadLater` 会抛；pipeline 把“没到 `DONE`”判 `INCOMPLETE`。

33. **L1 的“干净拒绝”不等于“功能正常”**：`overall_verdict` 要求 `DONE n/n` 且 exit 0。

39. **dev client 预装 Moonlight 启动崩（环境问题）**：`ExtraCodecs` 引用 `codecui.Schema` 时 CNFE；只在 dev 观察到，阻塞 Supplementaries 完整复跑。

55. **「已选 JBR 仍要重启」是产品失败（2026-11-18 所有者）**：玩家已在启动器选了 JBR，却因缺 `-XX:+AllowEnhancedClassRedefinition` 被要求再重启——把实现细节转嫁给玩家，不可接受。**2026-11-18 收口为单一动作「为热加载增强环境做准备」**：菜单（未就绪时常驻）与失败屏都指向同一入口；已安装/正在运行的 JBR 直接复用；缺失时官方下载 → SHA-512 → 解压到 `gameDir/jbr-21.0.11`；随后自动重启并带齐 `-XX:+AllowEnhancedClassRedefinition` + `-Dmodreloader.lateAt=true`。**仍余缺口**：参数只能启动时给，启动器每次不带参数则每会话最多一次自动重启；跨会话永久生效需要启动器/整合包预设或 classloader 隔离/软重启。**2026-11-20 补**：lateAt 不再依赖第二个属性——检测到增强 JBR 即自动启用（显式 `-Dmodreloader.lateAt=false` 可关），玩家点一次准备环境后不再遇到 AT 开关类阻塞。

### 已修

66. ✅ **热加模组的数据包注册表未注册（2026-11-21，dev L2 验证；Create 真机复验待做）**：Create 用
    `DataPackRegistryEvent.NewRegistry` 注册 `create:potato_projectile/type` 等数据包注册表（codec 进
    `DataPackRegistriesHooks`，世界加载时按 datapack 的 `data/<ns>/<registry>/` 建表并填条目）。
    热加漏发该事件 → 客户端 level `RegistryAccess` 不认识它 → 打开机械动力创造栏渲染马铃薯炮时
    `IllegalStateException: Registry create:potato_projectile/type not found`
    （`crash-reports/crash-2026-11-21_21.31.03-client.txt`；另一个 Create 页签不触发该路径，所以不崩）。
    **修法**：`hotadd/DatapackRegistries` 在 construct 后对新容器逐个 `OneShotReplay` 发
    `DataPackRegistryEvent.NewRegistry`，再调包私有 `process()`；重复 key 拒绝（`addRegistryCodec`
    无去重，重复会让 registry loader 出错）。加入 `ReplayCatalog`（审计口径同步）。
    世界加载用 `getDataPackRegistries()` 的**活视图**，网络同步用 `SYNCHRONIZED_REGISTRIES` 活视图，
    所以标题屏热加对**下一个**世界/连接生效；已打开的世界保留冻结视图（日志说明，需重启）。
    证据：`-PverifyCreatePonder`（dev client + 增强 JBR）热加 Create → 建世界 →
    `create:potato_projectile/type entries=25`、`PotatoCannonItem.getAmmo() = null`（装饰器判空跳过，此前此处抛异常）、
    `PonderRenderTypes` 正常 → `RESULT: PASS`。`./gradlew build` 绿（`ReplayCatalogTest` 23 项）。
    **真机已确认**：2026-11-21 21:55 会话中 `datapack registries registered: [create:potato_projectile/type]`，
    再开机械动力创造栏不再出现该异常（随后暴露问题 67，见待解决）。白名单 Create 仍 J2 直到 67 修复。

65. ✅ **热加 Mixin 的 accessor/`@Invoker` 接口未被改写（2026-11-21，dev 验证；Create 真机复验待做）**：Create（`ponder`）的
    `RenderTypeAccessor` 是 `@Mixin(RenderType.class)` 的接口，静态 `catnip$create` 是 Mixin 的
    “未应用”桩（直接抛 `AssertionError: Mixin application failed!`）。热加时我们对目标类做了 transform，
    但这个**接口本身**没有走 Mixin 的处理（静态 invoker 的真实实现要写回接口），于是 Ponder 首次渲染世界
    （`PonderRenderTypes.<clinit>`）就断言失败。标题屏 add 本身 `ok=true`；进世界即崩。
    **根因（JVM/Mixin 级）**：Mixin 0.8.7 的 accessor 接口是 *loadable mixin*，由
    `MixinCoprocessorAccessor`（+ `MixinCoprocessorPassthrough`）在**接口类 define 时**改写；
    这两个 coprocessor 只在 `MixinProcessor.prepareConfigs` 里被 `MixinConfig.addListener` 注册、并在
    `MixinConfig.prepare` 的 `onPrepare` 回调里登记接口名。我们热加时直接调
    `MixinConfig.prepare(extensions)`，跳过了 listener 注册，coprocessor 不认识该接口 →
    `couldTransformClass=false` → 接口按原字节加载（连 IllegalClassLoadError 都不触发）→ 保留桩体。
    **修法**：`MixinSupport.attachConfigListeners` 在 prepare 前把 processor 的 4 个 coprocessor
    （passthrough/synthetic-inner/accessor/nest-host）与 `MixinInheritanceTracker.INSTANCE` 挂到新 config 上；
    另外对“上一次 add 已经加载过”的 accessor 接口做 `transformClassBytes` + redefine（`processLoadedAccessors`）。
    证据：`experiments/mixin-fixture` 新增静态 `@Invoker` 接口（桩体同 ponder）→
    `-PnoJbr=true -PverifyMixinFixture=...` 日志 `attached 4 processor listener(s)`、
    `AccessTargetInvoker.mixinfixture$hidden() = reached-target`、`RESULT: PASS`；专用服 Create `serverAddCommand`
    复跑仍 PASS；Create 专用检查 `-PverifyCreatePonder` 也 PASS（`PonderRenderTypes` 正常初始化）；jar 已部署到 PCL
    （2026-11-21 20:57）。
    影响面（只读扫描本机 34 个 jar，含嵌套 jarjar，条件：类含 `Mixin application failed!` + `@Invoker`）：
    只有 Create/ponder 的 `RenderTypeAccessor` 命中，其余安装包/staging 无此类桩。
    **仍缺**：Create 真机进世界复验（此前崩溃：`crash-reports/crash-2026-11-21_18.33.26-client.txt`）；白名单 Create 暂留 J2。

58. ✅ **层/渲染器重放顺序（2026-11-21 真机复验 PASS）**：Mekanism 10.7.19 在 Windows/PCL2 + 增强 JBR 下标题屏热加
    成功：late AT 94 规则/43 类、注册表 93 个重放、blocks 1060→1260、items 1333→1714、自定义注册表 4 个、
    层定义 213→224、GUI 层 25→29、物品装饰 0→14、payloads 58 个，`isLoaded=true container=true`，
    **不再出现 `mekanism:robit` 未绑定 NPE**。紧接暴露的新崩溃见 64（同一 jar 的方法级 `-f`）。

2. ✅ **皇后级判定偏保守（2026-11-20 结案）**：JBR+lateAt 下 GuideME→AE2 热加 `ok=true`
    （blocks 1060→1162、items 1334→1698）；L2 已验：进世界后 `guideme.internal.screen.GuideScreen`
    与 `appeng.client.gui.me.common.MEStorageScreen` 均正常打开、无崩溃（`-PverifyAe2L2`）。
    AE2/GuideME 白名单转 verified（J1）。皇后级预测属于安全方向的过估，不再要求重启。

35. ✅ **post-construct 失败仍留注册表内容（2026-11-19 查明）**：Waystones 走 `DeferredRegister`，条目只在
    `RegistryStage.commit` 的 `RegisterEvent` 里注册；用 `-PfailAfterConstruct` 让它在提交前失败，
    `blocks/items` 计数不变、`abort` 打印“no entries were added … ids restored”。所以“构造后失败留内容”只可能来自
    两种情形：① **提交之后**失败（按设计不可逆，RunState=REGISTRY，重启）；② 模组在**构造器里直接**
    `Registry.register`。后者用 fixture 开关 `-PdirectRegister=true` 复现：`abort` 报告
    `block 1060->1061, item 1333->1334`、置 `RegistryStage.residue`，RunState 精确记为 REGISTRY，后续 add 被拒。
    注册表没有删除路径（删了会移动 id），因此“检测 + 精确定位 + 拒绝后续 add + 重启”是这一边界的正解；
    正常路径（t4fixture / Balm→Waystones）复跑 `ok=true`、无脏日志。

50. ✅ **失败回滚对 late-AT 重定义过的类 VerifyError（2026-11-19 已修）**：复现路径为 Moonlight 成功后热加
    Supplementaries（`-PlateAt=true`），分别注入 ModList 后/构造后失败。构造前失败时内嵌类回滚正常；构造后
    JBR 拒绝把 `MineshaftPieces$MineShaftPiece` 写回，抛裸 `VerifyError`。现在回滚对 late AT 是**尽力而为**：
    写回被拒时只 warn“AT stays applied”，访问加宽是加法且不会破坏已加载代码；`rollbackErrors` 为空，
    不再把整个 add 判成 `ROLLBACK_PARTIAL`。成功路径回归 `moonlight-lateat` 两个 `ok=true`。
    （构造后失败的构造器副作用仍属问题 35/7 的边界，与本条无关。）

63. ✅ **`-PverifyPayload` 标题屏路径 FAIL（2026-11-19 查明）**：不是产品问题——`/tmp/payloadfix` 里的 jar 是
    2026-11-15 的旧构建，构造函数只有 `onPayloads`，没有 `onLogin`/`onServerTick`，也没有写 marker 的代码。
    诊断日志证明注册、协商与两侧连接 setup 都包含 `payloadfix:ping`（`hasChannel=true`），`PlayerLoggedInEvent`
    正常到达。用当前源码 `experiments/server-fixture/build-payload.sh` 重建后，`sending ping → PING RECEIVED →
    marker`，`-PverifyPayload` PASS。教训：**harness 用的 /tmp 夹具也要跟着源码重建**。

53. ✅ **client setup 的 deferred task 早于热加资源包注入（2026-11-19 已修）**：World Map 的 `loadLater` 在
    `FMLClientSetupEvent` 的 deferred queue 里用 live `ResourceManager` 读自己的 `vanilla_states.dat`，
    而热加包当时还没进 manager → `NoSuchElementException`。关键事实：`ReloadableResourceManager.createReload`
    在**调用时**就同步换入含全部已选包的新 `MultiPackResourceManager`；只要先注册 reload listener、注入包并启动
    重载，再跑 deferred tasks，就与启动期语义一致（任务在初始重载内执行）。现在阶段为
    `REGISTRY → EVENT（common setup）→ PACK（注册+注入+启动重载）→ CLIENT（client setup 任务、层/渲染器、
    payload、命令）→ DATAPACK`。证据：`minimap-addon` 中 `xaeroworldmap ok=true`（此前必失败）。

62. ✅ **同会话第二个 Xaero 模组的 frozen 单例（2026-11-19 已修）**：World Map 热加后，再热加 Minimap 时
    `BuiltInConfigChannelPermissions → PermissionSystemRegistry.handleModPresence` 抛 `IllegalStateException`。
    XaeroLib 有四个同型 `INSTANCE + private boolean frozen` 单例：`ConfigChannelRegistry`、`PermissionRegistry`、
    `PermissionSystemRegistry`、`PacketHandlerRegistry`；`XaeroLibChannels` 现在统一开窗、成功后按 LIFO 恢复。
    证据：`minimap-addon` 中 `xaerominimap ok=true`。

20. ✅ **创造页签排序非幂等（已修）**：`sortTabs()` 追加不清空 → 4→8 → 越界崩；`afterRegistryContent()` 先清 `DEFAULT_TABS`。

22. ✅ **专用服妃级（已修）**：`runServer` 也用 JBR + enhanced；ZeroCore2 `redefined Slot` 成功。

26. ✅ **`RegisterCapabilitiesEvent` 重放（已修）**：commit 后、common setup 前只投新容器；审计移入 covered；模型层未重放。

28. ✅ **in-world 热加与区块发送竞态（已修）**：`ServerThreadHop` 把「清 id + freeze」投到服务器线程；单人两端共享同一张表，窗口只是缩小。

34. ✅ **失败热加留下半应用状态（已修）**：回滚改为 LIFO（先摘 mixin config 再还原类）；ModernFix 复跑不再崩。

36. ✅ **JiJ 嵌套 AT/元数据的误判（2026-11-18 已修）**：codecui 的 `META-INF/accesstransformer.cfg` 是**空文件**，且 `modType="LIBRARY"` 写在 toml（manifest 没有 `FMLModType`）；旧预检把“空 AT”和“没有 manifest 标记的库”都当成真 mod/真 AT。现在 AT 只认 FML 会应用且**有内容**的文件（含 toml `[[accessTransformers]]`），`isGameLibrary` 也认 toml `modType=LIBRARY`；`NestedMods` 跳过空 AT。Moonlight 因此越过 MODULE + late AT（126 条规则应用并回滚 ok），改卡在自定义注册表（见 46）。**嵌套 mod 的 AT 自 2026-11-19 起在 lateAt 下由 `LateAccessTransformers` 应用**（Create 的 Ponder、Iris 的 fabric-block-view-api 已越过）；GAMELIBRARY 嵌套 AT 仍不覆盖。

40. ✅ **JBR redefine 的 `ClassFormatError`（已修）**：根因是 JBR 触发 Mixin agent 二次 transform；`MixinAgentGuard` 在 redefine 窗口内暂停目标类，失败 dump 字节。guard 依赖内部字段，找不到时 no-op。

41. ✅ **同批热加同目标类冲突（已修）**：手动 transform 只保留本次新增 config；`GeckoLib→FerriteCore→ModernFix→MouseTweaks` 4/4 PASS。真正改同一个类的叠加仍未测。

42. ✅ **late AT 结果被后续 mixin 覆盖（已修）**：`ClassBytesCache` 记住实际字节；`captureOriginals` 优先用缓存。

43. ✅ **JourneyMap 资源重载崩溃（已修）**：根因是 late AT 去**类** final 把 `NativeImage`/`ModelPart` 的构造打成 null；现在前置拒绝（见 44）。

46. ✅ **自定义注册表（`NewRegistryEvent`）已支持（2026-11-18）**：构造期临时恢复 `pendingModdedRegistries`，构造后只对新容器 fire `NewRegistryEvent` 并调用包私有 `fill()`；`RegisterEvent`/freeze/快照由现有 `RegistryStage` 动态覆盖。夹具 + Moonlight 验证；失败语义：`fill()` 之后失败只能 dirty。datapack 注册表自 2026-11-21 起重放（问题 66，`DatapackRegistries`）；`ModifyRegistriesEvent` 仍不重放。见 [research-newregistry-hotadd.md](research-newregistry-hotadd.md)。

47. ✅ **late AT 对已混入的类二次 transform（2026-11-18，已修）**：Supplementaries 的 AT redefine `Entity`（Moonlight 已混入）时，Mixin agent 仍二次注入 → `ClassFormatError`。修法：`MixinAgentGuard` 改为常驻 wrapper（2026-11-18 晚已收窄为只包 Mixin agent，靠 `blanket()` 覆盖其它路径，待复验），并在每次 `redefineClasses` 期间用 `blanket()` **全局抑制**（JBR 该路径不一定传类名，按名匹配不可靠）。复跑日志：`transform guard installed (4 transformer(s) wrapped)`、Moonlight `ok=true`，Entity 不再报错。同类问题（40/41）的第三份。

48. ✅ **嵌套 `MixinSquared` bootstrap（2026-11-18，已修）**：`JarInJar` 递归拆包后，Supplementaries 的 3 个嵌套库（`mixinsquared.forge` / `MixinSquared` / `sable.companion.common`）全部进入 module path，`mixinsquared.forge.init.mixins.json` 能加载其 `MixinSquaredBootstrap`；Supplementaries 越过 MIXIN/CONSTRUCT，注册了 blocks 1061→1306、items 1335→1584。

49. ✅ **模型层/Skull 模型（2026-11-18，已修）**：`RegisterLayerDefinitions` 现在会被重放，随后调用 `EntityModelSet.onResourceManagerReload` 重建 roots（`LayerDefinitions.createRoots()` 会合并 `ClientHooks` 的静态表）；层回放与“重建 renderer”拆成两阶段、**全容器先层后渲染器**（否则嵌套容器先重建，外层层的模型还没注册）。`ItemBlockRenderTypes.setRenderLayer` 的 client-loading 门禁在 FMLClientSetup 重放窗口内临时放开（`ClientModLoader.loading`，消费者只有该门禁和 options 保存保护）。实测 Supplementaries 层 213→234、无 `No model for layer`、`ok=true`。

51. ✅ **无焦点 + 热加重载隐藏 screen 会撞 `PauseScreen` NPE（2026-11-18 发现，2026-11-19 已修）**：`GameRenderer.render` 在窗口失焦时调 `pauseGame`，而 `pauseGame` 只在 `mc.screen == null` 时动作；`ClientResourcePacks` 原先在 reload 链期间把 screen 置空，`PauseScreen.createPauseMenu` 读到 `minecraft.player == null` → NPE。现在改用**已初始化**的空白占位 `Screen`（`isPauseScreen=false`，手动 `init`，不触发 ScreenEvent），重载结束再还原。

60. ✅ **Moonlight 之后热加 Supplementaries 的 Mixin 失败（2026-11-19 已修）**：Moonlight 与 Supplementaries 都对
    `MapRenderer$MapInstance.updateTexture` 的同一 `DynamicTexture.upload()` 调用点做 `@WrapOperation`。
    Moonlight 先把原调用点包掉后，Supplementaries 的 config 单独应用到已混字节上时 0 匹配
    （`Critical injection failure ... Scanned 0 target(s)`）。现在 `MixinSupport` 在 transform 失败且目标曾被前一次
    add/late AT 改写过时，用**类资源原始字节 + 全部活动 configs** 重组，再把 live 的访问标志移植回来；
    `moonlight-lateat` 复跑 Supplementaries **ok=true**（blocks 1061→1306、items 1335→1584）。
    证据：`reports/20261119-162209-moonlight-lateat`（`recomposed ... with all active configs`）。

54. ✅ **xaerolib mixin 阶段挂起（2026-11-19 已修）**：jstack 显示 ABBA 死锁——Render 线程在
    `MixinSupport.transformBytes` 里持 `MixinTransformer`/`MixinProcessor` 锁后，注入点解析需要加载类，
    该加载走 ModLauncher 转换要 Mixin 插件列表锁；而 worldgen 线程正持插件列表锁等 transformer 锁。
    修法：`LaunchPluginMonitor` 通过 `transforming_class_loader` → `classTransformer.pluginHandler`
    找到 `MixinLaunchPlugin`（继承 `MixinLaunchPluginLegacy`）的 `processors` 监视器，直接 transform 前
    先拿它，统一 A→B 顺序。修复后同一场景 8 次复现全部走完 mixin（每次 13 个 redefine），不再挂起；
    后续失败是已知问题 52/53（`serverPlayerTickHandler` null、`loadLater` NoSuchElement）。

57. ✅ **轮询把已加载 mod 的替换 jar 当新候选（2026-11-18 真机，已修）**：轮询 `scan()` 只过滤 ignored/failed，
    没有“modId 已加载”检查（手动 `scanNow()` 有）。在游戏运行时覆盖 `modreloader` 自身 jar 后，它被当成新候选并
    弹 toast；底层 `SecureJarHandler` 指向的 jar 已被替换，加载尚未加载的类时 `NoClassDefFoundError`（类在新 jar 里
    实际存在），客户端崩溃。现在两路径都按 `inspection.alreadyLoaded()`/modId 过滤，日志提示“替换/更新已加载的 jar
    需要重启”。**运行时不要替换正在使用的 mod jar**；更新路径仍不支持。

59. ✅ **Create 的自定义注册表在构造期报「已冻结」（2026-11-19，已修）**：Create 的 `CreateBuiltInRegistries` 在构造期
    直接用 `RegistryBuilder.create()` 建表并注册进 `BuiltInRegistries.REGISTRY`。启动期根注册表在 construct 时仍可写
    （`NewRegistryEvent.fill()` 才冻结），运行期却是冻结的。现在 `CustomRegistries.open` 同步解冻根注册表
    （`BaseMappedRegistry.unfreeze`，经 trusted lookup），`fill()`/`close()`/回滚时重新冻结。
    复跑 `create-lateat`：**ok=true**，blocks 1060→1703、items 1333→2032，同批 flywheel/ponder（L1，玩法未验）。

## 玩家侧原则（ADR-0015，2026-11-18 最终）

- **安装/解压是玩家动作的上限**：JBR 可产品化；之后由运行时找到并接管（不改启动器、不填参数）。能自动时连这一步也不要求。
- **统一能力模型**：JBR 重启一次性带 `-XX` + `lateAt`；玩家只感知“用 JBR 重启后，需要 JBR 的模组就能热加”，界面不出现开关名。
- **能力不删**：已存在 JBR → 一键重启；不存在 → 自动下载（官方 + SHA-512）或整合包预置。
- **重启 = 产品失败（切到 JBR 本身除外）**：JBR 就位后仍要求重启的模组是缺口，要记录并消除；「放 `mods/` 重启」只能是最后手段。
- 手填参数/手动安装只给包作者、管理员、开发者（[guides/jbr.md](guides/jbr.md)），不是玩家路径。

## 下一步

));
> **当前交接（2026-11-21）**：本日完成 Mekanism/问题 58 复验（hot-add PASS）、问题 64（方法级 `-f`，JBR 边界）、
> ModernUI opt-in 适配（六步链路真机 PASS）、包装 jar（mezz → JEI 依赖链）、服务器一等目标（SERVER 配置更正、
> MixinAgentGuard/AT side/CLIENT 配置三处修复、专用服 Create/Mekanism/architectury/runState/why 全 PASS、
> 命令权限 2）、shader 重建时序修复。白名单：JBR J1=22 / J2=5 / BLACK=4（61 行）。
> **下一批（建议）**：① Create 真机复验（65+66 已修，dev L2 PASS；开两个创造栏、触发 Ponder）；
> ② 问题 64 产品决策；
> ③ 剩余 J1 candidate（Cloth/Balm/GeckoLib/Curios/Patchouli/Embeddium/ModernFix/FerriteCore/Xaero 小地图/
> voicechat/mcwifipnp/e4mc/Farmer's Delight/Comforts/Moonlight/Supplementaries/ZeroCore2）；④ 专用服多人
> （`mpRegistryServer`/`mpFeiServer`，需客户端配合）与 datapack/payload 专用服回归；⑤ ModernUI 默认可否开启。
> **环境**：构建用 `X:` subst + 本地 Gradle 8.10（`--init-script X:/neoforged-mirror.init.gradle`）；
> 专用服 dev 跑法 `runServer -x downloadAssets -PjbrJava=...`，并在 `build/moddev/minecraft_assets.properties`
> 写 `assets_root`/`asset_index` 指向 `.minecraft/assets`（本机 PKIX 拦截资产下载）；`run/mods` 需 MixinRuntime。
> **注意**：本地 `main` 比 `origin/main` 多 30 个提交（含本次 merge `e804261`），推送因 GitHub 网络 reset 未完成，
> 恢复网络后 `git push origin main`。
> 证据在 [history.md](history.md)；语料/夹具：`run/`、`.minecraft/.../modreloader/staging`、`corpus/j1`。

| 优先 | 任务 | 关键点 / 落点 | 验收 |
|---|---|---|---|
| **P1-3** | ✅ Git 收尾 | GitHub Actions 最新 `e0a0646` 为 success；需要时打 tag | Actions 绿 |
| **P0-新** | 流水线 L2 | 标题屏热加 → 建世界 → N tick → HUD/按键/截图；先 minimap/mouse-gestures | 断言 + 截图证据 |
| **P0-新** | 语料扩到 20–30 个常用模组 | 小地图/JEI/HUD/输入/前置库；`check` 进 PR | 报告 + 矩阵 |
| **P0-新** | 模组白名单实测（所有者驱动） | 61 行：stock T1×7 / BLACK×23；JBR J1×25 / J2×2 / BLACK×4；candidate 由所有者复测转 verified | TSV + 视图同步 |
| **P0-新** | ✅ Xaero's World Map config channel | `XaeroLibChannels` 窗口（`ConfigChannelRegistry.frozen`），仅对引用该类的 jar 生效；World Map `ok=true isLoaded=true` | 已转 J2 |
| **P0-新** | 服务端一次性事件（问题 52） | `ServerLifecycleReplay` 已实现（定向调用新增监听器 + integrated server 兜底），**未验证**：进入世界复跑卡在 54 | World Map J1 |
| **P0-新** | xaerolib mixin 阶段挂起（问题 54） | 先 `jstack` 留线程栈再杀；怀疑 Mixin/transform 锁或常驻 guard | 复跑不挂 |
| **P1-新** | ✅ 资源顺序（问题 53） | 阶段重排：注册 → 注入包/启动重载 → client setup 任务 → payload；`createReload` 同步换 manager | `minimap-addon` World Map/Minimap `ok=true` |
| **P0-新** | 类级 AT 的启动期替代 | 启动扫描队列里的 AT，在**加载期**去 final（覆盖 `NativeImage` 这类启动即加载的类） | 夹具/真实 jar 复跑 |
| **P0-新** | ✅ 自定义注册表（`NewRegistryEvent`） | `CustomRegistries` + `RegistryStage` 窗口；夹具 `custom registry RESULT: PASS`，Moonlight `ok=true`（3 个注册表）。剩余：`fill()` 后失败 dirty、`ModifyRegistriesEvent` 不重放、CraftTweaker 未复跑；datapack 注册表已由问题 66 补上 | Moonlight J1（candidate） |
| **P0-新** | ✅ late AT 对已混类二次 transform（问题 47） | 常驻 wrapper 包裹全部 transformer + 每次 redefine 的 `blanket()` 全局抑制；Moonlight 复跑 `ok=true`，Entity 不再报错 | 批次复跑 |
| **P0-新** | ✅ 嵌套 MixinSquared bootstrap（问题 48） | `JarInJar` 递归 3 层拆包；3 个嵌套库进 module path，MixinSquared config 可加载。Supplementaries 越过 MIXIN/CONSTRUCT | 批次复跑 |
| **P0-新** | ✅ 模型层/Skull 模型（问题 49） | `RegisterLayerDefinitions` 重放 + `EntityModelSet` roots 重建；全容器先层后渲染器；FMLClientSetup 窗口内放开 client-loading 门禁。Supplementaries 层 213→234、`ok=true` | 批次复跑 |
| **P1-新** | 回滚 VerifyError（问题 50） | 失败回滚 late-AT 类时 `VerifyError`；保证失败后是可重启的干净状态并明确提示 | 复跑 |
| **P1-新** | ✅ 同 modId 嵌套（Sodium 式外壳） | `NestedMods` 标记同 id 内嵌；引擎用内嵌实现作主文件（容器/mixin/资源/审计），外壳降级为库模块 | Sodium 热加 ok（J2，问题 61） |
| **P1-新** | Sodium 旧渲染对象（问题 61） | `ShaderInstance.uniformCache` 旧实例为 null → overlay NPE；add 后重建受影响对象或明确需重启 | Sodium J1 |
| **P1-新** | ✅ Create 的自定义注册表冻结（问题 59） | `CustomRegistries.open` 在构造期解冻根注册表、`fill/close/回滚` 再冻结；复跑 `create-lateat` ok=true（blocks→1703、items→2032） | Create J1 |
| **P1-新** | Moonlight 之后 Supplementaries 的 Mixin 失败（问题 60） | `MapRenderer$MapInstance` 先被 late AT 重定义，再被 mixin 处理；问题 47 同族，待复现 | 复跑 |
| **P0-新** | late AT 硬化 | L2 功能（Jade tooltip/JEI 界面）、Mekanism 进世界、多 mod 冲突、嵌套 AT、Windows/Linux、默认 UX | [late-at.md](features/late-at.md) |
| **P0-新** | 问题 64：方法级 `-f` | 决策：`VALIDATE` 拒绝/标 J2（按 44 同类边界）或找 workaround；architectury 对照复跑 | 真机复跑 |
| **P0-新** | 消除“JBR 后仍需重启”的缺口 | JBR BLACK 逐个修；**不得**把重启当正常路径（ADR-0015）。结构性的（JourneyMap 类级 AT）写明原因 | 缺口清零或写明结构性不可行 |
| **P1-新** | JBR 端到端 + 探针 | 剩余 Windows/PCL2 真机、GUI 按钮、真实 redefine 探针 | [jbr-support.md](features/jbr-support.md) §6 |
| **P3-1** | GUI/watcher Phase 3/4 | 主菜单弹窗、失败面板、多候选/依赖排序、同 mod id 冲突 | [gui-mod-watcher.md](features/gui-mod-watcher.md) |
| **P3-2** | 产品化下一步 | 托管重启端到端、嵌套 mod 容器、可分发形态、coremod target 报告 | [managed-relaunch.md](features/managed-relaunch.md) 未验证项 |
| **P3-3** | 分析器 Fabric 校准 | intermediary 名 → 核心类识别；置信度校准 | 校准表扩充 |
| **tech-debt** | ✅ P0-5/P0-6/P1-12/P1-13（高风险）/P1-14（规则）/P2-10/P2-15（TestWorld/TestConnection）/包分层 | [tech-debt.md](tech-debt.md) | 构建绿 + 回归 |

## 最近变更

> 逐步过程与证据见 [history.md](history.md)；这里只留最近三条。

- **2026-11-21（问题 67 记录：启动期 AT 被 redefine 覆盖）**：Create 进世界后右键方块 →
  `IllegalAccessError: BlockStateBase.useItemOn -> protected UseOnContext.<init>`。根因是 `UseOnContext.<init>`
  的 public 来自 NeoForge 启动期 AT（不是 Create 的），热加用类资源的原始字节 redefine 它时把 AT 丢了；
  已记录修复方向（把活类访问标志移植到 transform/回滚字节，附回归断言）。Create 白名单暂留 J2。

- **2026-11-21（问题 66 修复：数据包注册表）**：热加漏发 `DataPackRegistryEvent.NewRegistry`，Create 的
  `create:potato_projectile/type` 不在 level registry access → 打开机械动力创造栏渲染马铃薯炮崩。
  `DatapackRegistries` 在 construct 后补发并 `process()`（重复 key 拒绝）；dev L2 建世界后
  `entries=25`、`getAmmo()` 不抛，`ReplayCatalogTest` 23 项。jar 已部署（sha `a51bacb1…`），待真机复验。

- **2026-11-21（问题 65 修复：accessor/invoker 接口）**：根因是热加时手工 `MixinConfig.prepare` 跳过了
  `MixinProcessor.prepareConfigs` 的 coprocessor listener 注册，`MixinCoprocessorAccessor` 不知道新接口，
  静态 `@Invoker` 保留桩体。`MixinSupport` 现在在 prepare 前挂 4 个 coprocessor + inheritance tracker，
  并对已加载接口做 transform+redefine；mixin 夹具新增静态 `@Invoker` 回归（`-PnoJbr=true` PASS），
  jar 已部署到 PCL，等 Create 真机进世界复验。

- **2026-11-21（专用服回归批次 PASS）**：Create/Mekanism/architectury 的 `serverAddCommand` 均 PASS；`serverRunState`
  （failAfterRegistry）与 `serverWhy` PASS。服务器侧修复：MixinAgentGuard 无 transform 视为安全、AT 目标按 side 跳过、
  CLIENT 配置不加载、SERVER 配置加载、`why` 判词按 late AT 自动启用。
- **2026-11-21（ModernUI 适配 PASS；Create 进世界边界）**：ModernUI opt-in 适配真机通过（hot-add + 聊天框无崩溃）；
  Create 标题屏 add ok，但进世界崩于 Ponder 静态 accessor 接口未改写（问题 65），世界内加另有 SERVER 配置 NPE（已修）。
- **2026-11-21（J1 白名单复测第一批）**：Windows/PCL2 + 增强 JBR 同会话热加 Architectury（311 rule/150 类）、
  Searchables、Controlling（1 rule）、Jade（57 rule）；均 `isLoaded=true`、无崩溃。Architectury/Controlling
  转 verified；**Jade 因字体变暗（问题 56）转 J2**；**JEI 19.71 被 mezz_config 前置拦下**——standalone 为无
  container 的 GAMELIBRARY，需启动期在 `mods/` 后再复测。
- **2026-11-21（Mekanism 真机复验）**：问题 58 修复生效——标题屏热加成功（注册表 93、blocks→1260、
  items→1714、层→224、`isLoaded=true`），`robit` NPE 消失；随后暴露**方法级 `-f`** 在 JBR 下不可用（问题 64：
  `Screen.renderWithTooltip`/`AbstractWidget.render` 去 final 后约 2 秒崩溃，`-Xint` 复现，xlog BCI 为负）。
- **2026-11-20（0.1.0 release notes 草案）**：`docs/releases/0.1.0.md` 按“只承诺 verified + 可选扩展 +
  已知限制 + 存档警告”组织；支持矩阵与 `tools/whitelist.py check` 一致（61 行：stock T1=7/BLACK=23；
  jbr J1=25/J2=2/BLACK=4）。candidate/fixing 不作为发布承诺。
