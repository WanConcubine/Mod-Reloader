# 服务端热加（T2 / 命令重放 / payload 适配）

> 范围：runtime 在 **dedicated server** 上加载、发现、热加模组，以及两处必须"重放一次性事件"的适配。
> 不覆盖：T3 在服务端（需要 JBR，未验证）、注册表内容（T4）、一次性启动事件（`ServerStartedEvent` 等）。
> 状态：[status.md](../status.md) 的「服务端」相关表尺行。

## 1. 为什么不是"把客户端代码搬过去"

`ModReloader` 原本是 `dist = Dist.CLIENT`、`mods.toml` 依赖 `side = "CLIENT"`。双端化做了三件事：

1. 拆出 `Platform`（不引用客户端类型）与 `PlatformHooks`（引擎唯一的端相关出口）；`@Mod` 去掉 `dist` 限制，依赖改 `side = "BOTH"`。
2. `client/ClientBootstrap` 负责 UI/toast/资源包/tick；`server/ServerBootstrap` 负责控制台命令和启动触发。
3. `FmlEvents`、`HotAddEngine`、`ModsFolderWatcher` 加单侧守卫，资源包注入只在客户端执行（`resource_packs=n/a`）。

副作用：`mods.toml` 的 `side` 字段不可信（见 [status.md](../status.md) 已知问题 13）——EasyLAN 声明 `BOTH`，
但它在 `@Mod` 构造器里注册 `ScreenEvent`，在专用服上会被 NeoForge 主动拒绝：

```text
Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER
```

这类失败会走正常回滚（module / ModList / event listeners），所以不会污染进程。

## 2. 两个"一次性事件"问题

服务端在启动时触发一次 `RegisterCommandsEvent` 和一次 `RegisterPayloadHandlersEvent`，之后关闭注册。
热加的模组错过了这两次，于是命令不存在、payload 不能注册。两条路都靠**反射把状态翻回去 + 重放事件**。

### 2.1 命令：`ServerCommands`

- 启动时捕获 dispatcher / selection / build context（`RegisterCommandsEvent` 监听器）。
- 热加后 `NeoForge.EVENT_BUS.post(new RegisterCommandsEvent(...))`。
- **副作用**：这是全局总线事件，**所有**已加载模组的监听器都会再跑一遍；Brigadier 合并节点所以实测幂等，
  但监听器带副作用的模组会执行两次。
- **客户端也要装**：单人游戏的集成服务器同样触发该事件。2026-11-15 之前客户端没调用 `ServerCommands.install()`，
  所以单人游戏里热加模组的命令从未注册——这是双端化时顺带修掉的真实缺口（标题屏无服务器时安静跳过）。

### 2.2 payload：`NetworkAdaptation` + `ActiveConnections`

NeoForge 在注册阶段结束后把 `NetworkRegistry.setup` 置 true，之后任何 `register` 都抛
`Cannot register payload ... after registration phase.`。适配做法：

1. 反射把 `NetworkRegistry.setup` 置回 false；
2. 只对新容器重放 `RegisterPayloadHandlersEvent`；
3. 置回 true，diff `PAYLOAD_REGISTRATIONS` 得到新增 payload id；
4. `ActiveConnections`（服务端玩家连接 + 客户端到服务端的连接）把每个新 payload 的
   `NetworkChannel(id, version)` 补进活动连接的 `PayloadSetup`，让 `checkPacket` 放行。

边界：两端必须都装同一份 runtime + 同一个 jar（没有握手）；依赖 `@ApiStatus.Internal`，锁 1.21.1。

## 3. 证据（可复现）

| 场景 | 结果 | 命令 / 来源 |
|---|---|---|
| 专用服加载 | `loaded on dedicated server`；watcher 起在 Server thread；`Done (7.060s)`；无 `NoClassDefFoundError` | 2026-11-15 16:24：`./gradlew runServer`（`run/eula.txt` 本地接受） |
| dist 门禁 + 回滚 | EasyLAN 被 `invalid dist DEDICATED_SERVER` 拒绝；event listeners / mod list / module layer 回滚 ok | 2026-11-15 16:24：`runServer -PautoAdd=/tmp/lanmods/EasyLAN-...jar` |
| T2 夹具热加 | `@Mod constructed`；`isLoaded=true`；`keyMappings -1 -> -1`（客户端专用路径被跳过） | 2026-11-15 17:05：`runServer -PautoAdd=/tmp/servertest2/servertest-2.0.0.jar` |
| 命令重放 | `RegisterCommandsEvent fired; /servertest registered`；`replayed ...; root commands 93 -> 94`；`ServerTickEvent received 100/200/...`；无重复注册异常 | 2026-11-15 17:12：同上（源码 [experiments/server-fixture](../../experiments/server-fixture/README.md)） |
| payload 负向 | 不适配时被拒：`Cannot register payload ... after registration phase`；`NETWORK_PAYLOAD` 回滚干净；命令夹具不受影响 | 2026-11-15 17:19–17:20：`build-payload.sh` 夹具 |
| payload E1 | 单 JVM、建连接前热加：`adapted payloads for payloadfix: [payloadfix:ping]` → 玩家加入后客户端收到 | 2026-11-15 17:39：`runClient -PverifyPayload=...` |
| payload E2 | 进世界后热加：`patched payload setup of an existing connection`（两条连接各一次）→ 3 秒后客户端收到 | 2026-11-15 18:12：`-PverifyPayload=... -PverifyPayloadAfterJoin=true` |
| payload E3 | 两个独立 JVM：服务端 autoAdd + 客户端标题屏热加 → 连接 `127.0.0.1:25565` → `PING RECEIVED on side client` | 2026-11-15 20:20：`runServer -PautoAdd=payloadfix` + `runClient -PverifyPayloadRemote=payloadfix` |
| 回归 | 双端化后客户端 `AddCmdCheck RESULT: PASS`；capabilities 服务端 `resource_packs=n/a`，其余 ok；servertest 仍能热加 | 2026-11-15 16:25 / 21:08 |

## 4. 限制与下一步

- **没有握手**：两端各装各的 jar，靠人工保证一致；ADR-0007 第 3 条的登录期握手尚未实现。
- **一次性启动事件**（`ServerStartedEvent` 等）不重放；注册表内容（T4）不支持。
- **服务端 T3**：需要 JBR + `-XX:+AllowEnhancedClassRedefinition`，未在专用服验证。
- **重放的全局副作用**：命令监听器会执行两次（Brigadier 幂等，但副作用不可控）——这是已知问题 14/15。
- **side 门禁只警告不拦**（ADR-0007 第 4 条），预检的 side 判定仍待补。
