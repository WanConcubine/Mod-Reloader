# ADR-0010：连接后的注册表 id 同步走「运行时自有 play 通道 + 复用 applySnapshot」

- 状态：**Proposed**（实现与证据已就绪，待所有者确认后改 Accepted）
- 日期：2026-11-16
- 决策者：项目所有者（agent 提出，实现与验证由 agent 完成）

## 背景

- NeoForge 只在登录的 **configuration 阶段**同步注册表：`SyncRegistries` 发
  `FrozenRegistrySyncStartPayload` + 每个注册表的 `FrozenRegistryPayload` + `Completed`，
  客户端在 `Completed` 里调 `RegistryManager.applySnapshot(...)`。
- 单人游戏不需要这套：集成服务器和客户端共用 `BuiltInRegistries`（`registryAccess(BLOCK)` 与
  `BuiltInRegistries.BLOCK` 是同一个实例，见 [research-t4-registry.md](../research-t4-registry.md) §2）。
- **专用服 + 独立客户端**需要：服务端热加后，已在线客户端仍在用它自己算出来的 block/item id，
  区块段、物品栈、实体生成全部指错条目。
- 于是「连接前热加」成了硬性要求（[research-t4-registry.md](../research-t4-registry.md) §1.4 当时的推论）。
  但这只是把问题推给用户：**两端的 id 顺序未必相同**，即使都在连接前热加也不保证一致。
- 关键事实（读 `neoforge-21.1.265-sources.jar` 确认）：`RegistryManager.applySnapshot` / `takeSnapshot` /
  `revertToFrozen` 与 `RegistrySnapshot.STREAM_CODEC` 都是 public；`applySnapshot` 内部是
  `unfreeze → clear(false) → registerIdMapping → freeze`，而 `freeze` 会跑 bake callback
  （`BlockCallbacks.onBake` 按注册表顺序重建 `BLOCKSTATE_TO_ID_MAP`）——**登录同步和「晚同步」需要的
  是同一段代码**。缺的只是 play 阶段没有这条 payload。

## 决策

1. **运行时自己开一条 play 通道**（两端都装 Mod Reloader，热加的 mod 不需要知道）：
   - 服务端在 `RegistryStage.commit` 之后 `takeSnapshot(SYNC_TO_CLIENT)`，每个注册表一个
     `RegistrySyncPayload(registry, snapshot, last)`，发给 `PlatformHooks.registrySyncTargets()`
     （专用服=玩家列表；客户端=集成服务器的非 memory 连接，host 自己跳过，与 NeoForge 登录期规则一致）。
   - **反向也做**：客户端 commit 后发 `RegistrySyncRequestPayload` 拉一次，覆盖「客户端先加/服务端后加」的时序。
     单人不发（那是问自己）。
2. **应用就复用 `RegistryManager.applySnapshot(map, false)`**，不重写 id 重映射——blockstate bake 顺序、
   alias、`clear(false)` 的语义都由 NeoForge 保证；这也是登录期客户端走的那条路。
3. **整批、且两端条目集合必须一致**。`applySnapshot` 只重排 id、**不删客户端多出来的条目**；
   若快照是客户端集合的子集，多出来的条目会变成「没有 id」（`getId` = -1），同样错位。
   所以客户端应用前先比每个注册表的 key 集合，不一致就**完全不应用**（附带人话提示与差异摘要）。
4. **不踢人**。登录期不一致会让 NeoForge 断开连接，但游戏中间踢人比保持旧状态更糟：拒绝 + 提示，
   下次登录 NeoForge 自然带上新注册表（热加之后进来的客户端不需要任何额外步骤）。
   `applySnapshot` 返回 missing 时 `revertToFrozen()` 回到登录快照。
5. **能力守卫**：新增第七组 `Capabilities.registry_sync`（`takeSnapshot`/`applySnapshot`/`revertToFrozen`/
   `STREAM_CODEC`），漂移时启动就报 `registry_sync=missing`。

## 后果

- **好处**：专用服可以在玩家在线时热加内容 mod，只要客户端也热加了同一批 jar（或启动时就装好），
  客户端 id 会被服务端权威映射覆盖；顺序不再重要。
- **代价/边界**：
  - **客户端必须也有同一批模组**——registry 的「值」是客户端本地注册的对象，快照只重排 id，
    不会凭空造出方块。客户端不装 jar 的模式仍不支持（属于资源/类的分发问题，不是本 ADR 的范围）。
  - 两端都要装 Mod Reloader（自有通道）；只覆盖 `doesSync = true` 的 built-in 注册表。
  - 依赖 `RegistryManager.applySnapshot` 的语义，已进 `Capabilities`；升级 NeoForge 版本时要跑
    `-PverifyMpRegistry` 回归。
- 证据：2026-11-16 10:40 跨进程（专用服 + 独立客户端，两端**相反顺序**热加）
  `applied registry snapshot from the server: 30 registries, 5043 entries` →
  `ids match the server exactly (29475 lines, blocks=1141, items=1515)`，见
  [features/t4-registry-hotadd.md](../features/t4-registry-hotadd.md) §9。

## 备选（未采用）

- **重发登录期的 `FrozenRegistryPayload`**：这些 payload 只注册在 configuration 阶段，play 阶段发过去
  客户端会当成未注册 payload 处理；而且登录处理器会 `disconnect` 而不是恢复。
- **自己写 id 重映射 + blockstate 重建**：与 NeoForge 的逻辑重复，`clear(false)` 与 bake 顺序的坑
  （见 [research-t4-registry.md](../research-t4-registry.md) §2 的 id 漂移）已经踩过一次，不值得再踩。
- **要求用户重连**：能解决，但体验差，而且客户端重连时若 jar 仍未装好还是一样；不能算「连接后可用」。
- **只支持「连接前热加」**（现状）：两端顺序不一致时依然错位，只是把问题藏起来。
