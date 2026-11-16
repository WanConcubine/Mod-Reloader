# 热加兼容性矩阵（真实模组语料，首轮）

> 状态：**首轮，2026-11-16**。样本：本机 `../Ex-Nihilo-Satellitium/run/mods` 的 9 个真实 NeoForge 1.21.1 模组。
> 目的：把「别的模组会不会误打误撞成功」从推理变成实测，并找出下一个框架缺口。
> 关联：[status.md](../status.md)、[t4-registry-hotadd.md](t4-registry-hotadd.md)、[dogfood-gameplay.md](dogfood-gameplay.md)。

## 1. 方法

- 标题屏热加（`-PverifyCompat=<jar>[,<jar>...]`，`dev/CompatCheck`），逐个 jar：记录**预测 tier**、阶段、`FailureKind`、
  注册表增量、`isLoaded`。
- 用标题屏而不是进世界：只测热加引擎的路径（module / mixin / ModList / 构造 / 注册表 / 客户端资源 / 客户端注册），
  **不测 datapack**（标题屏加时世界加载会自然带上，见 [t4-datapack-hotadd.md](t4-datapack-hotadd.md) §3.3）。
- **不按预测改行为**：预测为皇后级的也照样尝试——矩阵就是要检验预测。

## 2. 结果（2026-11-16 09:38–09:41）

| jar | 预测 | AT | mixin | 结果 | 证据 |
|---|---|---|---|---|---|
| ZeroCore2 2.4.9 | 贵妃 | 无 | 有（1） | **PASS** | `ok=true`、`isLoaded=true`、items 1333→1334；redefine `Slot` / `GuiGraphics` / `ModelBakery` |
| Jade 15.10.6 | 贵妃 | **有** | 有 | 拒绝（干净） | `stage=VALIDATE kind=ACCESS_TRANSFORMER`，blocks/items 未变，客户端不崩 |
| jecharacters 4.5.15 | 嫔 | 无 | 无 | 失败 | `CONSTRUCT`：`NoClassDefFoundError: me/towdium/pinin/Keyboard`（`META-INF/jarjar` 内嵌依赖未抽出） |
| MekanismGenerators 10.7.19 | 嫔 | 无 | 无 | 拒绝（干净） | `stage=VALIDATE kind=MISSING_DEPENDENCY`：`needs [mekanism [10.7.19]] first` |
| JEI 19.27.0.34 | 贵妃 | **有** | 无 | 拒绝（干净） | `stage=VALIDATE kind=ACCESS_TRANSFORMER` |
| Mekanism 10.7.19 | 贵妃 | **有** | 无 | 扫描确认 AT，同 Jade 路径（未逐个跑） | `META-INF/accesstransformer.cfg` |
| AE2 19.2.18 | 皇后 | **有** | 有 | 扫描确认 AT（且预测皇后） | 同上 |
| guideme 21.1.22 | 贵妃 | **有** | 有 | 扫描确认 AT | 同上 |
| ExtremeReactors2 2.4.9 | 贵妃 | **有** | 无 | 扫描确认 AT | 同上 |

**汇总**：9 个真实模组里 **6 个带 AT**（67%）、1 个 JiJ 内嵌依赖失败、1 个缺依赖、**只有 1 个干净通过**。

## 3. 发现

1. **AT（access transformer）是最大的硬边界**。热加不应用 AT，而 AT 只在**类加载时**由 FML 应用；依赖 AT 的 mod 一旦读被放宽的私有成员就在构造时
   `IllegalAccessError`（Jade 实测）。对**已加载的原版类**做 AT 式 redefinition 不安全（会丢 NeoForge 对同一类的 patch），
   所以这是"要么重启、要么软重启"的边界，不是能顺手补的缺口。
   现在改为**变更前拒绝**：`FailureKind.ACCESS_TRANSFORMER` + 中英文案 + UI 预检（`ModInspection.canHotLoad` 直接返回 false）。
2. **JiJ（jar-in-jar）内嵌依赖是第二个缺口，且可补**。FML 正常启动由 `JarJarLocator` 把嵌套 jar 注册成独立 `IModFile`；
   我们只 `JarContents.of(外层 jar)`，所以 `pinin` 这类依赖不可见。补法是把嵌套 jar 也读成 `IModFile` + module layer。
3. **依赖顺序**：依赖方必须先热加被依赖方。原先会在 MODLIST 报含糊的 `no ModContainer built`，现在 VALIDATE 直接
   `MISSING_DEPENDENCY` 并给出需要的 id/版本（MekanismGenerators 实测）。
4. **预测 tier 不足以决定成败**：jecharacters / mekanismgenerators 预测嫔级却失败（JiJ / 缺依赖）；ZeroCore 预测贵妃却通过。
   tier 描述的是"注册表内容"，不描述"加载期依赖（AT/JiJ）"。`ModInspector` 已补 AT 维度。
5. 失败语义仍然成立：拒绝都发生在**任何变更之前**，`blocks/items` 不变、`isLoaded=false`、客户端不崩。

## 4. 对产品的含义

- 热加能覆盖的是「无 AT + 无 JiJ 依赖 + 无 worldgen + 无 native」的模组；在真实语料里这是**少数**。
  tier 表（嫔/妃/贵妃/皇后）描述的是内容形状，**不是**能否热加的完整判据。
- managed relaunch / soft restart 仍是"大范围覆盖"的正解（[ADR-0006](../decisions/0006-t4-managed-relaunch.md)）；
  热加是"能省一次重启就省"，不是"替代重启"。
- 下一步优先级（建议）：
  1. **JiJ 抽取**（可补的实事，能救回一类"库依赖"模组）；
  2. **AT 应用可行性 spike**：只对"热加后才加载"的类注入 AT 规则，已加载类明确不支持——先量一下 Jade/Mekanism 里有多少 AT 目标是晚加载的；
  3. 依赖预检已做；矩阵扩到 20–30 个模组，校准"预测 vs 实际"。

## 5. 复现

```bash
./gradlew runClient -PverifyCompat=/abs/a.jar[,/abs/b.jar]
# 日志关键字：[CompatCheck] START / RESULT / DONE
# 想留在世界/进世界测试，用 -PverifyGameplay=true -PverifyGameplayKeepOpen=true（见 dogfood-gameplay.md）
```

## 6. 边界

- 只测了标题屏热加；进世界后的 datapack/玩法另见 [dogfood-gameplay.md](dogfood-gameplay.md)。
- 语料是作者本机 9 个模组，不是随机抽样；6/9 带 AT 这个比例不能直接外推，但足以说明 AT 不是边缘情况。
- `CompatCheck` 会跳过崩溃后的继续测试（一个进程一个 jar），崩溃只能从 exit code + 日志判断。
