# 贵妃级 datapack 热加载（PoC）

> 状态：**已实现并验证（add-only）**，2026-11-15。方案 A：注入 pack + 原版 `/reload`。
> 范围：热加 mod 的 `data/`（配方、战利品表、标签、mod 自己注册的 server reload listener）。
> 不含：删除/更新、世界生成注册表（维度/生物群系/结构）。
> 背景与取舍见 [research-t4-datapack-reload.md](../research-t4-datapack-reload.md) 与 [ADR-0009](../decisions/0009-datapack-hotadd.md)。

## 1. 一句话

热加 mod 的 `data/` 现在会**不重启**进入运行中的服务器：把 jar 当 data pack 塞进服务器
`PackRepository`，再走原版 `MinecraftServer.reloadResources(...)`。众星捧月 1.5.0 实测
`recipes 0 -> 164`、`loot tables 0 -> 75`、方块/物品标签，以及它自己注册的
hammer/sieve/barrel/crucible/crook/compost 数据一起生效；一条配方真的能合成，方块战利品表真的掉东西。

## 2. 为什么注册表通了还不够

一个内容模组有两半，活在不同地方：

| | 存哪 | 什么时候读 | 谁负责 |
|---|---|---|---|
| 方块 / 物品 / 实体 | built-in `Registry` | 启动 bootstrap，之后 freeze | [RegistryStage](t4-registry-hotadd.md) |
| 配方 / 战利品 / 标签 / 机器数据 | `data/<ns>/…` | 世界加载时由 `WorldLoader` 建 `PackRepository` 读一遍 | **本页：`DatapackStage`** |

热加发生时世界已经加载完了，新 mod 的 jar 不在那个 `PackRepository` 里——所以它的 `data/`
从来没被读过。方块能放，但没有配方、没有掉落、标签缺失。

## 3. 机制（方案 A：注入 pack + 重载）

`hotadd/DatapackStage`：

```text
（必须先 RegistryStage.commit —— 否则数据里的 item id 全是 air）

apply(server, fileInfo, modIds)
  ├─ jar 没有 data/            → 跳过（嫔/妃级工具模组本来就没有数据）
  ├─ 没有 server（标题屏）      → 记 pending，ServerStartedEvent 时再注入
  ├─ 数据已经在服务器里         → 短路（见 3.3）
  └─ inject(server, …)
       ├─ Pack = ResourcePackLoader.createPackForMod(fileInfo)
       │         + readWithOptionalMeta(…, PackType.SERVER_DATA, …)
       ├─ PackRepository.addPackFinder(pack) + reload()
       │         // 加 finder 而不是直接塞 available：以后每次 /reload 都能重新发现它
       ├─ selected = getSelectedIds() + packId
       └─ MinecraftServer.reloadResources(selected)
                 // 原版 /reload：重建 ReloadableServerResources
                 // → RecipeManager / TagManager / ReloadableServerRegistries(loot table)
                 // → updateRegistryTags() → 广播 recipe/tag 给已连接玩家
```

三个实现细节，都不是可选的：

1. **顺序**：数据里的 recipe ingredient / loot item id 要对着注册表解析。`RegistryStage.commit`
   之前调用只会得到"unknown item"，所以 `HotAddEngine` 把 `Stage.DATAPACK` 放在 `PACK` 之后。
2. **线程**：GUI 和 dev harness 在**客户端线程**热加，而 `PackRepository` 与
   `ReloadableServerResources` 属于服务器线程。`DatapackStage` 检测到不在服务器线程时，
   把整个注入投递到 `server.execute(...)` 并等待结果，避免和服务器读 pack repository 竞态
   （在服务器线程上则直接执行；`reloadResources` 自己在同线程时会 `managedBlock`）。
3. **幂等**：pack id 用 `mod/<modId>`（与 NeoForge 自己的 mod pack 同名）。已经 available 就不再加
   finder；已经选中的不重复选。

`pack.mcmeta` 可以没有：用 `ResourcePackLoader.readWithOptionalMeta` 读元数据，
缺 mcmeta 时按 COMPATIBLE 处理（t4fixture 就没有 mcmeta，实测 `compatibility COMPATIBLE`）。
Ex Nihilo 的 `pack_format: 15` 被判成 `TOO_OLD`，也不影响加载——`/reload` 不校验兼容性。

### 3.1 为什么不用方案 B（重建 `ReloadableServerResources`）

`MinecraftServer.reloadResources` 就是 `/reload` 的实现：它已经做完 pack 选择、资源管理器重建、
recipe/tag/loot 重载、tag 重新绑定、玩家同步。自己拼一份只会少做其中几步。见 ADR-0009。

### 3.2 mod 自己的 reload listener 也会跑

NeoForge 在 `FMLModContainer.constructMod()` 里调用 `AutomaticEventSubscriber.inject(...)`,
所以热加 mod 的 `@EventBusSubscriber` 在构造时注册；`AddReloadListenerEvent` 在那次
`reloadResources` 里被重新 post 一次。实测众星捧月的
`[hammer] loaded 55 recipes` / `[sieve] loaded 7 inputs, 119 drops` /
`[barrel] loaded 16 transforms` / `[crook] ...` / `[crucible] ...` / `[compost] loaded 67 entries`
全部在重载里重新读了一遍。

### 3.3 标题屏热加：defer + 短路

标题屏没有 server，`DatapackStage` 记到 pending，并在下一次 `ServerStartedEvent` 注入。
但多数情况下**不需要注入**：`ClientResourcePacks.inject` 会把热加 mod 放进
`ResourcePackLoader.modResourcePacks`，世界加载时 NeoForge 建 `mod_data` 就会带上它，
数据已经在了。所以注入前先数一遍：namespace 下已有 recipe/loot/tag → 直接返回
`already live (recipes=164, loot tables=75, …); no reload needed`，不做多余重载。
只有"世界加载时没发现这个 mod"的组合（例如客户端此前已经建好 pack 缓存）才会真的走注入。

## 4. 代码锚点

| 文件 | 作用 |
|---|---|
| [hotadd/DatapackStage.java](../../src/main/java/dev/modreloader/hotadd/DatapackStage.java) | pack 构造、注入、`reloadResources`、前后计数、defer/短路 |
| [hotadd/PlatformHooks.java](../../src/main/java/dev/modreloader/hotadd/PlatformHooks.java) | `reloadDataPacks(fileInfo, modIds)`：端相关入口 |
| [client/ClientPlatformHooks.java](../../src/main/java/dev/modreloader/client/ClientPlatformHooks.java) | 客户端取集成服务器（标题屏传 null） |
| [server/ServerPlatformHooks.java](../../src/main/java/dev/modreloader/server/ServerPlatformHooks.java) | 专用服取 `ServerLifecycleHooks.getCurrentServer()` |
| [hotadd/HotAddEngine.java](../../src/main/java/dev/modreloader/hotadd/HotAddEngine.java) | `Stage.DATAPACK` / `FailureKind.DATAPACK`，在 `PACK` 之后调用；带 `data/` 的 jar 在 VALIDATE 阶段先检查 `DATAPACK_RELOAD` 锚点 |
| [capability/Capabilities.java](../../src/main/java/dev/modreloader/capability/Capabilities.java) | `DATAPACK_RELOAD`：`reloadResources` / `reloadableRegistries` / `addPackFinder` / `createPackForMod` / `readWithOptionalMeta` 的启动探测 |
| [diagnose/FailureKeys.java](../../src/main/java/dev/modreloader/diagnose/FailureKeys.java) | `datapack` 文案键（中英文案由单测核对） |

## 5. 证据

### 5.1 世界内热加真实模组（众星捧月 1.5.0，单人集成服务器，2026-11-15 23:48）

```bash
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium \
  -PverifyDatapack=true
```

```text
[AddCmdCheck] datapack baseline before add: recipes in exnihilosatellitium = 0 (a fresh world has none)
[ModReloader] datapack pack mod/exnihilosatellitium built for [exnihilosatellitium] (compatibility TOO_OLD)
[ModReloader] datapack reload: pack mod/exnihilosatellitium selected;
              recipes 0 -> 164, loot tables 0 -> 75, block tags 0 -> 1, item tags 0 -> 2
[AddCmdCheck] datapack recipes: 0 -> 164 in namespace exnihilosatellitium; exnihilosatellitium:wooden_crucible=true
[AddCmdCheck] datapack loot tables: 75 in namespace exnihilosatellitium; exnihilosatellitium:blocks/wooden_crucible=true
[AddCmdCheck] datapack block tag #exnihilosatellitium:wooden_crucibles: present=true, entries=11
[AddCmdCheck] datapack craft: 6x oak_log + oak_slab -> exnihilosatellitium:wooden_crucible = exnihilosatellitium:wooden_crucible
[AddCmdCheck] datapack loot drop: exnihilosatellitium:wooden_crucible -> [exnihilosatellitium:wooden_crucible x1]
[AddCmdCheck] datapack RESULT: PASS (recipes/loot tables/tags/crafting/drops)
[AddCmdCheck] RESULT: PASS
```

同一次重载里，mod 自己的数据表也重新加载（来自那次 `reloadResources` 的 server 线程）：

```text
Loaded 1454 recipes
[hammer] loaded 55 recipes (0 tag files, 0 patches, 0 overlay, waiting for tags)
[sieve] loaded 7 inputs, 119 drops …
[barrel] loaded 16 transforms (2 fluid, 14 item) …
[crook] loaded 10 leaves tables, 22 drops
[crucible] loaded 39 meltables / 9 heat sources
[compost] loaded 67 entries
```

**164 而不是 298 是正常的**：jar 里 298 个 recipe 文件中有 134 个带
`neoforge:conditions`（依赖 mekanism / thermal / immersiveengineering 等没装的模组）。
按同样条件静态核对，期望加载数正是 **164 / 跳过 134**——与正常启动一致，不是热加载丢了配方。
75 个 loot table 全部加载（jar 里就是 75 个文件）。

### 5.2 标题屏热加 → 建世界（2026-11-15 23:50）

```bash
# 上面同一条命令再加 -PverifyDatapackAtTitle=true
```

```text
[ModReloader] datapack for [exnihilosatellitium] deferred: no server yet; it will be injected when a world starts
…
[ModReloader] datapack for [exnihilosatellitium]: already live (recipes=164, loot tables=75, block tags=1, item tags=2); no reload needed
[AddCmdCheck] datapack RESULT: PASS (recipes/loot tables/tags/crafting/drops)
```

即主菜单/inbox 的 GUI 路径（先热加、再进世界）拿到的是同样完整的数据，且不会多付一次重载。

### 5.3 dedicated server 热加 + 重启（t4fixture，2026-11-15 23:49 复跑）

夹具新增 `data/t4fixture/{recipe,loot_table,tags}`（一条配方、一张方块战利品表、一个方块标签），
且**故意不带 `pack.mcmeta`**。流程见 [experiments/t4-fixture](../../experiments/t4-fixture/README.md)。

热加那一次：

```text
[ModReloader] datapack pack mod/t4fixture built for [t4fixture] (compatibility COMPATIBLE)
[ModReloader] datapack reload: pack mod/t4fixture selected; recipes 0 -> 1, loot tables 0 -> 1,
              block tags 0 -> 1, item tags 0 -> 0
[T4Fixture] datapack: recipe t4fixture:test_block=true loot t4fixture:blocks/test_block=true
            tag #t4fixture:test_blocks=true (1 entries)
[T4Fixture] datapack (after hot-add) RESULT: PASS
[T4Fixture] placed t4fixture:test_block at BlockPos{x=0, y=100, z=0}; readback=true stateId=26684
[T4Fixture] world saved; RESULT: PLACED
```

把 jar 放进 `run/mods` 正常重启（同一存档）：

```text
[T4Fixture] common setup: … entryId=1060 stateId=26684 item=t4fixture:test_block itemId=1333
[T4Fixture] restart readback BlockPos{x=0, y=100, z=0}: … isT4Block=true RESULT: PASS
[T4Fixture] datapack: recipe t4fixture:test_block=true loot t4fixture:blocks/test_block=true
            tag #t4fixture:test_blocks=true (1 entries)
[T4Fixture] datapack (after restart) RESULT: PASS
```

即成功标准第 4 条：**存盘 → jar 进 `mods/` → 重启 → 存档可读，数据也照常加载**。
注意这一条是在专用服 fixture 上验证的，众星捧月那个一次性客户端存档没有单独做重启回读。

## 6. 复现

```bash
# 1) 世界内：配方/战利品/标签/合成/掉落
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyDatapack=true

# 2) 标题屏（GUI 路径）：先热加，再建世界
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyDatapack=true -PverifyDatapackAtTitle=true

# 3) 专用服 + 重启：见 experiments/t4-fixture/README.md
```

日志关键字：`[ModReloader] datapack reload:`、`datapack RESULT:`、`[AddCmdCheck] datapack`、
`[T4Fixture] datapack`。`-PverifyDatapack` 只有配合 `-PverifyAddCommand`（和 mod id）才有意义。

## 7. 限制（按当前实现）

1. **add-only**：不能删除、不能更新；已提交的注册表内容仍然不可回滚。datapack 重载失败时
   `FailureKind.DATAPACK` 会明确说"注册表已提交，运行时 dirty"，让用户重启。
   注意：**这条失败路径只有实现、没有专门夹具触发过**（需要 mod 在 reload listener 里抛异常才会让
   `reloadResources` 失败；`RecipeManager`/`TagLoader` 对坏文件是逐条记日志后跳过，不会让重载失败）。
2. **世界生成注册表不生效**：`reloadResources` 只重建 `RegistryLayer.RELOADABLE`
   （战利品表、附魔、伤害类型、纹饰、物品修饰器等），维度/生物群系/结构属于 WORLDGEN 层，
   要世界加载才生效。mod 若靠 `data/<ns>/worldgen` 提供维度，仍然需要重启/新世界。
3. **存档会记录这个 pack**：`reloadResources` 会把当前选择写进 `level.dat` 的 data pack 配置
   （id 是 `mod/<modId>`）。重启时 jar 在 `mods/` 里则同一个 id 又被 NeoForge 自动收进
   `mod_data`，该包会出现两次（实测无副作用：资源按 id 归并，计数不变、无报错）。
4. **多人**：重载会通过 `PlayerList.reloadResources()` 给已连接玩家广播配方/标签，
   但"连接后再热加"的**注册表快照**同步仍未解决（那是注册表层的问题，不是 datapack）。
5. **只验证了两个模组**：众星捧月 1.5.0（真实大模组）与 t4fixture（最小夹具）；
   没验证过带 `data/<ns>/worldgen`、或 `pack.mcmeta` 声明 `supported_formats` 之外的写法。
6. **标题屏 + pack 缓存已建好**的极端组合走的是与世界内相同的注入路径，但没有单独的 harness；
   当前实测的标题屏路径走的是 `already live` 短路。

## 8. 下一步

- 连接后热加 + 注册表快照同步（真正的多人硬骨头）；
- datapack 删除/更新语义（需要 pack 变更 + 存档修复，属于"不可回滚"范畴）；
- 世界生成注册表（维度/生物群系）——大概率只能 managed relaunch。

## 9. 删除 / 更新语义（2026-11-16 量测：删除机制上不可能，更新不做）

§7/§8 一直把「删除/更新」列为未做。现在有实测答案，而不是"大概很难"。

### 9.1 问题

热加的 data pack 能不能在运行时**删掉**（deselect + reload）？能不能**换掉**（同一个 `mod/<modId>` 换成新内容）？

### 9.2 删除：从 selected 里摘掉不等于不加载（实测）

harness：`-PverifyDatapackRemove=true`（挂在 `AddCommandCheck` 上，跟在 `-PverifyDatapack` 之后）。流程是
热加众星捧月 → 断言 recipes 164 / loot 75 → 把 `mod/exnihilosatellitium` 从 `PackRepository` 的 selected 里移除 →
`server.reloadResources(...)` → 再读一次。2026-11-16 20:1x 的日志原文：

```
[AddCmdCheck] datapack removal attempt: mod/exnihilosatellitium was selected; deselecting it and reloading
              (available=[bundle, trade_rebalance, vanilla, mod/exnihilosatellitium, mod_data])
[AddCmdCheck] datapack removal: selected ids=[vanilla, mod_data]
              selected packs=[vanilla, mod_data, mod/mr, mod/modreloader, mod/neoforge, mod/exnihilosatellitium]
              recipes=164 loot tables=75
[AddCmdCheck] datapack REMOVE RESULT: NOT POSSIBLE
              (the id is deselected but the pack stays in selectedPacks as a child of the required
               mod_data root; recipes 164 and loot 75 still live)
```

**为什么**：NeoForge 把所有 mod 数据包挂成**一个 required 聚合包 `mod_data` 的子包**
（`ResourcePackLoader.makePack(...)` 用 `PackSelectionConfig(required=true, …)` + `withChildren(hiddenPacks)`），
而 `PackRepository.rebuildSelected` 每次都把 required 包的 children 重新加回来
（`expandAndRemoveRootChildren` 的注释："Remove all children of required packs -- as these will always be present"）。
所以「从 selected 的 id 列表里去掉」只影响列表本身，不影响真正参与 reload 的 pack 集合：
`getSelectedIds()` 里已经没有它，`getSelectedPacks()` 里还在。

要真删就得动 `ResourcePackLoader.modResourcePacks` 并重建聚合包——那是改 NeoForge 的启动期数据结构；
而且**注册表内容仍然在**（物品/方块没有删除接口），删完只会得到「mod 还在、数据没了」的半死状态，
存档里已有的引用也照样有效。这不是产品想要的"删除"。

### 9.3 更新：不做

同一个聚合结构让"换一个同 id 的新包"也不成立：`mod_data` 的 children 在启动时固定，包资源 supplier 也绑在
被热加的 `IModFile`（`SecureJar`）上，重新 reload 读到的仍是旧内容——要换就得先解决"重新读 jar"，
而那属于 §8 的 managed relaunch。产品上更简单：**更新 = 重启**（把新 jar 放进 `mods/`）。

### 9.4 结论与回归

- datapack 阶段保持 **add-only**；`-PverifyDatapackRemove` 作为"这条边界仍然成立"的回归：
  如果哪天 NeoForge 改了聚合结构、包真的能被摘掉，它会以 FAIL 提醒我们重写本章。
- 用户撤销热加的唯一可靠路径仍是**重启且不放那个 jar**（存档里该 mod 的注册表条目会变成 missing entries）。

```bash
# 一次跑完「热加 → 数据生效 → 尝试删除 → 断言删除不可行」
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium -PverifyDatapack=true -PverifyDatapackRemove=true
```

## 10. WORLDGEN（维度）边界：从「需重启」改写成「需新世界」（2026-11-16 实测）

§7 一直写「世界生成注册表（维度/生物群系/结构）仍需重启或新世界」——两个选项里到底是哪个？现在有答案。

夹具：[experiments/worldgen-fixture](../../experiments/worldgen-fixture/)（只有数据：一个超平坦维度 `wgfixture:wgflat` +
它的 `dimension_type`）。harness：`-PverifyWorldgen=<维度 id>`，配合标题屏热加
（`-PverifyDatapackAtTitle=true`）→ 新建世界 → 断言维度是否**真的能进**
（`Registries.DIMENSION` 里有 id + `server.getLevel(key) != null`）。

### 10.1 修之前：维度不可用（"需重启"）

```
[ModReloader] datapack for [wgfixture] deferred: no server yet; it will be injected when a world starts (one extra data reload)
[ModReloader] deferred datapack: pack mod/wgfixture selected; recipes 0 -> 0, loot tables 0 -> 0, …
[AddCmdCheck] worldgen: dimension wgfixture:wgflat registered=false level=false (reloaded packs=[vanilla, mod_data, mod/wgfixture])
[AddCmdCheck] FAILED: … the WORLDGEN boundary is still 'restart', not 'new world'
```

原因：标题屏热加走的是 `defer`，注入点是 `ServerStartedEvent`——**世界已经加载完**，而维度在 world load 时构建，
晚一步就再也补不上（reload 只重建 RELOADABLE 层）。

### 10.2 修法：标题屏热加时把包**预注册**进 NeoForge 的 mod 数据包表

世界加载时 `ServerPacksSource.create` → `ResourcePackLoader.populatePackRepository` 会用
`modResourcePacks`（`IModFile → ResourcesSupplier`）重建 `mod_data` 的子包列表。所以 `DatapackStage` 在
`server == null` 分支里先把 `ResourcePackLoader.createPackForMod(fileInfo)` 放进那张表（`preregister`），再照旧 `defer`
当兜底：下一次 world load 的仓库里就已经有它，维度随世界一起注册。

```
[ModReloader] datapack for [wgfixture] pre-registered for the next world load (mod data packs now 4)
[AddCmdCheck] worldgen: dimension wgfixture:wgflat registered=true level=true
[AddCmdCheck] worldgen RESULT: PASS (dimension wgfixture:wgflat is registered and has a level after a title-screen add)
```

### 10.3 结论与边界

- **标题屏热加 + 新建世界**：`RegistryLayer.WORLDGEN` **可用**（维度实测可进）→ 文档措辞从「需重启」改成「**需新世界**」。
- **已经存在的世界**：仍然不行（维度在它的 world load 时就定死了）——要进新维度就换新世界，或重启。
- **专用服启动期热加**（世界还没加载过）：`modResourcePacks` 还没被 FML 初始化（`findResourcePacks` 在第一次
  `populatePackRepository` 时才跑），`preregister` 只能 warn 并退回 `defer` → 这种情况下 WORLDGEN 仍不可用；
  要覆盖它得在热加前触发一次 `findResourcePacks`，没做（先记录）。
- 回归：标题屏热加众星捧月 + `-PverifyDatapack` 仍 PASS（预注册后它的 recipes 在 world load 就有了）；
  进世界 `/modreloader add` 的 `-PverifyGameplay` 回归 PASS。
