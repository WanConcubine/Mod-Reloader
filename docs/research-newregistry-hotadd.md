# 自定义注册表（`NewRegistryEvent`）能否热加：方案评估（2026-11-18）

> 结论：**技术上可行，属于中等工作量**；但先做“干净拒绝”，再用夹具 spike 验证“构造后重放”，不要直接全量上。
> 触发案例：Moonlight（`moonlight:villager_trades`）、CraftTweaker（同类）。

## 1. 现象与根因

Moonlight 热加（JBR + late AT，126 条 AT 已应用）卡在构造期：

```text
java.lang.IllegalStateException: Attempting to instantiate registry with name
moonlight:villager_trades after NewRegistryEvent was fired!
  at ...moonlight.api.platform.platform.RegHelperImpl.registerRegistry(RegHelperImpl.java:366)
  at ...moonlight.api.MoonlightRegistry.<clinit>(MoonlightRegistry.java:40)
```

根因在 **NeoForge**，不在 Moonlight：`RegistryManager.trackModdedRegistry`（`RegistryBuilder.create()` 调用）

```java
if (pendingModdedRegistries == null)
    throw new IllegalStateException("Attempting to instantiate registry with name " + registry + " after NewRegistryEvent was fired!");
```

`pendingModdedRegistries` 在启动的 `RegistryManager.postNewRegistryEvent()` 末尾被置为 `null`。之后任何
`DeferredRegister.makeRegistry(...)` / `RegistryBuilder.create()` 都会立刻失败——这是硬性守卫，不是偶然错误。

## 2. 启动时的真实顺序（NeoForge 21.1.265 源码）

| 步骤 | 代码 | 备注 |
|---|---|---|
| 1. 构造 mod | `FMLConstructModEvent` | Moonlight 在构造期 `DeferredRegister.create(...).makeRegistry(...)`；`RegistryBuilder.create()` 走 `trackModdedRegistry`（此时 pending 集合非 null） |
| 2. `NewRegistryEvent` | `RegistryManager.postNewRegistryEvent()` → `event.fill()` | `fill()` **unfreeze 根注册表**（`BuiltInRegistries.REGISTRY`）→ 把新注册表注册进去 → **freeze**；随后把 pending 置 null |
| 3. `RegisterEvent`（每个注册表） | `GameData.postRegisterEvents()` | `DeferredRegister.addEntries` 在这里真正登记条目；`seenRegisterEvent=true` |
| 4. 冻结 + 快照 | `GameData.freezeData()` | 冻结 root 里**所有**注册表（含新注册表，分配 id）；`RegistryManager.takeFrozenSnapshot()`；remap 事件 |
| 5. `ModifyRegistriesEvent` | `postNewRegistryEvent()` 末尾 | 全局事件，mod 可修改注册表 |

关键源码位置（`neoforge-21.1.265-sources.jar`）：
`net/neoforged/neoforge/registries/{NewRegistryEvent,RegistryManager,RegisterEvent,DeferredRegister,GameData,RegistryBuilder}.java`。

## 3. 运行时差距（只差三步，现有 `RegistryStage` 已覆盖大半）

我们的构造/提交顺序（[HotAddEngine](../src/main/java/dev/modreloader/hotadd/HotAddEngine.java) §4）：
`RegistryStage.begin`（`GameData.unfreezeData()`）→ `FmlEvents.construct` → `RegistryStage.commit`（按 `GameData.getRegistrationOrder()` 逐注册表 fire `RegisterEvent`，再 `GameData.freezeData()`）。

1. **`pendingModdedRegistries` 为 null** → 构造期 `makeRegistry` 失败。需要在 `begin` 时临时放一个空集合，提交/中止后恢复 null。
2. **新注册表不在根注册表里** → `getRegistrationOrder()` / `freezeData()` 都遍历 `BuiltInRegistries.REGISTRY`，**只要步骤 2 补上，commit/freeze 自动覆盖**，无需改 commit。补的方法是：构造后、commit 前，
   为新容器 fire 一次 `NewRegistryEvent`（**只发给新容器**，不能发给全部：已有 mod 的注册表会重复注册），再调用包私有的 `event.fill()`。
3. **守卫校验**：`postNewRegistryEvent` 会检查“创建了但没注册进事件”的注册表；重放后要复刻这个检查并把集合恢复 null。

已具备的条件：
- `RegistryStage.begin` 只在预测的 `registryContent` 为真时打开——Moonlight 的 `DeferredRegister#create` 引用正好命中 `inspect/RegistrySignal`，预测贵妃级（`GUIFEI`）。
- `RegisterEvent` 构造器已经用反射调用（`REGISTER_EVENT_CTOR`），同一套 `UnsafeAccess` 可拿 `NewRegistryEvent` 的包私有构造器与 `fill()`。
- 多人同步：`RegistrySync` 用 `RegistryManager.takeSnapshot(SYNC_TO_CLIENT)`，它遍历**活的**根注册表；新注册表若 `sync=true` 会自动进快照（前提仍是两端热加同一 jar）。
- 新注册表的 `id` 由 `GameData.freezeData()` 统一分配，BlockState id 表只与 block 注册表有关，不受影响。

## 4. 方案对比

| | A. 构造后重放 `NewRegistryEvent` | B. 预检阶段干净拒绝 | C. 启动期预注册 |
|---|---|---|---|
| 能力增益 | 真正支持（Moonlight/CraftTweaker 可能转 J1） | 无（但仍 BLACK） | 有条件支持（jar 必须在启动前已知） |
| 工作量 | 中（1–2 天 + 夹具/多人验证） | 小（≈0.5 天：检测 + 文案 + 单测） | 大（要在启动期加载 jar 的类并跑构造，等于“安装”） |
| 风险 | 运行时 unfreeze/freeze 根注册表、`fill()` 异常留下未冻结状态、失败后不可回滚 | 误判（反射/间接调用）、漏判 | 产品语义与“重启=失败”冲突 |
| 回滚 | `fill()` 之后失败只能标记 dirty（与现有注册表内容一致） | 无状态变更 | 无 |
| 结论 | **先 spike 再决定是否全量** | **先做** | 不建议 |

## 5. 推荐路线

1. **B 先行（安全网）**：在预检里识别“自定义注册表”形状（引用 `NewRegistryEvent` 且调用 `DeferredRegister#makeRegistry`/`RegistryBuilder`），
   `VALIDATE` 直接拒绝，文案说明“需要重启/放 `mods/`”。避免现在这种“mixin 已注入 + 构造崩溃 + 客户端需重启才稳定”的坏状态（对照已知问题 34）。
2. **A 的 spike**：新建 `experiments/registry-fixture`（构造期建一个自定义注册表 + 1 个条目），按 §3 的三步做最小实现，断言：
   - `BuiltInRegistries.REGISTRY` 包含新注册表；
   - 条目有 id、`RegisterEvent` 真的到达（`seenRegisterEvent=true`）；
   - 中止路径不污染根注册表；重放失败时根注册表仍处于 frozen。
3. spike 通过后再全量：接入 `HotAddEngine` 的 CONSTRUCT 阶段（commit 前）、新 `FailureKind` + 两语言文案、`/modreloader why` 诊断、多人复跑（两端同一 jar）。
4. **明确不做**：`DataPackRegistryEvent`（JSON 注册表，另一条路）、`ModifyRegistriesEvent` 的全局重放、跨 jar 的注册表删除/更新。

## 6. 风险清单（A）

- **`fill()` 无 try/finally**：它在 unfreeze 根注册表后注册；任一注册失败会留下未冻结的根注册表。实现必须在 finally 里保证重新 freeze。
- **重复名字**：`DeferredRegister.makeRegistry` 已拦“与现有注册表同名”，重放时仍需处理“同一 mod 创建两次”。
- **失败语义**：`fill()` 之后失败 = dirty，必须提示重启（与注册表内容不可回滚一致）；之前失败应能清掉 pending 集合且不留根注册表条目。
- **事件范围**：`NewRegistryEvent` 只发给新容器；`RegisterEvent` 由现有 commit 按注册表自动覆盖（`getRegistrationOrder` 是动态的）。
- **多人**：新注册表随 `takeSnapshot(SYNC_TO_CLIENT)` 进快照，但两端必须热加同一 jar；客户端不装 jar 仍不行。
- **单测边界**：`pendingModdedRegistries`/`fill()`/`RegisterEvent` 都依赖游戏类，纯单测只能覆盖“形状检测”和选项逻辑；核心要靠夹具 + harness。

## 7. 验收

- B：`-PverifyCompat=<moonlight>`（无 lateAt）→ `kind=CUSTOM_REGISTRY`（或复用 `REGISTRY` 语义）+ 干净回滚；单测覆盖检测。
- A：`experiments/registry-fixture` 热加 → `ok=true`，日志出现新注册表与条目 id；`-PfailAfterConstruct` 中止后根注册表无残留；
  服务端+客户端同时热加同一 fixture，连接存活且 id 一致。
## 8. 实施结果（2026-11-18）

方案 A 已实现（`hotadd/CustomRegistries` + `RegistryStage` 窗口 + `HotAddEngine` 构造后重放）：

- 夹具 `experiments/registry-fixture`（构造期 `DeferredRegister.makeRegistry` + 1 条目）：
  `custom registry RESULT: PASS (registryfixture:trades present=true, size=1)`；commit 90 个注册表。
- Moonlight 复跑（JBR + late AT）：创建 `moonlight:custom_map_data_types` / `world_saved_data_type` / `villager_trades`，
  commit 90→92，`ok=true isLoaded=true`，blocks 1060→1061、items 1333→1335、stoneId 稳定；白名单 JBR J1（candidate）。
- 过程中修了两个 late AT 健壮性问题：字节捕获改为 **缓存 → 类资源 → retransform**（避免 retransform 触发 Mixin agent 二次注入），
  `MixinAgentGuard` 改为**常驻 wrapper**（安装一次，只增删目标集合；反复 remove/add 会留下 wrapper 并让下一阶段找不到 agent）。

剩余：
- `fill()` 之后失败只能 dirty（与注册表内容一致）；中止路径会记录“自定义注册表已在 root 中无法移除”。
- datapack 注册表（`DataPackRegistryEvent`）、`ModifyRegistriesEvent` 未重放。
- CraftTweaker 未复跑。
- Supplementaries 在同一个 add 里暴露了另一个问题：late AT redefine **上一次 add 混入过的类**（`Entity`）时仍二次 transform
  （`ClassFormatError`），见 [status 已知问题 47](status.md)。
