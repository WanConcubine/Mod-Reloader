# server-fixture

一个**真实形态**的服务端测试夹具：像真的服务端 mod 那样，在构造器里
注册一个命令、并监听会重复触发的 tick 事件。

它存在的目的不是证明「能加载」，而是回答「**服务端热加到底有没有用**」：

| 能力 | 热加后是否生效 | 证据 |
|---|---|---|
| `@Mod` 构造 / ModList 注入 | ✅ | `@Mod constructed; modListIsLoaded=true` |
| 之后才触发的事件（`ServerTickEvent`） | ✅ | `ServerTickEvent received 100/200 times` |
| 命令（`RegisterCommandsEvent`） | ✅ **靠重放** | `RegisterCommandsEvent fired; /servertest registered`；根命令 `93 -> 94` |
| 网络 payload（`RegisterPayloadHandlersEvent`） | ❌ | 事件在握手前已发射，未验证能补 |
| 注册表内容 | ❌ | T4，需要重启（ADR-0006） |

命令能生效是因为运行时会**重放** `RegisterCommandsEvent`（见
`dev/modreloader/server/ServerCommands`）。代价：该事件是全局总线事件，
重放会让**所有已加载 mod** 的命令注册监听器再跑一遍；Brigadier 会合并
重复节点，所以实测根命令只增加 1，但监听器有副作用的 mod 会执行两次。

## 两个夹具

| 脚本 | 产物 | 用途 |
|---|---|---|
| `./build.sh` | `servertest-1.0.0.jar` | 注册 `/servertest` + 监听 `ServerTickEvent`：应**成功** |
| `./build-payload.sh` | `payloadfix-1.0.0.jar` | 注册自定义 payload：应被**拒绝**（`NETWORK_PAYLOAD`） |

## 构建与使用

```bash
# 先让 Gradle 生成 classpath（任意一次 runServer 即可）
./gradlew runServer

cd experiments/server-fixture && ./build.sh          # -> /tmp/servertest/servertest-1.0.0.jar
cd ../.. && ./gradlew runServer -PautoAdd=/tmp/servertest/servertest-1.0.0.jar
```

期望日志：

```text
[ModReloader] hot-adding [servertest] from servertest-1.0.0.jar (mixin configs: [])
[ServerTest] @Mod constructed; modListIsLoaded=true
[ServerTest] RegisterCommandsEvent fired; /servertest registered
[ModReloader] replayed RegisterCommandsEvent; root commands 93 -> 94
[ModReloader] server side: skipping resource pack injection for [servertest]
[ModReloader] verify servertest: isLoaded=true, container=true, keyMappings -1 -> -1
[ModReloader] hot-added [servertest] (modules [servertest])
[ServerTest] ServerTickEvent received 100 times (future events reach this mod)
```

`keyMappings -1 -> -1` 是服务端的关键证据：客户端专用的按键统计被跳过，
引擎没有在专用服上碰 `Minecraft`。

## payload 反例（应失败）

```bash
cd experiments/server-fixture && ./build-payload.sh   # -> /tmp/payloadfix/payloadfix-1.0.0.jar
cd ../.. && ./gradlew runServer -PautoAdd=/tmp/payloadfix/payloadfix-1.0.0.jar
```

期望日志（热加被拒并回滚）：

```text
[PayloadFix] @Mod constructed
[ModReloader] payloadfix registers network payloads, which NeoForge forbids after startup:
  ... java.lang.UnsupportedOperationException: Cannot register payload payloadfix:ping after registration phase.
[ModReloader] rollback: mod list ok
[ModReloader] rollback: module layer ok
```

## 反例对照

`EasyLAN` 声明 `side="BOTH"`，但构造器里注册 `ScreenEvent`，在专用服上会抛
`Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER`，
热加失败但回滚干净。见 [docs/status.md](../../docs/status.md) 已知问题 13。
