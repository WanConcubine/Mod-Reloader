# Client registration fixture

一个纯客户端夹具：注册 **一个 HUD 层**（`RegisterGuiLayersEvent`）、
**一个物品装饰**（`RegisterItemDecorationsEvent`，加在 `minecraft:diamond` 上）和
**一个配方书分类**（`RegisterRecipeBookCategoriesEvent`，给 `RecipeBookType.CRAFTING` 加一条）。
三者都是启动期只 fire 一次的 mod-bus 事件；热加模组的监听器错过它们时，
失败形态是**静默**的（HUD 少一块、物品少个角标、配方书分类缺失），没有任何报错。

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

期望日志：

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
