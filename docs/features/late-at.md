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
4. ASM 只改**成员级**访问标志/`final`，不碰方法体、字段、签名；**类级 `-f` 不去 final**（见 §5.1）；
5. `Instrumentation.redefineClasses` 在 mod 的 module/类加载之前应用；
6. 每个目标都在 `Rollback` 里登记「redefine 回原始字节」；
7. 规则匹配不到成员时**跳过并 warn**（FML 也容忍旧 AT 条目；JourneyMap 的 `heightMap` 就是过期的）；
8. 改字节**之前**先做类级 `-f` 检查：目标已加载且是 `final` → 整体拒绝并提示重启（`classFinalBlockers`）。

命中失败时不写「AT 硬边界」，而是 `FailureKind.ACCESS_TRANSFORMER` 带具体原因（JBR 拒绝/目标不可改/重写失败）。

## 4. 真实模组结果（L1，2026-11-17）

| jar | AT 规则 / 目标 | late AT | 结果 |
|---|---|---|---|
| Jade 15.10.6 | 57 / 39 | 39 applied，0 skipped | **PASS**：`ok=true`、`isLoaded=true`；插件 `VanillaPlugin`/`UniversalPlugin`/`DebugPlugin`/`CorePlugin`/`AccessibilityPlugin` 全部 bootstrap |
| JEI 19.70.0.469 | 34 / 16 | 16 applied，0 skipped | **PASS**：`ok=true`、`isLoaded=true`；嵌套 MezzConfig container 正常 |
| Controlling 19.0.5 | 1 / 1 | 1 applied（先加 Searchables） | **PASS**：`ok=true`、`isLoaded=true` |
| JourneyMap 6.0.9 | 39 / 26 | **拒绝**（类级 `-f`，见 §5.1） | `public-f NativeImage` / `public-f ModelPart`：去类 final 后构造返回 null → 资源重载崩；现在前置拒绝（`kind=ACCESS_TRANSFORMER`）。成员级规则本身可应用 |
| 6 个首轮 AT jar | 295 规则 / 151 目标 | 旧「晚 AT」量测 0/6 | **旧结论只适用于“类加载时应用”；不适用于本节机制** |

回滚实测：Jade + `-PlateAt=true -PfailAfterModList=true` → 39 个目标全部
`rollback: late AT ... ok`，结果 `ok=false stage=MODLIST`，没有 `ROLLBACK_PARTIAL`。

## 5. 边界（很重要）

- **JBR-only**：stock JVM 拒绝改访问标志；没有 JBR 仍然只能重启。这也是 JBR 价值变大的原因。
- **只覆盖成员级 access/final**：AT 能表达的也就是这些；但「删 final」不会撤销已经编译进调用方的常量内联。
  类级的 `public-f` 是例外，见 §5.1。
- **进程级、全局**：改的是已加载的 Minecraft/NeoForge 类，所有后续代码都能看到放宽后的访问；
  成功后不会自动还原（这是 AT 的语义）。失败时 `Rollback` 会还原。
- **加载时机**：目标类会被提前加载（`initialize=false`），可能触发类加载副作用/额外内存；没做大规模浸泡。
- **不解决其它启动期边界**：注册审计（如 `RegisterShadersEvent`）、Mixin 布局、coremod、datapack、注册表提交
  仍是各自的问题。JourneyMap 现在是类级 AT 挡住（§5.1），不再走到注册审计。
- **没有 L2 功能证明**：Jade/JEI 只到构造/插件 bootstrap/`isLoaded`；真正的 tooltip/配方界面/交互未验证。
- **没有多 mod 冲突测试**：两个 mod 对同一成员做相反 AT、或与启动期 AT 叠加，未验证。
- **平台**：只在 macOS + JBR 21 实测；Windows/Linux 未测。
- **AT 的发现规则**：按 FML 的规则（toml `[[accessTransformers]]` 优先，否则默认 `META-INF/accesstransformer.cfg`），
  **空文件/只有注释的文件不算 AT**（Moonlight 的嵌套 codecui 就带一个空文件）。本实现由 `AccessTransformerRules.entries/hasRules` 统一。
- **嵌套 jar 的真 AT**：`NestedMods` 仍拒绝非空嵌套 AT；晚 AT 只处理外层 jar。2026-11-18 扫描全语料（外层 + `META-INF/jarjar/*.jar`）
  没有发现非空嵌套 AT，所以还没有真实 subject。
- **信任**：AT 能放宽私有成员；只应对用户信任的 jar 开启，不要默认给所有模组开。
- **目标是“上一次 add 混入过的类”时（2026-11-18）**：`redefineClasses` 会触发 Mixin agent 再注入一次，MixinExtras 报
  `InjectionError` / JBR 报 `ClassFormatError`。`MixinAgentGuard` 现在是**常驻 wrapper**（只增删抑制集合），late AT 的字节捕获也
  改为缓存 → 类资源 → retransform；但 Supplementaries 对 `Entity` 的这条路径仍未被抑制，见 [status](../status.md) 已知问题 47。

### 5.1 类级 `-f`（`public-f <Class>`）是硬边界

2026-11-18 用独立 JBR 21.0.11 探针（不启动游戏，`-XX:+AllowEnhancedClassRedefinition` +
一次性 agent + ASM 重写）确认：

| 重写 | `new`/工厂/反射构造 |
|---|---|
| 只改字段/方法访问（`private→public`） | 正常 |
| 去**成员** final（`public-f <Class> <method>`） | 正常（`Screen.init` 的 final 可移除） |
| 去**类** final，类没有 final 实例字段 | 正常 |
| 去**类** final，类有 final 实例字段（`NativeImage`、合成 `FinalThing`） | **构造全部返回 null** |
| 上一步之后再 redefine 一次 | **JVM SIGSEGV**（`ConstantPoolCacheEntry::clear_entry`） |

后果：`NativeImage.read` 返回 null → 字体位图、`trim_palette`、动画精灵全失败
（`SpriteContents` 里 `byMipLevel` 为 null）；`ModelPart` 同理会把 `PartDefinition.bake` 打成 NPE。
所以 `LateAccessTransformers` **在改任何字节前**拒绝“已加载 + final 类 + 类级 `-f`”，
`FailureKind.ACCESS_TRANSFORMER` 的文案要求把 jar 放进 `mods/` 重启，让 FML 在**类加载期**应用 AT。

- 典型的受害者是 JourneyMap（`public-f NativeImage`，其 `ComparableNativeImage extends NativeImage`）；
  去掉这条规则会让子类在资源重载时 `IncompatibleClassChangeError`，客户端虽然活着但纹理全丢，比拒绝更糟。
- 类级 `-f` 打到**非 final** 类（如 `ModelPart` 已经是 `public class`）是空操作，可以继续。
- 想救这类模组只有两条路：启动期对“将来要热加的 jar”做类加载期变换（需要提前知道队列），或 JBR 修复该缺陷。

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
4. **嵌套 AT**：先用扫描确认有真实 subject；有的话让 `AccessTransformerRules.read` 读嵌套 jar 的 AT，`NestedMods` 不再拒绝。
5. **服务端/专用服**：AT 目标在服务端的可加载性、与 registry 提交的顺序。
6. **平台冒烟**：Windows/Linux 的 JBR 行为。
7. **CI**：late AT 不能进普通 CI（需要 JBR + 游戏），放在 nightly/手动。
8. **类级 `-f` 的启动期替代**：扫描队列里的 jar，在类**加载期**用 `ClassFileTransformer` 去 final
   （`NativeImage` 这类要在 Minecraft 启动时就变换），把 JourneyMap 这类模组从“必须重启”拉回热加。
