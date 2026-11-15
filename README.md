# Mod-Reloader

让 Minecraft 模组在游戏运行时**安全地增删 / 更新 / 热重载**。

> 状态（2026-11-15）：**分析器 v0 已可运行**；**A0（T2）已验证**；**A1.2（T3/Mixin + 布局变更）主路径已在 NeoForge 1.21.1 dev client 验证**：热加 `lanserverproperties` 后进世界打开 Open to LAN。**A1.2 失败回滚已跑通**（post-ModList / post-pack 强制失败，恢复 ModList/module/config/类/资源包；post-ModList 重试 add 成功）。构造后事件监听器无法注销、存档回读未验证，按契约 A1.2 尚不算完成。**mods/inbox 监听 + 预检 + toast/弹窗 + 失败分析初版已跑通**（EasyLAN 检测→加载、坏 jar 失败屏、T4 不可加载）。T4 / 软重启尚未开始。
> 短期目标是把工具做出来给社区用，论文/研究输出留到以后。
>
> **先读顺序**：[AGENTS.md](AGENTS.md) → [docs/status.md](docs/status.md)。状态只写在 status.md。

## 目标（2026-11-14 锁定）

- **Minecraft 1.21.1**
- **分析器**：Fabric + NeoForge，但语料与校准锁 1.21.1
- **运行时首发**：**NeoForge 1.21.1**
- **首个 dogfood**：`Ex-Nihilo-Satellitium`（T4 内容模组）
- **理由**：作者自有项目就是 NeoForge 1.21.1；其开发整合包 9 个模组里 6 个是 T4/T5；NeoForge 1.21.1 是 1.21.1 上最大的模组生态；NHMJ 只加不删，卸载/软重启仍是空白。

## 前置：MixinRuntime（只有 T3 需要）

| 能力 | 需要 MixinRuntime |
|---|---|
| T2 模组（无 mixin、无注册表） | **不需要** |
| T3 模组（有 mixin，含布局变更） | **需要** |

MixinRuntime 的 modId 是 **`mr`**——FML 的报错、日志、`mods/` 列表里出现的都是这个名字，
所以本项目所有面向用户的文案统一写成 **MixinRuntime (mr)**。

- 版本：`[1.0.2,)`；本仓库验证用 `MixinRuntime-1.0.2-mc1.21.jar`
- 放置：游戏 `mods/`（dev 环境是 `run/mods/`）
- 许可证：MIT

它在 [neoforge.mods.toml](src/main/resources/META-INF/neoforge.mods.toml) 里是 **`type = "optional"`**，
这是刻意的。写成 `required` 时，缺前置会让 FML 在加载阶段直接终止整个启动，并且只打印原始 modId：

```text
Missing or unsupported mandatory dependencies:
	Mod ID: 'mr', Requested by: 'modreloader', Expected range: '[1.0.2,)', Actual version: '[MISSING]'
```

（格式来自 `ModSorter#formatDependencyError`；`[[dependencies]]` 没有显示名字段，改不掉。）
那种情况下我们的代码一行都不会执行，也就没法用人话告诉用户该装什么。

改成可选之后，缺 MixinRuntime 时：

- 游戏正常启动，**T2 热加载照常工作**；
- 主菜单弹一次 toast，日志打一条 warn，**用产品名**说明「T3 需要 MixinRuntime (mr)」；
- T3 候选的预检显示「T3（含 mixin）需要安装 MixinRuntime (mr)」，Load 变灰；
- 直接 `/modreloader add` 一个 T3 模组会失败并按 LIFO 回滚，失败屏给出 `MIXIN_RUNTIME`。

## A0 已经跑通

在 NeoForge 1.21.1 dev client 中，不重启游戏热加载 `EasyLAN`（T2、无 mixin）：

```text
[ModReloader] resource packs selected after add: ... (new [mod/easylan])
[ModReloader] verify easylan: isLoaded=true, container=true, keyMappings 34 -> 34
[ModReloader] hot-added [easylan] (modules [easylan])
```

复现：

```bash
./gradlew build
./gradlew runClient -PautoAdd=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar
```

`runClient` 会自动使用 JBR 21（`~/Library/Java/JavaVirtualMachines/jbr-21.jdk`）并开启
`-XX:+AllowEnhancedClassRedefinition`；启动日志会打印 `enhancedRedefinition=true`。

实现与限制见 [A0 文档](docs/features/a0-hot-add.md)。A0 只支持 **T2**（无 mixin、无注册表）；资源包已选中并重载；EasyLAN 的 GUI 按钮和 `/modreloader add` 还需人工进世界验证一次。

## A1.2 也跑通了（Mixin + 布局变更）

在 JBR + MixinRuntime 下热加 `lanserverproperties`（4 个 mixin，给 `ShareToLanScreen` 加字段/接口），然后进世界打开 Open to LAN：

```text
[ModReloader] mixin setup: redefined=[net.minecraft.core.UUIDUtil, net.minecraft.client.gui.components.AbstractWidget], eager=[net.minecraft.client.gui.screens.ShareToLanScreen, net.minecraft.server.players.PlayerList], deferred=[]
[ModReloader] resource packs selected after add: [... mod/lanserverproperties] (new [mod/lanserverproperties])
[LanCheck] ShareToLanScreen.getPort()=25565
[LanCheck] Button '在线模式: 启用' ...
[LanCheck] A1.2 LAN screen checks passed (port=25565)
[LanCheck] RESULT: PASS
```

复现：

```bash
./gradlew runClient \
  -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyLan=true
```

`-PverifyLan` 是 dev-only 自动检查：真实建世界、打开 LAN 屏、检查 `lsp_objects` / `getPort` / 端口输入框 / PlayerList mixin，截图到 `run/screenshots/a1-lan-screen.png`，然后停客户端。

关键坑（详见 [A1 文档](docs/features/a1-mixin-hotadd.md)）：只 `prepare` 不 `postInitialise` 会丢接口合并；已 mixed 的类不能再次 transform；config 的 `JAVA_17` 会降低 Mixin 全局级别；mod resource pack 需要显式选中并 `reloadResourcePacks()`。

失败回滚验证：

```bash
./gradlew runClient \
  -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyRollback=true -PfailAfterModList=true
```

```text
[RollbackCheck] rollback state checks passed; retrying add
[RollbackCheck] retry result: hot-added [lanserverproperties] ...
[RollbackCheck] RESULT: PASS
```

`-PfailAfterPack=true` 也验证了资源包回滚。

世界内命令（新建世界后由 harness 执行 `/modreloader add`，不再需要人工）：

```bash
./gradlew runClient -PverifyAddCommand=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar
```

```text
[AddCmdCheck] dispatcher has /modreloader=true
[AddCmdCheck] /modreloader add /tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar returned 1
[AddCmdCheck] ModList easylan: isLoaded=true, container=true
[AddCmdCheck] RESULT: PASS
```

负向回归：缺 MixinRuntime 时 add 一个 T3 模组必须被拒且不污染状态：

```bash
./gradlew runClient \
  -PverifyAddCommand=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyAddCommandModId=lanserverproperties \
  -PverifyAddCommandExpectFailure=true
```

```text
[ModReloader] [lanserverproperties] declares mixins but MixinRuntime (mr) is not installed
[AddCmdCheck] ModList lanserverproperties: isLoaded=false, container=false
[AddCmdCheck] RESULT: PASS
```

世界内 toast 路径（已经在世界里时把 jar 丢进 inbox，必须只 toast、不弹窗）：

```bash
./gradlew runClient \
  -PverifyAddCommand=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar \
  -PverifyAddCommandWatchJar=/tmp/lanmods/mcwifipnp-1.9.0-1.21-neoforge.jar
```

```text
[ModReloader] new mod(s) detected in world; toast only (run /modreloader gui to open)
[AddCmdCheck] detection toast queued/visible: 1
[AddCmdCheck] in-world detection: screen=null, promptOpened=false
[AddCmdCheck] in-world RESULT: PASS (toast only, no prompt)
```

断言的是 toast 队列本身（`ToastComponent.visible`/`queued` 里按标题匹配），不是只看日志。

限制：仅 dev client + JBR；MixinRuntime 现在是**可选**依赖（缺它时 T3 被拦、T2 照常），但**构建**仍需要 `libs/` 里的 jar；构造后事件监听器无法注销、存档回读未验证，A1.2 仍不算契约完成；只验证了 `lanserverproperties`，不等于通用 T3 支持。

## mods 文件夹监听与弹窗（初版）

游戏运行时轮询 `run/mods` 和 `run/modreloader/inbox`，稳定检测到新 jar 后：

- 主菜单自动弹出候选屏；世界中只 toast（不打断）。
- 预检 T2–T5、依赖、mixin/registry/native；T4/T5 只提示“需要重启”，Load 禁用。
- 点击加载后调用现有 `HotAddEngine`；失败打开结构化失败屏（原因/建议/回滚/复制/日志）。
- 永不自动加载；忽略/失败记录在独立 `config/modreloader/watcher.json`。
- **主菜单 / 暂停菜单左下角有常驻「模组热加载」入口**；被忽略的 jar 在「已忽略」视图里可以**恢复**，点过「稍后」的 jar 也仍留在待处理列表里（主动打开列表时不按「已见过」过滤）。
- GUI/按钮/toast/失败摘要支持 `en_us` / `zh_cn`。

```text
[ModReloader] new mod candidate watchcheck-EasyLAN-neoforge-1.21.1-v1.6a.jar: easylan 1.5 · T2
[WatchCheck] prompt opened ... (1 candidate(s))
[ModReloader] UI load succeeded: hot-added [easylan]
[WatchCheck] RESULT: PASS
```

复现：

```bash
./gradlew runClient \
  -PverifyWatch=true \
  -PwatchJar=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar
```

截图：`run/screenshots/watch-prompt.png`、`run/screenshots/watch-failure.png`。详见 [计划与实测](docs/features/gui-mod-watcher.md)。

## 先做“只读”的那一半

热替换最危险的部分是“改字节码、动存档”。所以我们先从**只读分析器**开始：它不启动游戏、不改任何东西，只回答：

> 这个模组如果要在运行中关掉/更新/加上，最低需要付到什么代价？为什么？

```text
$ python3 tools/analyzer/run.py scan /path/to/mods

Scanned 7 jars
  loaders: fabric: 5  neoforge: 2
  tiers:   T3: 3  T4: 1  T5: 3
  hot-swappable (T0-T3): 3/7 (43%)
  needs restart (T4-T5): 4/7 (57%)

MOD                        VERSION    LOADER   TIER CONF  VERDICT
appleskin                  3.0.6+mc1… fabric   T3   0.59  hot-swap with caveats
jade                       15.10.6+n… neoforge T3   0.75  hot-swap with caveats
jei                        19.70.0.4… fabric   T3   0.65  hot-swap with caveats
sophisticatedbackpacks     3.26.21    neoforge T4   0.84  needs world/game-layer restart
fabric-api                 0.116.18+… fabric   T5   0.85  needs full restart
lithium                    0.15.4+mc… fabric   T5   0.95  needs full restart
sodium                     0.8.13+mc… fabric   T5   0.85  needs full restart
```

详见 [分析器文档](tools/analyzer/README.md)。

## 能力分级（T0–T5）

| Tier | 变更 | 代价 | 现状 |
|---|---|---|---|
| T0 | 资源（纹理/模型/声音） | 秒级 | 原版已支持（F3+T） |
| T1 | 数据（配方/战利品/标签/数据包注册表） | 秒级 | 原版 `/reload`；可做选择性重载 |
| T2 | 代码，无 mixin / 无注册表 | 毫秒级 | **A0 已验证（EasyLAN）** |
| T3 | 代码 + Mixin，无注册表内容 | 0.5–2 秒 | **A1.2 主路径 + 失败回滚验证（lanserverproperties，JBR）；构造后 listener 不可注销** |
| T4 | 注册表内容（方块/物品/维度） | 5–30 秒 | 空白区：需要进程内软重启 |
| T5 | native / 性能核心 / 库模组 | 只能完整重启 | 拉黑或要求重启 |

## 文档

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 入口与契约：目标、命令、硬规则、记忆约定 |
| [项目状态](docs/status.md) | **唯一状态源**：目标、表尺、验证状态、下一步 |
| [设计](docs/design.md) | 分层模型、架构、不变量、技术约束、开放问题 |
| [术语表](docs/glossary.md) | Tn、软重启、sidecar、registry 等 |
| [使用场景](docs/use-cases.md) | 什么情况下无需重启最有价值；LAN 补丁场景实测 |
| [A0 热加验证](docs/features/a0-hot-add.md) | NeoForge 1.21.1 热加 EasyLAN：实现、证据、限制 |
| [A1 热加验证](docs/features/a1-mixin-hotadd.md) | T3/Mixin 热加：JBR vs sidecar、A1.0 probe、A1.1 spike、A1.2 全流水线、失败回滚 |
| [GUI / 文件夹监听](docs/features/gui-mod-watcher.md) | 检测新增 jar、预检 tier/依赖、toast/弹窗、失败原因分析（初版实测） |
| [LAN Doctor 规格](docs/features/lan-doctor.md) | B 路线 fallback：状态 / offline / republish / 地址 |
| [决策记录](docs/decisions/README.md) | ADR-0001..0004：目标、分层、顺序、论文 |
| [调研报告](docs/research-hot-mod-reload.md) | 启动流水线、JVM/Mixin/注册表约束、Hotwire 与 NHMJ 解剖 |
| [项目定位与路线图](docs/roadmap.md) | 长期路线与调研背景；短期以 status/ADR 为准 |
| [分析器 v0](tools/analyzer/README.md) | 用法、Tier 定义、信号提取、已知限制 |
| [自附加 PoC](experiments/self-attach-poc/README.md) | 进程内拿到 `Instrumentation`，验证「改方法体成功 / 加方法被拒」 |

## 仓库结构

```
src/main/java/dev/modreloader/   # NeoForge 1.21.1 热加载器（A0 / A1.2）
  hotadd/                        # 自附加 / module / mixin / ModList / 事件 / 资源包
build.gradle, settings.gradle    # ModDevGradle 2.0.148 / NeoForge 21.1.265
tools/
  analyzer/                      # 只读分析器（Python 3.9+，无第三方依赖）
experiments/
  self-attach-poc/               # 自附加 + 类重定义最小验证（JDK 17+）
```

## 已验证的事实

本机（macOS arm64，Temurin 17）实测：

- 不依赖 `-javaagent`、不依赖 `-Djdk.attach.allowAttachSelf`，可以在运行中的进程里拿到 `Instrumentation`；
- 方法体替换有效，单类耗时亚毫秒到几毫秒；
- 给已加载类**新增方法**会被 JVM 拒绝：`UnsupportedOperationException: ... attempted to add a method`。

复现：`cd experiments/self-attach-poc && ./build.sh`。

## 设计原则

1. **只读优先**：能静态回答的问题，不启动游戏；能 dry-run 的切换，不直接改。
2. **分层升级**：先试最便宜的 tier，失败再升级；T4/T5 明确告知用户。
3. **可回滚**：任何写操作前备份；切换失败必须能回到原状。
4. **解释优先**：不只给结论，要给理由（哪个 mixin、哪个注册表、哪个 native）。
5. **开源可审计**：不做闭源 native hook；许可证 MIT（见 [LICENSE](LICENSE)）。

## 下一步

- **人工收尾**：游戏内 `/modreloader add` 手动验证；EasyLAN GUI/按键；
- **A1 产品化**：构造后 listener 回滚 / 存档回读；第二个 T3 模组；把 MixinRuntime 从 dev-only 依赖换成可分发实现；
- **GUI 完善**：世界内 toast 实测、忽略/重试持久化、批量/依赖排序、i18n、自动化 GUI 检查；
- **分析器校准**：50 个 1.21.1 模组人工标注，输出准确率；
- **T4 软重启 PoC**：以 Ex Nihilo 为 dogfood。

> Fabric 的 yarn/intermediary 映射仍是分析器的兼容项，但不是首发阻塞。

## 参考

- Hotwire：<https://modrinth.com/mod/irenehotwire> ｜ <https://github.com/dearIrene/hotwire-public>
- NeoHotModInjector：<https://github.com/yiyuyan/NeoHotModInjector>
- JVMTI `RedefineClasses`：<https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html>
- Fabric 热替换文档：<https://wiki.fabricmc.net/tutorial:hotswapping>
