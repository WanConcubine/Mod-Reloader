# Client registration fixture

一个纯客户端夹具：注册 **一个 HUD 层**（`RegisterGuiLayersEvent`）、
**一个物品装饰**（`RegisterItemDecorationsEvent`，加在 `minecraft:diamond` 上）、
**一个配方书分类**（`RegisterRecipeBookCategoriesEvent`，给 `RecipeBookType.CRAFTING` 加一条）和
**一个 shader**（`RegisterShadersEvent`，`clientfixture:marker`，JSON 在夹具自己的
`assets/clientfixture/shaders/core/` 里）。
前三个都是启动期只 fire 一次的 mod-bus 事件；热加模组的监听器错过它们时，
失败形态是**静默**的（HUD 少一块、物品少个角标、配方书分类缺失），没有任何报错。
shader 不同：热加资源重载本身会跑 `GameRenderer.reloadShaders` 并给 ModList 里所有容器
（含刚热加的那个）fire 事件，所以它验的是“包注入 + 重载 + 事件”这条完整链路；
`client/ShaderReplay` 只在没有触发资源重载时兜底。

用它来验证运行时的客户端注册重放，而不是靠"看起来没崩"：

```bash
# 构建（需要先跑过 ./gradlew build，生成 build/moddev/*LegacyClasspath.txt）
./build.sh                      # 输出 /tmp/clientfixture/clientfixture-1.0.0.jar

# 进世界后热加，断言 HUD 层 + 物品装饰都出现且渲染不崩
cd ../..
./gradlew runClient \
  -PverifyAddCommand=/tmp/clientfixture/clientfixture-1.0.0.jar \
  -PverifyAddCommandModId=clientfixture \
  -PverifyGuiLayers=true \
  -PverifyItemDecorators=true \
  -PverifyItemDecoratorItem=minecraft:diamond
```

配方书分类不需要额外开关：热加夹具时看日志即可（2026-11-18 实测）：

```text
[ClientFixture] registered recipe book categories for CRAFTING
[ModReloader] recipe book categories replayed: 1 new, 4 aggregate / 1 type / 0 lookup
```

shader 用兼容性 harness（不需要进世界；`-PverifyShaderCount` 断言 `GameRenderer` 的 shader map 增量）：

```bash
./gradlew runClient \
  -PverifyCompat=/tmp/clientfixture/clientfixture-1.0.0.jar \
  -PverifyShaderCount=1 -PverifyCompatSettle=240 -PnoFocus=true
```

期望（2026-11-18 实测）：

```text
[ClientFixture] registered shader clientfixture:marker        # 只出现一次
[ModReloader] shader replay skipped: the resource reload already ran RegisterShadersEvent for the hot-added mod
[CompatCheck] shaders RESULT: PASS (56 -> 57, expected +1)
[CompatCheck] DONE 1/1
```

> 2026-11-18 之前 `ShaderReplay` 会在 reload 之后再补一次，同名的第二个 `ShaderInstance`
> 覆盖活实例并泄漏第一个。现在“有热加资源重载就跳过 replay”，日志里会说明。

期望日志（HUD/装饰/配方书）：

```text
[ClientFixture] constructed; listeners attached
[ModReloader] GUI layers replayed for clientfixture: 1 new (25 -> 26 total); new layers: [clientfixture:hud_marker]
[ModReloader] item decorations replayed for clientfixture: 1 new (0 -> 1 decorated item(s))
[AddCmdCheck] GUI layers RESULT: PASS (replayed, order preserved, 60 ticks rendered with the new layers)
[AddCmdCheck] item decorations: minecraft:diamond 0 -> 1; decorated items: [minecraft:diamond]
[AddCmdCheck] item decorations RESULT: PASS
```

为什么需要它：众星捧月（dogfood）既没有 HUD 层也没有物品装饰，所以这两条重放
以前没有任何真实 subject 可断言——改坏了所有 harness 也照样 PASS。
