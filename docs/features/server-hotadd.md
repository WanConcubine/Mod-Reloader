# 服务端热加（嫔级 / 命令重放 / payload 适配）

> 范围：runtime 在 **dedicated server** 上加载、发现、热加模组，以及两处必须"重放一次性事件"的适配。
> 不覆盖：贵妃级注册表内容（改由 [t4-registry-hotadd.md](t4-registry-hotadd.md) 覆盖，含 §9 的多人 id 同步）、一次性启动事件（`ServerStartedEvent` 等）。
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
| 嫔级夹具热加 | `@Mod constructed`；`isLoaded=true`；`keyMappings -1 -> -1`（客户端专用路径被跳过） | 2026-11-15 17:05：`runServer -PautoAdd=/tmp/servertest2/servertest-2.0.0.jar` |
| 命令重放 | `RegisterCommandsEvent fired; /servertest registered`；`replayed ...; root commands 93 -> 94`；`ServerTickEvent received 100/200/...`；无重复注册异常 | 2026-11-15 17:12：同上（源码 [experiments/server-fixture](../../experiments/server-fixture/README.md)） |
| payload 负向 | 不适配时被拒：`Cannot register payload ... after registration phase`；`NETWORK_PAYLOAD` 回滚干净；命令夹具不受影响 | 2026-11-15 17:19–17:20：`build-payload.sh` 夹具 |
| payload E1 | 单 JVM、建连接前热加：`adapted payloads for payloadfix: [payloadfix:ping]` → 玩家加入后客户端收到 | 2026-11-15 17:39：`runClient -PverifyPayload=...` |
| payload E2 | 进世界后热加：`patched payload setup of an existing connection`（两条连接各一次）→ 3 秒后客户端收到 | 2026-11-15 18:12：`-PverifyPayload=... -PverifyPayloadAfterJoin=true` |
| payload E3 | 两个独立 JVM：服务端 autoAdd + 客户端标题屏热加 → 连接 `127.0.0.1:25565` → `PING RECEIVED on side client` | 2026-11-15 20:20：`runServer -PautoAdd=payloadfix` + `runClient -PverifyPayloadRemote=payloadfix` |
| 回归 | 双端化后客户端 `AddCmdCheck RESULT: PASS`；capabilities 服务端 `resource_packs=n/a`，其余 ok；servertest 仍能热加 | 2026-11-15 16:25 / 21:08 |
| 控制台短名 add | 把 jar 放进 `mods/` → `/modreloader scan`（行尾 `→ /modreloader add t4fixture-1.0.0.jar`）→ `/modreloader add t4fixture-1.0.0.jar` 返回 1、`Loaded: hot-added [t4fixture]`、`isLoaded=true`；未知名字返回 0 并打印所有查找位置；TAB 补全列出 `[MixinRuntime-…, t4fixture-1.0.0.jar]` | 2026-11-16 11:17：`runServer -PserverAddCommand=<t4fixture>`（`ServerAddCmdCheck`） |

## 4. 限制与下一步

- **没有握手**：两端各装各的 jar，靠人工保证一致；ADR-0007 第 3 条的登录期握手尚未实现。
- **控制台便捷性**：`add` 只做「名字 → 路径」的解析与补全，**不会**自动挑选候选、也不会自动加载；`watcher.json` 里的 `ignored`/`failed` 指纹仍由人维护（放回同名同大小同 mtime 的文件不会重新提示）。
- **一次性启动事件**（`ServerStartedEvent` 等）不重放。
- **服务端妃级**：需要 JBR + `-XX:+AllowEnhancedClassRedefinition`；2026-11-16 起 `runServer` 自动用 JBR，
  实测 ZeroCore2 在专用服上 `redefined net.minecraft.world.inventory.Slot with mixins` 并 `hot-added [zerocore]`
  （此前同一操作是 `ROLLBACK_PARTIAL`）。只验证了这一个模组；「服务端 + 客户端同时热加妃级 mod」未测。
- **注册表内容（贵妃级）**：本页不覆盖，见 [t4-registry-hotadd.md](t4-registry-hotadd.md)（服务端闭环 + §9 的多人 id 同步）。
- **重放的全局副作用**：命令监听器会执行两次（Brigadier 幂等，但副作用不可控）——这是已知问题 14/15。
- **side 门禁只警告不拦**（ADR-0007 第 4 条），预检的 side 判定仍待补。

## 5. `/modreloader why <jar>`：只读诊断（2026-11-16）

热加被拒时，「为什么」原先散在四处：`ModInspector` 的 tier/内容形状/依赖、`AccessTransformerRules` 的 AT 组成、
注册审计（启动期 mod-bus 事件）、以及 side / JBR / MixinRuntime 这些运行期条件。命令行只有一个
`/modreloader add`（拒绝后失败屏里是一句话），控制台更是连界面都没有。

`why` 把这些按**引擎 VALIDATE 的顺序**收成一份只读报告，最后一行是结论：

```
[ServerWhy] why Jade-1.21.1-NeoForge-15.10.6.jar (dedicated server)
[ServerWhy]   ids: jade 15.10.6+neoforge
[ServerWhy]   tier: guifei, mixins, registry content
[ServerWhy]   content: registry=true, mixins=true, native=false, client-only=false, already-loaded=false
[ServerWhy]   access transformer: 57 rule(s) over 39 target class(es) (39 runtime); e.g. [...]
[ServerWhy]   dependencies: neoforge (required, present)
[ServerWhy]   registration audit: 4 mod-bus event(s); covered [...]; other-side [...]; unresolved [...]
[ServerWhy]   note: mixins: [jade.mixins.json]
[ServerWhy]   warning: references client classes: [...] - if they are on the startup path this add fails on a dedicated server (the runtime rolls back cleanly)
[ServerWhy] verdict: refused (access transformers apply at class load; restart with the jar in mods/) - 57 rule(s) over 39 target class(es) ...
```

三种形态实测（专用服控制台路径，`-PserverWhy`）：

| jar | 结论 |
|---|---|
| Jade 15.10.6 | `refused (access transformers …)` + 57 规则 / 39 目标 |
| t4fixture 1.0.0 | `experimental add-only (registry content; … cannot be undone)` |
| EasyLAN 1.6a | `hot-add (no mixins, no registry content)` + **warning: references client classes** |

关于最后一条：EasyLAN 在专用服上实际会在构造时崩（`@Mod` 构造器注册 `ScreenEvent` 监听器，见 status 已知问题 13），
但**静态扫描无法区分"客户端类只在 client-only 代码里"（Jade 就是这样）与"启动路径上就用"**。所以 `why` 只给 warning、
不据此拒绝；真正的兜底是 `HotAddEngine` 的 dist 门禁 + 回滚。`ModInspector` 现在会把扫描到的客户端类写进 notes
（`references client classes: …`），UI/报告都能看到，但 `clientSideOnly` 仍以元数据为准。

复现：

```bash
./gradlew runServer -PserverWhy="/abs/jade.jar|access transformer,/tmp/t4fixture/t4fixture-1.0.0.jar|experimental add-only,/tmp/lanmods/EasyLAN-neoforge-1.21.1-v1.6a.jar|references client classes"
# [ServerWhy] RESULT: PASS（每条断言的 token 可出现在报告任意一行；另外会跑一次控制台命令并断言返回 1）
```

边界：`why` 只读（不注入、不改状态）；报告是英文技术行（与 `probe` 一致），未走 i18n；判定顺序与引擎一致，
但**结论依据的是静态信息**，不等于运行期一定会成功/失败。
