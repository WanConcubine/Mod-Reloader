# A1：热加一个妃级模组（Mixin + 布局变更）

> 状态：**A1.0 完成**（只读 probe）；**A1.1 spike 完成**（只验证字段/方法）；**A1.2 主路径验证通过**（lanserverproperties 全流水线，dev client 进世界打开 Open to LAN）。失败回滚已覆盖 post-ModList / post-pack；按仓库契约尚未完成：构造后事件监听器无法注销、存档回读未做。
> 目标：`lanserverproperties` 1.13.2 NeoForge 1.21.1（4 个 mixin，给 `ShareToLanScreen` 加字段/接口）。

## 1. 为什么 A0 不够

A0 能热加 `EasyLAN`（嫔级，无 mixin），但 `lanserverproperties` 是妃级：

- 4 个 mixin 类；
- `MixinOpenToLanScreen` 给 `ShareToLanScreen` 加 `port` / `portEdit` / `lsp_objects` 等字段；
- `MixinUUIDUtil` 给 `UUIDUtil` 加 static handler；
- 还有 `PlayerListAccessor` / `WidgetHeightAccessor`。

A0 的流水线**完全绕过 Mixin**：

- 没有注册新 mod 的 mixin config；
- 没有对已加载的目标类重跑 Mixin；
- 没有处理“新增字段/方法”的布局变更。

所以 A0 直接加载它，只会得到一个“`ModList` 认识、但没有 mixin 效果”的空壳；一旦打开 `ShareToLanScreen`，可能 `NoSuchFieldError`/`NoSuchMethodError`。

## 2. 两条技术路线

### A. JBR 增强类重定义（先做，开发期最快）

**环境已就绪（2026-11-14）：** JBR 21.0.11 已安装在
`~/Library/Java/JavaVirtualMachines/jbr-21.jdk/Contents/Home`；`runClient` 自动使用它并加
`-XX:+AllowEnhancedClassRedefinition`，启动日志为 `enhancedRedefinition=true`。

前提：用 JetBrains Runtime 21 启动，并加

```text
-XX:+AllowEnhancedClassRedefinition
```

流程：

1. 用 Mixin 对目标类重跑织入，得到“新版本”字节码；
2. `Instrumentation.redefineClasses` 直接替换（JBR 允许新增字段/方法）；
3. 再跑 module 注入 / `ModList` / `constructMod` / 事件。

优点：不用写 sidecar/holder/trampoline；A1 可以在一周级跑通。
缺点：用户必须换 JBR + 启动参数；不是“开箱即用”的社区方案。

### B. stock JVM 的 sidecar / holder（产品路线）

沿用 Hotwire/NHMJ 的思路：

- 新增方法 → sidecar（nestmate）；
- 新增字段 → holder（weak map）；
- 调用点 → `invokedynamic` 重写；
- 目标类本身保持 JVM 允许的布局。

优点：不要求 JBR。
缺点：工程量高，需要完整的字节码重写与调用点分析；A1 可能变成数月。

### 建议

- **A1 先用 A（JBR）**，把“re-mix + redefine + 生命周期”跑通；
- **B 作为后续产品化**，A1 不阻塞；
- 如果用户环境无法上 JBR，再评估 B 或退化为“只支持嫔级”。

## 3. 流水线（JBR 路线）

```
jar
 │ 1. 读 mod 元数据 / mixin configs / targets
 ▼
加载类（module 注入）
 │ 2. 对已加载的 target 类重跑 Mixin
 ▼
mixed bytes
 │ 3. 与 running bytes 做布局 diff
 ▼
JBR redefineClasses
 │ 4. 验证：类可加载、字段/方法存在
 ▼
ModList / constructMod / 客户端事件
 │ 5. 失败回滚（redefine 回原字节）
```

### 3.1 关键难点

1. **Mixin 重入**：Mixin 的 config 注册在启动后关闭；需要 MixinRuntime/NHMJ 的“运行时注册 + 重新 transform”能力，或自己 patch `DeferredMixinConfigRegistration`。
2. **refmap / 混淆**：NeoForge 1.21.1 运行时用官方名，但仍需正确的 refmap/映射。
3. **目标类已加载**：`ShareToLanScreen` 在打开界面前可能已加载；必须对已加载类 redefine，而不是等它重新加载。
4. **现有实例**：`ShareToLanScreen` 实例在 redefine 后才有新字段；旧实例可能为 null，需要初始化或拒绝。
5. **回滚**：redefine 失败/事件抛异常时，必须恢复原字节与 `ModList`。
6. **JBR 检测**：启动时检查 `java.vendor` / `-XX:+AllowEnhancedClassRedefinition` 是否生效；不满足就拒绝 A1。

## 4. 第一个里程碑：A1.0 dry-run probe（只读）

先不做任何写操作，输出：

- `lanserverproperties` 的 4 个 mixin 类、target 类；
- 每个 target 是否已加载；
- 每个 mixin 会新增哪些字段/方法（与 target 的 running layout diff）；
- 当前 JVM 是否 JBR、是否开启 `AllowEnhancedClassRedefinition`；
- 预估：能否直接 redefine，还是需要 sidecar；
- go/no-go。

验收：probe 报告与 Python 分析器（妃级、4 mixins、`ShareToLanScreen` 字段）一致；不崩游戏、不改任何状态。

### 4.1 实测结果（2026-11-14）

```bash
./gradlew runClient -Pprobe=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar
```

关键输出：

```text
[MixinProbe] jbr=true enhancedRedefinition=true
[MixinProbe] config lsp.mixins.json: 4 mixin classes
[MixinProbe] mixin ...MixinOpenToLanScreen (interface=false) targets=[...ShareToLanScreen]
[MixinProbe]   target net.minecraft.client.gui.screens.ShareToLanScreen loaded=false
[MixinProbe]   layout: +1 fields, +15 methods
[MixinProbe] mixin ...MixinUUIDUtil (interface=false) targets=[...UUIDUtil]
[MixinProbe]   target net.minecraft.core.UUIDUtil loaded=true
[MixinProbe]   layout: +0 fields, +1 methods
[MixinProbe] mixin ...PlayerListAccessor (interface=true) targets=[...PlayerList]
[MixinProbe]   target net.minecraft.server.players.PlayerList loaded=false (interface mixin; layout skipped)
[MixinProbe] mixin ...WidgetHeightAccessor (interface=true) targets=[...AbstractWidget]
[MixinProbe]   target net.minecraft.client.gui.components.AbstractWidget loaded=true (interface mixin; layout skipped)
[MixinProbe] summary: configs=1 mixins=4 targets=4 loadedTargets=2 addedFields=1 addedMethods=16
[MixinProbe] verdict=JBR_REDEFINE
```

解读：

- `ShareToLanScreen` 在主菜单阶段**尚未加载**；A1.1 必须先 `Class.forName` 或打开界面，才能对它 redefine。
- `UUIDUtil` 已加载；`PlayerList` 未加载；`AbstractWidget` 已加载（accessor 接口不合并布局）。
- 布局变更：**+1 字段 / +16 方法**，stock JVM 会拒绝；JBR 可做。
- probe 全程只读，没有加载/重定义任何类，没有崩游戏。

## 5. A1.1：只对 `ShareToLanScreen` 做 JBR redefine

**当前阻塞点（A1.0 已确认）：**

1. `ShareToLanScreen` 在主菜单未加载；需要先加载（`Class.forName` 或打开界面）。
2. 最难的未知项是 **Mixin 运行时重放**：Mixin 的 config 注册在启动后关闭，需要
   MixinRuntime/NHMJ 式“运行时注册 + 重新 transform”，或自己 patch Mixin 内部。
3. JBR redefine 本身已具备条件（`enhancedRedefinition=true`）。

范围缩到最小：

- 只处理 `MixinOpenToLanScreen`；
- 只对 `ShareToLanScreen` 重跑 Mixin + redefine；
- 打开 Open to LAN 界面，确认 `port`/`portEdit` 字段存在、按钮/输入框出现；
- 其他 3 个 mixin 在 A1.2 处理。

验收：

1. `ShareToLanScreen` 的 class layout 包含新字段；
2. 界面能打开、不崩；
3. 关闭界面后游戏正常；
4. 失败时不崩（完整回滚仍未实现）。

### 5.1 A1.1 spike 实测（2026-11-14）

环境：JBR 21.0.11 + `-XX:+AllowEnhancedClassRedefinition`；`run/mods` 放 MixinRuntime 1.0.2-mc1.21。

```bash
./gradlew runClient -Pa1=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar
```

> 这是 A1.1 的历史命令；`-Pa1` 入口已在 A1.2 移除。现在复现用 `-PautoAdd=... -PverifyLan=true`。

日志：

```text
[A1Spike] module injected: lanserverproperties
[A1Spike] mixin config registered: lsp.mixins.json
[A1Spike] loaded net.minecraft.client.gui.screens.ShareToLanScreen
[A1Spike] ShareToLanScreen.lsp_objects present
[A1Spike] ShareToLanScreen.getPort() present
[A1Spike] ShareToLanScreen.movePortEditBox(IIII) present
[A1Spike] UUIDUtil redefined: 9155 bytes
[A1Spike] UUIDUtil method present: handler$zzb000$lanserverproperties$detour_createOfflinePlayerUUID
```

解读：

- 启动后注册 Mixin config 可行：`DeferredMixinConfigRegistration.added=false` +
  `Mixins.addConfiguration` + `MixinConfigUtils.prepare` / `MixinProcessorUtils.addIntoProcessor`。
- **未加载的 target**（`ShareToLanScreen`）在 `Class.forName` 时直接被 Mixin 转换，字段/方法已经在，不需要 redefine。
- **已加载的 target**（`UUIDUtil`）用 `MixinTransformerUtils.transform` + `redefineClasses` 成功；JBR 允许新增方法。
- 这验证了 A1 的核心假设：运行时 Mixin 重放 + JBR 布局变更可行。

限制：

- 还没和 A0 的 `ModList` / `constructMod` / 客户端事件合并（A1.2 已解决）。
- Open to LAN 界面没有实际打开；按钮/输入框还没目视（A1.2 已解决）。
- MixinRuntime 是 dev 依赖（`run/mods`）；产品化需要 vendor 最小实现或自己实现（仍未解决）。
- config 名和 mod id 目前硬编码为 `lsp.mixins.json` / `lanserverproperties`（A1.2 已解决）。

**A1.1 的实际缺口（2026-11-15 复盘）**：spike 只检查了 `lsp_objects` / `getPort` / `movePortEditBox`，没有检查 `getInterfaces()`。它调用 `prepare` 后没有调用 `postInitialise`，`MixinInfo` 未验证，字段/方法会合并，但 `IShareToLanScreenParamAccessor` 接口不会合并；A1.1 的“ShareToLanScreen 带 mixin 加载”说法因此不完整。A1.2 修正并补上接口检查。

## 6. A1.2：完整流水线 + Open to LAN 验证（2026-11-15）

### 6.1 实现

`HotAddEngine.add` 现在执行：

```text
读 jar / identifyMods（含 [[mixins]]）
 → ModuleInjector.addToGameLayer
 → MixinSupport.register
     每个 config：addConfiguration → onSelect → prepare → postInitialise
                  → addIntoProcessor；恢复 Mixin 兼容级别 JAVA_21
 → 已加载 target：MixinTransformerUtils.transform + JBR redefine
 → 未加载 target：Class.forName(..., false) 首载织入（不再二次 transform）
 → ModListInjector.rebuild
 → FmlEvents.constructAndInit
 → 资源包：选中新 mod pack + reloadResourcePacks()
```

要点：

- config 名、mod id 全部来自 jar（`ModFile.getMixinConfigs()` / `ModFileInfo.getMods()`）；`-Pa1` 和 `A1Spike` 已删除。
- **`postInitialise` 不能省**：只调用 `prepare` 时字段/方法会合并，但 `MixinInfo` 没有 validate，接口不会合并；LSP 的 `(IShareToLanScreenParamAccessor) screen` 会 `ClassCastException`。
- **已 mixed 的类不能再次 `transform`**：对已加载且已织入的类再 transform + redefine 会丢接口。只在“注册前已加载”的快照里做 redefine，其余用首载织入。
- **compatibilityLevel**：LSP 的 config 声明 `JAVA_17`，`MixinConfig` 会把全局级别从 21 降回 17；`prepare` 后恢复 `JAVA_21`，否则 Java 21 mixin 类会被降级处理。
- **资源包**：mod pack 不是 required，`PackRepository` 不会自动选中；A1.2 显式选中并 `reloadResourcePacks()`，否则 LSP 按钮显示 raw translation key。

### 6.2 证据

命令：

```bash
./gradlew runClient \
  -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyLan=true
```

`-PverifyLan=true` 是 dev-only 自动检查（`LanScreenCheck`）：用真实 `CreateWorldScreen` 建世界 → 等客户端进入世界 → `new ShareToLanScreen(null)` → 反射检查 → 截图 → 退出。它只在 system property `modreloader.verifyLan` 为 true 时运行。

关键日志（2026-11-15，`run/logs/`）：

```text
[ModReloader] mixin setup: redefined=[net.minecraft.core.UUIDUtil, net.minecraft.client.gui.components.AbstractWidget], eager=[net.minecraft.client.gui.screens.ShareToLanScreen, net.minecraft.server.players.PlayerList], deferred=[]
[ModReloader] resource packs selected after add: [vanilla, mod_resources, mod/mr, mod/modreloader, mod/neoforge, mod/lanserverproperties] (new [mod/lanserverproperties])
[ModReloader] resource reload finished after adding [mod/lanserverproperties]
[ModReloader] verify lanserverproperties: isLoaded=true, container=true, keyMappings 34 -> 34
[LanCheck] new screen class=...ShareToLanScreen; lsp_objects=true; interfaces=[interface rikka.lanserverproperties.IShareToLanScreenParamAccessor]
[LanCheck] ShareToLanScreen.getPort()=25565
[LanCheck] EditBox value='25565' ... class=EditBox
[LanCheck] Button '在线模式: 启用' ...
[LanCheck] EditBox value='8' ... class=IntegerEditBox
[LanCheck] PlayerList.setMaxPlayers(int) present (mixin applied on load)
[LanCheck] A1.2 LAN screen checks passed (port=25565)
[LanCheck] RESULT: PASS
```

截图：[a1-lan-screen.png](images/a1-lan-screen.png)（LAN 屏，端口 25565、在线模式、PvP、最大玩家数、保存/读取配置；翻译已加载）。

截图也暴露了资源包问题：第一次 A1.2 运行（只 `repo.reload()`，不选中 pack）显示的是 `lanserverproperties.gui.port` 这类 raw key；选中 pack 并 `reloadResourcePacks()` 后才变成“监听端口”等中文。

### 6.3 还没验证 / 限制（按 AGENTS §6，A1.2 尚不算契约完成）

- **JBR only**：需要 `-XX:+AllowEnhancedClassRedefinition`；stock JVM 的 sidecar 路线未做。
- **MixinRuntime 是第三方前置**：`run/mods` 里的 1.0.2-mc1.21；`mr` 在 mods.toml 里已改为 `type="optional"`（缺它嫔级照常、妃级被拦），产品化要 vendor 或自己实现。
- **失败回滚（部分）**：已覆盖 post-ModList 和 post-pack 失败（见 6.4）；构造后的 `@Mod` 构造器/事件监听器无法注销，存档回读未验证，因此仍不是契约意义的“完成”。
- **存档回读**：测试世界已创建并保存，但没有重新启动并打开它；存档可读性未独立验证。
- **eager load**：未加载 target 用 `Class.forName(..., false)` 立即加载；只验证了 LSP 的 4 个 target，不能推断任意妃级。
- **资源包重载**：`reloadResourcePacks()` 会短暂卡顿；真实玩家场景要评估。
- **只测了一个模组**：`lanserverproperties` 1.13.2 NeoForge 1.21.1；不是“通用妃级支持”。
- **只有 add，没有 remove/update**：运行时删除/卸载/更新未实现，也没有测试。mixin 已经写进目标类，删 jar 不会撤销；需要 classloader 隔离或贵妃级软重启。

### 6.4 失败回滚（2026-11-15）

`HotAddEngine.add` 现在是一个 LIFO undo 事务（`Rollback`）：

- 阶段开始时先注册 undo：module 快照、ModList 快照、mixin config、target 原始字节、资源包 map/选中列表。
- 失败时 `Rollback.run()` 逆序执行；单个 undo 失败只记录，不中断其余步骤。
- target 原始字节在 config 加入 processor **之前**抓取：已加载类用 `InstUtils.getClassBytes`，未加载类读 class resource。

强制失败验证：

```bash
# 在 mixin 已应用、ModList 已重建之后失败
./gradlew runClient \
  -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyRollback=true -PfailAfterModList=true

# 在 construct + 资源包之后失败
./gradlew runClient \
  -PautoAdd=/tmp/lanmods/lanserverproperties-1.13.2-neoforge.jar \
  -PverifyRollback=true -PfailAfterPack=true
```

结果：

- **post-ModList**：4 个 target 的字节被 redefine 回原样（`ShareToLanScreen` 的 `lsp_objects`/接口消失），mixin config 被 deactivate，ModList/module 恢复；`RollbackCheck` 重试 add 成功。
- **post-pack**：资源包选中项被移除（in-place 恢复 `modResourcePacks` map，避免旧 finder 继续提供 LSP pack），类/ModList/config/module 恢复；构造器和监听器无法注销，所以不重试。

关键日志：

```text
[ModReloader] rollback: restored class net.minecraft.client.gui.screens.ShareToLanScreen (10609 bytes)
[ModReloader] rollback: deactivated mixin config lsp.mixins.json
[ModReloader] rollback: module layer ok
[RollbackCheck] rollback state checks passed; retrying add
[RollbackCheck] retry result: hot-added [lanserverproperties] ...
[RollbackCheck] RESULT: PASS
[RollbackCheck] pack rollback state checks passed; not retrying because the mod was already constructed (listeners remain)
```

限制：

- JVM 不能 undefine module：回滚从 layer 移除 module，但 `Module` 对象缓存起来供重试；同一 module 名换 jar 需要重启。
- 被回滚的 mixin config 对象保留（inactive），供重试复用；否则 `Mixins.addConfiguration` 会把它当重复注册。
- `@Mod` 构造器注册的事件监听器无法注销；post-construct 失败只能恢复 ModList/module/mixin/pack，监听器残留。
- 资源包 reload 是异步的；回滚后的选中状态同步恢复，但资源管理器可能还要一次 reload 才完全释放。
- 未覆盖：真实依赖缺失、构造器抛异常、两个模组交替失败等；目前是 2 个强制失败点 + 成功路径回归。

## 7. 测试计划

| 步骤 | 操作 | 预期 | 状态 |
|---|---|---|---|
| 1 | 启动 JBR client，`-Pprobe=lanserverproperties` | probe 报告 4 个 mixin、target 已加载、字段 diff | **通过**（A1.0） |
| 2 | `-PautoAdd=lanserverproperties -PverifyLan=true`（JBR） | isLoaded/container true，无异常 | **通过**（A1.2） |
| 3 | 打开 Open to LAN 界面 | 端口输入框/LSP 控件出现 | **通过**（自动检查 + 截图） |
| 4 | 修改端口并发布 | 使用新端口 | **未做**（需人工点击“创建局域网世界”） |
| 5 | 关闭界面、退世界 | 无残留崩溃 | **部分**：截图后自动关屏/停客户端，未人工长测 |
| 6 | 强制失败（post-ModList / post-pack） | 回滚，游戏继续 | **通过**（`-PverifyRollback`；构造后 listener 不可注销） |

## 8. 环境与待确认

1. **A1 先走 JBR 路线：已确认。** `runClient` 已切到 JBR 21.0.11 + `-XX:+AllowEnhancedClassRedefinition`。
2. **JBR 是否已安装：已完成。** 路径 `~/Library/Java/JavaVirtualMachines/jbr-21.jdk/Contents/Home`；Gradle 编译仍用 Temurin 21。
3. **A1 的验收只做 `ShareToLanScreen` 一个 target 是否够？** A1.2 处理了全部 4 个 config target：`ShareToLanScreen`、`UUIDUtil`、`AbstractWidget`、`PlayerList`。
4. **`-PverifyLan` 是 dev-only**：它用真实建世界 + `ShareToLanScreen` 自动检查，不是产品 UI；游戏内 `/modreloader add` 已由 `-PverifyAddCommand`（`src/dev/java/.../AddCommandCheck`）自动化，同样 dev-only。
5. **`-PverifyRollback` 是 dev-only**：`-PfailAfterModList` / `-PfailAfterPack` 强制失败，`RollbackCheck` 验证状态并（post-ModList）重试；不要在产品启动里使用。

## 9. 代码落点

```
src/main/java/dev/modreloader/hotadd/
  MixinProbe.java       # A1.0：读 configs、查 target、diff layout
  MixinSupport.java     # A1.2：config 注册 + postInitialise + JBR redefine / 首载织入
  Rollback.java         # A1.2：LIFO undo 事务
  HotAddEngine.java     # A1.2：module → mixin → ModList → construct → events → pack
  ModuleInjector.java   # 复用
  ModListInjector.java  # 复用
  FmlEvents.java        # 复用
src/dev/java/dev/modreloader/dev/   # 独立 source set，不进发布 jar
  LanScreenCheck.java   # dev-only：-PverifyLan 自动建世界 / 打开 LAN 屏 / 截图
  RollbackCheck.java    # dev-only：-PverifyRollback 强制失败 / 状态检查 / 重试
```

`A1Spike` 已删除；`-Pa1` 入口已移除。
