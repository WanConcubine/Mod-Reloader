# A0：热加一个 T2 模组（已验证）

> 状态：**已验证**（2026-11-14，NeoForge 1.21.1 dev client；2026-11-15 资源包改为选中并重载后复测）。
> 目标：不重启游戏，把 `EasyLAN`（T2、无 mixin）从磁盘加载进来，让 `ModList` 认识它、`@Mod` 构造器和客户端事件跑起来。

## 1. 验收结果

测试目标：`EasyLAN-neoforge-1.21.1-v1.6a.jar`（`org.xiaoxian.EasyLAN`）。

启动方式：

```bash
./gradlew runClient -PautoAdd=/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar
```

日志（`run/logs/latest.log`）：

```text
[ModReloader] loaded; hot-add engine ready (module -> mixin -> ModList -> events -> packs)
[ModReloader] resource packs selected after add: ... (new [mod/easylan])
[ModReloader] resource reload finished after adding [mod/easylan]
[ModReloader] verify easylan: isLoaded=true, container=true, keyMappings 34 -> 34
[ModReloader] hot-added [easylan] (modules [easylan])
[ModReloader] autoAdd result: hot-added [easylan] (modules [easylan])
```

结论：

- `ModList.get().isLoaded("easylan") == true`
- `ModList.get().getModContainerById("easylan").isPresent() == true`
- `@Mod` 构造器、`FMLConstructModEvent`、`FMLCommonSetupEvent`、`FMLClientSetupEvent`、`RegisterKeyMappingsEvent`、`FMLLoadCompleteEvent` 均执行且无异常。
- **资源包**：`mod/easylan` 已进入可用列表，并被显式选中、`reloadResourcePacks()` 重载（2026-11-15 复测）。
- `keyMappings` 数量 34 → 34：EasyLAN 的入口不是 `KeyMapping`，而是 `ScreenEvent.Opening`（世界选择 / Open to LAN 界面）。
- `EasyLAN.<init>` 在 `@Mod` 构造器里把 `GuiWorldSelectionEdit` / `GuiShareToLanEdit` 注册到 `NeoForge.EVENT_BUS`；`constructMod` 已执行，所以监听器已注册。
- **实际按钮是否绘制/可点，还没有人工在游戏里确认。**

## 2. 实现流水线

```
jar
 │ 1. JarModsDotTomlModFileReader.read
 ▼
ModFile (identifyMods / identifyLanguage / compileContent / setScanResult)
 │ 2. 手动扫描，绕过 ModSorter/ModValidator
 ▼
Module 注入 game layer（ModuleLayer / Configuration / ModuleClassLoader）
 │ 3. Instrumentation.redefineModule：新模块读 boot+game 所有模块
 ▼
重建 ModList（ModList.of + setLoadedMods + ModLoader.modList）
 │ 4. buildMods → ModContainer
 ▼
constructMod + 客户端生命周期事件
```

### 2.1 为什么绕过 `ModValidator`

`ModValidator.stage2Validation()` 会：

1. 用**只有新 jar** 的列表做 `ModSorter`，把已加载的 `neoforge`/`minecraft` 误判为缺失；
2. 调 `LoadingModList.addMixinConfigs()`，在 Mixin 已关闭后注册配置，抛
   `Too late to add mixin configs!`。

A0 直接对目标 `ModFile` 做 `identifyMods()` / `identifyLanguage()` / `compileContent()`，
再手动构造 `LoadingModList`，完全跳过这两步。代价是**依赖校验也被跳过**，见限制。

### 2.2 Module 注入

需要同时更新三套状态：

- `java.lang.ModuleLayer`：`modules`、`nameToModule`、`servicesCatalog`
- `java.lang.module.Configuration`：`modules`、`nameToModule`
- `cpw.mods.cl.ModuleClassLoader`：`resolvedRoots`、`packageLookup`

并用 `Instrumentation.redefineModule` 给新模块加上对 **boot layer + game layer** 所有模块的 reads。
只加 game layer 不够：`net.neoforged.bus` 在 boot layer，漏掉就会
`IllegalAccessError: module easylan does not read module net.neoforged.bus`。

### 2.3 自附加 agent

`JvmAccess` 写一个临时 agent jar，包含 `AgentMain.class` 和 manifest，然后：

1. `appendToClassPathForInstrumentation`
2. `sun.instrument.InstrumentationImpl.loadAgent`

**JDK 21 的坑**：manifest 只写 `Agent-Class` 会抛无信息的 `InternalError`；
必须同时写 `Premain-Class`（我们同时写了 `Launcher-Agent-Class`）。

## 3. 已验证 / 未验证

> A0 代码侧收尾完成。`/modreloader add` 已由 `dev/AddCommandCheck` 自动化验证（`-PverifyAddCommand`，
> 2026-11-15：命令返回 1、`isLoaded=true`）；剩下的人工项是点一次 EasyLAN 的 GUI 按钮。

### 已验证

- 运行中的 NeoForge 1.21.1 dev client 能加载一个 T2 jar。
- 模块注入、`ModList` 重建、`@Mod` 构造、客户端生命周期事件全部跑通。
- 资源包：`mod/easylan` 出现在可用包列表，并被选中/重载。
- 自附加 agent 在游戏进程内可用（无 `-javaagent`、无 `-Djdk.attach.allowAttachSelf`）。

### 未验证

- **EasyLAN 的真实 GUI**：监听器已注册，但没有人工在世界选择/Open to LAN 界面点过按钮；`keyMappings` 也没增加。
- **游戏内 `/modreloader add` 命令**：命令已通过 `RegisterClientCommandsEvent` 注册，但主菜单没有 player，`ClientCommandHandler.getDispatcher()` 为 null；只在自动触发路径测过，需进世界手动执行一次。
- **生产 jar**：只在 dev client 测过；未在正式安装环境/第三方启动器测。
- **资源包**：已选中/重载 EasyLAN 的 `assets/`；但 EasyLAN 的 GUI 仍未人工点过。
- **依赖校验**：被绕过；缺依赖时会在构造阶段才炸。
- **T3/T4**：A0 明确不支持 mixin 与注册表内容。

## 4. 代码位置

| 文件 | 职责 |
|---|---|
| `src/main/java/dev/modreloader/ModReloader.java` | mod 入口；`/modreloader` 命令；`-PautoAdd` 测试钩子 |
| `src/main/java/dev/modreloader/command/ReloaderCommands.java` | `/modreloader add <jar>` |
| `src/main/java/dev/modreloader/hotadd/HotAddEngine.java` | 总流水线 |
| `src/main/java/dev/modreloader/hotadd/JvmAccess.java` | 自附加 agent |
| `src/main/java/dev/modreloader/hotadd/UnsafeAccess.java` | `Unsafe` 字段访问 + trusted lookup |
| `src/main/java/dev/modreloader/hotadd/ModuleInjector.java` | game layer / boot layer 注入 |
| `src/main/java/dev/modreloader/hotadd/ModListInjector.java` | `ModList` 重建 |
| `src/main/java/dev/modreloader/hotadd/FmlEvents.java` | 构造 + 客户端生命周期事件 |

## 5. 下一步（已被 A1.2 接上）

A1 目标：`lanserverproperties`（4 个 mixin，给 `ShareToLanScreen` 加字段）。A1.2 主路径已完成该目标（失败回滚未实现，见 [a1-mixin-hotadd.md](a1-mixin-hotadd.md)）。

A0 的流水线还缺：

1. **Mixin 重应用**：目标类已加载，需要 re-mix + `redefineClasses`；
2. **布局变更**：mixin 新增字段/方法，stock JVM 会拒绝；需要 JBR 或 sidecar/holder；
3. **依赖校验**：至少做一次“已加载依赖”检查，缺依赖时提前拒绝；
4. **资源包**：`ResourcePackLoader` 注入 + 重载；
5. **回滚**：切换失败时恢复到原 `ModList`/模块层（A0 目前失败会留下半完成状态）。
