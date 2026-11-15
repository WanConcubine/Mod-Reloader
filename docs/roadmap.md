# 项目定位与长期路线（v0.1）

> 目标：做一个**通用、开源、可发表**的 Minecraft 模组热重载工具，而不是 Hotwire 的复刻品。
> 日期：2026-11-14
>
> **2026-11-14 修订：** 短期（6 个月）以**工具**为主，不安排论文产出；第 2 节与第 5 节保留为长期备选。当前执行顺序是「只读分析器 → 游戏内解释 → T4 软重启」，论文只在有数据、有合作者时再启动。
>
> **状态提示：** 本文是路线调研 + 长期计划；**短期执行目标以 [status.md](status.md) 和 [ADR-0001](decisions/0001-target-1.21.1-neoforge.md) 为准**。第 5 节论文计划为长期备选（[ADR-0004](decisions/0004-no-paper-short-term.md)）。

---

## 0. 目标锁定（2026-11-14）

- **Minecraft**：1.21.1
- **分析器**：Fabric + NeoForge 都支持，但语料与校准全部锁 1.21.1
- **运行时首发**：**NeoForge 1.21.1**（原计划 Fabric，已改）
- **首个 dogfood 对象**：`Ex-Nihilo-Satellitium`（NeoForge 1.21.1，T4 内容模组）

### 为什么从 Fabric 改成 NeoForge

1. **作者自己的项目就是 NeoForge 1.21.1**，可以拿真实 mod 当第一个测试对象，而不是空想。
2. **首轮实测**：Ex Nihilo 1.5.0 是 T4（`DeferredRegister#register` 注册方块/物品）；它的开发整合包 `run/mods` 里 9 个模组有 **6 个是 T4/T5**，只有 Jade 是 T3。Fabric 式的 T3 热开关在这个生态里价值有限。
3. **NeoForge 1.21.1 是 1.21.1 上最大的模组生态**（Modrinth 约 22.5k 项目，Fabric 约 20.3k），大型科技/魔法整合包几乎都在这里。
4. **NHMJ 只加不删、且只做客户端**，NeoForge 的“卸载/软重启”仍是空白。
5. **痛点可量化**：该开发整合包从 ModLauncher 启动到进世界约 **83 秒**；T4 软重启的目标是把这段压到 5–30 秒。

### 代价

FML / ModLauncher / `ModuleLayer` 比 Fabric 的 `Knot` 难得多。缓解策略：**先做 T4 软重启（整层重建），而不是外科手术式类替换**——软重启绕开了很多 FML 内部细节，且正好覆盖 Ex Nihilo 这类注册表内容模组。

---

---

## 1. 先把“颠覆性”说清楚

### 1.1 已经被人占掉的部分

- **Hotwire**（Fabric 客户端，2026-10）已经证明：纯客户端、无注册表内容、非性能核心的模组，可以在 0.5–1 秒内热开关。
- **NHMJ**（NeoForge 客户端）已经证明：可以在运行期重放 FML 的启动流水线，把新模组注入进去（只加不删）。
- **MixinRuntime** 已经证明：运行期注册 Mixin 配置、retransform 已加载类，是可行的。

所以“热重载模组”这个**工程命题**不再是空白。再做一个 Fabric 客户端开关工具，最多是“第二个实现”，很难称为颠覆，也很难发论文。

### 1.2 仍然空白的部分（真正的机会）

| 空白 | 为什么难 | 价值 |
|---|---|---|
| **注册表内容模组**（方块/物品/维度/生物群系） | 注册表冻结、数值 ID 进存档/网络、worldgen 固化进区块 | 这是大型整合包的核心；Hotwire/NHMJ 都不碰 |
| **进程内软重启**（丢弃整个游戏层，保留窗口/GL/进程） | GL/窗口所有权、native 库、单例、类卸载 | 唯一能覆盖“任意模组”的通用路线；目前没有成熟 MC 实现 |
| **可重载性预测**（哪些能热、哪些不能、为什么） | 需要静态分析 + 运行时证据 + 语料库标注 | 既是工具卖点，也是论文的核心贡献 |
| **事务化正确性**（dry-run、回滚、存档一致性） | 现有工具都是“尽力而为”，失败可能半死 | 决定用户敢不敢用 |
| **跨加载器统一抽象**（Fabric / NeoForge / Forge） | 三套加载器内部模型完全不同 | “通用工具”的题眼 |
| **不可合作的三方二进制** | 经典 DSU 假设源码可改/可配合；这里是混淆、Mixin 织入、无源码 | 这是学术上的真空白 |

### 1.3 一句话定位

> **不是“让模组热重载”，而是“给不可合作的三方 JVM 插件，做一个分层、可预测、可回滚的动态更新系统，并用 Minecraft 作为极端案例验证”。**

这个定位同时满足：社区有用、工程可落地、学术有 novelty。

---

## 2. 研究框题（paper framing）

### 2.1 领域归属

这不是“Minecraft 小工具”，它属于 **Dynamic Software Updating（DSU）** 与 **managed runtime / bytecode transformation** 的交叉：

- 经典 DSU：Hicks & Nettles（PLDI 2001 / TOPLAS 2005）、Ginseng（PLDI 2009）、UpStare（USENIX ATC 2009）、Rubah（OOPSLA 2014）等。
- 工业实践：JRebel、DCEVM、OSGi、Erlang hot code loading。
- 共同假设：**源码可改、编译器可配合、更新单元有明确边界**。
- 我们的场景：**二进制、无源码、被 Mixin 二次织入、没有更新边界、应用状态巨大**。这是一个经典 DSU 没有覆盖的组合。

### 2.2 Thesis（建议）

> **Tiered Reloading**：对每个变更，先判定它触及的最深层次（资源 / 数据 / 方法体 / 类布局 / 注册表 / native），在该层次用最便宜的安全机制执行；当低层机制无法保证正确性时，升级到“进程内游戏层软重启”；整个过程事务化，可 dry-run、可回滚。

### 2.3 贡献（论文的四根柱子）

1. **分类学（Taxonomy）**：把“模组变更”拆成 6 个 tier，给出每个 tier 的 JVM/加载器约束与可判定条件。
2. **测量研究（Measurement）**：对 N 个真实模组（建议 200–500）做静态 + 动态标注，回答“多少模组能热、失败原因分布、与 Hotwire/NHMJ 的覆盖差异”。**这部分单独就能发 workshop paper。**
3. **预测器（Predictor）**：用静态特征（mixin 目标、注册调用、静态状态、线程/native、反射、依赖）预测 tier 与失败风险；给出置信度与解释。
4. **运行时（Runtime）**：分层执行器 + 事务化切换 + 软重启兜底；评估成功率、延迟、内存/类泄漏、存档完整性。

### 2.4 评估设计（审稿人最看重的）

| 维度 | 指标 | 方法 |
|---|---|---|
| 有效性 | 热切换成功率、需重启比例 | 自动化 harness，N 个 mod × M 次切换 |
| 性能 | 切换延迟 p50/p95、内存增长、类数量增长 | JFR / `jcmd` class histogram / NMT |
| 正确性 | 切换后功能自检通过率、存档可读性、崩溃率 | 预置 gametest / 世界快照对比 |
| 覆盖 | 与 Hotwire / NHMJ / 完整重启的对比 | 同一语料库、同一操作 |
| 可用性 | 报告可理解性、误报率 | 小规模用户研究 / modpack 作者访谈 |

### 2.5 目标 venue（务实版）

- **先投 workshop/short paper**：MPLR（Managed Programming Languages and Runtimes）、VMIL（SPLASH workshop）——6–9 个月可期。
- **再扩 full paper**：ASE / ISSTA（工具 + 实证）、ECOOP / OOPSLA（若强调字节码与类型安全）、USENIX ATC（若强调软重启架构）。
- 诚实预期：**“能发”取决于评估有多硬**，不是取决于 demo 有多酷。Hotwire 的存在意味着 prior art 必须被正面处理。

---

## 3. 系统设计：分层重载（Tiered Reload）

### 3.1 Tier 模型

| Tier | 变更类型 | 机制 | 单次耗时 | 覆盖 | 风险 |
|---|---|---|---|---|---|
| **T0** | 资源（纹理/模型/声音/语言/shader） | 资源重载 | < 1 s | 全部 | 极低 |
| **T1** | 数据（配方/战利品/标签/函数/数据包注册表） | `/reload` 或选择性重载 | 0.5–10 s | 内容型 | 低 |
| **T2** | 方法体 / 常量池 | `redefineClasses` | ms 级 | 开发期、热修复 | 中 |
| **T3** | 类布局（新增方法/字段/接口） | sidecar / holder / trampoline + indy 重写 | 0.5–2 s | 客户端、非注册表 | 高 |
| **T4** | 注册表内容 / 任意模组 | 进程内游戏层软重启（新 ClassLoader 代际） | 5–30 s | 理论上全部 | 很高 |
| **T5** | native / 性能核心 / 无法安全重建 | 不支持，要求完整重启 | — | — | — |

**决策器**：`Tier = max(静态分析预测, dry-run 证据)`；若 T3 失败则升级 T4；若 T4 不可用（native/平台）则 T5。

### 3.2 模块划分

```
mod-reloader/
  analyzer/      # jar 扫描、mixin 目标、注册调用、静态状态、线程/native、依赖图
  predictor/     # tier 预测 + 置信度 + 解释（规则 + 可选 ML）
  agent/         # 自附加、原始字节、mixin journal、注册 journal
  reloader/      # T2/T3：离线 re-mix、class split、indy 重写、tripwire
  restarter/     # T4：boot/game 两层、ClassLoader 代际、GL/native 保留
  guard/         # dry-run、健康检查、回滚、存档备份
  bench/         # 语料库、自动化 harness、指标采集
  ui/            # 报告、切换、回滚（先 CLI，后 GUI）
```

**关键原则：analyzer 先行。** 它只读、不写字节码、不改存档，风险最低，却同时是：

- 社区可用的第一版产品（“为什么这个模组必须重启？”）；
- 论文的测量数据来源；
- 后续 T3/T4 的决策引擎。

### 3.3 第一版产品：Reload Doctor（建议名）

功能：

- 扫描一个整合包/实例，列出每个模组的**可重载等级**、**理由**、**置信度**；
- 回答“我能不能在游戏里关掉 X？”“更新 X 需要重启吗？”；
- 导出报告（Markdown/JSON），可贴给整合包作者；
- 后续加“一键安全切换”（带备份/回滚）。

它不碰危险操作，却能立刻建立社区信任，并积累语料库。**这是最低风险、最高信息量的起点。**

---

## 4. 12 个月路线图

| 阶段 | 时间 | 交付 | 验收指标 |
|---|---|---|---|
| **P0 语料库 + 分析器** | 第 1–3 周 | 200+ mod jar 的扫描器；tier 规则 v0；50 个手工标注 | 规则与人工标注一致率 ≥ 70%；报告可读 |
| **P1 Reload Doctor 发布** | 第 4–8 周 | CLI/GUI 报告；整合包作者可用 | 10 个真实整合包跑通；收集 50 份反馈 |
| **P2 T0/T1 事务化切换** | 第 9–14 周 | 资源/数据重载 + 备份/回滚 | 切换失败可 100% 回滚；存档零损坏 |
| **P3 T2/T3（Fabric）** | 第 15–26 周 | 方法体/布局热替换；20 个 mod 自动化测试 | 成功率 ≥ 80%（安全子集）；p95 < 2 s |
| **P4 T4 服务端软重启** | 第 27–38 周 | 专用服务端 boot/game 分层 | 切换 < 30 s；带方块 mod 可用；零 native 错误 |
| **P5 论文 + 客户端 T4** | 第 39–52 周 | workshop paper + 开源发布 | 投稿；社区版可用 |

> 如果目标是 full paper，建议在 P2 之后就找一位做 DSU/PL 的合作者，把“正确性”部分做扎实；单靠工程指标很难过 OOPSLA/ATC 的审稿。

---

## 5. 论文计划（先写 4 页，再扩）

### 5.1 Workshop paper（6–9 个月）

**Title 候选**：*How Reloadable Are Minecraft Mods? A Measurement Study of Third-Party JVM Plugin Updates*

**结构**：

1. 问题：模组更新/增删为什么必须重启？
2. 分类学：6 个 tier + 判定规则。
3. 测量：N 个 mod 的 tier 分布、失败原因、与 Hotwire/NHMJ 的覆盖差异。
4. 讨论：哪些空白值得系统化解决（引出 T4）。
5. 威胁与可复现性。

**卖点**：这是**第一个**对“Minecraft 模组可重载性”的系统测量；数据本身有价值，且为后续系统论文铺路。

### 5.2 Full paper（12–18 个月）

**Title 候选**：*Tiered Reloading: Safe Runtime Updates for Uncooperative Binary Plugins*

**新增**：

- T4 软重启架构与实现；
- 事务化切换的正确性论证（至少是操作语义级别）；
- 与完整重启、Hotwire、NHMJ 的对比；
- 真实用户/服主的使用数据。

### 5.3 学术诚信与 prior art

- **必须**引用并讨论 Hotwire、NHMJ、MixinRuntime、Rubah、Ginseng、UpStare、Hicks & Nettles。
- 不能声称“首个热重载模组工具”；可以声称“首个覆盖注册表内容/首个分层+软重启/首个可预测可回滚”。
- 保留实验日志、语料库版本、随机种子；开源 harness。

---

## 6. 风险与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| **Hotwire prior art** | 审稿人/用户会说“已经有人做了” | 明确差异化：注册表内容、软重启、预测、正确性、跨加载器 |
| **范围过大** | 三条路线都想做，12 个月做不完 | 严格按 tier 推进；P0/P1 先出社区价值 |
| **MC 版本漂移** | 26.x 后版本节奏快 | 抽象层 + 只支持 LTS 式版本；CI 矩阵 |
| **存档损坏** | 一次事故就毁掉口碑 | 所有写操作前强制备份；T4 只做“新代际”，不原地改注册表 |
| **“工程 not research”** | 审稿人常见的拒稿理由 | 必须有分类学 + 测量 + 可复现评估，不能只有 demo |
| **法律/许可** | Mojang EULA、mod 许可、Hotwire 许可 | 不分发 MC/mod 本体；只做工具；许可证选 MIT |
| **安全** | 自附加/动态 agent 被平台视为可疑 | 明确告知、可关闭、开源可审计 |

---

## 7. 当前执行顺序（2026-11-14 修订）

1. **分析器校准**：50 个 1.21.1 模组人工标注 tier；算准确率；修 `mekanismgenerators` 这类跨模组注册漏判。
2. **游戏内解释**：NeoForge 1.21.1 的 `/modreload why <mod>`，只读，先给玩家/整合包作者用。
3. **T4 软重启 PoC**：NeoForge 1.21.1，dogfood Ex Nihilo；先把“进世界 83 秒”压到 5–30 秒。
4. **规则声明式化**：JSON/YAML spec，Python CLI 与未来 Java 模组共用，避免逻辑漂移。

> 原先的“200–500 语料 + 2 页 abstract”是论文导向；短期不做论文，见 [ADR-0004](decisions/0004-no-paper-short-term.md)。

## 8. 已拍板（2026-11-14）

| 事项 | 决定 | 记录 |
|---|---|---|
| 首发平台 | Minecraft 1.21.1；运行时 **NeoForge 1.21.1** | [ADR-0001](decisions/0001-target-1.21.1-neoforge.md) |
| 分级模型 | T0–T5 + T?，Tier = 最低安全机制 | [ADR-0002](decisions/0002-tier-model.md) |
| 推进顺序 | 只读分析器 → 游戏内解释 → T4 软重启 | [ADR-0003](decisions/0003-analyzer-first.md) |
| 论文 | 短期不做 | [ADR-0004](decisions/0004-no-paper-short-term.md) |
| 身份 | runtime，不是 mod；A 主线，B 仅 fallback | [ADR-0005](decisions/0005-runtime-not-a-mod.md) |

### 仍未决

- **T4 先做客户端还是专用服务端？** 服务端没有 GL/窗口，可能更容易；但 dogfood 在客户端。
- **规则如何共享**：Python CLI 与 Java 模组共用 JSON/YAML spec，还是各自实现？
- **语料规模**：50 个标注够不够校准？是否需要 200+？
