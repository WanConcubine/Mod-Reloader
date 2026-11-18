# 热加兼容性测试流水线（corpus + 分层 + 报告）

> 状态：**v0 已实现（2026-11-17）**：L0 静态检查 + L1 标题屏热加（`-PverifyCompat`）+ 语料/依赖闭包/报告。
> L2（进世界后的功能断言）仍是下一步；已有 L1 只能证明“引擎路径不崩、拒绝理由正确”，**不能证明模组功能正常**。
> L1 默认走 [no-focus.md](no-focus.md)（窗口不抢前台；但用户点击/OS 仍可能给焦点，pipeline 会判 `FOCUS_STOLEN`）；L2/输入/截图用 `--visible`。
> **2026-11-17 起 AT 有了实验性的 `late_at` 场景**（JBR-only，见 [late-at.md](late-at.md)），baseline 的“AT 干净拒绝”仍保留。
> 关联：[hotadd-compatibility-matrix.md](hotadd-compatibility-matrix.md)、[status.md](../status.md)、[jbr-support.md](jbr-support.md)。

## 1. 为什么需要流水线

首轮矩阵是**手工**的：9 个模组、一次会话、结果抄进 Markdown。它首轮结论是「AT 是硬边界」（后被 [late-at.md](late-at.md) 的 JBR 实验修正），但有几个问题：

1. **不可重复**：jar 版本、JBR、`run/mods` 内容、启动器 JVM 都会变；没有 sha256 就不是同一个实验。
2. **不覆盖“前置”**：真实模组经常 required 依赖另一个 mod；依赖方先加、被依赖方没加，失败原因是 `MISSING_DEPENDENCY`，不是模组本身。
3. **只记录“能加/不能加”**：没有把“干净拒绝”当成一等结果，也没有断言拒绝的 `FailureKind`；
   一旦拒绝原因漂移（例如 AT 变成构造期崩溃），矩阵看不出来。
4. **没有回归**：修了 JiJ、coremod、HUD 层之后，没有一个命令能重跑同一批模组并对比。

流水线的目标：**把“某个版本的真实 jar + 前置 + JVM 条件 → 预期结果”固化成可重跑的实验**，每次产出日志 + JSON + Markdown，而不是一段聊天记录。

## 2. 分层

| 层 | 做什么 | 需要游戏 | 频率 | 命令 |
|---|---|---|---|---|
| **L0 静态** | jar 存在/哈希、loader、tier、AT、mixin、JiJ、required 依赖闭包、场景顺序、负向前置条件 | 否 | 每次改动 / PR | `run.py check` |
| **L1 标题屏热加** | 按顺序逐个 `HotAddEngine.add`，断言 `ok`/`isLoaded` 或 **干净的 `FailureKind`**；记录 blocks/items 增量 | 是（GL + JBR 可选） | 手动 / nightly | `run.py run --scenario ...` |
| **L2 进世界功能** | 热加后建世界、跑 N tick、HUD/按键/配置/截图；断言“模组真的在工作” | 是 | weekly | **未实现**（见 §7） |
| **L3 边界对** | 同一 jar 的 JBR / 无 JBR、缺前置 / 有前置、正确顺序 / 错误顺序 | 是 | 手动 | 由场景表达 |
| **L4 浸泡** | 全语料、重复热加、内存/资源重载、失败回滚 | 是 | 发布前 | 未实现 |

**核心原则**：L0 负责“这个实验是否成立”，L1 负责“引擎行为是否符合预期”，L2 才负责“模组功能是否正常”。
不要把 L1 的 PASS 写成“这个模组支持热加”。

## 3. 语料（corpus）

`tools/pipeline/corpus.json`，只存**元数据**，不存 jar：

```json
{
  "id": "mousetweaks",
  "name": "Mouse Tweaks",
  "project": "mouse-tweaks",
  "version": "1.21-2.26.1-neoforge",
  "file": "MouseTweaks-neoforge-mc1.21-2.26.1.jar",
  "sha256": "…",
  "groups": ["mouse", "common"],
  "expect": {"hotadd": "pass"},
  "notes": "玩家口中的“鼠标手势”"
}
```

- **固定 `version` + `file` + `sha256`**：`fetch` 只解析这个精确版本，不取 latest；哈希不符直接报错。
- **`expect`**：`pass` / `refuse`（可带 `kind`）/ `unknown`。`refuse` 是**预期结果**，不是失败。
- **场景**：

```json
{
  "id": "input",
  "title": "键位搜索：Searchables → Controlling",
  "jbr": true,
  "late_at": false,          // true: JBR-only experimental late AT
  "jars": ["searchables", "controlling"],
  "expect": {"default": "pass",
             "jars": {"controlling": {"hotadd": "refuse", "kind": "ACCESS_TRANSFORMER"}}},
  "notes": "前置先加；Controlling 自身有 AT"
}
```

- `jars` 的顺序就是热加顺序；pipeline 会用 `mods.toml` 的 required 依赖检查它。
- `requires_absent` 用于负向场景：“这个前置必须不在 `run/mods`，否则负向场景无效”。
- 场景的 `expect.jars` 覆盖 entry 的默认 expectation。

### 3.1 依赖（“前置”）怎么处理

L0 读 **required** 依赖（忽略 `minecraft`/`neoforge`/`java` 等平台 id 与 optional），然后：

1. 依赖在**同一场景前面** → 通过；
2. 依赖在 `run/mods` 已加载 → 通过（但该 jar 不会被热加）；
3. 依赖在 `corpus` 里但不在场景里 → 报错并提示“把它加进场景”；
4. 依赖是 jar-in-jar 的**嵌套 mod**（例如 Xaero's 自带的 XaeroLib）→ 单独标记：
   - 普通安装由 NeoForge `JarJar` locator 建 container；
   - 热加引擎 2026-11-17 起也会为嵌套 mod 建 container（见 §6.1），但**旧版本/未验证路径**仍可能 `MISSING_DEPENDENCY`；
5. 依赖是纯库 JiJ（无 `mods.toml`）→ 只进 module path，不需要 container；
6. 缺失 → `MISSING_DEPENDENCY`；如果场景**预期**这个拒绝，则允许运行并记录。

`check` 还会读 `run/mods/*.jar` 的 modId，避免“场景以为缺前置，其实启动时已装”的假实验。

## 4. 一次实验长什么样

```bash
# 1. 取语料（只下载，不写仓库；--pin 把 sha256 写回 corpus）
python3 tools/pipeline/run.py fetch --mods-dir /tmp/modreloader-corpus

# 2. 静态检查：jar/哈希/依赖闭包/顺序/AT 与 expectation 是否矛盾
python3 tools/pipeline/run.py check --mods-dir /tmp/modreloader-corpus

# 3. 看某个场景的添加顺序和将执行的命令
python3 tools/pipeline/run.py plan --scenario input

# 4. 真机跑（需要显示；默认 JBR，--no-jbr 场景自动加 -PnoJbr=true）
python3 tools/pipeline/run.py run --scenario input --mods-dir /tmp/modreloader-corpus
```

产物（`tools/pipeline/reports/<时间>-<场景>/`）：

```
run.log       # 完整 Gradle + 游戏日志
report.json   # 每个 jar：predicted tier、expected、actual、verdict、sha256、原始 RESULT 行
report.md     # 人看的表
```

`report.json` 的 verdict：

| verdict | 含义 |
|---|---|
| `MATCH` | 实际符合 expectation（pass 且 isLoaded，或拒绝且 kind 一致） |
| `MISMATCH` | 行为与 expectation 不符（含“预期拒绝但成功了”） |
| `RECORDED` | expectation 是 unknown，只记录 |
| `NOT_RUN` | 游戏崩溃/超时/harness 没跑完，没有 RESULT |

`NOT_RUN` 与 `MISMATCH` 都会让整次 run 失败；`unknown` 不会。

## 5. 首批语料（11 个真实 jar，2026-11-17）

| 场景 | jar（顺序） | 预期 | 说明 |
|---|---|---|---|
| `input` | Searchables → Controlling | **PASS**：Searchables PASS；Controlling **refuse AT** | 前置先加；但 Controlling 19.0.5 自带 1 条 AT |
| `input-nojbr` | Searchables（无 JBR） | **refuse `JBR_REQUIRED`** | 同 jar 的 JBR 边界对照组 |
| `mouse-gestures` | Mouse Tweaks（JBR） | **PASS** | 玩家口中的“鼠标手势”；`redefined AbstractContainerScreen` |
| `mouse-gestures-nojbr` | Mouse Tweaks（无 JBR） | **refuse `JBR_REQUIRED`** | 消息点名 `AbstractContainerScreen` 加接口；证明不是“随便失败” |
| `minimap` | Xaero's Minimap | **PASS**（含 JiJ XaeroLib） | 6 mixin config / 20 类 redefine / key 34→57 / HUD 25→26 / 两个资源包 |
| `minimap-addon` | World Map → Minimap → XaeroPlus | 见 §6.1 | XaeroPlus required 依赖 World Map |
| `minimap-journeymap` | JourneyMap | **PASS：refuse `ACCESS_TRANSFORMER`** | commonnetworking（JiJ）先满足，然后 39 条 AT / 26 个运行期目标拒绝 |
| `missing-dependency` | Controlling（单独） | **PASS：refuse `MISSING_DEPENDENCY`** | 负向：Searchables 必须不在 `run/mods`；实测 `needs [searchables [1.0.1,)] first` |
| `tooltip-at` | Jade | **PASS：refuse `ACCESS_TRANSFORMER`** | 57 条规则 / 39 个运行期目标；已知 AT 硬边界 |
| `recipe-at` | JEI | **refuse `ACCESS_TRANSFORMER`**（34 条） | 嵌套 MezzConfig 0.6.10 有 mods.toml，container 正常；拒绝点是 AT |
| `input-lateat` | Searchables → Controlling（`late_at`） | **PASS**（L1） | Controlling 1/1 AT 在加载前生效 |
| `tooltip-lateat` | Jade（`late_at`） | **PASS**（L1） | 57 规则 / 39 目标全应用；插件 bootstrap；仍非 L2 |
| `recipe-lateat` | JEI（`late_at`） | **PASS**（L1） | 34/16 全应用；`hot-added [mezz_config, jei]` |
| `hud` | AppleSkin | **PASS** | `ok=true`、`isLoaded=true` |
| `library` | Balm | **PASS** | 通用前置库；`hot-added [kuma_api, balm]`（Balm 也 JiJ 嵌套了一个 mod） |

语料只覆盖**作者本机可下载、NeoForge 1.21.1** 的模组；它不是随机抽样，比例不能外推。

## 6. 首轮发现（2026-11-17）

### 6.1 “前置”有三种，不是一种

1. **外部 required mod**（Controlling → Searchables、XaeroPlus → Xaero's World Map）：
   必须先在同一个 `ModList` 里；pipeline 用拓扑顺序表达。
2. **Jar-in-Jar 的纯库**（jecharacters 的 PinIn）：只进 module path，已支持。
3. **Jar-in-Jar 的嵌套 mod**（Xaero's → XaeroLib，JourneyMap → commonnetworking）：
   普通启动由 `JarJarDependencyLocator` 建 `ModContainer`；热加原先只建 module，required 依赖因此 `MISSING_DEPENDENCY`。
   **2026-11-17 已补**：`hotadd/NestedMods` 为嵌套 mod 建 `ModFile`/`ModFileInfo`/`ModInfo`，
   先注册嵌套 container、再注册外层；嵌套 mod 的 mixin 配置一起注册，`FMLCommonSetupEvent` 改为“所有 container 先同步执行、再统一跑 enqueued 任务”
   （XaeroLib 会在自己的队列里 freeze 包注册表，逐 container 跑会把外层挡在 freeze 之后）。

   这一步仍然有边界：嵌套 mod 的 AT 仍拒绝；嵌套 mod 的 `data/` 不会单独注入（资源包已补，见下）；
   注册审计只扫外层 jar，嵌套 jar 的启动期事件引用没有单独审计；嵌套 mod 自己的 `@Mod` 构造器现在会跑，
   但它如果依赖“启动期一次性事件”，仍可能不完整。

   **嵌套资源包（2026-11-17 同日补）**：只注入外层 `mod/<outer>` 会让 XaeroLib 的
   `shaders/core/xaerolib/*.json` 找不到。`ClientResourcePacks.injectAll` 现在把外层 + 所有嵌套
   `ModFileInfo` 的 pack 一次加入并只重载一次；实测 `mod/xaerolib` 与 `mod/xaerominimap` 同时选中，
   `Successfully reloaded the XaeroLib shaders!`。

   **验证（2026-11-17，L1 标题屏）**：
   - `xaerominimap 26.6.0`：`ok=true`、`isLoaded=true`；嵌套 `xaerolib 1.7.3` 有 container；
     6 个 mixin config、20 个类被 redefine；keyMappings 34→57；HUD 层 `xaerohud:hud`（25→26）；
     `mod/xaerolib` + `mod/xaerominimap` 资源包；`XaeroLib shaders` 重载成功；`DONE 1/1`。
     **这是第一个“带 JiJ 前置的常见小地图”L1 PASS。**
   - `mousetweaks 2.26.1`：JBR 下 `ok=true`、`isLoaded=true`、`redefined AbstractContainerScreen`；
     `-PnoJbr=true` 下 `ok=false kind=JBR_REQUIRED`，消息点名 `AbstractContainerScreen` 加接口。
     同一 jar 的对照组由 pipeline 的 `mouse-gestures` / `mouse-gestures-nojbr` 两个场景自动跑。
   - `input`（Searchables → Controlling）：Searchables PASS；Controlling 的 required 前置已满足，
     但仍被 1 条 AT 干净拒绝（`KeyBindsList$Entry`）——**“前置”与“AT”是两个独立的失败原因**。
   - `missing-dependency`（只加 Controlling）：`kind=MISSING_DEPENDENCY`，消息带 `searchables [1.0.1,)`；
     负向断言成立。
   - `minimap-journeymap`：commonnetworking 的 JiJ 嵌套 container 先满足；随后 JourneyMap 39 条 AT /
     26 个运行期目标干净拒绝。
   - `tooltip-at`：Jade 57 条 AT / 39 个运行期目标干净拒绝。
   - `library`（Balm）：`ok=true`，`hot-added [kuma_api, balm]`——第二个“自带 JiJ 嵌套 mod 的前置库”也过了；
     嵌套 `kuma_api` 的 mixin 配置（`kuma_api.mixins.json`）与外层一起注册。
   - `hud`（AppleSkin）：`ok=true`、`isLoaded=true`。
   - `recipe-at`（JEI）：嵌套 MezzConfig 0.6.10 的 required 依赖被 container 满足，随后 34 条 AT 干净拒绝。
   - `xaeroworldmap 1.47.0`：引擎 `ok=true`、`isLoaded=true`，但随后 Xaero 自己的
     `OldFormatSupport.loadVanillaStates` 在标题屏抛 `NoSuchElementException`，进程在 `DONE` 之前退出。
     这属于 **L1 环境限制**（没有世界/旧格式状态），不是热加拒绝；L2 必须进世界复测。
   - JourneyMap / JEI 尚未跑；不要从静态检查推断。

### 6.2 分析器也修了两个“假阴性”

- **hybrid jar 的 loader 检测**：JourneyMap/XaeroPlus 同时有 `fabric.mod.json`（wrongloader 哨兵）和
  `META-INF/neoforge.mods.toml`；原顺序先看 Fabric，导致 modId/依赖/mixin 全读错。现在 NeoForge 优先。
- **`[[mods]]` 后面的子表**：XaeroPlus 的 `[mods."lithium:options"]` 会把 list 覆盖成 dict，modId 变 unknown；
  `tomlmini` 现在把子表挂到最后一个数组元素上。

这两个 bug 都会让流水线“看起来缺前置/没有元数据”，而不是模组本身有问题——所以 L0 必须先可信。

### 6.3 AT：stock JVM 仍是硬边界，JBR late AT 是实验性可行（2026-11-17 修正）

11 个 jar 里 5 个带 AT（Controlling / JourneyMap / Jade / JEI / XaeroPlus）。
**baseline**（不加 `-PlateAt=true`）仍是变更前拒绝，这是默认行为。

但旧结论「AT 只能重启」只对 **stock JVM** 成立。JBR 的 enhanced redefinition 可以改**已加载类**的
access/final 标志；`LateAccessTransformers` 捕获当前字节、只改 AT 的 access/final、在加载 mod 类前 redefine：

- Jade 57/57 规则、39/39 目标 → `ok=true`、插件 bootstrap；JEI 34/34、16/16 → `ok=true`；
  Controlling 1/1；Jade `-PfailAfterModList` 回滚 39/39 ok。
- JourneyMap 的 21/26（成员级规则）可应用，但 `public-f NativeImage/ModelPart` 是类级去 final，JBR 做不到（构造返回 null、二次 redefine SIGSEGV），现在前置拒绝（[status 已知问题 43/44](../status.md)、[late-at.md](late-at.md) §5.1）。
- 仍然 JBR-only、opt-in、只覆盖**成员级** access/final、只到 L1；类级 `public-f`（去类的 final）不可用（[late-at.md](late-at.md) §5.1），**不要写成“AT 已解决”**。

新场景 `input-lateat` / `tooltip-lateat` / `recipe-lateat` 用 `late_at: true` 表达；baseline 场景保留。
详见 [late-at.md](late-at.md)。

## 7. 下一步：L2 功能断言

L1 只能到 `isLoaded`。对“小地图/鼠标手势”真正重要的是：

| 场景 | L2 要断言什么 |
|---|---|
| 小地图 | 进世界后 HUD 层出现/更新；打开设置屏不崩；按键映射存在；截图（人工复核 minimap 可见） |
| 鼠标手势 | 打开物品栏，模拟拖拽/滚轮；断言物品移动；或至少确认 mixin 目标被 redefine 且客户端 tick 不抛异常 |
| JEI/配方 | 打开 JEI 界面、搜索一个物品、点配方；断言不崩且结果非空 |
| 前置库 | 先加库、再加消费方；消费方功能可用（不是只看 isLoaded） |

实现方式（建议）：

1. 新 dev harness `-PverifyScenario=<name>`：标题屏热加 → 建世界 → 等 N tick → 采集 HUD 层/按键/截图 → 停客户端；
2. pipeline 把 `--scenario` 映射到 harness 参数，解析 `[Scenario]` 行；
3. 断言分两级：**机器可判定**（层数/按键/无异常/截图存在）+ **人工复核**（截图）；
4. 失败也保存截图和日志，避免“只在通过时才有证据”。

L2 之前，`check`/`run` 的结论必须写成“引擎路径通过/干净拒绝”，不能写“模组功能正常”。

## 8. CI 建议

- **PR**：L0（纯 Python，无游戏、无 GL）必须绿；corpus 变更必须带 sha256。
- **nightly**：L1 全语料（Linux + xvfb + JBR；或 macOS runner 自托管），报告归档；
- **release**：L2 关键场景（小地图/鼠标手势/HUD）+ 上一版对比；
- 不要让 PR 依赖真实游戏启动：dev client 有 GL/显示层不稳定（status 已知问题 30），CI 上更容易卡死。

## 9. 边界（诚实）

- L1 会打开**真实 dev client 窗口**；默认 no-focus（不抢前台），`--visible` 才会。不要连续批量跑；
  自动化优先 L0/单测/`runServer`，必须起客户端时一次一个场景，跑完即停（见 AGENTS 硬规则 17）。
- 语料是**人工挑选**的常见模组，不是随机样本；“11 个里 5 个 AT”不能直接外推。
- L1 在**标题屏**热加，测不到 datapack/世界生成/进世界后的 HUD；这些是 L2/L3 的事。
- `-PverifyCompat` 一次进程内逐个加；**一个 jar 把进程搞崩，后面的 jar 不会跑**，只能从 exit code + 日志判断。
- pipeline 不写 `mods/`、不改存档；`fetch` 只写 `--mods-dir`（默认 `/tmp/modreloader-corpus`）。
- 期望值是**当前引擎的合同**，不是“应该支持”的愿望；支持能力提升后要同步更新 corpus 并留一条历史记录。
