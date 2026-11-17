# mixin-fixture —— 免 JBR 的 mixin 路径夹具

一个 `@Mod` + 一个 `@Inject` mixin，目标是**本 jar 自己**的 `mixinfixture.Probe`：`Probe.value()` 初始返回 1，
mixin 在 `HEAD` 处 `setReturnValue(2)`。因为 `Probe` 在热加时**还没加载**，mixin 在类 define 时织入，
不需要 JBR 的 enhanced redefinition；`Probe.value()` 是否变成 2 就是「mixin 真的生效了」的断言。

用来验证 2026-11-16 收窄后的 JBR 门禁（`docs/features/a1-mixin-hotadd.md` §10.4）：
以前「有 mixin 就要 JBR」会在入口整包拒绝，现在只有「已加载类的布局变更」才需要 JBR。

## 构建

```bash
./build.sh /tmp/mixinfixture
# 需要先跑过一次 ./gradlew build，生成 build/moddev/{client,server}LegacyClasspath.txt
```

产物：`/tmp/mixinfixture/mixinfixture-1.0.0.jar`

## 运行

```bash
./gradlew runClient -PnoJbr=true \
  -PverifyMixinFixture=/tmp/mixinfixture/mixinfixture-1.0.0.jar
```

期望日志：

```
[MixinFixtureCheck] hot-add result: ok=true ... (mixins [mixinfixture.mixins.json], redefined [], eager [mixinfixture.Probe], deferred [])
[MixinFixtureCheck] Probe.value() = 2 (JBR=true, enhancedRedefinition=false): expected 2
[MixinFixtureCheck] RESULT: PASS
```

`-PnoJbr=true` 只是不加 `-XX:+AllowEnhancedClassRedefinition`；`enhancedRedefinition=false` 与 stock JVM 的
redefine 语义一致。

## 边界

- 只断言「未加载目标」这一条路径；**已加载类 + 布局变更仍然要 JBR**（Mouse Tweaks / ZeroCore2 那类 accessor）。
- 夹具故意没有 `pack.mcmeta`/`assets`，所以不触发资源包路径；mod 侧最小。
