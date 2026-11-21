# mixin-fixture —— 免 JBR 的 mixin 路径夹具

一个 `@Mod` + 一个 `@Inject` mixin，目标是**本 jar 自己**的 `mixinfixture.Probe`：`Probe.value()` 初始返回 1，
mixin 在 `HEAD` 处 `setReturnValue(2)`。因为 `Probe` 在热加时**还没加载**，mixin 在类 define 时织入，
不需要 JBR 的 enhanced redefinition；`Probe.value()` 是否变成 2 就是「mixin 真的生效了」的断言。

另有一个静态 `@Invoker` 接口 `mixinfixture.mixin.accessor.AccessTargetInvoker`（形状同 ponder 的
`RenderTypeAccessor`：桩体抛 `AssertionError("Mixin application failed!")`），目标是 `AccessTarget` 的私有静态
`hidden()`。harness 在热加**之后**才加载这个接口并调用它：Mixin 的 accessor coprocessor 只在该接口
define 时改写方法体，返回值必须是 `reached-target`。这条断言覆盖问题 65（热加时没把 coprocessor 挂到
config 上 → 接口保留桩体 → Create 进世界崩溃）。

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
[MixinFixtureCheck] hot-add result: ok=true ... (mixins [mixinfixture.mixins.json], redefined [], eager [mixinfixture.Probe, mixinfixture.AccessTarget], deferred [])
[MixinFixtureCheck] Probe.value() = 2 (JBR=true, enhancedRedefinition=false): expected 2
[MixinFixtureCheck] AccessTargetInvoker.mixinfixture$hidden() = reached-target: expected reached-target
[MixinFixtureCheck] RESULT: PASS
```

`-PnoJbr=true` 只是不加 `-XX:+AllowEnhancedClassRedefinition`；`enhancedRedefinition=false` 与 stock JVM 的
redefine 语义一致。

## 边界

- 只断言「未加载目标」这一条路径；**已加载类 + 布局变更仍然要 JBR**（Mouse Tweaks / ZeroCore2 那类 accessor）。
- 夹具故意没有 `pack.mcmeta`/`assets`，所以不触发资源包路径；mod 侧最小。
