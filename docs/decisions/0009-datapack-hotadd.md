# ADR-0009：datapack 热加载走「注入 pack + 原版 `/reload`」

- 状态：**Proposed**（实现与证据已就绪，待所有者确认后改 Accepted）
- 日期：2026-11-15
- 决策者：项目所有者（agent 提出，实现与验证由 agent 完成）

## 背景

- 众星捧月（Ex Nihilo 1.5.0）的 dogfood 证明注册表层已通：80 方块 / 181 物品 / 134 个 BlockState
  能热加，但 **299 个配方、77 个战利品表、标签、自定义机器数据都不加载**——它们是 datapack 内容，
  在**世界加载时**由 `WorldLoader` 建 `PackRepository` 读一遍；热加的 jar 不在那个仓库里。
- 于是"能注册"不等于"能玩"：方块能放，但合成/掉落/挖掘等级不对。
- [research-t4-datapack-reload.md](../research-t4-datapack-reload.md) 列了三个候选：
  A 注入 pack + `MinecraftServer.reloadResources`；B 自己重建 `ReloadableServerResources`；C 明确不支持。
- ADR-0008 §3 曾写"datapack 内容不支持（检测到即拒绝）"。这条现在是空白项，不是结论。

## 决策

1. **采用方案 A**：构造 `Pack`（`ResourcePackLoader.createPackForMod` + `readWithOptionalMeta`），
   以 `PackRepository.addPackFinder` 注入并以 `mod/<modId>` 选中，然后调用
   `MinecraftServer.reloadResources(selectedIds)` —— 与 `/reload` 完全同一条路径。
   不重建 `ReloadableServerResources`（方案 B 只会少做步骤，见下）。
2. **顺序是硬约束**：datapack 阶段必须在 `RegistryStage.commit` 之后。数据里的 recipe ingredient
   与 loot item id 要对着已提交的注册表解析，否则全是 air。`HotAddEngine` 的
   `Stage.DATAPACK` 因此排在 `Stage.PACK` 之后。
3. **端无关**：通过 `PlatformHooks.reloadDataPacks(fileInfo, modIds)` 暴露；客户端传集成服务器
   （标题屏传 null → defer 到 `ServerStartedEvent`），专用服传
   `ServerLifecycleHooks.getCurrentServer()`。引擎不 import 任何端类型（维持 P0-2 的边界）。
4. **线程**：不在服务器线程时把注入投递到服务器线程并等待；`PackRepository` 与
   `ReloadableServerResources` 不是线程安全的。
5. **幂等 + 短路**：pack id 用 `mod/<modId>`；已 available 就不重复加 finder；
   namespace 下已有 recipe/loot/tag（标题屏热加 + 世界加载时已发现该 mod）就直接返回
   `already live`，不做多余重载。
6. **失败语义不变**：datapack 失败时新增 `FailureKind.DATAPACK`，文案明确
   "注册表已提交、运行时 dirty、请重启"。不假装可回滚。
7. **边界写死**：
   - add-only，不删除/更新；
   - 只覆盖 `RegistryLayer.RELOADABLE`（战利品、附魔、伤害类型、纹饰、物品修饰器…）；
     维度/生物群系/结构等 **WORLDGEN 层不生效**，仍要重启或新世界；
     （2026-11-16 补充实测：标题屏热加时把包预注册进 `modResourcePacks`，**下一个新世界**就能拿到维度
     ——`-PverifyWorldgen` 里 `wgfixture:wgflat` `registered=true level=true`；已存在的世界仍需重启。
     见 [t4-datapack-hotadd.md](../features/t4-datapack-hotadd.md) §10。）
   - 连接后热加的**注册表快照**同步仍未解决（与 datapack 无关，是登录配置阶段的问题）；
   - 存档会记录 `mod/<modId>` 这个 data pack。重启时 jar 在 `mods/` 里则它在 `mod_data`
     下也会出现一次；实测重复出现在资源管理器里无副作用（按 id 归并、计数不变、无报错）。

## 后果

正面：

- 真实 dogfood 从"能注册"推进到"能玩"：众星捧月热加后配方 0→164、战利品 0→75、标签 2+1，
  一条配方真的能合成、方块真的按战利品表掉落；mod 自己的 hammer/sieve/barrel/crucible/crook/compost
  数据也在同一次重载里重新加载。
- 复用原版 `/reload`，副作用（tag 重绑、玩家同步、函数库替换）与用户按 `/reload` 时一致，
  不需要逐个模拟。
- 主菜单/inbox 的 GUI 路径（先热加再进世界）不需要额外重载：世界加载时 NeoForge 就发现了这个 pack。

负面：

- 存档会绑定这个 data pack（与注册表绑定同源）；删除 jar 后 NeoForge 会提示 missing entries。
- "datapack registry" 这个词容易让人以为维度也能热加——文案必须区分 RELOADABLE 与 WORLDGEN。
- `PackRepository` 的选择会被写进 `level.dat`，重启时同一个包可能被列两次（无害，但不优雅）。

## 备选（未采用）

- **方案 B：自己重建 `ReloadableServerResources`**：要手工处理 pack 选择、资源管理器生命周期、
  tag 重新绑定、玩家同步、函数库替换；少做任何一步都得到"半生效"。只有在 A 被证明不可用时才考虑。
  实测 A 可行，B 不进入仓库。
- **方案 C：明确不支持**：与"尽可能扩大可用范围"的初衷相反，且 dogfood 已经卡在这里。
- **只把 jar 放进 `mod_data` 的隐藏子包**：需要重建 NeoForge 的 `modResourcePacks` 静态缓存
  （`findResourcePacks()` 只在第一次调用时扫描 `ModList`），比加一个独立 pack 更脆。
