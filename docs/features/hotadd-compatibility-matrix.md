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

## 2. 结果（2026-11-16 09:38–09:41；jecharacters 行于 09:56 复跑）

| jar | 预测 | AT | mixin | 结果 | 证据 |
|---|---|---|---|---|---|
| ZeroCore2 2.4.9 | 贵妃 | 无 | 有（1） | **PASS** | `ok=true`、`isLoaded=true`、items 1333→1334；redefine `Slot` / `GuiGraphics` / `ModelBakery` |
| Jade 15.10.6 | 贵妃 | **有** | 有 | 拒绝（干净） | `stage=VALIDATE kind=ACCESS_TRANSFORMER`，blocks/items 未变，客户端不崩 |
| jecharacters 4.5.15 | 嫔 | 无 | 无 | **PASS**（JiJ 抽取后） | 2026-11-16 09:56 复跑：`ok=true`、`isLoaded=true`、`jar-in-jar: 2 nested jar(s) -> modules [JustEnoughCharacters.coremod.neoforge, PinIn]`、`modules [jecharacters, PinIn, JustEnoughCharacters.coremod.neoforge]`、`mod/jecharacters` 资源包选中并重载。边界见 §3.2 |
| MekanismGenerators 10.7.19 | 嫔 | 无 | 无 | 拒绝（干净） | `stage=VALIDATE kind=MISSING_DEPENDENCY`：`needs [mekanism [10.7.19]] first` |
| JEI 19.27.0.34 | 贵妃 | **有** | 无 | 拒绝（干净） | `stage=VALIDATE kind=ACCESS_TRANSFORMER` |
| Mekanism 10.7.19 | 贵妃 | **有** | 无 | 扫描确认 AT，同 Jade 路径（未逐个跑） | `META-INF/accesstransformer.cfg` |
| AE2 19.2.18 | 皇后 | **有** | 有 | 扫描确认 AT（且预测皇后） | 同上 |
| guideme 21.1.22 | 贵妃 | **有** | 有 | 扫描确认 AT | 同上 |
| ExtremeReactors2 2.4.9 | 贵妃 | **有** | 无 | 扫描确认 AT | 同上 |

**汇总**：9 个真实模组里 **6 个带 AT**（67%）、1 个缺依赖、**2 个干净通过**（ZeroCore2、jecharacters）。
jecharacters 原先因 JiJ 内嵌依赖在 CONSTRUCT 失败，修好抽取后转为 PASS——**JiJ 是可补的缺口，AT 不是**。

## 3. 发现

1. **AT（access transformer）是最大的硬边界**。热加不应用 AT，而 AT 只在**类加载时**由 FML 应用；依赖 AT 的 mod 一旦读被放宽的私有成员就在构造时
   `IllegalAccessError`（Jade 实测）。对**已加载的原版类**做 AT 式 redefinition 不安全（会丢 NeoForge 对同一类的 patch），
   所以这是"要么重启、要么软重启"的边界，不是能顺手补的缺口。
   现在改为**变更前拒绝**：`FailureKind.ACCESS_TRANSFORMER` + 中英文案 + UI 预检（`ModInspection.canHotLoad` 直接返回 false）。
2. **JiJ（jar-in-jar）内嵌依赖是第二个缺口，且已经补上**。FML 正常启动由 `JarJarLocator`
   （`JarInJarDependencyLocator`）读 `META-INF/jarjar/metadata.json`，把嵌套 jar 注册成独立 `IModFile` 并放进 game layer 的 module path；
   我们只 `JarContents.of(外层 jar)`，所以 `pinin` 这类依赖对热加 mod 不可见。
   现在 `hotadd/JarInJar` 做 locator 的前半段：读 metadata（用 NeoForge 自己的 `MetadataIOHandler`）→ 把每个嵌套 jar 抽到
   `<gamedir>/modreloader/jarjar/<内容哈希>/<原名>.jar` → `SecureJar.from(...)`；
   `ModuleInjector.addToGameLayer(files, extraJars)` 把它们和 mod 的 module 一起塞进 game layer（双向 read 边 + `packageLookup`）。
   实测 jecharacters：`modules [jecharacters, PinIn, JustEnoughCharacters.coremod.neoforge]`，构造通过、资源包重载、`PASS`。
   **剩下的边界**：① 只恢复类路径——带 `mods.toml` 的嵌套 mod 会拿到 module，但**没有 `ModContainer`**，它的 `@Mod` 构造器不跑
   （语料里两个嵌套 jar 都是纯库，没有触发这条）；② `isObfuscated: true` 的嵌套 jar 需要 FML 构建期的反混淆，直接跳过并 warn；
   ③ jecharacters 的 coremod（`META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod`，藏在嵌套 jar 里）**2026-11-16 起会晚注入**：
   `hotadd/LateCoremod` 扫外层 + `META-INF/jarjar` 里的服务文件，把 coremod jar 复制成带 `Automatic-Module-Name` 的副本、
   在**先于 module 注入**创建的独立 `ModuleLayer` 里加载（否则同包模块解析冲突），再按目标应用：
   已加载 → `redefineClasses`，未加载 → `ClassFileTransformer` 在 define 时变换，回滚可恢复。
   实测 jecharacters：4 个目标变换（JEI 搜索/过滤器/前缀解析 + 原版 `SearchTree`）、80 个未安装目标 deferred，
   `ElementSearchLowMem.matches("tieding","铁锭")` 变换前 false、变换后 true（`-PverifyPinyin`，JEI 需在 `run/mods`）。
   **剩余边界**：只覆盖 class 级 `ITransformer`（`ClassNode`/`byte[]`）；method/field 级与「目标在别的 class loader」只报告 skipped；
   真机只跑了 at-load 路径，redefine 路径未实测。
3. **依赖顺序**：依赖方必须先热加被依赖方。原先会在 MODLIST 报含糊的 `no ModContainer built`，现在 VALIDATE 直接
   `MISSING_DEPENDENCY` 并给出需要的 id/版本（MekanismGenerators 实测）。
4. **预测 tier 不足以决定成败**：矩阵首轮 jecharacters / mekanismgenerators 预测嫔级却失败（当时的 JiJ / 缺依赖，JiJ 已修）；ZeroCore 预测贵妃却通过。
   tier 描述的是"注册表内容"，不描述"加载期依赖（AT/JiJ）"。`ModInspector` 已补 AT 维度；JiJ 现在能跑通，但预测仍不体现它
   （有 JiJ 的模组**可能**因为嵌套 jar 缺失/混淆/coremod 失败，这属于运行期才看得出来）。
5. 失败语义仍然成立：拒绝都发生在**任何变更之前**，`blocks/items` 不变、`isLoaded=false`、客户端不崩。

## 4. 对产品的含义

- 热加能覆盖的是「无 AT + 无 worldgen + 无 native」的模组；JiJ 内嵌**库**与 **coremod（class 级）** 已不再是边界（嵌套 mod、method/field 级 coremod 仍是）。
  在真实语料里可覆盖的仍是**少数**——6/9 卡在 AT。
  tier 表（嫔/妃/贵妃/皇后）描述的是内容形状，**不是**能否热加的完整判据。
- managed relaunch / soft restart 仍是"大范围覆盖"的正解（[ADR-0006](../decisions/0006-t4-managed-relaunch.md)）；
  热加是"能省一次重启就省"，不是"替代重启"。
- 下一步优先级（建议）：
  1. ~~**JiJ 抽取**~~ 已做（2026-11-16，见 §3.2 与 §5）；
  2. ~~**AT 应用可行性 spike**~~ 已量（2026-11-16，见 §8）：最好情况下也只有 19% 的 AT 目标类还没加载，**结论是不做**；
  3. 依赖预检已做；矩阵扩到 20–30 个模组，校准"预测 vs 实际"。

## 5. JiJ 抽取：做了什么（2026-11-16）

原计划落点写在这里，实际实现与结论：

- 嵌套 jar 由 `META-INF/jarjar/metadata.json` 描述：`jars[].path` 是 jar 内路径（如
  `META-INF/jarjar/PinIn-1.6.0.jar`）。解析用 NeoForge 自己的
  `net.neoforged.jarjar.metadata.MetadataIOHandler`（和 locator 同一套代码，不自己拼 JSON）。
- 语料里的两个嵌套 jar **都没有 `mods.toml`**（纯库）→ 不需要 `IModFile`/container，
  把它们的类放进 game layer 的 module path 即可，外层 mod 的 automatic module 就能读到。
- 落点：新增 [hotadd/JarInJar](../../src/main/java/dev/modreloader/hotadd/JarInJar.java)（读 metadata + 抽取 + `SecureJar`）；
  [HotAddEngine.add](../../src/main/java/dev/modreloader/hotadd/HotAddEngine.java) 在 `Stage.MODULE` 里、`ModuleInjector.snapshot()` 之前调用它；
  [ModuleInjector.addToGameLayer](../../src/main/java/dev/modreloader/hotadd/ModuleInjector.java) 增加
  `addToGameLayer(Collection<ModFileInfo>, Collection<SecureJar>)` 重载（旧签名保留并委托）。
- 抽取位置是 **内容寻址** 的：`<gamedir>/modreloader/jarjar/<sha256 前 16 位>/<原文件名>`。这样重试（回滚后再 add）会拿到
  **同一个路径**，`ModuleInjector` 的「同名 module 必须同路径」检查才能通过——JVM 不能 undefine module，
  路径一变就会抛 `Module name X was already used by ...`。
- 新增 `FailureKind.JAR_IN_JAR`（中英文案 + `FailureKeys`）：metadata 读不出来时在 `Stage.MODULE` 明确失败，而不是等到构造期 `NoClassDefFoundError`。
- 验收（2026-11-16 09:56）：`runClient -PverifyCompat=<jecharacters jar>` →
  `jar-in-jar: 2 nested jar(s) ... -> modules [JustEnoughCharacters.coremod.neoforge, PinIn]`、
  `hot-added [jecharacters] (modules [jecharacters, PinIn, JustEnoughCharacters.coremod.neoforge])`、
  `isLoaded=true`、`mod/jecharacters` 资源包重载、`RESULT ... ok=true`（终版代码 10:01 复跑同样 `ok=true`）。回归：同一次进程里先 ZeroCore2（mixin redefine）再 jecharacters，
  两个都 `ok=true`（09:57）。
- 重试路径也验证过（这是内容寻址路径的目的）：`-PverifyCompat=<jecharacters>,<jecharacters> -PfailAfterModList=true` →
  两次都在 `stage=MODLIST` 被注入的失败打断（`rollback: mod list ok` / `rollback: module layer ok`），
  第二次**没有** `Module name PinIn was already used by ...`（09:58）。
- 仍未做（记录，不阻塞）：嵌套 **mod**（带 `mods.toml`）目前只拿到 module、没有 `ModContainer`，构造器不跑；
  coremod（`FMLCorePlugin`）不会运行。

## 6. 复现

```bash
./gradlew runClient -PverifyCompat=/abs/a.jar[,/abs/b.jar]
# 日志关键字：[CompatCheck] START / RESULT / DONE
# 想留在世界/进世界测试，用 -PverifyGameplay=true -PverifyGameplayKeepOpen=true（见 dogfood-gameplay.md）
```

## 7. 边界

- 只测了标题屏热加；进世界后的 datapack/玩法另见 [dogfood-gameplay.md](dogfood-gameplay.md)。
- 语料是作者本机 9 个模组，不是随机抽样；6/9 带 AT 这个比例不能直接外推，但足以说明 AT 不是边缘情况。
- 「jecharacters PASS」现在包含 **coremod 晚注入**：`-PverifyPinyin` 断言 JEI 的搜索类被改写并返回拼音匹配。
  但断言用的是 JEI 私有 `matches`（stub 元素），没有开 JEI 界面手动输入；其它 coremod 的**功能**仍要单独的断言，
  不能从「引擎 PASS」推断（coremod 也可能依赖 method/field 级变换，那部分只报告 skipped）。
- `CompatCheck` 一次热加一个 jar；某个 jar 把进程搞崩了，后面的 jar 就不会跑——崩溃只能从 exit code + 日志判断。

## 8. AT 应用可行性量测（2026-11-16，只读，不实装）

「只对晚加载的类注入 AT」值不值得做？先量再说。AT 是**类粒度**生效的（一个类加载时，它的全部 AT 规则一起应用），
所以正确的指标就是：真实 jar 的 AT 目标类里，有多少在热加那一刻**还没加载**。

`dev/AtSpikeCheck`（`-PverifyAtSpike=<jar>[,<jar>]`，只读）解析每个 jar 的 `META-INF/accesstransformer.cfg`，
在**标题屏**（≈世界加载前）和**进世界后**各量一次「已加载类集合」，然后按类统计。

| jar | 规则 | 目标类 | 标题屏已加载 | 进世界已加载 |
|---|---|---|---|---|
| Jade 15.10.6 | 57 | 39 | 32（**18% 未加载**） | 36（8%） |
| JEI 19.27.0.34 | 21 | 12 | 9（25%） | 11（8%） |
| Mekanism 10.7.19 | 94 | 45 | 38（16%） | 42（7%） |
| AE2 19.2.18 | 75 | 37 | 27（27%） | 34（8%） |
| guideme 21.1.22 | 47 | 17 | 16（6%） | 16（6%） |
| ExtremeReactors2 2.4.9 | 1 | 1 | 0（100%） | 0（100%） |
| **合计** | **295** | **151** | **122（81% 已加载，19% 未加载）** | **139（92% 已加载，8% 未加载）** |

上下文：标题屏已加载 25 964 个类，进世界后 31 249 个。明细见 `run/logs`（`[AtSpike]` 前缀）。

**未加载的都是什么**：客户端 GUI（`EditBox`、`CreativeModeInventoryScreen`、`ConnectScreen`、`AbstractSelectionList`、
`Screen$DeferredTooltipRendering`）、datagen/gametest（`RecipeProvider`、`BlockModelGenerators`、`GameTestHelper`）、
服务端专用（`ChunkMap`、`ServerGamePacketListenerImpl`）。也就是说，剩下那 8–19% 里很大一部分是客户端热加**基本不会碰**的类。

**结论：不做。** 理由三条：

1. **收益上限就是「未加载占比」**：最好的时机（标题屏、世界都没加载）也只有 19%，进世界后 8%，且集中在 datagen/服务端类上。
   而「晚 AT」要解决的是「mod 在构造期就要用某个被放宽的成员」——那种目标类几乎必然已经加载。
2. **一个已加载目标上缺一条规则就足够失败**：Jade 就是构造期 `IllegalAccessError`（§3.1），不会因为我们补上了另外 32 个类的 AT 就好起来。
3. **代价与失败语义都不划算**：要在类定义期挂 transformer，和 NeoForge 的 patch 流水线、Mixin 的变换顺序竞争；
   而失败点会落在「注册表已提交、不可回滚」之后。相比之下，软重启（[ADR-0006](../decisions/0006-t4-managed-relaunch.md)）是确定能覆盖的办法。

### 8.1 逐 jar 判定：这条能不能彻底关掉？（2026-11-16 19:52 复跑，排除 datagen）

上面的百分比回答的是「有多少目标还没加载」，但**决定性问题**是另一个：**有没有任何 jar 的
所有运行期 AT 目标都还没加载**（只需要一个已加载目标，晚 AT 就不可能）。`dev/AtSpikeCheck` 现在逐 jar 直接输出这个判定，
并把 datagen / gametest 目标排除在外（`inspect/AccessTransformerRules.isDatagenClass`，纯规则 + 6 个单测）。

| jar | 运行期目标 | 标题屏已加载 | 进世界已加载 | 晚 AT 可行？ |
|---|---|---|---|---|
| Jade 15.10.6 | 39 | 32 | 35 | **否** |
| JEI 19.27.0.34 | 12 | 9 | 11 | **否** |
| Mekanism 10.7.19 | 45 | 38 | 42 | **否** |
| AE2 19.2.18 | 33（另 4 个 datagen） | 26 | 33 | **否** |
| guideme 21.1.22 | 17 | 16 | 16 | **否** |
| ExtremeReactors2 2.4.9 | **0**（唯一目标 `net.minecraft.data.recipes.RecipeProvider` 是 datagen） | — | — | **n/a（AT 只服务 datagen）** |
| **合计** | **146** | **121** | **137** | **0/6 可行** |

日志原文（进世界那一刻）：

```
[AtSpike] Jade-...jar [in world]: late-AT viable here: NO (39 runtime target(s), 35 already loaded: [...])
[AtSpike] ExtremeReactors2-...jar [in world]: late-AT viable here: NO (0 runtime target(s), 0 already loaded; every target is a datagen class)
[AtSpike] SUMMARY [in world]: 6 jar(s) with ATs; late-AT viable on 0 of them (1 jar(s) have datagen-only ATs)
```

**结论：这条彻底关闭**（不再是「先量着看」）：6 个真实 jar 在两个时机上都没有一个满足「所有运行期目标都未加载」，
连最理想的情形（标题屏、世界未加载）也是 0/6。

**顺带把 datagen-only 的误伤变成可见事实**：ExtremeReactors2 的 AT 只放宽 `RecipeProvider`，运行期永不加载。
放行仍然**不在本次结论内**（还有别的边界，例如它本身是贵妃级内容模组），但拒绝文案现在会写清 AT 的组成：
`… ships META-INF/accesstransformer.cfg (1 rule(s) over 1 target class(es) (0 runtime, 1 datagen-only); every target
is a datagen class, which a playing game never loads); access transformers only apply at startup. Restart …`
（`inspect/AccessTransformerRules`，纯解析 + 6 个单测；UI 预检与 `-PverifyCompat` 都走同一条消息）。

复现：`./gradlew runClient -PverifyAtSpike=<jar>[,<jar>]`。它会像 `-PverifyAddCommand` 那样新建一个世界来取「进世界」那组数字
（会留下一个 `run/saves` 里的测试存档），除读类和读 jar 外不做任何写操作。
