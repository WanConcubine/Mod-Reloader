# ADR-0008：T4 先做"注册表适配"评估，产品仍走 managed relaunch

- 状态：**Proposed（待所有者确认）**
- 日期：2026-11-15
- 决策者：项目所有者（agent 提出，待确认）

## 背景

- [ADR-0006](0006-t4-managed-relaunch.md) 决定 T4 走 managed relaunch，明确拒绝"unfreeze + 手工 RegisterEvent 重放"。
- 同日 payload 主动适配（E1–E3）证明"框架的一次性阶段"可以被重放，因此 ADR-0006 的修订要求：
  T4 开题前先评估"注册表适配"是否成立，并单独开 ADR、写明"已有存档不可用"。
- 调研结论见 [research-t4-registry.md](../research-t4-registry.md)：
  - 机制可行：`unfreeze()` 是 public，freeze 的 bake callbacks 会重建 BlockState id / 缓存 / loot table，
    `RegisterEvent` 可按注册表单容器重放，`registryAccess()` 是同一批 registry 的视图；
  - 但**裸 re-freeze 会移动 BlockState 的 canonical id**（实测 stone 1 → 26685），
    必须先清空并重建 id 映射，否则会写出重启后不存在的 id；
  - 无法撤销已注册条目 → **单向提交**；存档会绑定该 mod；
  - datapack 内容（biome/dimension/recipe/loot/tag）、连接后同步、mod 自建注册表仍是空白。

## 决策（提议）

1. **产品路径不变**：T4/T5 对用户仍然只提示"需要重启"；managed relaunch 是唯一推荐的用户路径。
   [ADR-0006](0006-t4-managed-relaunch.md) 的决策不被推翻。
2. **允许一个实验性、默认关闭、server-only、add-only 的注册表适配 PoC**，作为技术验证；
   不进入用户 UI，不作为"T4 支持热加"宣传。
3. PoC 必须满足的硬约束（同时写进实现与文案）：
   - **add-only**：不删除、不更新、不替换条目；回滚只覆盖 unfreeze 之前的阶段。
   - **存档**：只允许新世界，或用户明确接受"存档绑定该 mod"；删除 mod 会导致 NeoForge 的
     missing entries 提示并丢弃这些条目。
   - **datapack 内容不支持**：检测到 mod 声明 biome/dimension/recipe/loot/tag 等即拒绝。
   - **必须在建连接前完成**：登录配置阶段的 registry snapshot 是唯一的同步机会。
   - **提交后失败 = dirty**：标记运行时不可信并要求重启，不尝试回滚。
   - **BlockState id 必须先清空重建**（反射 `BlockCallbacks.BLOCKSTATE_TO_ID_MAP.clear()`），
     否则写出坏存档。
4. 是否转正取决于实验 6.4（数据包能否在不重启的情况下重建）与失败语义；
   在此之前不承诺时间表。

## 后果

正面：

- 用最小成本回答"T4 适配能不能成立"，避免先投入数周的 relaunch 工程后才发现方向问题。
- 已得到的观测（id 漂移、同步时机、回调结构）对 relaunch 路线也有用。

负面：

- 实验代码进入仓库，增加维护面；单向提交与项目"可回滚"的叙事冲突，文案必须严格。
- 如果数据包无法解决，PoC 只能覆盖"纯注册表、无数据"的模组，价值有限。

## 备选（未采用）

- **直接做 managed relaunch，不评估适配**：省事，但回答不了 payload 类比提出的问题。
- **把适配直接做成用户功能**：违反硬规则 3（T4/T5 必须说需要重启）与"可预测/可回滚"的承诺。
