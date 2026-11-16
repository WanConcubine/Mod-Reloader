# T4 调研：注册表内容能不能"就地适配"

> 状态：**调研中**（源码阅读 + 一次运行时观测 + 一次受控实验）。
> 结论只覆盖 1.21.1 / NeoForge 21.1.265；未做任何注册表写入实验。
> 关联：[ADR-0006](decisions/0006-t4-managed-relaunch.md)（当前 T4 决策：managed relaunch）、
> [ADR-0007](decisions/0007-dual-side-server-hotadd.md)（双端）、[status.md](status.md)。
> 术语见 [glossary.md](glossary.md)（T4、soft restart、registry、freeze、numeric ID）。

## 0. 要回答的问题

T4 = 注册表内容（方块 / 物品 / 实体 / 生物群系 / 维度…）。ADR-0006 决定先做 managed relaunch，
但 payload 适配（E1–E3）证明"框架的一次性阶段可以重放"，于是开题前要先评估：

1. **"解冻 + 重放 `RegisterEvent` + 重建依赖缓存"能不能成立？**
2. 如果成立，代价是什么？哪些内容仍然不行？
3. 它能否满足项目的不变量（可预测、可回滚、存档一致）？

本文只回答"机制与边界"，不实现。

## 1. 1.21.1 的注册表机械事实

### 1.1 数据结构与 id 分配

`MappedRegistry`（`net/minecraft/core/MappedRegistry.java`）持有：

```text
byId: ObjectList<Holder.Reference<T>>   // 第 i 项就是 id=i
toId: Reference2IntMap<T>               // 值 -> id
byLocation / byKey / byValue / registrationInfos
tags: Map<TagKey<T>, HolderSet.Named<T>>
frozen: boolean
```

- `register(key, value, info)` 的 id = `byId.size()`，**只追加**；`byLocation` 重复即抛异常。
- `validateWrite()` 在 `frozen` 时抛 `Registry is already frozen`。
- `unfreeze()` 在 `MappedRegistry` 上是 **public**（已运行时确认），`freeze()` 是 public。
- `freeze()` 会跑 NeoForge 的 **bake callbacks**，并对未绑定 value 的 holder 抛异常。
- `MappedRegistry` 没有删除单个条目的公开路径；`clear(full)` 是 NeoForge 加的 protected 方法。

**推论**：add-only 的注册不会移动已有条目的 id；但回滚（删除刚加的条目）没有公开机制，
所以 T4 适配天然是"**单向提交**"，不是 A0–A1.2 那种可 LIFO 撤销的事务。

### 1.2 NeoForge 在 freeze 时做什么

`NeoForgeRegistryCallbacks`（`net/neoforged/neoforge/registries/`）在启动时注册到各注册表：

| 注册表 | 回调 | freeze 时做什么 |
|---|---|---|
| `BLOCK` | add / bake / clear | 对新方块 `initCache()` + 取 loot table；**遍历整个 BLOCK 注册表，把每个 `BlockState` 再加进 `Block.BLOCK_STATE_REGISTRY`**；`DebugLevelSource.initValidStates()` |
| `ITEM` | add | `BlockItem` 注册到 `BLOCK_TO_ITEM_MAP` |
| `ATTRIBUTE` | bake | `DefaultAttributes.validate()`（只对缺默认属性的实体类型 `logAndPauseIfInIde`，不抛） |
| `POI_TYPE` | add / clear | 维护 `BlockState -> Holder<PoiType>` |

`Block.BLOCK_STATE_REGISTRY` 就是 `GameData.getBlockStateIDMap()` → `BlockCallbacks.BLOCKSTATE_TO_ID_MAP`，
也就是上面这个 bake 回调维护的 `IdMapper<BlockState>`。

**关键**：`IdMapper.add()` = `addMapping(value, nextId)`，**无条件分配新 id**（`IdMapper.java`）。
它不会因为对象已有 id 而复用。所以"unfreeze → 什么都不注册 → freeze"也会让每个 blockstate 获得新 id，
旧 id 槽位仍然保留（`addMapping` 不删旧槽），表现为：

- `getId(state)` 变成新值；
- `byId(旧 id)` 仍然能读回原来的 state；
- `size()` 不变（`size()` 是 `tToId.size()`），但内部 `idToT` 变长。

### 1.3 启动期的事件与顺序

`GameData.postRegisterEvents()`：

```text
for (registryName : GameData.getRegistrationOrder())   // 89 个内置注册表
    new RegisterEvent(registryKey, registry)
    ModLoader.postEventWrapContainerInModOrder(registerEvent)   // 每个 mod 的 bus
CommonHooks.modifyAttributes()
SpawnPlacements.fireSpawnPlacementEvent()
ModLoader.postEvent(new BlockEntityTypeAddBlocksEvent())
CreativeModeTabRegistry.sortTabs()
```

- `RegisterEvent` 的构造器是**包私有**（运行时确认：`net.neoforged.neoforge.registries.RegisterEvent(ResourceKey, Registry)`），
  重放需要 trusted lookup 或反射。
- 事件的顺序由 `getRegistrationOrder()` 决定：`attribute → data_component_type → armor_material → 原版顺序 → modded 注册表按名字排序`。
- `ModContainer.acceptEvent(event)` 可以只投递给**一个** mod 的 bus（FML 的 `postEventWrapContainerInModOrder` 是投递给所有 mod）。
  这正是重放需要的粒度：不能全局重放，否则每个已加载 mod 的注册监听器都会再跑一遍并撞 `duplicate key`。

### 1.4 注册表内容如何到达客户端

- `NeoForgeRegistriesSetup.modifyRegistries()` 把 `BLOCK`/`ITEM`/`ENTITY_TYPE`/`FLUID`/`BLOCK_ENTITY_TYPE` 等
  标记为 `doesSync = true`。
- `SyncRegistries`（`net/neoforged/neoforge/network/configuration/`）在**登录的 configuration 阶段**发送
  `FrozenRegistrySyncStartPayload` + 每个注册表的 `FrozenRegistryPayload`（id→name 映射）+ `Completed`。
- 客户端 `ClientPayloadHandler` 收齐后 `RegistryManager.applySnapshot(...)`；有客户端不认识的 key 就**断开连接**。
- **没有**"连接建立后再同步一次"的路径（payload 是登录协商的一部分）。

**推论（当时）**：T4 热加必须在**建连接之前**完成，才能进入登录快照；连接后再热加，客户端拿不到新的
block/item id 映射（这一点和 payload 的 E2 不同：payload 可以补 `PayloadSetup`，注册表快照没有等价机制）。

> **2026-11-16 更新**：这个推论只对「NeoForge 内置的登录同步」成立。运行时可以自己开一条 play 通道，
> 把 `RegistryManager.takeSnapshot(SYNC_TO_CLIENT)` 发给在线客户端，客户端用同一个
> `RegistryManager.applySnapshot`（登录期那段代码）应用它——连接后的 id 同步已经实现并跨进程验证，
> 见 [features/t4-registry-hotadd.md](features/t4-registry-hotadd.md) §9。前提仍是两端都有同一批模组。

### 1.5 创造栏与资源

- `CreativeModeTab.buildContents()` 每次调用都会重放 `BuildCreativeModeTabContentsEvent`
  （`EventHooks.onCreativeModeTabBuildContents`），并把结果缓存在 `displayItems`。
- `CreativeModeTabs.tryRebuildTabContents(...)` 由 `CreativeModeInventoryScreen` 在需要时调用，
  `buildAllTabContents()` 重建所有 tab。
- 模型 / blockstates / 语言走资源包；A1.2 已有 `ClientResourcePacks` 注入 + `reloadResourcePacks()` 的路径。
- **数据包内容**（tags / recipes / loot / worldgen）由 `ReloadableServerResources` 从数据包加载；
  `RegistryDataLoader.WORLDGEN_REGISTRIES`（biome / dimension / feature / structure…）在**世界加载**时构建，
  是 per-world 的 `RegistryAccess`，不在 `BuiltInRegistries` 里。

### 1.6 `RegistryAccess` 是视图，不是拷贝

`RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)` 返回一个**按名字查 registry** 的视图
（`RegistryAccess.java`），`LayeredRegistryAccess` 把 built-in 层与 datapack 层合起来。
所以 `server.registryAccess().registryOrThrow(BLOCK)` 就是 `BuiltInRegistries.BLOCK` 本身（运行时已确认），
往 built-in 注册表里追加的条目**立刻**能通过 `registryAccess()` 看到。

但 datapack 层（biome/dimension…）是另一套对象，不共享。

## 2. 运行时观测（2026-11-15 21:28 / 21:30，dev client + 集成服务器）

工具：`src/dev/java/dev/modreloader/dev/RegistryProbeCheck.java`
（`-PverifyRegistryProbe=true`；受控实验再加 `-PverifyRegistryRefreeze=true`）。
它**不写**任何注册表、不投递事件；实验部分只调用 `GameData.unfreezeData()` / `freezeData()`，
且只应在一次性测试世界里跑。

```
block registry: size=1060 frozen=true
item registry: size=1333 frozen=true
registryAccess.BLOCK == BuiltInRegistries.BLOCK: true
registryAccess.ITEM == BuiltInRegistries.ITEM: true
registry layers: 117     registry-of-registries size: 89
blockstate id map: size=26684 sameAsGameData=true stone=1 dirt=10 grass=9
registration order: 89 registries; first=[attribute, data_component_type, armor_material, game_event, sound_event, ...]
register-event ctor:  net.neoforged.neoforge.registries.RegisterEvent(ResourceKey, Registry)   // 包私有
unfreeze method: public void net.minecraft.core.MappedRegistry.unfreeze()
callbacks on BLOCK: add=1 bake=1 clear=1;  ITEM add=1;  ATTRIBUTE bake=1
block tags: 302 names; stone has 15
creative tabs: sorted=10 all=14
```

受控实验（unfreeze → 不注册任何东西 → freeze）：

```
refreeze: before blocks=1060 states=26684 frozen=true stone=1    grass=9
refreeze: unfrozen; block frozen=false
refreeze: after 15 ms; blocks=1060 states=26684 frozen=true stone=26685 grass=26693 stable=false
```

**结论**：`unfreezeData`/`freezeData` 在运行中的集成服务器上可执行（15 ms，不抛异常），
但**裸的 re-freeze 会移动 BlockState 的 canonical id**（stone 1 → 26685）。
旧 id 仍能 `byId` 读回（`addMapping` 只追加），但任何在 re-freeze 之后写出的存档
（chunk palette 用 `getId`）都会带上重启后不存在的 id。

所以：**适配路线必须先清空 `Block.BLOCK_STATE_REGISTRY` 再 freeze，让 id 按注册表顺序从 0 重建**。
本项目没有"清空并重建"的公开 API，只能反射 `BlockCallbacks.BLOCKSTATE_TO_ID_MAP.clear()`（trusted lookup）。
这条如果做错，T4 会静默地写出坏存档——比"需要重启"危险得多。

## 3. 如果要做，完整流水线长什么样

以"热加一个带方块 + 物品 + 方块实体的 mod"为例（**add-only**）：

1. **预检（不触碰注册表）**：依赖、mixin、side、JBR、`Capabilities`、jar 内容；
   以及 `mods.toml` 里是否声明了 datapack registry 内容（后者直接拒绝）。
2. **module + mixin + ModList**（复用 A1.2）。
3. **unfreeze 窗口开始**（`GameData.unfreezeData()`）。
4. **construct**（`@Mod` 构造器；有些 mod 会在这里直接 `Registry.register`）。
5. **逐注册表重放**：按 `GameData.getRegistrationOrder()` 的顺序，
   `container.acceptEvent(new RegisterEvent(key, registry))`（构造器反射）。
6. **注册后事件**：`BlockEntityTypeAddBlocksEvent`、`SpawnPlacementRegisterEvent`、
   `CommonHooks.modifyAttributes()`，以及 mod 自己的 tab（`CreativeModeTab` 也是注册表条目）。
7. **清空并重建 BlockState id 映射**，然后 `GameData.freezeData()`：
   重新跑 bake callbacks（新方块 initCache/loot table、state id 重建、`IdMappingEvent`）。
8. **客户端**：资源包注入（模型/blockstates/lang）+ `CreativeModeTabs` 重建（`buildAllTabContents`）。
9. **数据包**：tags / recipes / loot。

第 9 步是最大的空白：热加 mod 的 `data/` 不在当前数据包列表里，`/reload` 看不到它。
可选做法是重建 `PackRepository` + `ReloadableServerResources`（等于一次服务端资源重载），
但它会重建大量依赖注册表的东西，风险未评估。

## 4. 为什么它和 A0–A1.2 不是一类东西

| 维度 | A0–A1.2（代码热加） | T4 注册表适配 |
|---|---|---|
| 失败回滚 | LIFO undo（module / ModList / class bytes / pack） | **无法撤销**已注册的条目；没有公开的 remove |
| 提交语义 | 先试后回滚 | **pre-flight → 单向提交**；提交后任何失败都只能"重启" |
| 存档 | 类不影响存档格式 | 新内容写进 chunk / level.dat 后，**下次启动必须装同一个 mod**；删 mod 会导致 missing entries（NeoForge 会提示并删除） |
| 单/双端 | 单端即可 | 服务器与客户端必须**各自**热加同一 jar，且在**建连接前** |
| 数据包 | 不涉及 | tags/recipes/loot 需要额外重建，否则"能放但挖不动/合不成" |

因此，即使机制全部打通，也不能对用户说"T4 支持热加"；最多是
"实验性、一次性、不可回退、存档会绑定这个 mod"。

## 5. 结论与建议

**机制上可行，但只在一个很窄的切片里**：

- ✅ built-in 注册表的 **add-only**（方块 / 物品 / 实体 / 方块实体 / 属性 / 创造栏）；
- ✅ 新世界或"接受存档绑定该 mod"的既有世界；
- ✅ 服务端优先（没有资源包/创造栏/GL），客户端后补；
- ❌ 删除 / 更新 / 替换注册表条目；
- ❌ datapack 内容（biome / dimension / enchantment / damage type / recipe / loot / tag）；
  （**2026-11-15 更新**：recipe / loot / tag 与 RELOADABLE 层已可热加载，见 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md)；WORLDGEN 层仍不行）
- ❌ mod 自己 `NewRegistryEvent` 建的新注册表（启动后 `pendingModdedRegistries = null`，直接抛）；
- ❌ 可回滚 / 存档无副作用。

**建议（待所有者确认）**：

1. 产品路径保持 ADR-0006 的 managed relaunch，T4 对用户仍然写"需要重启"。
2. 用**实验性、默认关闭**的 server-only PoC 验证机制，目标只到
   "新世界 + 一个最小方块/物品夹具 + 存档重开仍可读"。
3. 是否继续投入，取决于第 9 步（数据包）能否在不重启的情况下解决——如果解决不了，
   T4 适配只能覆盖"纯注册表、无数据包内容"的模组，价值有限，不如直接做 relaunch。

## 6. 下一步实验（按顺序，每步都可独立否决）

1. ✅ **最小夹具**（2026-11-15 21:48，已完成）：`experiments/t4-fixture/`（一个方块 + 一个 BlockItem，无数据包），
   在 dedicated server 热加 → 放置 `(0,100,0)` → 存盘 → 把 fixture 放进 `run/mods` 正常启动 → 读档。
   结果：blocks 1060→1061、items 1333→1334、states 26684→26685，stoneId 1→1 稳定；重启后
   `entryId=1060 / stateId=26684` 与热加时一致，`RESULT: PASS`。机制与复现见 [features/t4-registry-hotadd.md](features/t4-registry-hotadd.md)。
2. **失败语义**：construct 阶段抛异常（此时注册表已 unfreeze）→ 确认进程被标记 dirty、
   不尝试回滚、提示重启；这是"单向提交"的诚实实现。
3. **客户端**：同一夹具在客户端登录前热加 → 进入世界 → 模型/翻译/创造栏可见。
4. ✅ **数据包**（2026-11-15，已完成）：夹具已加 `data/`（配方 + 战利品表 + 方块标签），走「注入 pack + 原版 `/reload`」，热加后与重启后都生效。见 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md)。

## 7. 未解问题

1. **登录时 `applySnapshot` 的 id 漂移**：客户端在 configuration 阶段会 `clear(false)` + `freeze()`，
   按源码看同样会移动 BlockState id。NeoForge 线上没出问题，可能是因为旧 id 槽位仍可 `byId` 读回
   （客户端不写存档）。这一点需要单独实验确认，否则不能依赖它的语义。
2. **`IdMappingEvent` 重放**：freeze 会触发它；监听者（JEI、配方查看器等）在运行中收到会怎样，未测。
3. **mod 自带注册表**（`NewRegistryEvent`）：启动后直接抛，是否有绕法（`pendingModdedRegistries` 反射）未评估。
4. ~~**数据包重建**~~（2026-11-15 已评估并解决）：`PackRepository` + `reloadResources` 可行、add-only；见 [t4-datapack-hotadd.md](features/t4-datapack-hotadd.md)。
5. **性能**：89 个注册表全部 unfreeze/freeze 约 15 ms（无新内容）；带新内容 + bake 的实际耗时未测。
6. **`ModifyRegistriesEvent` / `RegisterDataMapTypesEvent` / `DataPackRegistryEvent`**：都是启动期一次性事件，
   T4 模组若依赖它们，需要各自的重放策略。
