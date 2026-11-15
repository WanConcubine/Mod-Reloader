# AGENTS.md —— 在这个仓库里干活的契约

> 这是入口，不是文档本体。**每次会话先读 [docs/status.md](docs/status.md) 的「一句话 / 表尺 / 验证状态 / 下一步」。**
> 当前状态只写 `status.md`；架构和分层写 `design.md`；术语写 `glossary.md`；决策写 `decisions/`。
> 不要在各处复制状态——状态源只有一个。

## 1. 项目是什么

- **目标**：让 Minecraft Java 版模组在游戏运行时**安全地增删 / 更新 / 热重载**。
- **当前阶段**：只读分析器 v0 已完成；**A0（T2 模组热加）已验证**；**A1.2（T3/Mixin + 布局变更）主路径已在 dev client 验证**：`lanserverproperties` 热加后进世界打开 Open to LAN，LSP 控件可用；**失败回滚已覆盖 post-ModList / post-pack**（恢复 ModList/module/config/类/资源包，post-ModList 重试成功）。构造后事件监听器无法注销、存档回读未做，按 §6 尚不算完成。**mods/inbox 监听 + 预检 + toast/弹窗 + 失败分析初版已实现**（EasyLAN 检测→加载、坏 jar 失败屏、T4 不可加载）。T4 软重启未开始。剩余人工：点 EasyLAN 的 GUI 按钮（`/modreloader add` 已由 `-PverifyAddCommand` 自动化）。**服务端**：runtime 已能在 dedicated server 加载并热加「纯构造型」T2（夹具 `servertest` 验证）；启动期事件（`RegisterCommandsEvent`、`ServerStartingEvent`）无法重放，带这类依赖的服务端 mod 不支持（ADR-0007）。
- **目标版本**：**Minecraft 1.21.1**；分析器支持 Fabric + NeoForge，语料与校准锁 1.21.1。
- **运行时首发**：**NeoForge 1.21.1**（原因见 [ADR-0001](docs/decisions/0001-target-1.21.1-neoforge.md)）。
- **首个 dogfood**：`../Ex-Nihilo-Satellitium`（NeoForge 1.21.1，T4 内容模组）。
- **边界**：Ex Nihilo 是**独立项目**，只作为 dogfood / 语料；不在本仓库任务中修改它，除非所有者明确要求。
- **身份**：**runtime，不是 mod**——热加载用户已信任的第三方模组，不替代它们的功能（[ADR-0005](docs/decisions/0005-runtime-not-a-mod.md)）。
- **差异化**：注册表内容（T4）、进程内软重启、可预测 / 可回滚；**不是** Hotwire 的复刻，也不是另一个 LAN mod。
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
./gradlew build

# 启动 dev client，并在主菜单后自动热加一个 T2 mod
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

# 负向回归：缺 MixinRuntime 时 add 一个 T3 模组必须被拒且不污染状态
./gradlew runClient \
  -PverifyAddCommand=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyAddCommandModId=lanserverproperties \
  -PverifyAddCommandExpectFailure=true

# 塞入/删除语义：放入 → 删除 → 同字节拷回，断言 pending 列表与 MISSING_FILE（`-PverifyDelete`）
./gradlew runClient -PverifyDelete=true

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
- **服务端**：`./gradlew runServer -PautoAdd=<jar>`；服务端安全夹具见 [experiments/server-fixture](experiments/server-fixture/README.md)（`./build.sh` 生成 `servertest-1.0.0.jar`，用于验证服务端热加成功路径）。
- 测试目标：`EasyLAN`（T2、无 mixin）、`lanserverproperties`（T3、A1.2）。日志与限制见 [docs/features/a0-hot-add.md](docs/features/a0-hot-add.md) / [a1-mixin-hotadd.md](docs/features/a1-mixin-hotadd.md)。

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
3. **不删除注册表内容**：T4/T5 必须明确告诉用户“需要重启”；不要在文档里写成“支持”。
4. **写操作前备份**：未来的切换器必须先 dry-run、再备份、失败可回滚。
5. **版本 / 加载器 / 许可证变更必须所有者同意**：不能由 agent 自行决定或从“目标切换”推导。
6. **不提交下载的 mod jar**：语料放 `/tmp` 或本机目录，仓库只保留工具和报告。
7. **没跑过就不要写“已验证”**：静态分析结果不等于真实切换成功；区分“预测”和“验证”。
8. **不跨界改 Ex Nihilo**：它只是 dogfood / 语料；除非所有者明确要求，否则只读。
9. **不要夸大 A0**：A0 只支持 T2（无 mixin、无注册表）；不要在文档/UI 里写成“支持任意模组”。
10. **不要夸大 A1**：A1.2 只验证了 `lanserverproperties` + JBR + dev client；MixinRuntime 是**可选**依赖（缺它 T3 被拦、T2 照常，但构建仍需 `libs/` 的 jar）；失败回滚只覆盖 post-ModList/post-pack，构造后事件监听器和存档回读未解决；不等于通用 T3 支持。
11. **不要夸大 GUI/监听**：只验证了 EasyLAN、坏 jar、Ex Nihilo T4 三个场景；`scan`/`gui` 命令、失败重试、忽略记录跨重启未自动验证（`add` 命令、世界内 toast、主菜单入口与忽略恢复已有 harness）；T4/T5 只提示重启，不热加。

## 4. 目录

```
AGENTS.md                  # 本文件：入口与契约
docs/
  status.md                # 唯一状态源（先读这个）
  design.md                # 架构、分层、不变量
  glossary.md              # 术语（Tn、软重启、sidecar、registry…）
  use-cases.md             # 真实场景：什么情况下无需重启最有价值
  features/                # 功能规格与验证：a0-hot-add.md / a1-mixin-hotadd.md / gui-mod-watcher.md / lan-doctor.md
  decisions/               # ADR：为什么这样定
  research-hot-mod-reload.md  # 2026-11 调研报告；历史背景，结论以 status 为准
  roadmap.md               # 长期路线；短期执行以 status 为准
src/main/java/dev/modreloader/  # NeoForge 1.21.1 热加载器（A0 / A1.2）
tools/analyzer/            # 只读分析器（Python）
experiments/self-attach-poc/  # 自附加 + 类重定义最小验证（Java）
experiments/server-fixture/   # 服务端安全的最小 @Mod 夹具（验证服务端热加成功路径）
```

## 5. 记忆约定

- **状态变了**：只改 `docs/status.md` 的对应小节，并在末尾更新日期。
- **做了新决定**：在 `docs/decisions/` 新增 ADR，编号递增；不要改旧 ADR 的结论，只加“Superseded by”。
- **新增术语**：写进 `docs/glossary.md`，不要在聊天里造新词。
- **长文**：调研/路线可以长；`status.md` 必须一屏能读完。
- **证据**：命令、版本、结果三件事一起写；不要只写“测试通过”。

## 6. 什么算“完成”

- 分析器改动：单测全绿 + 至少一次真实 jar 扫描 + `status.md` 更新表尺。
- 文档改动：链接可点、状态与 `status.md` 不矛盾、不新增未定义术语。
- 运行时改动（未来）：真实游戏内验证 + 存档可读 + 失败可回滚；缺一项就不算完成。
