# 晚到 access transformer（实验性，JBR）

> 状态：**2026-11-17 实验性可用，默认关闭**。只在 **JBR 21 + `-XX:+AllowEnhancedClassRedefinition`** 下工作；
> 只在 L1 验证过 Jade/JEI/Controlling，**没有 L2 功能验证、没有 Windows/Linux 验证、没有大规模浸泡**。
> 它推翻了「AT 一律是硬边界」的旧结论，但**不是**通用 AT 支持。

## 1. 旧结论错在哪里

旧结论（[matrix](hotadd-compatibility-matrix.md) §8）是：AT 只在**类加载时**由 FML 应用，
所以「晚 AT」只能等目标类还没加载；量到 0/6 后关闭。

这只覆盖了「在类加载时把 AT 打进去」这一种实现。它漏了另一条路：

> **对已经加载的类，用 JBR 的 enhanced redefinition 改访问标志。**

JBR 不只可以加字段/方法/接口；它也可以把 `private` 改成 `public`、去掉 `final`。
AT 的语义正好就是这些（access / final），所以对已加载类做 access-flag redefinition 是可行的。
难点不在 JVM，而在：

1. 要拿到目标类**当前**的字节（NeoForge/Mixin 已经改过）；
2. 要在加载热加 mod 的类**之前**改完；
3. 要能回滚；
4. 要接受「这是 JBR-only、进程级、全局」的代价。

## 2. 最小 JVM 证明（无游戏）

合成一个只有私有字段/方法的 `Target`，用 `Instrumentation.redefineClasses` 换成
`public`/非 `final` 版本，再由一个按 public 编译的 `Caller` 直接调用：

| JVM | 结果 |
|---|---|
| Temurin 21（stock） | `UnsupportedOperationException: class redefinition failed: attempted to change the schema (add/remove fields)` |
| JBR 21 + `-XX:+AllowEnhancedClassRedefinition` | **接受**；`before field=private static final` → `after field=public static`；`Caller.call()` 返回 `hidden:7` |

这证明：**问题不是“AT 不能晚到”，而是“stock JVM 不允许改访问标志”**。
JBR 恰好允许。

## 3. 实现

`hotadd/LateAccessTransformers`（`-Dmodreloader.lateAt=true` / Gradle `-PlateAt=true`）：

1. 解析 `META-INF/accesstransformer.cfg`（`AccessTransformerRules`，按 `<modifier> <class> [member]`；
   支持行尾 `# comment`、字段、方法 + 描述符、class-level 规则、`-f`）；
2. 对每个目标类：`Class.forName(name, false, loader)`（只加载，不初始化）；
3. `ClassBytes.of(inst, type)` 通过一次性 transformer 捕获**当前字节**（保留 NeoForge/Mixin 的修改）；
4. ASM 只改访问标志/`final`，不碰方法体、字段、签名；
5. `Instrumentation.redefineClasses` 在 mod 的 module/类加载之前应用；
6. 每个目标都在 `Rollback` 里登记「redefine 回原始字节」；
7. 规则匹配不到成员时**跳过并 warn**（FML 也容忍旧 AT 条目；JourneyMap 的 `heightMap` 就是过期的）。

命中失败时不写「AT 硬边界」，而是 `FailureKind.ACCESS_TRANSFORMER` 带具体原因（JBR 拒绝/目标不可改/重写失败）。

## 4. 真实模组结果（L1，2026-11-17）

| jar | AT 规则 / 目标 | late AT | 结果 |
|---|---|---|---|
| Jade 15.10.6 | 57 / 39 | 39 applied，0 skipped | **PASS**：`ok=true`、`isLoaded=true`；插件 `VanillaPlugin`/`UniversalPlugin`/`DebugPlugin`/`CorePlugin`/`AccessibilityPlugin` 全部 bootstrap |
| JEI 19.70.0.469 | 34 / 16 | 16 applied，0 skipped | **PASS**：`ok=true`、`isLoaded=true`；嵌套 MezzConfig container 正常 |
| Controlling 19.0.5 | 1 / 1 | 1 applied（先加 Searchables） | **PASS**：`ok=true`、`isLoaded=true` |
| JourneyMap 6.0.9 | 39 / 26 | 21 applied，5 skipped（过期规则） | AT 不再是拒绝点；随后被 **`CLIENT_REGISTRATION`**（`RegisterShadersEvent` 未重放）拒绝 |
| 6 个首轮 AT jar | 295 规则 / 151 目标 | 旧「晚 AT」量测 0/6 | **旧结论只适用于“类加载时应用”；不适用于本节机制** |

回滚实测：Jade + `-PlateAt=true -PfailAfterModList=true` → 39 个目标全部
`rollback: late AT ... ok`，结果 `ok=false stage=MODLIST`，没有 `ROLLBACK_PARTIAL`。

## 5. 边界（很重要）

- **JBR-only**：stock JVM 拒绝改访问标志；没有 JBR 仍然只能重启。这也是 JBR 价值变大的原因。
- **只覆盖 access/final**：AT 能表达的也就是这些；但「删 final」不会撤销已经编译进调用方的常量内联。
- **进程级、全局**：改的是已加载的 Minecraft/NeoForge 类，所有后续代码都能看到放宽后的访问；
  成功后不会自动还原（这是 AT 的语义）。失败时 `Rollback` 会还原。
- **加载时机**：目标类会被提前加载（`initialize=false`），可能触发类加载副作用/额外内存；没做大规模浸泡。
- **不解决其它启动期边界**：注册审计（如 `RegisterShadersEvent`）、Mixin 布局、coremod、datapack、注册表提交
  仍是各自的问题。JourneyMap 就是 AT 过了、卡在注册审计。
- **没有 L2 功能证明**：Jade/JEI 只到构造/插件 bootstrap/`isLoaded`；真正的 tooltip/配方界面/交互未验证。
- **没有多 mod 冲突测试**：两个 mod 对同一成员做相反 AT、或与启动期 AT 叠加，未验证。
- **平台**：只在 macOS + JBR 21 实测；Windows/Linux 未测。
- **嵌套 mod 的 AT**：`NestedMods` 目前仍拒绝嵌套 jar 的 AT；晚 AT 只处理外层 jar。
- **信任**：AT 能放宽私有成员；只应对用户信任的 jar 开启，不要默认给所有模组开。

## 6. 用法

```bash
# 直接跑 Gradle（JBR 默认 + 无焦点 agent）
./gradlew runClient -PverifyCompat=/abs/Jade.jar -PlateAt=true -PnoFocus=true

# 游戏内/服务端：JVM 参数加 -Dmodreloader.lateAt=true
# 然后 /modreloader add <jar>

# pipeline：场景里写 "late_at": true
python3 tools/pipeline/run.py run --scenario tooltip-lateat --mods-dir /tmp/modreloader-corpus
```

默认关闭：不加 `-PlateAt=true` 时行为与以前完全一样（AT 变更前拒绝，提示重启）。
如果 late AT 失败，拒绝文案会说明是 JBR 拒绝、目标不可改、还是规则重写失败。

## 7. 下一步（按优先级）

1. **L2 功能**：Jade 进世界看 tooltip；JEI 开界面搜索/点配方；不是只看 `isLoaded`。
2. **更多真实 AT jar**：Mekanism、AE2、guideme、ExtremeReactors2；记录 applied/skipped、失败点。
3. **冲突/多次 add**：同一目标被多个 AT 改、先 late AT 再失败回滚再重试；确认访问标志恢复。
4. **嵌套 AT**：把 `NestedMods` 的拒绝改成晚 AT 路径。
5. **服务端/专用服**：AT 目标在服务端的可加载性、与 registry 提交的顺序。
6. **平台冒烟**：Windows/Linux 的 JBR 行为。
7. **CI**：late AT 不能进普通 CI（需要 JBR + 游戏），放在 nightly/手动。
