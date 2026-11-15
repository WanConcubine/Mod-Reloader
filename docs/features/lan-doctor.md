# LAN Doctor 功能规格（B 路线，v0）

> 状态：**Fallback only（非主线）**。
> 身份决策见 [ADR-0005](../decisions/0005-runtime-not-a-mod.md)：我们是 runtime，不是 mod。
> 只在 A 路线（热加载 LAN 模组）失败/不可用时使用；不投入主要精力。
> 目标：NeoForge 1.21.1，客户端。

## 1. 目标

已经进入世界后，朋友因为以下原因进不来时，**不重启游戏**就能修好：

- 集成服务器开着 `online-mode`，朋友不是正版 / 不在线；
- LAN 端口不对或被占用；
- 不知道应该把哪个地址发给朋友。

## 2. 非目标（v0 明确不做）

- UPnP / NAT 穿透（`mcwifipnp` 的核心功能之一）。
- 端口持久化、配置文件、完整设置 GUI。
- 离线 UUID 修复（`UUIDFixer`）——先只做“能进来”，UUID 冲突后续处理。
- 跨公网隧道（e4mc 之类）。
- 替代 `lanserverproperties` / `mcwifipnp` 的全部功能。

## 3. 用户流程

1. 玩家已进入单人世界，朋友进不来。
2. 按快捷键（默认未绑定，建议 `L`）或输入 `/lan`。
3. 面板显示：
   - 集成服务器是否已发布；
   - 当前 LAN 端口；
   - `online-mode` 状态；
   - 本机局域网地址。
4. 点“关闭在线验证并发布”：
   - 首次会弹安全确认；
   - 调 `setUsesAuthentication(false)`；
   - 调 `publishServer(gameType, allowCommands, port)`；
   - 显示 `192.168.x.x:端口`，可一键复制。
5. 朋友加入。

## 4. 功能清单

### v0（必须）

- [ ] 快捷键打开面板（客户端）。
- [ ] `/lan` 命令（如果开了作弊）作为备用入口。
- [ ] 状态展示：`isPublished`、`getPort`、`usesAuthentication`。
- [ ] 操作：`offline` / `online` / `republish [port]`。
- [ ] 局域网地址枚举（IPv4，非 loopback）。
- [ ] 首次 offline 的安全确认。
- [ ] 失败提示：端口占用、未进入世界、服务端未启动、`publishServer` 返回 false。

### v1（后续）

- [ ] 暂停菜单按钮（不用记快捷键）。
- [ ] 端口输入框 + 持久化。
- [ ] 发布状态变化时自动刷新。
- [ ] 复制地址到剪贴板。
- [ ] 中文/英文文本。

### v2（可选）

- [ ] UPnP。
- [ ] UUID 修复/冲突提示。
- [ ] 与 A 路线联动：如果用户坚持要原 mod 的完整功能，热加载它。

## 5. 关键 API（1.21.1 已确认）

```java
IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
boolean published = server.isPublished();
int port = server.getPort();
boolean online = server.usesAuthentication();

server.setUsesAuthentication(false);
boolean ok = server.publishServer(gameType, allowCommands, port);
```

- `gameType` / `allowCommands` 从 `server.getWorldData()` 读取，不能硬编码。
- `getPort()` 在未发布时可能是默认值；发布前要确认。
- 局域网地址：`NetworkInterface.getNetworkInterfaces()` 过滤 IPv4、非 loopback、`isSiteLocalAddress()`。

## 6. 边界与错误

| 情况 | 行为 |
|---|---|
| 不在单人世界 | 提示“先进入一个单人世界” |
| 集成服务器为 null | 同上 |
| 已经发布 | 先显示当前端口；republish 时先尝试复用/停止旧 pinger |
| 端口被占用 | `publishServer` 返回 false；提示换端口 |
| 作弊未开 | `/lan` 不可用；快捷键仍可用 |
| 朋友不在同一局域网 | 提示需要 e4mc/端口转发，B 不解决 |
| 离线模式 | 首次确认；说明会关闭 Mojang 验证 |

## 7. 安全

- `online-mode=false` 会关闭 Mojang 验证：局域网内可伪造用户名、UUID 可能冲突。
- 只在**可信局域网**使用；工具必须显式提示，不能默认静默开启。
- 不提供“绕过正版验证”的自动化；只是把服务端已有的离线模式开关暴露出来。
- 不修改存档；不写 `server.properties`（v0 只改运行时状态）。

## 8. 验收标准（v0）

1. 进入单人世界，按快捷键能打开面板。
2. 面板正确显示 published/port/online。
3. 点 offline 后，`usesAuthentication()` 变 false。
4. republish 后，另一台机器能用显示的地址加入（离线账号）。
5. 端口占用时，有明确错误，不崩游戏。
6. 不进入世界时，操作被拒绝，不崩游戏。
7. 不改存档；退出世界后状态不影响下次启动（v0 不持久化）。

## 9. 开放问题

1. `setUsesAuthentication(false)` 对**新登录**是否立即生效？需要两台机器实测。
2. 重复 `publishServer` 是否会产生多个 `LanServerPinger`？需要看是否需要先停旧的。
3. `publishServer` 的 `allowCommands` 与当前世界作弊设置如何保持一致？
4. 快捷键在多人服务器上是否应该隐藏/禁用？
5. v0 是否要支持“已发布但想换端口”而不重启集成服务器？

## 10. 与 A 路线的关系

- B 是**垂直功能**：只解决 LAN，验证“操作运行中的集成服务器”。
- A 是**通用引擎**：热加载任意客户端工具模组。
- 长期：B 的原生快路径 + A 的热加载兜底；不要为了“完全取代某个 mod”而无限重写它的功能。
