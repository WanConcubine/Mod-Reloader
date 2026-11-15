# ADR-0005：我们是 runtime，不是 mod；A 优先，B 仅作降级

- 状态：Accepted
- 日期：2026-11-14
- 决策者：项目所有者

## 背景

LAN 场景暴露了一个产品岔路：

- **A（热加载 LAN 模组）**：保留 `lanserverproperties` / `mcwifipnp`，只把“必须重启”去掉。
- **B（原生 LAN Doctor）**：不加载第三方模组，自己实现 offline / republish / 地址。

B 更简单、更快，但它会把项目变成“另一个联机补丁模组”。这与本项目的身份冲突：

> **我们是运行时（runtime），不是功能模组集合。**

如果继续做 B，下一步就会变成“再实现 UPnP”“再实现 UUID 修复”“再实现端口持久化”——最后变成和 `mcwifipnp` 竞争，而不是做热重载。

## 决策

1. **项目身份**：Minecraft 模组的运行时加载器 / 热换器；不是 LAN 模组，也不是“内置功能大全”。
2. **主线是 A**：热加载用户已经信任的第三方模组，消灭重启，而不是替代模组本身。
3. **B 降级为 fallback**：只在 A 失败/不可用时，作为诊断后的安全兜底；不作为产品卖点，不投入主要精力。
4. **LAN 是第一个 use case，不是产品定义**。下一个场景可能是语音、手柄、HUD——同一套引擎。

## 后果

正面：

- 身份清晰：我们卖的是“不用重启”，不是“又一个 LAN mod”。
- 可泛化：LAN 之外的工具模组都能受益。
- 不与成熟 LAN 模组正面竞争；它们是乘客，不是对手。

负面：

- A 明显更难：T3 布局变更（`MinecraftServer` 加字段）、已加载类、JBR/sidecar。
- 用户仍然需要安装 LAN mod（只是不用重启）。
- 如果 A 对某个模组失败，体验会退回到 B 或重启。

缓解：

- 先做 **A0：T2 热加**（EasyLAN，无 mixin），验证 classpath + ModList + 入口点。
- 再做 **A1：T3 热加**（lanserverproperties，4 个 mixin，布局变更），验证 re-mix + redefine。
- NHMJ（MIT）已证明 NeoForge 1.21 客户端热加可行，可作为 prior art，不复制代码。
- B 的实现留在 `features/lan-doctor.md`，标为 fallback，不阻塞 A。

## 备选（未采用）

- **B 作为主线**：更快，但身份漂移，最终变成 LAN mod 竞品。
- **纯 B、不做 A**：放弃核心差异化。
- **A + B 混合**：采纳，但 B 只做 fallback，不做完整功能。
