# AGENTS.md —— 在这个仓库里干活的契约

> 这是入口，不是文档本体。**每次会话先读 [docs/status.md](docs/status.md) 的「一句话 / 表尺 / 验证状态 / 下一步」。**
> 当前状态只写 `status.md`；架构和分层写 `design.md`；术语写 `glossary.md`；决策写 `decisions/`。
> 不要在各处复制状态——状态源只有一个。

## 1. 项目是什么

- **目标**：让 Minecraft Java 版模组在游戏运行时**安全地增删 / 更新 / 热重载**。
- **当前阶段**：只读分析器 v0 已完成；**A0（嫔级模组热加）已验证**；**A1.2（妃级/Mixin + 布局变更）主路径已在 dev client 验证**：`lanserverproperties` 热加后进世界打开 Open to LAN，LSP 控件可用；**失败回滚已覆盖 post-ModList / post-pack**（恢复 ModList/module/config/类/资源包，post-ModList 重试成功）。构造后事件监听器无法注销、存档回读未做，按 §6 尚不算完成。**mods/inbox 监听 + 预检 + toast/弹窗 + 失败分析初版已实现**（EasyLAN 检测→加载、坏 jar 失败屏、贵妃级实验性加载与警告）。贵妃级软重启未开始；**贵妃级注册表热加（实验性，add-only）已验证**：服务端热加一个方块 + BlockItem、放置存盘、重启读档 PASS（[t4-registry-hotadd.md](docs/features/t4-registry-hotadd.md)）；仍限 add-only；单人客户端资源包/创造栏已验证，多人登录同步未做，**datapack 热加载已验证**（注入 pack + 原版 `/reload`：配方/战利品/标签/机器数据，见 [t4-datapack-hotadd.md](docs/features/t4-datapack-hotadd.md)）；UI 已放行并显示实验性警告；真实 dogfood（众星捧月 1.5.0）注册层 + datapack 都通过（80 方块/181 物品；recipes 0→164、loot 0→75，真实合成与掉落 PASS；ADR-0008 Accepted，ADR-0009 Proposed）。剩余人工：点 EasyLAN 的 GUI 按钮（`/modreloader add` 已由 `-PverifyAddCommand` 自动化）。**服务端**：runtime 能在 dedicated server 加载并热加嫔级；命令注册通过重放 `RegisterCommandsEvent` 生效（根命令 93→94），重复触发的 `ServerTickEvent` 也能到达；payload 可通过 `NetworkAdaptation` 解冻注册表后注册，并修补已建立连接的 `PayloadSetup`（E1/E2 单人 + E3 跨进程均已验证）；一次性启动事件 / 世界生成注册表（维度/生物群系/结构）仍不支持（ADR-0007/0009）；built-in 注册表 add-only 的服务端热加已验证；**datapack（配方/战利品/标签/机器数据）在 dedicated server 上也会随热加重载**（fixture 热加 + 重启验证）。**工程侧（2026-11-15）**：引擎通过 `PlatformHooks` 与两端解耦，启动时有 `Capabilities` 反射锚点守卫，`src/dev` 独立 source set（不进发布 jar），`./gradlew test` 有 3 个无游戏 Java 单测。
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

# 真实 dev 语料（本机）
python3 tools/analyzer/run.py scan ~/Projects/Ex-Nihilo-Satellitium/run/mods --verbose
python3 tools/analyzer/run.py scan ~/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar --verbose
```

### Mod Reloader（NeoForge 1.21.1 / Java 21）

```bash
# 编译 + Java 单测（i18n 键/占位符、FailureKind 文案）+ 编译 dev harness
./gradlew build

# 只跑不需要游戏的 Java 单测
./gradlew test

# 启动 dev client，并在主菜单后自动热加一个嫔级 mod
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

# 众星捧月玩法验收：热加后驱动真实交互（tables/锤/筛/合成）+ 创造物品栏渲染（详见 docs/features/dogfood-gameplay.md）
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyGameplay=true -PverifyCreative=true
# 检查完把客户端留在世界里，手动玩桶/坩埚/堆肥
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyGameplay=true -PverifyCreative=true -PverifyGameplayKeepOpen=true

# 贵妃级调研：只读注册表探针（加 -PverifyRegistryRefreeze=true 会做一次受控 unfreeze/freeze，仅一次性世界）
./gradlew runClient -PverifyAddCommand=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar -PverifyRegistryProbe=true

# 主菜单入口与「已忽略」恢复（`-PverifyMenu`）
./gradlew runClient -PverifyMenu=true

# 世界内 toast 路径：已在世界时把 jar 丢进 inbox，断言只 toast、不弹窗
./gradlew runClient \
  -PverifyAddCommand=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar \
  -PverifyAddCommandWatchJar=/tmp/lanmods/mcwifipnp-1.9.0-1.21-neoforge.jar

# GUI / 文件夹监听初版验证（把 jar 放进 inbox，等待弹窗并加载）
./gradlew runClient \
  -PverifyWatch=true \
  -PwatchJar=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar
```

- 游戏内入口：`/modreloader add <jar>`（已注册；自动触发路径与世界内执行均由 harness 验证）。
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

**JBR 21 已安装并接入 `runClient`：**

```
~/Library/Java/JavaVirtualMachines/jbr-21.jdk/Contents/Home
JBR-21.0.11+10-1163.125-nomod
```

`runClient` 会自动使用 JBR 并加 `-XX:+AllowEnhancedClassRedefinition`；启动日志会打印
`[ModReloader] JVM JetBrains s.r.o. 21.0.11; enhancedRedefinition=true`。
Gradle 编译仍用 Temurin 21 toolchain，只有游戏运行用 JBR。

## 3. 硬规则

1. **只读优先**：分析器不得写 `mods/`、不得改存档、不得启动游戏。
2. **不复制代码**：Hotwire 是 Modified Polyform Strict；GPL 模组（原版 Ex Nihilo 等）只读、不复制、不打包。
3. **不删除注册表内容**：皇后级必须明确告诉用户“需要重启”；贵妃级只做实验性 add-only，必须提示“存档会依赖该 mod、不可回滚”，不要写成“支持任意注册表内容”。
4. **写操作前备份**：未来的切换器必须先 dry-run、再备份、失败可回滚。
5. **版本 / 加载器 / 许可证变更必须所有者同意**：不能由 agent 自行决定或从“目标切换”推导。
6. **不提交下载的 mod jar**：语料放 `/tmp` 或本机目录，仓库只保留工具和报告。
7. **没跑过就不要写“已验证”**：静态分析结果不等于真实切换成功；区分“预测”和“验证”。
8. **不跨界改 Ex Nihilo**：它只是 dogfood / 语料；除非所有者明确要求，否则只读。
9. **不要夸大 A0**：A0 只支持嫔级（无 mixin、无注册表）；不要在文档/UI 里写成“支持任意模组”。
10. **不要夸大 A1**：A1.2 只验证了 `lanserverproperties` + JBR + dev client；MixinRuntime 是**可选**依赖（缺它妃级被拦、嫔级照常，但构建仍需 `libs/` 的 jar）；失败回滚只覆盖 post-ModList/post-pack，构造后事件监听器和存档回读未解决；不等于通用妃级支持。
11. **不要夸大 GUI/监听**：只验证了 EasyLAN、坏 jar、Ex Nihilo 贵妃级三个场景；`scan`/`gui` 命令、失败重试、忽略记录跨重启未自动验证（`add` 命令、世界内 toast、主菜单入口与忽略恢复已有 harness）；**皇后级只提示重启**；贵妃级走实验性 add-only 热加（UI 有警告，仅验证过 fixture）。
12. **客户端启动期注册/快照：主要项已重放/补齐，还剩两项**：已重放 `FMLClientSetupEvent` / `RegisterKeyMappingsEvent` / `RegisterMenuScreensEvent` / `EntityRenderersEvent.RegisterRenderers`（并重建两个 dispatcher）/ `RegisterColorHandlersEvent.Block/.Item` / `RegisterClientExtensionsEvent`，并补齐 `ItemRenderer` 的 item→model 快照（否则物品图标是紫黑方块）；**仍未重放** `RegisterClientReloadListenersEvent`（客户端机器数据，主要给 JEI）与 `RegisterLayerDefinitions`（模型层启动时已 bake）。别写「客户端完全正常」，见 [status.md](docs/status.md) 已知问题 21。
13. **不要夸大 datapack**：只覆盖 `RegistryLayer.RELOADABLE`（配方/战利品/标签/附魔/伤害类型/纹饰/物品修饰器 + mod 自注册的 server reload listener）；**世界生成注册表**（维度/生物群系/结构）仍需重启或新世界。只能 add、不能删/改；提交后不可回滚；存档会绑定 `mod/<modId>` 这个 data pack。只验证过众星捧月 1.5.0 与 t4fixture，不要写成"支持任意 datapack"。

## 4. 目录

```
AGENTS.md                  # 本文件：入口与契约
docs/
  status.md                # 唯一状态源（先读这个）
  design.md                # 架构、分层、不变量
  glossary.md              # 术语（嫔/妃/贵妃/皇后、软重启、sidecar、registry…）
  use-cases.md             # 真实场景：什么情况下无需重启最有价值
  features/                # 功能规格与验证：a0-hot-add.md / a1-mixin-hotadd.md / gui-mod-watcher.md / server-hotadd.md / t4-registry-hotadd.md / t4-datapack-hotadd.md / dogfood-gameplay.md / lan-doctor.md
  decisions/               # ADR：为什么这样定
  research-hot-mod-reload.md  # 2026-11 调研报告；历史背景，结论以 status 为准
  research-t4-registry.md    # 贵妃级注册表适配调研（机制/边界/实验计划）
  research-t4-datapack-reload.md # 贵妃级 datapack 热加载调研（已完成；结论见 features/t4-datapack-hotadd.md）
  roadmap.md               # 长期路线；短期执行以 status 为准
  tech-debt.md             # 结构性技术债与架构升级点（P0/P1/P2）
  history.md               # 追加式变更历史（做了什么/证据/为什么）；状态只看 status.md
src/main/java/dev/modreloader/  # NeoForge 1.21.1 热加载器（A0 / A1.2）；含 capability/ 反射锚点守卫
src/dev/java/dev/modreloader/dev/  # dev-only harness（独立 source set，不进发布 jar）
src/test/java/dev/modreloader/     # 不需要游戏的 JUnit 测试
tools/analyzer/            # 只读分析器（Python）
experiments/self-attach-poc/  # 自附加 + 类重定义最小验证（Java）
experiments/server-fixture/   # 夹具：命令+tick、payload（payload 适配验证）
experiments/t4-fixture/       # 夹具：一个方块 + BlockItem + 一份最小 datapack（贵妃级注册表/datapack 热加验证）
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
