# 使用场景

> 记录“什么情况下无需重启最有价值”。场景优先级高于技术炫技。
> 当前状态见 [status.md](status.md)；分级含义见 [glossary.md](glossary.md) / [design.md](design.md)。

## 1. LAN 补丁：启动后才发现需要（候选 MVP）

### 场景

- 和朋友联机；朋友不一定正版 / 在线模式。
- 需要 LAN 补丁（LAN World Plug-n-Play、Lan Server Properties 之类）来改端口、关闭在线验证、UPnP。
- 已经启动游戏、甚至已经进了世界，才发现朋友进不来。
- 重启代价：本项目 dev 包约 **83 秒**；大整合包更久，还要重新进世界、朋友重连。

**这是“热加”最有价值的形态**：需求出现得晚、修复很小、重启代价很高。

### 候选模组（2026-11-14 实测）

| 模组 | 加载器 / 版本 | 下载量 | Tier | 说明 |
|---|---|---|---:|---|
| [Lan Server Properties](https://modrinth.com/mod/lan-server-properties) | NeoForge 1.21.1 | ~106 万 | **T3** | 4 个 mixin；改 `ShareToLanScreen`、`UUIDUtil`、`PlayerList` |
| [LAN World Plug-n-Play](https://modrinth.com/mod/mcwifipnp) | NeoForge 1.21 | ~739 万 | **T3** | 5 个 mixin；改 `PauseScreen`、`GridLayout`、`UUIDUtil`、`MinecraftServer` |
| [EasyLAN](https://modrinth.com/mod/easylan) | NeoForge 1.21.1 | ~73 万 | **T2** | 无 mixin，69 个类；有独立 GUI/线程 |

三个都是**客户端侧、无注册表内容、jar 44–120 KB**——正是“小工具模组”的典型。

### 技术形态：不是 T2，是 T3

它们会往**已经加载**的类里加字段/方法：

- `mcwifipnp` 的 `MixinMinecraftServer` 给 `MinecraftServer` 加 `uPnPInstance` 字段 + getter/setter；`MixinPauseScreen` 给 `PauseScreen` 加 `cfg` 字段。
- `lanserverproperties` 的 `MixinOpenToLanScreen` 给 `ShareToLanScreen` 加 `port` / `portEdit` / `lsp_objects` 等字段，并注入 `init` / 按钮点击 / 端口校验。
- 两者都有 `MixinUUIDUtil`（离线 UUID 修复）和 `PlayerListAccessor`。

所以：

- **不能只靠 `redefineClasses`**：新增字段/方法会被 stock JVM 拒绝。
- 需要 **JBR 的 `-XX:+AllowEnhancedClassRedefinition`**，或 **sidecar / holder / trampoline**。
- `MinecraftServer` 已经在跑；新增字段的现有实例是默认值，`online-mode` 可能需要**重启集成服务器 / 重新发布 LAN** 才生效。
- 结论：热加 LAN 模组 = **T3 + 集成服务器重启**，不是“零操作”。

### 主线：A（热加载 LAN 模组）

> 身份决策见 [ADR-0005](decisions/0005-runtime-not-a-mod.md)：**我们是 runtime，不是 mod**；B 只做 fallback。

- 加载模组类 → 重跑它的 mixin（针对已加载目标）→ 重定义 → 跑入口点 → 重新发布 LAN。
- 优点：通用，同一套引擎可支持语音、手柄、HUD 等。
- 难点：布局变更、`MinecraftServer` 已加载、集成服务器状态。
- 这是主线，也是 LAN 场景的真正价值：用户继续用自己信任的 LAN mod，只是不用重启。

### 降级 fallback：B（内置 LAN Doctor，非主线）

> **只在 A 失败/不可用时使用**；不作为产品卖点。规格见 [features/lan-doctor.md](features/lan-doctor.md)。

不加载第三方模组，直接在我们自己的工具里操作**正在运行的集成服务器**。

1.21.1 已确认的公开 API（`javap` 于 NeoForm mapped jar）：

| API | 用途 |
|---|---|
| `MinecraftServer.usesAuthentication()` / `setUsesAuthentication(boolean)` | 读/改在线验证（online-mode） |
| `MinecraftServer.getPort()` / `setPort(int)` | 读/改 LAN 端口 |
| `MinecraftServer.publishServer(GameType, boolean, int)` | 重新发布 LAN |
| `IntegratedServer.isPublished()` / `getPort()` / `publishServer(...)` | 判断状态、取端口、重发 |

所以 B **不需要 mixin、不需要 JBR、不需要改字节码**，是一个纯 API 功能。

用户流程：

1. 进世界后发现朋友进不来；
2. `/lan doctor`（或暂停菜单按钮）显示：是否已发布、端口、online-mode；
3. 点“关闭在线验证并重新发布”；
4. 工具调 `setUsesAuthentication(false)`，再调 `publishServer(...)`；
5. 显示 `192.168.x.x:端口`，朋友加入。

待验证的细节：

- `setUsesAuthentication(false)` 是否只影响**新登录**；已在线玩家不受影响（预期如此）。
- 重复 `publishServer` 是否会产生多个 LAN pinger；需要先测，必要时先停旧的。
- `GameType` / `allowCommands` 要沿用当前世界设置，不能硬编码。
- 如果端口被占用，要报错并让用户换端口。

优点：小、快、安全；几天到一周可落地；不依赖 JBR。
缺点：只解决 LAN；不能替代通用热加；不提供 UPnP/端口持久化等完整功能。

注意：`online-mode=false` 会关闭 Mojang 验证；只适合可信局域网，需明确提示安全影响。

### 执行顺序（ADR-0005）

1. **A0：T2 热加** — `EasyLAN`（无 mixin）验证 classpath + ModList + 入口点。
2. **A1：T3 热加** — `lanserverproperties`（4 个 mixin，布局变更）验证 re-mix + redefine（JBR 或 sidecar）。
3. **B 只在 A 失败时兜底**：例如目标类布局变更无法安全处理，才提供 offline/republish 作为降级。
4. 不要一上来承诺“所有 LAN 模组都能热加”；先把一个模组、一个版本跑稳。

## 2. 其他候选场景（待验证）

| 场景 | 典型模组 | 预估 Tier | 价值 |
|---|---|---|---|
| 语音聊天 | Simple Voice Chat | T3 | 高：进世界后才发现没装 |
| 手柄 / 输入 | Controllable、MidnightControls | T3 | 中 |
| HUD / 截图 / 缩放 | AppleSkin、Zoomify | T3 | 中 |
| 配方查看 | JEI / REI / EMI | T3/T4 | 中：大模组，注册内容多 |
| 内容模组 | Ex Nihilo、Mekanism | T4 | 最高，但需要软重启 |
| 性能模组 | Sodium / Lithium | T5 | 不做 |

## 3. 好场景的判据

一个场景值得优先做，通常满足：

1. **客户端侧**：不需要服务端同步注册表。
2. **无注册表内容**：不新增方块/物品/维度。
3. **需求出现得晚**：已经进世界、朋友已经在等。
4. **修复很小**：jar 小、mixin 少、目标类集中。
5. **重启代价高**：大整合包、dev 环境、多人等待。

LAN 补丁同时满足 1–5，所以它是目前最好的产品锚点。
