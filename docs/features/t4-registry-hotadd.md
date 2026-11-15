# T4 注册表热加（实验性，server-only）

> 状态：**实验性能力**，已在 dedicated server 端到端验证，GUI 路径也已放行（2026-11-15）。
> 范围：built-in 注册表的 **add-only**（方块 / 物品）；不含删除、更新、datapack 内容。
> 背景与取舍见 [research-t4-registry.md](../research-t4-registry.md) 与 [ADR-0008](../decisions/0008-t4-registry-adaptation-poc.md)。

## 1. 机制

`hotadd/RegistryStage` 把一次 T4 热加分三步（顺序与正常启动一致）：

```text
begin   → GameData.unfreezeData()        // 在构造之前：有的 mod 在构造器里直接 Registry.register
construct → @Mod 构造器 / DeferredRegister 挂监听
commit  → for registry in GameData.getRegistrationOrder():
              for container: container.acceptEvent(new RegisterEvent(key, registry))
          → 清空 Block.BLOCK_STATE_REGISTRY（NeoForge 的 BlockCallbacks 映射）
          → GameData.freezeData()        // 跑 bake：新方块 initCache/loot table、state id 从 0 重建、IdMappingEvent
```

两个关键点：

1. **`RegisterEvent` 按注册表、按容器重放**。全局重放会让每个已加载 mod 的注册监听器再跑一遍并撞
   `duplicate key`；`RegisterEvent` 的构造器是包私有的，用 trusted lookup 构造。
2. **freeze 前必须清空 BlockState id 映射**。`BlockCallbacks.onBake` 用 `IdMapper.add()` 重建，
   而 `add()` 无条件分配新 id；不清空的话 stone 的 id 会从 1 变成 26685（见 research §2），
   之后写出的区块会引用重启后不存在的 id。

失败语义是**单向提交**：注册表条目无法撤销。`begin` 时注册的 undo 只在 commit 之前有效
（清空 state map 后重新 freeze，id 回到 canonical）；commit 之后失败只记录
"registry content was committed and cannot be rolled back"，运行时视为 dirty。

## 2. 证据（2026-11-15 21:48，dedicated server）

夹具：[experiments/t4-fixture](../../experiments/t4-fixture/)（一个方块 + 一个 BlockItem，DeferredRegister）。

第一次运行（热加 + 放置 + 存盘）：

```text
[ModReloader] registry content detected: true
[ModReloader] registry window open: blocks=1060 items=1333 states=26684 stoneId=1
[T4Fixture] constructed
[ModReloader] registry commit: 89 registries replayed; blocks 1060 -> 1061, items 1333 -> 1334,
              states 26684 -> 26685 (cleared 26684 entries), stoneId 1 -> 1 (stable)
[T4Fixture] common setup: block=t4fixture:test_block entryId=1060 stateId=26684 item=t4fixture:test_block itemId=1333
[ModReloader] hot-added [t4fixture] (modules [t4fixture])
[T4Fixture] placed t4fixture:test_block at BlockPos{x=0, y=100, z=0}; readback=true stateId=26684
[T4Fixture] world saved; RESULT: PLACED
```

第二次运行（fixture 放进 `run/mods`，正常启动，读档）：

```text
[T4Fixture] common setup: block=t4fixture:test_block entryId=1060 stateId=26684 item=t4fixture:test_block itemId=1333
[T4Fixture] restart readback BlockPos{x=0, y=100, z=0}: expected=t4fixture:test_block
            actual=t4fixture:test_block isT4Block=true RESULT: PASS
```

结论：add-only 的方块/物品在服务端可以热加，id 稳定，放置后存盘、重启读档仍然正确。

## 3. 客户端路径（2026-11-15 22:21，单人/集成服务器）

fixture 现在带 blockstate / model / lang，并监听 `BuildCreativeModeTabContentsEvent`。
在**世界里**执行 `/modreloader add`：

```text
registry commit: ... stoneId 1 -> 1 (stable)
[T4Fixture] added item to creative tab minecraft:building_blocks
[ModReloader] rebuilt 14 creative tab(s) after registry content
[T4Fixture] common setup: entryId=1060 stateId=26684 item=t4fixture:test_block itemId=1333
[ModReloader] resource packs selected after add: [... mod/t4fixture] (new [mod/t4fixture])
[AddCmdCheck] RESULT: PASS
```

`PlatformHooks.afterRegistryContent()` 在 commit 之后重建创造栏（`CreativeModeTabRegistry.sortTabs()` +
对每个 tab `buildContents(params)`），所以已经在世界里时也能看到新物品。资源包仍走 A1.2 的注入路径。

## 4. GUI 路径（2026-11-15 22:18）

watcher 把 fixture 丢进 inbox 后，提示屏显示 `[T4]` 与警告文案（实验性 / 仅新增 / 存档依赖 / 不可回滚），
Load 可用；点击后走同一个引擎，`WatchCheck RESULT: PASS`。截图：

![T4 提示屏](images/t4-registry-prompt.png)

```bash
./gradlew runClient -PverifyWatch=true -PwatchJar=/tmp/t4fixture-ui.jar -PwatchModId=t4fixture
```

## 5. 复现

```bash
# 1) 构建夹具（需要先 ./gradlew build 生成 build/moddev/*LegacyClasspath.txt）
./experiments/t4-fixture/build.sh

# 2) 热加 + 放置 + 存盘 + 退出
./gradlew runServer -PautoAdd=/tmp/t4fixture/t4fixture-1.0.0.jar -Pt4fixtureSelftest=true

# 3) 把夹具放进 run/mods（模拟"下次正常启动"），读档验证
cp /tmp/t4fixture/t4fixture-1.0.0.jar run/mods/
./gradlew runServer -Pt4fixtureSelftest=true -Pt4fixtureSelftestRead=true
rm run/mods/t4fixture-1.0.0.jar
```

## 6. 限制（按当前实现）

- **只有服务端验证**：客户端资源包（模型/blockstates/语言）、创造栏重建、登录同步还没接。
- **add-only**：没有删除/更新；删掉 mod 会让存档里的条目变成 missing entries（NeoForge 提示并丢弃）。
- **不支持 datapack 内容**：biome / dimension / enchantment / damage type / recipe / loot / tag 仍需重生。
- **不支持 mod 自建注册表**：`NewRegistryEvent` 在启动后关闭（`pendingModdedRegistries = null`）。
- **post-register 事件未重放**：`BlockEntityTypeAddBlocksEvent` / spawn placements / attribute modifications
  还没有接；方块 + 物品之外的实体类内容暂时不要指望。
- **UI 已放行，但只有警告、没有确认框**：Load 一次点击即提交，且不可回滚。
- **多人登录同步未验证**：连接建立后再热加，已在线客户端拿不到新的 registry snapshot（登录配置阶段是唯一同步点）；两端都必须在连接前热加。
- **提交后失败不可回滚**：运行时被标记 dirty，只能重启。

## 7. 下一步

1. 多人：两端在连接前热加 → 登录快照包含新条目；连接后热加则明确拒绝/提示。需要一个跨进程 harness。
2. 失败语义：construct 抛异常时确认 abort 能恢复 canonical id，并把 dirty 状态暴露给用户。
3. datapack：评估数据包重建；不可行就写死边界。
