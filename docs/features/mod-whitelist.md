# 模组白名单分级（人机工作模式）

> 状态：**工作模式已定**（2026-11-17，所有者）；数据表在 [whitelist/mods.tsv](../whitelist/mods.tsv)，
> 2026-11-19 起扩到 61 行（新增渲染/内容/联机一轮）；2026-11-21 合并冗余 J1 后为 60 行。
> 新行多为 `candidate`，等所有者实例复测后转 `verified`。
>
> 一句话：**所有者实测 → agent 分析和修 → 按“能不能热加 + 有没有 bug”分桶**。
> 只记运行期证据；静态分析、预测、他人报告都不能单独入表（AGENTS 硬规则 7）。

## 1. 分工

- **所有者**：拿常用模组在真实实例里热加，反馈“能加 / 不能加 + 有没有 bug”，附 jar 名、版本、JVM、现象或日志。
- **agent**：分析阻碍（tier / AT / 依赖 / JiJ / 注册审计 / payload / 客户端注册 / coremod / worldgen…），
  必要时改代码；维护本表与证据；给出“修 / 记录 / 黑名单”的下一步。
- **判定只认运行时证据**；同一个 mod 在 **stock JVM 与 JBR 下分别判定**（两行，互不覆盖）。
  例外：**stock 已可热加（T1/T2）的模组不单列 J1/J2**——T1/T2 是更强结论，JBR 复验证据并入该行。

## 2. 六个桶

| 桶 | 含义 | 进入条件 |
|---|---|---|
| **一等（T1）** | stock JVM 可热加、未发现 bug | 所有者实测通过 + 无可见问题 |
| **二等（T2）** | stock JVM 可热加、但有 bug | 实测通过但功能/表现有问题，bug 已记录 |
| **三等（T3）** | 默认判不可热加，但 agent 分析后认为实际可热加 | 分析结论 + 待处理 |
| **JBR 一等（J1）** | JBR 下可热加、无 bug | 仅 JBR 成立（stock 不成立） |
| **JBR 二等（J2）** | JBR 下可热加、有 bug | 同上，bug 已记录 |
| **黑名单（BLACK）** | 分析确认完全无法热加 | agent 结案，写明结构性原因与依据 |

三条容易混淆的规则：

1. **T1/T2/T3 只针对 stock JVM**（不靠 `-XX:+AllowEnhancedClassRedefinition` / `-Dmodreloader.lateAt=true`）。
2. **重启是产品失败（切到 JBR 除外）**：JBR 就位后仍只能“重启才能用”的模组，按缺口记录/修复，不把“重启”当成正常可接受结果；stock 上的 BLACK 若 JBR 可救，应进 JBR 复测而不是直接接受重启。
3. **黑名单是按 JVM 的**：可以出现“stock 黑名单 / JBR 一等”（典型：带 AT 的模组在 stock 不行，JBR late AT 可以）。
4. **“能加但有 bug”永远不是黑名单**：T2/J2 是长期状态，bug 修好才升一等。
5. **T1/T2 优先于 J1/J2**：stock 能热加时不再另立 JBR 行；J1/J2 只留给“stock 不行、仅 JBR 成立”的模组。
   JBR 下的复验结果写进同一行的证据列（例：Simple Voice Chat 的 T1 行）。

## 3. 流转

- **T1 →（复现出 bug）T2**：记录 bug、复现步骤、影响面；转入优先修。
- **T2 →（修好并复验）T1**：注明修复的改动与复验证据；修不动就留在 T2，bug 长期记录。
- **T3 →（agent 处理后可热加）T2**：按所有者规则先入二等；若当次实测无 bug，再由所有者决定是否升 T1。
- **T3 →（处理仍不可加）BLACK**：写清为什么不可行；若只能重启，按 ADR-0015 记为产品失败/缺口，而不是正常替代路径。
- **J1 ↔ J2** 同上；JBR 的结论不自动影响 stock 那一行。
- **BLACK 不是“失败一次”**：必须能指出结构性原因（例：class-level AT 在 stock 无法模拟、payload 只能在启动期注册、
  注册表内容无删除/更新路径、一次性启动事件未重放…），并注明“重启/JBR 是否能解决”。

## 4. 数据表字段（`docs/whitelist/mods.tsv`）

> 人读版：[whitelist/README.md](../whitelist/README.md)（由 `python3 tools/whitelist.py render --write` 生成，不要手改）。
> 改完 TSV 后跑 `python3 tools/whitelist.py check` 校验，再 `render --write` 刷新视图。

```
name  jar  sha256  jvm  bucket  tier  bug  state  evidence  updated
```

| 列 | 含义 |
|---|---|
| `name` | mod 显示名（可带 modId） |
| `jar` | jar 文件名（不含路径） |
| `sha256` | 前 16 位即可；用来区分同名不同版本 |
| `jvm` | `stock` 或 `jbr` |
| `bucket` | `T1` / `T2` / `T3` / `J1` / `J2` / `BLACK` |
| `tier` | 运行时**实际用到**的 tier（pin/fei/guifei/huanghou），不是预测 |
| `bug` | 一行摘要；无则 `-`；详细经过写 history.md |
| `state` | `candidate`（harness 过、待所有者实例确认）/ `verified` / `fixing` / `fixed` / `wontfix` |
| `evidence` | harness 命令、pipeline 报告路径，或 `owner report YYYY-MM-DD` + 现象 |
| `updated` | 该行最后更新时间 |

新增/修改一行时同时更新 `updated`；一个 mod 在 stock 与 JBR 各有一行是正常状态。

## 4.6 读法（一屏版）

```bash
python3 tools/whitelist.py render          # 打印分组视图（Markdown）
python3 tools/whitelist.py render --write  # 刷新 docs/whitelist/README.md
python3 tools/whitelist.py check           # 校验 TSV（桶/环境/状态/重复/日期）
```

- 先看**总览**（每种环境各桶的数量），再看对应环境的表；
- **J1/J2 里的 AT 模组**要 JBR + `-Dmodreloader.lateAt=true`；只开 enhanced redefinition 不够；这是**内部证据**：玩家侧只做“一次 JBR 重启”，运行时统一带两个开关，界面不展示 per-mod 参数；
- 同一 mod 可能有两行（stock / JBR），结论可以相反；
- `candidate` 只是 harness 过，`verified` 才是确认过的；BLACK 一定带结构性原因和替代路径。

```bash
# stock（Temurin 21）：注意 -PnoJbr=true 只是不加 enhanced 参数，JavaExec 默认仍用 Gradle 守护进程的 JVM。
JAVA_HOME=~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x/jdk-21.0.12.1+1/Contents/Home \
  ./gradlew runClient -PnoJbr=true -PnoFocus=true -PverifyCompat=<jar>[,<jar>...]

# JBR：仓库默认（runClient 自动用 JBR + -XX:+AllowEnhancedClassRedefinition）
./gradlew runClient -PnoFocus=true -PverifyCompat=<jar>[,<jar>...]
```

- 判定以日志里的 `[CompatCheck] RESULT ... predicted=<tier> ok=<bool> stage=<stage> kind=<kind>` 为准；
- harness 在**标题屏**加，世界里会多加载一批类，结果可能更严格（布局 mixin、已加载目标）；
- 从 harness 得来的行记 `state=candidate`（`verified` 留给“所有者实例实际用过、确认无 bug/或确认结构拒绝”）。

## 5. 所有者反馈格式（越具体越快）

1. mod 名 + 版本；
2. jar 文件名（能给 sha256 更好）；
3. **stock 还是 JBR**；JBR 的话说明有没有 `-XX:+AllowEnhancedClassRedefinition`、有没有开 late AT；
4. 操作：什么时候把 jar 放进去（标题屏 / 世界内）、怎么触发的加载；
5. 结果：能加 / 不能加；报错原文或截图；`logs/latest.log` 里 `[ModReloader]` 开头的行；
6. **“能加但功能不对”的具体表现**——这是 T1 与 T2 的唯一区别，必须单独说。

## 6. 已有 harness 证据（仅供参考，不直接入表）

dev harness 验证过的：EasyLAN、lanserverproperties、ZeroCore2、jecharacters、AppleSkin、众星捧月 1.5.0、
pipeline 的 minimap / mouse-gestures / hud / library 等。它们多数跑在 **JBR dev client** 上，
不能直接当“stock 一等”；等所有者实测后再入表。

## 6.5 库（GAMELIBRARY）不进白名单

带 `FMLModType: GAMELIBRARY` 的 jar（没有 `[[mods]]`，例如独立分发的 `mezz_config`）是**依赖库，不是模组**：
它没有 ModContainer，热加管线无法加载。这类 jar 不是“黑名单模组”，而是**不参与分级**——
放进 `mods/` 重启即可；如果它只是某个模组的 JiJ 依赖，通常不需要单独处理。

## 7. 为什么这样分

- **stock / JBR 的差别是机制性的**（enhanced redefinition），不是版本或运气，所以必须分开记；
- **“能加”和“好用”是两件事**（T1 vs T2），分开才能让“优先修 bug”有依据；
- **黑名单必须带结构性原因**，否则会把“还没修”误判成“不能修”，白名单也就失去了意义。
