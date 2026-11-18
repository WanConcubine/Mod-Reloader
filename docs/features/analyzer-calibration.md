# 分析器校准（Tier 预测 vs 运行期人工标注，2026-11-16）

> 状态：**已做首轮校准**（11 个有真机结论的 jar，10 个 tier 一致，1 个已知过估）。
> 2026-11-17 补第二把尺：**方向性门禁**（漏估必须为 0）与**运行期差分测试**（`ModInspector` vs 本分析器），差分当场抓到一个漏估 bug（JourneyMap 被预测成 PIN），见 §7。
> 工具：[tools/analyzer/calibrate.py](../../tools/analyzer/calibrate.py) + [calibration/ground-truth.tsv](../../tools/analyzer/calibration/ground-truth.tsv)。
> 为什么不提交 jar：语料是本机下载的第三方模组（AGENTS 硬规则 6），ground truth 只用**文件名**做键。

## 1. 问题

分析器（只读、纯 Python）的输出是 tier（嫔/妃/贵妃/皇后）+ 理由 + 置信度。它此前**没有真机标注**：
「Tier 预测 vs 人工标注」一直挂在 status 的「未验证」里，准确率未知。
同时它只识别 Fabric 的 `accessWidener`，**看不到 NeoForge 的 `META-INF/accesstransformer.cfg`**——
而 AT 恰好是运行期**第一拒绝原因**（兼容性矩阵 9 个真实模组里 6 个被 AT 拒）。

## 2. 方法

```bash
# 1) 跑分析器 + 对比 ground truth（jar 路径本机自备，按文件名匹配）
python3 tools/analyzer/calibrate.py --ground-truth tools/analyzer/calibration/ground-truth.tsv \
  /tmp/lanmods/*.jar /tmp/user-mods/*.jar ~/Projects/Ex-Nihilo-Satellitium/run/mods/*.jar …

# 2) 纯规则 / 解析的单测
cd tools/analyzer && python3 -m unittest discover -s tests -v
```

ground truth 的每一行都来自**真机**：`pass` 是 harness 验证过的热加结果，`refused:at` / `refused:dependency`
是运行期在 `VALIDATE` 真的拒过（见 [status](../status.md) 表尺、[hotadd-compatibility-matrix](hotadd-compatibility-matrix.md)）。

## 3. 首轮结果（2026-11-16 20:4x）

| jar | 分析器 tier | AT | JiJ | 真机（tier/结果） | 判定 |
|---|---|---|---|---|---|
| clientfixture 1.0.0 | PIN | 0 | 0 | pin / pass | ✅ |
| wgfixture 1.0.0 | PIN | 0 | 0 | pin / pass（维度数据生效） | ✅ |
| t4fixture 1.0.0 | **GUIFEI** | 0 | 0 | guifei / pass | ✅（修了一个 bug，见 §4.2） |
| servertest 1.0.0 | PIN | 0 | 0 | pin / pass | ✅ |
| payloadfix 1.0.0 | PIN | 0 | 0 | pin / pass | ✅ |
| EasyLAN 1.6a | PIN | 0 | 0 | pin / pass | ✅ |
| lanserverproperties 1.13.2 | FEI | 0 | 0 | fei / pass（4 mixin） | ✅ |
| AppleSkin 3.0.9 | PIN | 0 | 0 | pin / pass（HUD 层、物品装饰） | ✅ |
| 众星捧月 1.5.0 | GUIFEI | 0 | 0 | guifei / pass（注册 + datapack） | ✅ |
| ZeroCore2 2.4.9 | **GUIFEI** | 0 | 0 | **fei / pass** | ⚠️ 过估（安全方向，见 §4.3） |
| jecharacters 4.5.15 | PIN | 0 | **2** | pin / pass（JiJ 抽取后） | ✅ |
| Jade 15.10.6 | FEI | **57** | 0 | - / refused:at | ✅ AT 被抓到 |
| JEI 19.27.0.34 | GUIFEI | **21** | 0 | - / refused:at | ✅ |
| AE2 19.2.18 | HUANGHOU | **75** | 0 | - / refused:at | ✅ |
| Mekanism 10.7.19 | GUIFEI | **94** | 0 | - / refused:at | ✅ |
| guideme 21.1.22 | HUANGHOU | **47** | 0 | - / refused:at | ✅ |
| ExtremeReactors2 2.4.9 | GUIFEI | **1** | 0 | - / refused:at | ✅ |
| MekanismGenerators | GUIFEI | **0** | 0 | - / refused:**dependency** | ✅ 纠正了一个假设 |
| Searchables 1.0.2 | FEI | 0 | 0 | fei / 未做完整热加 | 未标注 |
| Controlling 19.0.5 | HUANGHOU | **1** | 0 | - / 未标注 | 未标注 |
| mcwifipnp 1.9.0 | FEI | 0 | 0 | - / 未标注 | 未标注 |

**11 个有标注的 jar：10 个 tier 一致，1 个已知过估。**

## 4. 结论

### 4.1 AT 现在能看见了，而且和运行期的独立测量对得上

分析器新增 `META-INF/accesstransformer.cfg` 解析（`at_rules` / `at_targets`，规则语义与运行期的
`inspect/AccessTransformerRules` 一致），默认表格新增 **AT 列**（规则数，`-` = 没有），verbose/JSON 里有 targets。
6 个带 AT 的 jar 的**规则数**与运行期 `-PverifyAtSpike` 的独立测量**逐个相同**（57 / 21 / 75 / 94 / 47 / 1）——
两条完全不同的代码路径（Python 读 jar vs 游戏内读 cfg）互相验证。

顺带纠正一个口口相传的假设：**MekanismGenerators 没有 AT**，它在矩阵里被拒是因为**缺依赖**，不是 AT。
「6 个 AT jar」始终是 Jade / JEI / AE2 / Mekanism / guideme / ExtremeReactors2。

### 4.2 修掉一个**危险方向**的 bug：嵌套 DeferredRegister 漏检

t4fixture 第一轮被预测成 **PIN**，而运行期实际要开注册表窗口（**GUIFEI**）。原因：真实 mod 用的是
`DeferredRegister$Blocks` / `$Items` 这些**嵌套子类**的 `register*` 方法，而旧规则只匹配
`cls_name.endswith("/DeferredRegister")` —— 嵌套类名以 `$Blocks` 结尾，直接漏掉。
现在改成「类名里含 `DeferredRegister` 且方法名以 `register` 开头」，并有单测
（`DeferredRegisterNestedClassesTest`）钉住 `DeferredRegister$Blocks#register` → GUIFEI。

这条值得单独记：**过估只多一条警告，漏估会让用户以为可以热加、结果构造时崩**。

### 4.3 剩一个过估（ZeroCore2），方向安全，暂不修

ZeroCore2 预测 GUIFEI（理由里能看到 `DeferredRegister#register` + Block/Item/EntityType 等内容类引用），
但真机只需要 fei（它注册的是 capabilities 而不是方块/物品内容）。过估的代价只是更保守的警告，方向安全；
要修就得区分「引用内容类」与「真的注册内容」（也就是运行期 `ModInspector.registry()` 那种字节码判定），
成本明显高于收益——保持现状，写在这里。

### 4.4 tier 不是可热加性的全部（重申 AGENTS §14）

tier 描述**内容形状**；AT / 缺依赖 / JiJ 嵌套 mod / 启动期事件是**另外几条轴**。校准表把几条轴放在一起看，
才解释得了「Jade 是 FEI 但被拒（AT）」「jecharacters 是 PIN 但曾经失败（JiJ 内嵌库缺失）」这两件事。

## 5. 怎么复现

- ground truth 只存文件名，本机把 jar 放到任意目录，把路径传给 `calibrate.py`；输出是逐 jar 的
  `tier / AT / JiJ / verified / state` 表 + `labelled/matches/under/over` 计数。
- 退出码按**方向**分流：漏估（预测得比真机低）和“分析失败（T?）”**永远**退出 1；
  过估默认也退出 1，CI / 批量跑用 `--allow-overestimate` 只放过过估。
  理由：过估只是多一条警告，漏估是对用户说“可加载”然后构造期崩。
- 新增真机结论后，往 `calibration/ground-truth.tsv` 加一行即可（`untested` 的行不参与判定）。
- 全绿基线（2026-11-17，含 fixture 与 `/tmp` 语料）：
  `labelled jars: 11, matches: 10, mismatches: 1 (under: 0, over: 1, failed: 0)`，唯一 mismatch 是 ZeroCore2 过估（§4.3）。

## 6. 仍缺

- **Fabric 语料没校准**：Fabric 生产 jar 是 intermediary 名（`net.minecraft.class_310`），tier 规则退化（status 已知问题 3）；
  当前有真机结论的语料全是 NeoForge。
- 只有 3 个 jar 还是「未标注」（Searchables / Controlling / mcwifipnp）——要做完整热加才有标注。
- tier 的**置信度**没有校准（只知道 tier 对不对，不知道 0.55 vs 0.85 意味着什么）。

## 7. 第二把尺：方向性门禁 + 运行期差分（2026-11-17）

§3 的那张表只回答“tier 字符串对不对”，它有两个盲区：

1. **没有方向**：`PIN` 被预测成 `GUIFEI`（过估）和 `GUIFEI` 被预测成 `PIN`（漏估）在表里都只是一行 MISMATCH，
   但后者会让弹窗说“可加载”然后构造期崩。`calibrate.py` 现在按 `TIER_RANK` 分流成 `under/over/unknown`，
   漏估与 `T?` 永远退出 1（§5）。
2. **只覆盖“跑到了 tier”的 jar**：被 AT / 依赖 / 注册审计提前拒掉的 jar 在 TSV 里是 `-`，永远不进校准。
   而这类 jar 恰恰是新规则最容易错的地方。

所以加了**差分测试**：同一个 jar 分别过运行期 `inspect/ModInspector`（字节码 + `mods.toml`，管着 UI 的
tier 显示）和 `tools/analyzer`（Python，管着 `scan` / 流水线），逐 jar 比较 tier 与各轴信号。
真值不是人工写的，而是两边互相作证：一致 → 至少没实现漂移；不一致 → 必须有一个人改，或者明确写“这是策略差异”。

**2026-11-17 实测（`/tmp/modreloader-corpus` 9 个 jar，运行期 tier 取自 `tools/pipeline/reports/*/report.json` 的
`RESULT ... tier=`）**：修复前 6 一致 / 3 不一致，其中 **JourneyMap 是危险方向**——运行期 `FEI`，分析器 `PIN`。
根因：`mixin` 配置里的条目是**相对 `package`** 的，而 JourneyMap 写的是 `client.GuiMixin` / `server.ChunkMapAccessor`
（子包带点）。旧代码 `if "." in entry: 当作全限定名` 于是 26 个 mixin 类一个都没算上，`mixin_class_count=0` → `PIN`。
修法（`tools/analyzer/modreloader/mixins.py` 的 `_qualify`）：**按 jar 里类是否真的存在**来解析候选名
（`package.entry` 优先，其次裸 `entry`），并把 `test_mixins_package.py` 4 个用例钉住。
修复后：JourneyMap → `HUANGHOU`（26 个 mixin、25 个 target），差分变 **7 一致 / 2 不一致，且 2 个都是过估**
（Controlling、JourneyMap：分析器有“render-core mixin + 侵入注入 ⇒ 皇后级”策略，运行期 inspector 没有这条轴，判 `FEI`）。
策略差异保留，方向安全；写在这里而不是悄悄改规则。

**决策层比 tier 字符串更重要**：`HotAddEngine.VALIDATE` 里**没有 tier 门禁**（只拦 AT / 缺依赖 / 注册审计 / 客户端类等），
tier 只在 UI 的 `canHotLoad` 里当门禁。于是出现“UI 会拒、引擎能加”的分叉，已有实例：
Xaero's Minimap 两边都判 `HUANGHOU`（`META-INF/coremods.json` → native 轴 + 分析器 render-core 轴），
但流水线 `minimap` 场景 `ok=true`、`isLoaded=true`（coremod 晚注入 + 9 个 mixin config 都过了）。
这不是 tier 算错，而是**tier 被当成了可热加性本身**（AGENTS §14 早就写着不是）。要把“分级正确”落到决策上，
需要一张决策矩阵：`预测可加载 / 预测需重启` × `真机 pass / refused`，漏报（预测可加载、真机失败）为 0 才算过；
现在的 TSV 只能算其中两格。

### 仍缺（按优先级）

1. **把运行期 tier 自动进校准集**：`report.json` 里已有 `RESULT ... tier=`，`calibrate.py` 应能吃 pipeline 报告，
   而不是只吃手写 TSV——差分这次抓到 bug 就是因为它覆盖了 TSV 没有的 jar。
2. **决策层矩阵 + CI 门槛**：漏报必须为 0；过报要可见。当前 `calibrate.py` 只做 tier，不做 `canHotLoad`。
3. **逐轴标注**：`mixins / registry / native / at / jij / deps` 各一列，tier 由轴**推导**出来（单一函数），
   这样错在轴上而不是错在最终标签上；否则“tier 对了、轴错了”会被掩盖。
4. **置信度校准与 abstain**：`T?` 只在读不了 jar 时出现；规则全没命中时也应输出“未知/需人工”，
   而不是给一个确信的 `PIN`（JourneyMap 就是这么错的）。
5. **版本锁**：以上全部只对 NeoForge 1.21.1 语料成立；换版本 / 换加载器必须重跑，不能沿用数字。
