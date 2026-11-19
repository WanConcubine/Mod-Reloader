# AGENTS.md —— 在这个仓库里干活的契约

> 这是入口，不是文档本体。**每次会话先读 [docs/status.md](docs/status.md) 的「一句话 / 表尺 / 验证状态 / 下一步」。**
> 当前状态只写 `status.md`；架构和分层写 `design.md`；术语写 `glossary.md`；决策写 `decisions/`。
> 不要在各处复制状态——状态源只有一个。

## 1. 项目是什么

- **目标**：让 Minecraft Java 版模组在游戏运行时**安全地增删 / 更新 / 热重载**。
- **当前阶段**：只读分析器 v0 已完成；**A0（嫔级模组热加）已验证**；**A1.2（妃级/Mixin + 布局变更）主路径已在 dev client 验证**：`lanserverproperties` 热加后进世界打开 Open to LAN，LSP 控件可用；**失败回滚已覆盖 post-ModList / post-pack**（恢复 ModList/module/config/类/资源包，post-ModList 重试成功）。构造后事件监听器 2026-11-16 起可回滚（`EventBusSnapshot`，只覆盖“已注册的监听器”，构造器里的其它副作用仍不可撤销）、存档回读只做了专用服内容路径，按 §6 尚不算完整。**mods/inbox 监听 + 预检 + toast/弹窗 + 失败分析初版已实现**（EasyLAN 检测→加载、坏 jar 失败屏、贵妃级实验性加载与警告）。贵妃级软重启未开始；**贵妃级注册表热加（实验性，add-only）已验证**：服务端热加一个方块 + BlockItem、放置存盘、重启读档 PASS（[t4-registry-hotadd.md](docs/features/t4-registry-hotadd.md)）；仍限 add-only；单人客户端资源包/创造栏已验证，**连接后热加的注册表 id 同步已验证**（运行时自有 play 通道 + `applySnapshot`，跨进程 29475 行 id 一致；前提是两端热加同一批 jar），**datapack 热加载已验证**（注入 pack + 原版 `/reload`：配方/战利品/标签/机器数据，见 [t4-datapack-hotadd.md](docs/features/t4-datapack-hotadd.md)）；UI 已放行并显示实验性警告；真实 dogfood（众星捧月 1.5.0）注册层 + datapack 都通过（80 方块/181 物品；recipes 0→164、loot 0→75，真实合成与掉落 PASS；ADR-0008 Accepted，ADR-0009 / ADR-0010 Proposed）。剩余人工：点 EasyLAN 的 GUI 按钮（`/modreloader add` 已由 `-PverifyAddCommand` 自动化）。**服务端**：runtime 能在 dedicated server 加载并热加嫔级；命令注册通过重放 `RegisterCommandsEvent` 生效（根命令 93→94），重复触发的 `ServerTickEvent` 也能到达；payload 可通过 `NetworkAdaptation` 解冻注册表后注册，并修补已建立连接的 `PayloadSetup`（E1/E2 单人 + E3 跨进程均已验证）；一次性启动事件仍未支持（ADR-0007）；世界生成注册表（维度/生物群系/结构）在**标题屏热加 + 新建世界**下已可生效（2026-11-16 实测，`-PverifyWorldgen`），已存在的世界仍要重启（ADR-0009 注）；built-in 注册表 add-only 的服务端热加已验证；**datapack（配方/战利品/标签/机器数据）在 dedicated server 上也会随热加重载**（fixture 热加 + 重启验证）。**工程侧（2026-11-15）**：引擎通过 `PlatformHooks` 与两端解耦，启动时有 `Capabilities` 反射锚点守卫，`src/dev` 独立 source set（不进发布 jar），`./gradlew test` 有 110 个无游戏 Java 单测；`VALIDATE` 有**注册审计**（启动期 mod-bus 事件清单，已知崩溃三类变更前拒绝）；**capability 事件重放**已验证（桶/坩埚/机器的 item/fluid/energy provider 热加后可用）。**热加覆盖面（2026-11-16 矩阵首轮）**：9 个真实模组 2 PASS / 6 AT 拒绝 / 1 缺依赖；**JiJ 内嵌库已补**（`META-INF/jarjar` 的嵌套 jar 进 game layer 的 module，jecharacters 转 PASS）；**AT 在 stock JVM 仍是硬边界，JBR late AT 是实验性可行**（Jade/JEI/Controlling L1，JBR-only、默认关闭）。**2026-11-17 补强**：兼容性测试流水线 v0（`tools/pipeline`，语料/前置闭包/L1 报告）；**JiJ 嵌套 mod 会建 container**（Xaero's → XaeroLib 实测 `isLoaded=true`）；`/modreloader jbr` 诊断与 JBR 玩家指南；**无焦点 L1**（`-PnoFocus=true`，窗口不抢前台；pipeline 默认）。
- **目标版本**：**Minecraft 1.21.1**；分析器支持 Fabric + NeoForge，语料与校准锁 1.21.1。
- **运行时首发**：**NeoForge 1.21.1**（原因见 [ADR-0001](docs/decisions/0001-target-1.21.1-neoforge.md)）。
- **首个 dogfood**：`../Ex-Nihilo-Satellitium`（NeoForge 1.21.1，贵妃级内容模组）。
- **边界**：Ex Nihilo 是**独立项目**，只作为 dogfood / 语料；不在本仓库任务中修改它，除非所有者明确要求。
- **身份**：**runtime，不是 mod**——热加载用户已信任的第三方模组，不替代它们的功能（[ADR-0005](docs/decisions/0005-runtime-not-a-mod.md)）。
- **差异化**：注册表内容（贵妃级）、进程内软重启、可预测 / 可回滚；**不是** Hotwire 的复刻，也不是另一个 LAN mod。
- **短期不做论文**（见 [ADR-0004](docs/decisions/0004-no-paper-short-term.md)）。

## 2. 构建与验证

### 分析器（Python 3.9+，无第三方依赖）

```bash
# 单测
cd tools/analyzer && python3 -m unittest discover -s tests -v

# 扫描一个 mods 目录
python3 tools/analyzer/run.py scan /path/to/mods

# 输出 JSON / Markdown
python3 tools/analyzer/run.py scan /path/to/mods --json report.json --md report.md

# 真机校准：tier 预测 vs 已验证的热加结果（ground truth 只存 jar 文件名）
python3 tools/analyzer/calibrate.py \
  --ground-truth tools/analyzer/calibration/ground-truth.tsv \
  /tmp/lanmods/*.jar /tmp/user-mods/*.jar ~/Projects/Ex-Nihilo-Satellitium/run/mods/*.jar

# 真实 dev 语料（本机）
python3 tools/analyzer/run.py scan ~/Projects/Ex-Nihilo-Satellitium/run/mods --verbose
python3 tools/analyzer/run.py scan ~/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar --verbose
```

### 兼容性测试流水线（真实 jar + 前置，v0）

```bash
python3 tools/pipeline/run.py fetch --mods-dir /tmp/modreloader-corpus
python3 tools/pipeline/run.py check --mods-dir /tmp/modreloader-corpus
python3 tools/pipeline/run.py plan --scenario input
python3 tools/pipeline/run.py scenarios
python3 tools/pipeline/run.py run --scenario mouse-gestures --mods-dir /tmp/modreloader-corpus
python3 tools/pipeline/run.py run --scenario mouse-gestures-nojbr --mods-dir /tmp/modreloader-corpus
python3 tools/pipeline/run.py report
```

设计/场景/边界见 [hotadd-test-pipeline.md](docs/features/hotadd-test-pipeline.md)。JBR/late AT 是**运行时自动准备**的能力（[ADR-0015](docs/decisions/0015-player-side-zero-tinkering.md)）：玩家侧零手工（一键重启自动带参数；JBR 缺失时自动下载/整合包预置），不删能力。
包作者/管理员的步骤见 [guides/jbr.md](docs/guides/jbr.md)；`python3 tools/jbr_check.py` 可查本机 JBR 21；游戏内 `/modreloader jbr`
打印当前 JVM、参数与已安装 JBR。
**产品不变量（ADR-0015）**：安装/解压是玩家动作的上限；JBR 重启统一带两个开关，玩家只感知“用 JBR 重启后模组可热加”，不感知 per-mod 参数。
**重启 = 产品失败（切到 JBR 本身除外）**：JBR 就位后仍要求重启才能加载的模组，是缺口而不是正常路径。

### Mod Reloader（NeoForge 1.21.1 / Java 21）

```bash
# 编译 + Java 单测（i18n/占位符、FailureKind 文案、JiJ 路径）+ 编译 dev harness；不需要 libs/
./gradlew build

# 只跑不需要游戏的 Java 单测
./gradlew test

# 启动 dev client，并在主菜单后自动热加一个嫔级 mod
# 自动化建议加 -PnoFocus=true（窗口不抢前台）；交互/截图不要加
./gradlew runClient -PautoAdd=/path/to/EasyLAN-neoforge-1.21.1-v1.6a.jar

# A1.2 自动验证：热加 lanserverproperties，建世界、打开 Open to LAN、截图后退出
./gradlew runClient \
  -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyLan=true

# A1.2 失败回滚验证：强制在 ModList / 资源包之后失败
./gradlew runClient \
  -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyRollback=true -PfailAfterModList=true
./gradlew runClient \
  -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyRollback=true -PfailAfterPack=true

# 世界内 /modreloader add 自动验证：新建世界后执行命令并断言 ModList
./gradlew runClient -PverifyAddCommand=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar

# 负向回归：缺 MixinRuntime 时 add 一个妃级模组必须被拒且不污染状态
./gradlew runClient \
  -PverifyAddCommand=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyAddCommandModId=lanserverproperties \
  -PverifyAddCommandExpectFailure=true

# 塞入/删除语义：放入 → 删除 → 同字节拷回，断言 pending 列表与 MISSING_FILE（`-PverifyDelete`）
./gradlew runClient -PverifyDelete=true

# payload 端到端：热加会发包的 mod，断言客户端收到（`-PverifyPayload`）
./gradlew runClient -PverifyPayload=/tmp/payloadfix/payloadfix-1.0.0.jar

# payload 端到端（连接后再热加）：加 `-PverifyPayloadAfterJoin=true`
./gradlew runClient -PverifyPayload=/tmp/payloadfix/payloadfix-1.0.0.jar -PverifyPayloadAfterJoin=true

# 跨进程 payload：专用服 + 客户端各自热加后连接
./gradlew runServer -PautoAdd=/tmp/payloadfix/payloadfix-1.0.0.jar
./gradlew runClient -PverifyPayloadRemote=/tmp/payloadfix/payloadfix-1.0.0.jar

# 专用服控制台路径：把 jar 放进 mods/ → scan 看提示 → 短名 add（含 TAB 补全与未找到的报错）
./gradlew runServer -PserverAddCommand=/tmp/t4fixture/t4fixture-1.0.0.jar

# 热加告知层：专用服热加、客户端**故意不装**该 jar → 应收到通知（聊天+toast）再被踢，服务端日志显示 ack
./gradlew runServer -PmpNoticeServer=/tmp/t4fixture/t4fixture-1.0.0.jar
./gradlew runClient -PverifyMpNotice=true

# 布局变更边界：先加载 EditBox，再热加一个给它加接口的 mixin（JBR 下应成功；无 JBR 应被拒）
./gradlew runClient -PverifyMixinLayout=/tmp/user-mods/Searchables-neoforge-1.21.1-1.0.2.jar

# 免 JBR 的 mixin：@Inject 进一个「热加时还没加载」的类（fixture，期望 RESULT: PASS）
# 先 build 夹具：experiments/mixin-fixture/build.sh /tmp/mixinfixture
./gradlew runClient -PnoJbr=true -PverifyMixinFixture=/tmp/mixinfixture/mixinfixture-1.0.0.jar

# coremod 晚注入（jecharacters → JEI 拼音搜索）；**JEI 必须在 run/mods**，否则断言会报 JEI not installed
./gradlew runClient -PverifyPinyin=/Users/<you>/Downloads/jecharacters-neoforge-4.5.15.jar

# 托管重启：只验证 plan/脚本/spawn 路径（不会真的重启游戏；跑完自动关客户端）
# 真实“新窗口起来”的全链路要人工点一次（会退出客户端）；见 docs/features/managed-relaunch.md
./gradlew runClient -PverifyRelaunchPlan=true

# JBR 玩家路径端到端（macOS dev 已 PASS；会退出父客户端并启动第二个 JBR 窗口）
# stock Temurin → 真实下载/校验/解压 JBR → 找到它 → 用 JBR 重启 → 子进程热加列出的 J1 模组
JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x/jdk-21.0.12.1+1/Contents/Home \
  ./gradlew runClient -PnoJbr=true -PnoFocus=true \
  -PverifyJbrFlow=<jbr-j1-a.jar>,<jbr-j1-b.jar>,...

# 多人注册表 id 同步：客户端先连接并按自己的顺序热加，服务端按相反顺序热加后推快照
# （两端顺序故意不同 → id 必然不同；客户端必须应用服务端映射才能 PASS。见 t4-registry-hotadd.md §9）
./gradlew runServer -PmpRegistryServer=<modA.jar>,<modB.jar>
./gradlew runClient -PverifyMpRegistry=<modB.jar>,<modA.jar>

# 服务端+客户端同时妃级：两侧各自热加同一个 mixin jar，连接必须活着（详见 a1-mixin-hotadd.md §10.3）
./gradlew runServer -PmpFeiServer="/abs/ZeroCore2-1.21.1-2.4.9.jar|zerocore"
./gradlew runClient -PverifyMpFei="/abs/ZeroCore2-1.21.1-2.4.9.jar|zerocore"

# 贵妃级注册表热加：热加夹具 → 放置/存盘 → 放进 run/mods → 重启读档（详见 experiments/t4-fixture/README.md）
./gradlew runServer -PautoAdd=/tmp/t4fixture/t4fixture-1.0.0.jar -Pt4fixtureSelftest=true

# 贵妃级 datapack 热加载：世界内热加后断言配方/战利品/标签/合成/掉落（详见 docs/features/t4-datapack-hotadd.md）
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyDatapack=true
# 同一断言，但先在标题屏热加（GUI 路径），数据由世界加载/延迟注入生效
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyDatapack=true -PverifyDatapackAtTitle=true

# capability 重放：主菜单热加 → 建世界 → 放桶/坩埚/机器，断言 item/fluid/energy provider 存在
# 反证：加 -PskipCapabilityReplay=true，五项应全 MISSING（用来确认断言本身有效）
./gradlew runClient -PverifyCapabilities=<dogfood.jar>

# 主菜单热加 → 建世界 → 创造栏：断言 mod 自己的页签（含真实 `>` 翻页按钮）
# 加 -PverifyCreativeAtTitleWarm=true = 先开过创造栏再回主菜单热加；-PverifyCreativeAtTitleInstalled=true = 正常安装对照
./gradlew runClient -PverifyCreativeAtTitle=<dogfood.jar>

# 众星捧月玩法验收：热加后驱动真实交互（tables/锤/筛/合成）+ 创造物品栏渲染（详见 docs/features/dogfood-gameplay.md）
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyGameplay=true -PverifyCreative=true
# 检查完把客户端留在世界里，手动玩桶/坩埚/堆肥
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyGameplay=true -PverifyCreative=true -PverifyGameplayKeepOpen=true

# 实验性 late AT（JBR-only）：把 AT 的成员级 access/final 改到已加载类上；默认关闭
# 类级 `public-f <Class>`（去类的 final）不可用：JBR redefine 后构造返回 null，再来一次会 SIGSEGV，
# runtime 改字节前就拒绝；这类模组（JourneyMap 的 NativeImage）只能放 mods/ 重启（见 late-at.md §5.1）
./gradlew runClient -PverifyCompat=/abs/Jade.jar -PlateAt=true -PnoFocus=true

# AT「晚注入」可行性量测（历史，只读）：量 AT 目标类在标题屏/进世界时的加载比例（见 matrix §8）
./gradlew runClient -PverifyAtSpike=/abs/jade.jar,/abs/jei.jar

# 注册审计：只读扫 jar 的启动期事件并分级，再造一个必然被拒的致命 jar 验证拒绝点在注入之前（见 client-registration-audit.md）
./gradlew runClient -PverifyRegAudit=/abs/a.jar[,/abs/b.jar]

# /modreloader why 只读诊断（专用服控制台路径）："<jar>|<期望 token>,…"，每条断言 token 出现在报告任意一行
./gradlew runServer -PserverWhy="/abs/jade.jar|access transformer,/tmp/t4fixture/t4fixture-1.0.0.jar|experimental add-only"

# 贵妃级调研：只读注册表探针（加 -PverifyRegistryRefreeze=true 会做一次受控 unfreeze/freeze，仅一次性世界）
./gradlew runClient -PverifyAddCommand=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar -PverifyRegistryProbe=true

# 主菜单入口与「已忽略」恢复（`-PverifyMenu`）
./gradlew runClient -PverifyMenu=true

# 世界内 toast 路径：已在世界时把 jar 丢进 inbox，断言只 toast、不弹窗
./gradlew runClient \
  -PverifyAddCommand=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar \
  -PverifyAddCommandWatchJar=/tmp/lanmods/mcwifipnp-1.9.0-1.21-neoforge.jar

# 脏状态：提交后失败必须置脏，第二次 add 以 RUNTIME_DIRTY 拒绝（客户端 / 专用服）
./gradlew runClient -PverifyRunState=/tmp/t4fixture/t4fixture-1.0.0.jar -PfailAfterRegistry=true
./gradlew runServer -PserverRunState=/tmp/t4fixture/t4fixture-1.0.0.jar -PfailAfterRegistry=true -PdirtyLogSeconds=2

# 构造期直接注册的残留路径（问题 35）：fixture 在构造器里直接 Registry.register
./gradlew runClient -PverifyCompat=/tmp/t4fixture/t4fixture-1.0.0.jar -PfailAfterConstruct=true -PdirectRegister=true

# 多 jar 场景只让一个模组失败（配合 failAfterModList / failAfterConstruct）
#   -PfailAfterModListModId=<modId> / -PfailAfterConstructModId=<modId>

# GUI / 文件夹监听初版验证（把 jar 放进 inbox，等待弹窗并加载）
./gradlew runClient \
  -PverifyWatch=true \
  -PwatchJar=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar

# 客户端注册重放夹具（HUD 层 / 物品装饰 / 配方书 / 自注册 shader；先 experiments/client-fixture/build.sh /tmp/clientfixture）
./gradlew runClient -PverifyAddCommand=/tmp/clientfixture/clientfixture-1.0.0.jar \
  -PverifyAddCommandModId=clientfixture -PverifyGuiLayers=true -PverifyItemDecorators=true
# shader：热加资源重载自己会 fire RegisterShadersEvent（给 ModList 里所有容器）；
# 断言 GameRenderer 的 shader map 至少 +1（日志里夹具只应注册一次）
./gradlew runClient -PverifyCompat=/tmp/clientfixture/clientfixture-1.0.0.jar \
  -PverifyShaderCount=1 -PverifyCompatSettle=240 -PnoFocus=true
```

- 游戏内入口：`/modreloader add <jar>`；`/modreloader why <jar>` 是只读诊断（tier/AT/依赖/注册审计/结论，见 [server-hotadd.md](docs/features/server-hotadd.md) §5）。**控制台可以用短名**：`/modreloader add t4fixture-1.0.0.jar`（先在当前目录找，再找 `mods/` 与 `modreloader/inbox/`；`probe` 同样），TAB 会补全两个目录里的 jar；`/modreloader scan` 每一行末尾直接给出可粘贴的 `→ /modreloader add <名字>`。自动触发路径与世界内执行均由 harness 验证（`-PverifyAddCommand` 客户端、`-PserverAddCommand` 专用服控制台）。
- **服务端**：`./gradlew runServer -PautoAdd=<jar>`；夹具见 [experiments/server-fixture](experiments/server-fixture/README.md)：`./build.sh` → `servertest-1.0.0.jar`（命令+tick，应成功）、`./build-payload.sh` → `payloadfix-1.0.0.jar`（payload，应成功，配 `-PverifyPayload` 使用）。
- 测试目标：`EasyLAN`（嫔级、无 mixin）、`lanserverproperties`（妃级、A1.2）。日志与限制见 [docs/features/a0-hot-add.md](docs/features/a0-hot-add.md) / [a1-mixin-hotadd.md](docs/features/a1-mixin-hotadd.md)。

### 自附加 PoC（JDK 17+）

```bash
cd experiments/self-attach-poc && ./build.sh
```

### Java 21

系统注册的 JDK 只有 17/8；**Gradle toolchain 的 JDK 21 在这里**：

```
~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x/jdk-21.0.12.1+1/Contents/Home
```

`/usr/libexec/java_home` 看不到它。要在 Gradle 之外启动 1.21.1，显式用这个路径，或安装系统级 JDK 21 / JBR。

**JBR 21 已安装并接入 `runClient` 与 `runServer`：**

```
~/Library/Java/JavaVirtualMachines/jbr-21.jdk/Contents/Home
JBR-21.0.11+10-1163.125-nomod
```

`runClient` 和 `runServer` 都会自动使用 JBR 并加 `-XX:+AllowEnhancedClassRedefinition`（`build.gradle` 里的 `useJbr` helper；可用 `-PjbrJava=/path/to/jbr/bin/java` 覆盖；
JBR 不存在时只 warn，不静默降级）；启动日志会打印
`[ModReloader] JVM JetBrains s.r.o. 21.0.11; enhancedRedefinition=true`。
**stock JVM 自动化（白名单证据用）**：`-PnoJbr=true` 只是“不加 enhanced 参数”，JavaExec 默认仍用
Gradle 守护进程的 JVM（本机守护进程是 JBR）。要真跑 Temurin，先设 `JAVA_HOME` 再跑：

```bash
JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x/jdk-21.0.12.1+1/Contents/Home \
  ./gradlew runClient -PnoJbr=true -PnoFocus=true -PverifyCompat=<jar>[,<jar>...]
# 日志确认：[ModReloader] loaded on client; JVM Eclipse Adoptium ...; enhancedRedefinition=false
```

Gradle 编译用 21 toolchain，只有游戏运行用 JBR。**JBR 不是所有 mixin 的前提**：只有"改已加载类的布局"才必需；
加 `-PnoJbr=true` 可以跑无 enhanced redefinition 的路径（用来验证未加载 target 的 mixin，见 §2）。

## 3. 硬规则

1. **只读优先**：分析器不得写 `mods/`、不得改存档、不得启动游戏。
2. **不复制代码**：Hotwire 是 Modified Polyform Strict；GPL 模组（原版 Ex Nihilo 等）只读、不复制、不打包。
3. **不删除注册表内容**：皇后级必须明确告诉用户“需要重启”（**这也是产品失败，记入缺口；切到 JBR 本身除外**）；贵妃级只做实验性 add-only，必须提示“存档会依赖该 mod、不可回滚”，不要写成“支持任意注册表内容”。
4. **写操作前备份**：未来的切换器必须先 dry-run、再备份、失败可回滚。
5. **版本 / 加载器 / 许可证变更必须所有者同意**：不能由 agent 自行决定或从“目标切换”推导。
6. **不提交下载的 mod jar**：语料放 `/tmp` 或本机目录，仓库只保留工具和报告。
7. **没跑过就不要写“已验证”**：静态分析结果不等于真实切换成功；区分“预测”和“验证”。
8. **不跨界改 Ex Nihilo**：它只是 dogfood / 语料；除非所有者明确要求，否则只读。
9. **不要夸大 A0**：A0 只支持嫔级（无 mixin、无注册表）；不要在文档/UI 里写成“支持任意模组”。
10. **不要夸大 A1**：A1.2 只验证了 `lanserverproperties` + JBR + dev client（JBR 只在布局变更时需要，2026-11-16 收窄，见 a1 §10.4）；MixinRuntime 是**可选**依赖、**只通过反射调用**（缺它妃级被拦、嫔级照常；构建不需要 `libs/`）；失败回滚覆盖 post-ModList/post-pack，**2026-11-16 补了 post-construct 的游戏总线监听器**（`EventBusSnapshot`，按实例摘除，实测 38→38）；构造器里的其它副作用（静态状态/全局注册/线程）仍不可回滚，post-construct 之后重试经测量仍失败（FML `ModLoadingException`），所以那条路径是「干净回滚 + 重启」而不是「可重试」；存档回读：内容路径有专用服重启读档 PASS（`experiments/t4-fixture`），客户端内保存→重开未做；**服务端+客户端同时妃级已验证**（2026-11-16，两侧各热加 ZeroCore2，连接存活，`-PmpFeiServer` + `-PverifyMpFei`）；不等于通用妃级支持。
11. **不要夸大 GUI/监听**：只验证了 EasyLAN、坏 jar、Ex Nihilo 贵妃级三个场景；`scan`/`gui` 命令、失败重试、忽略记录跨重启未自动验证（`add` 命令、世界内 toast、主菜单入口与忽略恢复已有 harness）；**皇后级只提示重启**；贵妃级走实验性 add-only 热加（UI 有警告，仅验证过 fixture）。
12. **多人（专用服 + 独立客户端）**：连接后热加同一批 jar 时，id 由服务端快照同步给客户端（见 [t4-registry-hotadd.md](docs/features/t4-registry-hotadd.md) §9）；
    两端条目集合不一致时客户端**拒绝应用**并提示，不会部分应用、不踢人。**客户端不装 jar 的模式仍不行**（registry 的值必须本地存在）。
    `runServer` 与 `runClient` 都走 JBR + `-XX:+AllowEnhancedClassRedefinition`，专用服妃级热加已验证（2026-11-16，status 已知问题 22）。
13. **客户端启动期注册/快照：仍然是一张手写清单（结构性）**：已重放 `FMLClientSetupEvent` / `RegisterKeyMappingsEvent` / `RegisterMenuScreensEvent` / `EntityRenderersEvent.RegisterRenderers`（并重建两个 dispatcher）/ `RegisterColorHandlersEvent.Block/.Item` / `RegisterClientExtensionsEvent`，`RegisterClientReloadListenersEvent`（临时 manager + 按类名去重）、`RegisterClientTooltipComponentFactoriesEvent`（tooltip 组件工厂）、`RegisterRecipeBookCategoriesEvent`（配方书分类，复制 live map 后合并）、`EntityRenderersEvent.RegisterLayerDefinitions`（层定义；之后重建 `EntityModelSet` roots，且全容器先层后渲染器）、`RegisterShadersEvent`（shader，等热加资源重载完成后重放）、`RegisterGuiLayersEvent`（HUD 层）、`RegisterItemDecorationsEvent`（物品装饰）与 `RegisterParticleProvidersEvent`（粒子 provider，容器定向重放），`RegisterCapabilitiesEvent`（`CapabilityReplay`，只投新容器），并补齐 `ItemRenderer` 的 item→model 快照（否则物品图标是紫黑方块）；**仍未重放** `ColorResolvers`（逐个的结论见 [client-registration-audit.md](docs/features/client-registration-audit.md) §7.1）。另外热加 mod 的 **config 不会自动加载**（`registerConfig` 只登记），已由 `hotadd/ModConfigs` 按容器调 `ConfigTracker.openConfig` 补上；`SERVER` config 仍跳过。别写「客户端完全正常」：NeoForge 1.21.1 的 mod bus 上一批一次性事件，我们只重放其中十几个，2026-11-16 又漏了 `RegisterClientTooltipComponentFactoriesEvent`（AppleSkin 悬停崩）；现在是**注册审计**（`-PverifyRegAudit`）：`VALIDATE` 打印每个 jar 的 covered/recurring/datagen/crash/behavior/silent 清单，一个「一用就崩」的事件（sprite source）变更前拒绝（`FailureKind.CLIENT_REGISTRATION`），其余只 warn——覆盖清单集中在 `inspect/ReplayCatalog`（重放调用点传同一常量并运行期校验事件类），漏了哪个可报告；见 [status.md](docs/status.md) 已知问题 21 / 26 与 [client-registration-audit.md](docs/features/client-registration-audit.md)。
14. **不要夸热火加覆盖面**：兼容性矩阵首轮（9 个真实模组）只有 2 个干净通过（ZeroCore2、jecharacters）；6 个带 access transformer 的被变更前拒绝（**stock JVM 上 AT 仍是硬边界**；JBR + `-PlateAt=true` 的 late AT 是实验性可行，见 [late-at.md](docs/features/late-at.md)，默认关闭），1 个缺依赖。**late AT 只到成员级**：类级 `public-f` 去掉已加载类的 final 会让该类构造返回 null（再来一次 redefine SIGSEGV），runtime 前置拒绝；JourneyMap 因此从“可热加”退回 BLACK（已知问题 44）。**JiJ 内嵌库已补**（嵌套 jar 进 game layer 的 module，jecharacters 因此转 PASS），嵌套 **mod**（带 `mods.toml`）自 2026-11-17 起会建 container（`hotadd/NestedMods`；Xaero's → XaeroLib 实测 `ok=true`/`isLoaded=true`），但嵌套 mod 的 AT 仍拒绝、`data/`/资源包不单独注入；**coremod（class 级 `ICoreMod`）2026-11-16 起会晚注入**（jecharacters 拼音搜索已功能验证），但 method/field 级 coremod 不覆盖——「能热加」仍不等于「功能完整」。tier 只描述内容形状，不描述 AT/JiJ/依赖；别写“支持任意模组”，见 [hotadd-compatibility-matrix.md](docs/features/hotadd-compatibility-matrix.md)。
15. **热加必须告知在线客户端**：服务端热加后要给所有在线玩家发 `server_mod_notice`（mod 名/版本/jar/sha256/tier/是否注册表内容），
    并**在注册表提交与 datapack 重载之前**发——实测反例：放在末尾时通知到达的是一条已经被 `update_recipes` 弄死的连接。
    客户端没装该 jar 时会被踢、且装回之前**连不回来**（jar/资源/channel 都无法同步，只有 id 映射能同步），文案必须说清这一点。
    见 [t4-registry-hotadd.md](docs/features/t4-registry-hotadd.md) §10、[ADR-0011](docs/decisions/0011-hotadd-must-inform-clients.md)。
16. **不要夸大 datapack**：只覆盖 `RegistryLayer.RELOADABLE`（配方/战利品/标签/附魔/伤害类型/纹饰/物品修饰器 + mod 自注册的 server reload listener）；**世界生成注册表**（维度/生物群系/结构）2026-11-16 实测：**标题屏热加 + 新建世界可用**（`wgfixture:wgflat` 维度可进，`-PverifyWorldgen`），已存在的世界仍要重启，专用服启动期（世界尚未加载）热加仍不可用。只能 add、不能删/改；提交后不可回滚；存档会绑定 `mod/<modId>` 这个 data pack。只验证过众星捧月 1.5.0 与 t4fixture，不要写成"支持任意 datapack"。
17. **未经用户明确要求，不启动可见客户端**：`runClient` 和 `-Pverify*` 会打开真实 GLFW 窗口；连续跑多个场景会反复打断用户。自动化优先用 `check`／`./gradlew test`／`runServer`／`runGameTestServer`；必须起客户端时一次只跑一个场景，先说明，跑完即停。**无焦点跑法已实现**：`-PnoFocus=true`（dev agent，窗口不抢前台；pipeline `run` 默认），L2/输入/截图场景用 `--visible`；不要批量起客户端。
18. **模组白名单只记实测**：所有者实测 → agent 分析/修复 → 按 [mod-whitelist.md](docs/features/mod-whitelist.md) 分桶
    （stock 一等/二等/三等/黑名单；JBR 一等/二等）。**stock 与 JBR 分开判定**；"能加但有 bug"是二等，不是黑名单；
    黑名单必须写结构性原因。数据在 [whitelist/mods.tsv](docs/whitelist/mods.tsv)，静态分析/预测/他人报告不能单独入表。


## 4. 目录

```
AGENTS.md                  # 本文件：入口与契约
docs/
  guides/                  # 面向玩家/管理员的步骤指引（jbr.md）
  retro/                   # 复盘（2026-11-18-jbr-saga.md：协作与 JBR 长跑）
  status.md                # 唯一状态源（先读这个）
  design.md                # 架构、分层、不变量
  glossary.md              # 术语（嫔/妃/贵妃/皇后、软重启、sidecar、registry…）
  use-cases.md             # 真实场景：什么情况下无需重启最有价值
  features/                # 功能规格与验证：mod-whitelist.md（人机分级工作模式）/ a0-hot-add.md / a1-mixin-hotadd.md / gui-mod-watcher.md / server-hotadd.md / t4-registry-hotadd.md / t4-datapack-hotadd.md / dogfood-gameplay.md / hotadd-compatibility-matrix.md / client-registration-audit.md / analyzer-calibration.md / lan-doctor.md / managed-relaunch.md / hotadd-test-pipeline.md / jbr-support.md / no-focus.md / late-at.md
  decisions/               # ADR：为什么这样定
  research-hot-mod-reload.md  # 2026-11 调研报告；历史背景，结论以 status 为准
  research-t4-registry.md    # 贵妃级注册表适配调研（机制/边界/实验计划）
  research-t4-datapack-reload.md # 贵妃级 datapack 热加载调研（已完成；结论见 features/t4-datapack-hotadd.md）
  roadmap.md               # 长期路线；短期执行以 status 为准
  tech-debt.md             # 结构性技术债与架构升级点（P0/P1/P2）
  history.md               # 追加式变更历史（做了什么/证据/为什么）；状态只看 status.md
  whitelist/mods.tsv       # 模组白名单数据（stock/JBR 分桶，只记实测）
src/main/java/dev/modreloader/  # NeoForge 1.21.1 热加载器（A0 / A1.2）；含 capability/ 反射锚点守卫
src/dev/java/dev/modreloader/dev/  # dev-only harness（独立 source set，不进发布 jar）
src/test/java/dev/modreloader/     # 不需要游戏的 JUnit 测试
tools/whitelist.py         # 白名单渲染/校验（render / check，视图 docs/whitelist/README.md）
tools/analyzer/            # 只读分析器（Python）
tools/pipeline/            # 兼容性测试流水线（语料/前置闭包/L1 报告；Python）
experiments/self-attach-poc/  # 自附加 + 类重定义最小验证（Java）
experiments/server-fixture/   # 夹具：命令+tick、payload（payload 适配验证）
experiments/t4-fixture/       # 夹具：一个方块 + BlockItem + 一份最小 datapack（贵妃级注册表/datapack 热加验证）
experiments/client-fixture/   # 夹具：1 个 HUD 层 + 1 个物品装饰（客户端启动期事件重放验证）
experiments/mixin-fixture/    # 夹具：@Inject 进一个「热加时未加载」的类（免 JBR 的 mixin 路径验证）
experiments/worldgen-fixture/ # 夹具：只有数据，1 个超平坦维度（WORLDGEN 边界实验）
experiments/access-rewrite-poc/ # 已否决（ADR-0014）：改写调用方实现 AT 字段/方法子集的 Stage-0 证据
```

## 5. 记忆约定

- **状态变了**：只改 `docs/status.md` 的对应小节，并在末尾更新日期。
- **过程记录**：做了什么、证据、为什么写 [docs/history.md](docs/history.md)（追加）；`status.md` 只留当前状态和最近几条，不要把历史堆回去。
- **做了新决定**：在 `docs/decisions/` 新增 ADR，编号递增；不要改旧 ADR 的结论，只加“Superseded by”。
- **新增术语**：写进 `docs/glossary.md`，不要在聊天里造新词。
- **长文**：调研/路线可以长；`status.md` 必须一屏能读完。
- **证据**：命令、版本、结果三件事一起写；不要只写“测试通过”。

## 6. 什么算“完成”

- 分析器改动：单测全绿 + 至少一次真实 jar 扫描 + `status.md` 更新表尺。
- 文档改动：链接可点、状态与 `status.md` 不矛盾、不新增未定义术语。
- 运行时改动（未来）：真实游戏内验证 + 存档可读 + 失败可回滚；缺一项就不算完成。构建层面还要 `./gradlew build` 通过（含单测与 dev harness 编译）。

## 7. 与所有者协作（2026-11-18 复盘）

> 复盘全文：[docs/retro/2026-11-18-jbr-saga.md](docs/retro/2026-11-18-jbr-saga.md)。以下五条是硬约定。

1. **删/改玩家可见能力、产品方向、默认行为之前，先问，得到明确确认再做。** 不把“我理解的意思”当成所有者的决定。
2. **不交付未验证的构建**：一次诊断 → 一次构建；说清哪些验证过、哪些没有（例：macOS 资产哈希实测，Windows 全链路未跑）。
3. **修 bug 可以直接做；涉及取舍（安全/体验/性能/删功能）先给方案与回滚方式。**
4. **情绪优先于进度**：对方说停就停；疲惫/绝望时不再推技术方案。
5. **不删除历史**：复盘与失败记录追加到 `docs/retro/` 与 `docs/history.md`，方便下次不再犯。
6. **提交/推送等增益任务免逐次确认**（2026-11-19 所有者）：常规 `commit` 与 `origin/main` 推送直接做，
   回复里报告提交号与验证状态；破坏性操作（force push、`reset --hard`、删分支/文件）、仓库外共享状态、
   版本/许可证变更仍先问（硬规则 5）。
