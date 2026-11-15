# ADR-0007：运行时可双端运行，但服务端热加首版只支持 T2，且双端一致性由我们自己校验

- 状态：Accepted
- 日期：2026-11-15
- 决策者：项目所有者（"模组本身也可做成双端，服务端也能热加，玩家自觉双端同时热加"）

## 背景

所有者提出：本 runtime 自己也做成客户端 + 服务端双端，服务端同样能热加模组；
一致性由"玩家自觉在双端同时热加"保证。

调查后有三个事实必须先摆清：

1. **现状是纯客户端**：`@Mod(dist = Dist.CLIENT)`；`mods.toml` 的 neoforge/minecraft 依赖 `side = "CLIENT"`；
   14 个文件直接引用 `net.minecraft.client.*`；`FmlEvents` 只重放客户端语义的 mod-bus 事件。
2. **服务端侧有两类事件无法重放**：`RegisterEvent`（注册表内容）与 `RegisterPayloadHandlersEvent`（网络通道）
   都只发生一次，且发生在连接建立之前。热加的 mod 因此永远不会出现在 FML 的网络协商里。
3. **FML 自己的检查不够用**（`NetworkComponentNegotiator`）：
   - 比的是 `ModdedNetworkComponent(id, version)` 的**版本字符串**，不是内容哈希；
   - 组件来自启动期的 payload 注册 → **热加的 mod 不在协商范围内**；
   - 协商只在连接建立时发生，**连上之后再热加不会被复查**；
   - mod 可用 `displayTest` 主动退出检查（`lanserverproperties` 就是 `IGNORE_ALL_VERSION`）。
   另外 `ModMismatchEvent` 是**存档级**提示，与本问题无关，不要混用。

## 决策

1. **双端化**：runtime 拆成 common（引擎 / 预检 / 监听 / 命令）+ client（UI、toast、资源包、keyMappings）
   + server（控制台命令、无 UI）。`@Mod` 不再是 client-only，`mods.toml` 依赖改 `side = "BOTH"`。
2. **服务端热加首版只支持 T2**（无 mixin、无注册表内容、无网络 payload）。
   T3 在服务端还需要 JBR + `-XX:+AllowEnhancedClassRedefinition`，属后续；T4 按 ADR-0006 走重启。
3. **不依赖"玩家自觉"**：新增 `modreloader` 自有的登录期握手——
   客户端上报已热加集合 `(modId, version, sha256)`，服务端与自身集合比对；
   不一致时按"仅提示"处理（与 side 门禁的宽松策略一致），输出双方差异，而不是静默放行。
   后续可加配置项升级为拒绝连接，首版不做。
4. **side 门禁只警告不拦**（所有者决定）：预检里显示 `side` 判定与证据（`clientSideOnly`、依赖 `side`、
   `Dist.DEDICATED_SERVER` 订阅、payload/registry 使用），是否加载仍由用户决定。
5. **诚实边界**：即使双端都热加，带网络 payload 的 mod 仍**预期不可用**（payload 注册发生在握手前）。
   这一点没有任何实测证据，因此首版不承诺，并在 UI 中把它标为未知/不支持。

## 后果

正面：

- 服务端可以热加"无状态辅助类"模组（日志、命令、简单 tweak），这是真实需求。
- 握手把"玩家自觉"变成可诊断的契约，延续项目"可预测"的定位。
- 双端化顺手把引擎与客户端 UI 解耦，对后续服务端测试（无 GL）也有利。

负面：

- 服务端能力一开始很窄（T2），容易被误解为"服务端也能热加任意模组"——文案必须写清。
- 服务端通常跑普通 JDK，T3 会被拦；要求 JBR 会影响部署体验。
- 若 FML 未来把 hot-add 纳入协商范围，我们这层握手可能变成冗余（保持薄，便于删）。

## 备选（未采用）

- **完全依赖 FML 协商**：覆盖不到热加场景（见背景 3），且会被 `displayTest` 关闭。
- **自动同步双端（服务端推送 jar 给客户端）**：等于自建分发，触碰"不打包第三方 mod"与许可证问题，
  且客户端自动执行服务端下发的代码是安全红线。不做。
- **首版就支持带 payload 的服务端 mod**：语义上不成立（事件无法重放），先不承诺。

## 验证记录（2026-11-15）

- **服务端加载**：`runServer` → `[ModReloader] loaded on dedicated server`；watcher 起在 Server thread；`Done (7.060s)`；无 `NoClassDefFoundError`。
- **服务端热加（负向）**：`runServer -PautoAdd=EasyLAN` → 失败
  `Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER`；
  `event listeners / mod list / module layer` 回滚全部 ok。EasyLAN 的 `mods.toml` 写的是 `side="BOTH"`，
  说明 **side 字段不可信**；服务端可行性必须扫字节码（`net/minecraft/client/**`）判断，且仍需运行期兜底。
- **客户端回归**：双端化后 `runClient -PverifyAddCommand=...` → `AddCmdCheck RESULT: PASS`。
- **未验证**：服务端热加**成功**路径（还没找到真正服务端安全的 T2 样例）；双端握手（尚未实现）。
- **服务端热加（正向）**：2026-11-15 17:05，`experiments/server-fixture/` 的 `servertest`（纯构造型 T2）在 dedicated server 上热加成功：`hot-added [servertest]`、`isLoaded=true`、`keyMappings -1 -> -1`。**边界**：夹具不注册服务器命令/事件——`RegisterCommandsEvent`、`ServerStartingEvent` 在启动期已发射，热加的 mod 收不到；这类服务端 mod 仍未验证。
- **命令重放（2026-11-15 17:12）**：夹具升级为真实形态（注册命令 + tick 监听）后实测：tick 能到、命令收不到（`RegisterCommandsEvent` 早于热加发射）。新增 `server/ServerCommands` 重放该事件，命令随即注册成功（根命令 `93 -> 94`）。**代价**：重放是全局事件，所有 mod 的命令监听器会再次执行；Brigadier 合并节点使实测幂等，但需在文档中说明。payload / 一次性事件 / 注册表仍不支持。
