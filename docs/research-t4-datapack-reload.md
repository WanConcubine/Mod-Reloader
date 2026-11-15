# 贵妃级下一步：datapack 热加载（调研 + PoC 计划）

> 状态：**未开始**。这是新会话的入口文档；先读 [status.md](status.md) 的「下一步」。
> 目标：让热加模组的 `data/`（配方、战利品、标签、自定义机器数据）**不重启**就能生效，
> 把众星捧月从「能注册」推进到「能玩」。
> 关联：[t4-registry-hotadd.md](features/t4-registry-hotadd.md) §5/§7、[research-t4-registry.md](research-t4-registry.md)、ADR-0008。

## 0. 为什么是它

2026-11-15 的 dogfood（众星捧月 1.5.0）证明注册层已通：80 方块 / 181 物品 / 134 个 BlockState、
payload、创造栏、资源包都生效。但它有 **299 个配方、77 个战利品表、标签、自定义机器数据**
（`data/exnihilosatellitium/{recipe,loot_table,hammer,sieve,barrel,crucible_*}` 等），
这些是 **datapack 内容**，热加后不在当前数据包列表里——方块能放，但合成/掉落/挖掘等级不对。
所以下一步不是继续扩注册表，而是**数据包热加载**。

## 1. 成功标准（PoC）

在**一次性世界**里：

1. 热加众星捧月（注册层已能过）后，**不重启**把它的 `data/` 加入当前服务端数据包并重载；
2. 日志显示配方/战利品/标签被加载（数量包含 Ex Nihilo 的内容）；
3. 至少验证一条端到端：
   - 配方书/工作台能做出一个 Ex Nihilo 物品（如锤子），或
   - 挖掉一个 Ex Nihilo 方块能按它的 loot table 掉落；
4. 存盘 → 把 jar 放进 `mods/` → 重启 → 世界仍可读。

任何一步失败都要记录**具体失败点**，不要"半成功"就写成支持。

## 2. 已知事实（来自源码阅读 + 实测）

- 服务端数据包由 `WorldLoader` 在**世界加载时**构建 `PackRepository`，随后
  `ReloadableServerResources.loadResources(...)` 加载 recipe/loot/tag 等 manager。
- `/reload` 走 `MinecraftServer.reloadResources(Collection<String>)`：重建
  `ReloadableServerResources` 并替换 recipe/tag/loot manager（具体锚点待核实）。
- 客户端资源（模型/语言）走的是另一条路，已由 A1.2 的 `ClientResourcePacks` 处理；
  本任务只关心**服务端 data**。
- NeoForge 的 `ResourcePackLoader` 有 `populatePackRepository(PackRepository, PackType, boolean)`
  与 `createPackForMod(IModFileInfo)`，客户端用它注入资源包；**服务端 data 是否可复用同一套**
  需要核实（`PackType.SERVER_DATA`、`mod_resources` pack）。
- 热加 mod 的 `data/` 在它的 `ModFile`/`SecureJar` 里；服务端 pack 列表是在世界加载时
  按当时的 mod 列表构建的，所以新 mod 不在里面。
- 自定义 recipe serializer 已在注册阶段注册（`RegisterEvent` 重放），数据里只引用 id，
  理论上不需要再动注册表。

## 3. 候选方案

### A. 注入 pack + 重载（首选，最像 `/reload`）

1. 用热加 mod 的 `ModFileInfo`/`ModFile` 构造一个 `Pack.ResourcesSupplier`（服务端 data 用）；
2. 放进 `PackRepository` 的可用包集合（`Pack`/`PackLocationInfo` + `PackSelectionConfig`），
   标记为 required/selected；
3. 调 `MinecraftServer.reloadResources(selectedIds)`（或等价的 `ReloadableServerResources` 重建）；
4. 观察 recipe/loot/tag 是否包含新内容。

风险：`PackRepository` 的可用包缓存在 `reload()` 里重建；如果它从 mod 列表重新扫描，
手工塞进去的 pack 可能被刷掉。需要先读 `PackRepository.reload` / `ServerPacksSource`。

### B. 重建 `ReloadableServerResources`（更重）

不碰 `PackRepository`，直接构造新的 `ReloadableServerResources`，把新 mod 的 data 作为
额外 `ResourceManager` 输入。风险更高（命令/标签/loot 的引用、`MinecraftServer` 的字段替换），
只在 A 不可行时评估。

### C. 明确不支持（fallback）

如果 A/B 都证明不可行，就把"datapack 内容需要重启"写死到 UI 与文档，
贵妃级只承诺"注册表内容"，不再宣传"配方/战利品也能热加"。

## 4. 实验步骤（一次性世界）

```bash
# 0) 构建当前 runtime（含贵妃级注册层）
./gradlew build

# 1) 开一次性 dev world，热加众星捧月（注册层已验证）
./gradlew runClient -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar -PverifyAddCommandModId=exnihilosatellitium

# 2) 在 datapack 注入实现后，跑同一个 harness（或新 harness），断言：
#    - reload 日志里出现 Ex Nihilo 的 recipe/loot 数量
#    - 用命令/程序化方式检查 RecipeManager 是否认识某个 Ex Nihilo 配方 id
#    - 放一个方块并检查掉落（或直接查 loot table）
```

优先用**程序化断言**（读 `server.getRecipeManager()` / `getLootData()` / `TagManager`），
不要只靠截图。

## 5. 需要先读的源码/锚点

- `MinecraftServer.reloadResources(Collection<String>)`：精确的重载入口与副作用。
- `ReloadableServerResources.loadResources(...)` / `updateRegistryTags(...)`。
- `PackRepository`：`reload()`、`getAvailablePacks()`、`setSelected(...)`、`Pack` 的构造。
- `ServerPacksSource` / `WorldLoader`：服务端世界加载时 pack 列表怎么来的。
- `ResourcePackLoader.populatePackRepository(...)` 在服务端 `PackType.SERVER_DATA` 下的行为。
- `PackType.SERVER_DATA` 的 `ResourcesSupplier` 语义（`Pack.ResourcesSupplier.openPrimary` 等）。
- 客户端配方同步：数据重载后是否要重发 `ClientboundUpdateRecipesPacket`（如果只做单人可先跳过）。

## 6. 风险与边界（不随实现改变）

- add-only、不可回滚、存档绑定该 mod 的约束不变；
- 数据重载可能触发大量 listener（`/reload` 语义），要在一次性世界验证副作用；
- 多人：连接后再热加仍然无法同步注册表快照；数据包同步是另一个问题；
- 如果 A 只能"部分生效"（例如 recipes 生效但 tags 不生效），要如实写边界，不要宣传"支持 datapack"。

## 7. 现状

未开始。下一步就是读第 5 节的源码、实现 A 的最小版本、用 §4 的 harness 验证。
