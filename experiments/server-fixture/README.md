# server-fixture

一个**最小、服务端安全**的测试夹具，用来验证「服务端热加成功路径」。

它只有一个 `@Mod` 类，构造时打一行日志；没有客户端类、没有注册表内容、
没有 mixin、没有网络 payload。因此它是 T2，而且两端都能加载——正好是
"服务端热加到底成不成立"需要的那个对照物。

## 构建与使用

```bash
# 先让 Gradle 生成服务端 classpath（任意一次 runServer 即可）
./gradlew runServer

cd experiments/server-fixture && ./build.sh          # -> /tmp/servertest/servertest-1.0.0.jar
cd ../.. && ./gradlew runServer -PautoAdd=/tmp/servertest/servertest-1.0.0.jar
```

期望日志：

```text
[ModReloader] hot-adding [servertest] from servertest-1.0.0.jar (mixin configs: [])
[ServerTest] @Mod constructed; modListIsLoaded=true; side=...
[ModReloader] server side: skipping resource pack injection for [servertest]
[ModReloader] verify servertest: isLoaded=true, container=true, keyMappings -1 -> -1
[ModReloader] hot-added [servertest] (modules [servertest])
```

`keyMappings -1 -> -1` 是服务端的关键证据：客户端专用的按键统计被跳过，
说明引擎没有在专用服上碰 `Minecraft`。

## 反例对照

`EasyLAN` 声明 `side="BOTH"`，但构造器里注册 `ScreenEvent`，在专用服上会抛
`Attempted to load class net/minecraft/client/gui/screens/Screen for invalid dist DEDICATED_SERVER`，
热加失败但回滚干净。见 [docs/status.md](../../docs/status.md) 已知问题 13。
