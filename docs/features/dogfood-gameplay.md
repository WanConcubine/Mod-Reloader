# 众星捧月玩法验收（热加后）

> 状态：**自动化 5 项 PASS**（2026-11-16）；手动项（桶 / 坩埚 / 堆肥 / 网耐久）待所有者。
> 目的：把「能注册 + 数据加载」升级成「能玩」——驱动真实游戏路径，而不是只看计数。
> 关联：[t4-registry-hotadd.md](t4-registry-hotadd.md)、[t4-datapack-hotadd.md](t4-datapack-hotadd.md)、
> [ADR-0009](../decisions/0009-datapack-hotadd.md)。

## 1. 自动化检查（`-PverifyGameplay`）

世界内热加 `exnihilosatellitium-1.21.1-1.5.0.jar` 后，在**服务器线程**驱动真实交互
（`dev/GameplayCheck`，属于独立 `src/dev` 源集）：

| # | 检查 | 结果（2026-11-16 08:47） |
|---|---|---|
| 1 | mod 自己的诊断命令 `/exnihilo tables` | `sieve 7 inputs / 119 drops; hammer 55; crook 10; barrel 16; compost 67; crucible 39 melt / 9 heat` |
| 2 | 铁锤砸圆石（走 `BlockDropsEvent` 替换） | 掉 `minecraft:gravel x1` |
| 3 | 筛子：放筛子 → 装铁网 → 放泥土（只接受已注册输入）→ 连点 240 tick | 掉 `cobblestone_pebble x6`、`andesite x5`、`granite x5`、`diorite x6`、`deepslate x3`、`grass_seeds x1` |
| 4 | 真实 3×3 合成：铁锭×2 + 木棍×2 | 命中 `exnihilosatellitium:iron_hammer` |
| 5 | 机器 GUI 的 screen factory（`MenuScreens`） | 2 个 menu type 全部有 screen（`missing=[]`） |
| 6 | 客户端注册：BE 渲染器 / 物品染色 / 流体扩展 | `beRenderers 6/6 (missing=[])`、瓷桶染色已注册、`fluidExtensions=3` |
| 7 | 物品图标模型 | `181/181 resolved, missing=[]` |
| 8 | 创造物品栏渲染 40 tick（`-PverifyCreative`） | 不崩，`defaults=4`，PASS |

```bash
./gradlew runClient \
  -PverifyAddCommand=/Users/kaifengcrt/Projects/Ex-Nihilo-Satellitium/build/libs/exnihilosatellitium-1.21.1-1.5.0.jar \
  -PverifyAddCommandModId=exnihilosatellitium \
  -PverifyGameplay=true -PverifyCreative=true
```

加 `-PverifyGameplayKeepOpen=true` 会在检查完把客户端留在世界里（创造物品栏已修好），方便手动玩。

## 2. 验收抓到的真问题：创造物品栏越界崩溃（已修）

第一次手动验收（热加后切创造模式、打开创造物品栏）客户端直接崩：

```text
java.lang.ArrayIndexOutOfBoundsException: Index 7 out of bounds for length 7
  at CreativeModeInventoryScreen.renderTabButton(CreativeModeInventoryScreen.java:850)
```

根因不在 NeoForge，在**我们自己的 `afterRegistryContent()`**：

1. `CreativeModeTabRegistry.sortTabs()` 每次都把 HOTBAR / SEARCH / OP_BLOCKS / INVENTORY 四个默认页签
   `add` 进 `DEFAULT_TABS`，**从不清空**。正常启动调用一次 → 4 个；热加为了让新页签进入分页再调一次 → **8 个重复项**。
2. 创造界面把「不在当前页的默认页签」用 `(index % (DEFAULT_TABS.size()/2)) + 5` 映射到列；
   8 个时列是 5 / 6 / **7** / **8**。
3. `renderTabButton` 只有 7 张页签贴图，而 NeoForge 的补丁把上界写成
   `Mth.clamp(i, 0, aresourcelocation.length)`（应为 `length - 1`）→ 列 7 直接越界。

修法：调 `sortTabs()` **之前清空 `DEFAULT_TABS`**（`UnsafeAccess.getStatic` + `clear()`），让它幂等；
失败只 warn（退化为旧行为，创造界面可能再崩）。回归检查 `-PverifyCreative` 断言 `defaults == 4`，
并真的把创造物品栏渲染 40 tick（崩了就当次运行失败）。

```text
# 修前（开创造物品栏必崩）
creative tabs: all=15 defaults=8 sorted=11

# 修后
creative tabs: all=15 defaults=4 sorted=11
creative inventory rendered 40 ticks without crashing
creative RESULT: PASS
```

### 2.2 机器 GUI 打不开（已修）

手动验收时打开 `exnihilosatellitium:fuel_machine`，日志只有一行警告、界面不出来：

```text
[Render thread/WARN] [minecraft/MenuScreens]: Failed to create screen for menu type: exnihilosatellitium:fuel_machine
```

根因在我们的客户端生命周期重放：`MenuScreens.init()`（`@ApiStatus.Internal`）只在启动时 fire 一次
`RegisterMenuScreensEvent`，把 screen 工厂写进 `MenuScreens.SCREENS`；热加 mod 的
`@EventBusSubscriber` 虽然注册了，但那个事件早就 fire 过了，所以它的 2 个 GUI 从来没有工厂。

修法：`ClientPlatformHooks.acceptClientLifecycle()` 里重放 `RegisterMenuScreensEvent`。注意两个坑：

1. `MenuScreens.SCREENS` 的 `register` 在 key 已存在时**抛异常**，而事件是全局的——直接用真 map 会让
   已加载 mod 的监听器再注册一遍并抛 `Duplicate attempt to register screen`；
2. 所以先往一个**临时 map** 重放（其他 mod 的重复写入只落在临时 map 里），再把「真 map 里没有」的条目合并进去。

```text
[ModReloader] menu screens replayed: 2 new, 27 total
[GameplayCheck] menu screens: 2 type(s) in exnihilosatellitium, missing=[]
```

### 2.3 桶/坩埚内部内容不渲染、瓷桶颜色不对（已修）

同一个「启动期一次性事件」问题还有三处，且比 GUI 更隐蔽：**方块本体在，但内部内容不画**——
桶里的流体、筛网上的物品、坩埚里的熔融物都空着；瓷桶的流体颜色也不对。

根因链：

1. `EntityRenderersEvent.RegisterRenderers` / `RegisterColorHandlersEvent` /
   `RegisterClientExtensionsEvent` 都只在启动时 fire 一次；
2. 渲染器 provider 表（`BlockEntityRenderers.PROVIDERS`）重放是安全的（纯 `put`），
   **但真正的渲染器实例是启动时由 `BlockEntityRenderDispatcher.onResourceManagerReload`
   从 provider 烤出来的**——只放 provider 不重建 dispatcher，就永远不会实例化；
3. 颜色处理（`ItemColors`/`BlockColors`）也是纯 `put`，幂等；
4. 客户端扩展（`ClientExtensionsManager` 的四个静态 map）**重复 key 会抛异常**，不能直接重放。

修法（`client/ClientRegistrationReplay`）：

- 重放 `RegisterRenderers`，然后显式调 `BlockEntityRenderDispatcher` / `EntityRenderDispatcher`
  的 `onResourceManagerReload`，用新 provider 重建实例；
- 重放 `RegisterColorHandlersEvent.Block` / `.Item`（幂等）；
- 扩展事件：四个静态 map 先换成临时 map → 重放 → 把「原 map 里没有的」条目合并回去 → 还原原 map。

```text
[ModReloader] renderers replayed; block entity renderers rebuilt
[ModReloader] color handlers replayed
[ModReloader] client extensions replayed: 3 new
[GameplayCheck] client registrations: beRenderers 6/6 (missing=[]),
                porcelainBucketTintRegistered=true (tint=-1), fluidExtensions=3
```

> 注：瓷桶空桶时 handler 返回 `0xFFFFFFFF`，在 Java 里就是 `-1`，所以断言查的是「`ItemColors` 的 map 里有这个 item」，
> 而不是 tint 值。

### 2.4 物品栏图标全是紫黑方块（已修）

方块放出来是正常的，只有**物品图标**是紫黑。原因还是启动期快照，但这次在 `ItemRenderer`：

- `ItemRenderer` 的构造器遍历 `BuiltInRegistries.ITEM`，把每个 item → `ModelResourceLocation.inventory(id)`
  注册进 NeoForge 的 `RegistryAwareItemModelShaper`；
- 热加的 181 个物品不在那次遍历里，`getItemModel(item)` 返回 null → 退化成 missing model（紫黑立方体）；
- 方块模型不受影响：它们是资源重载时按 blockstate JSON 解析的。

修法：`ClientRegistrationReplay` 里把 shaper 里**还没有**的 item 补注册
（`ModelResourceLocation.inventory(id)`）；热加本身会触发一次客户端资源重载，
`ItemRenderer.onResourceManagerReload → rebuildCache()` 会把这些位置解析成真实模型。
断言放在资源重载之后：

```text
# 修前
[GameplayCheck] item models: 0/181 resolved, missing=[exnihilosatellitium:porcelain, ...]
[GameplayCheck] RESULT: FAIL

# 修后
[ModReloader] item model shaper: registered 181 new item(s); the next resource reload resolves them
[GameplayCheck] item models: 181/181 resolved, missing=[]
[GameplayCheck] RESULT: PASS
```

## 3. 手动清单（待所有者）

用 `-PverifyGameplayKeepOpen=true` 起一个热加后的世界（检查完留在世界里；该世界已开 LAN/作弊，
可以直接 `/gamemode` 与 `/give`）：

| 项 | 做法 | 期望 |
|---|---|---|
| 桶 barrel | 放桶、倒水、放入可堆肥物（如泥土/树叶），等待 | 按 `data/exnihilosatellitium/barrel` 的 16 条转换表变化 |
| 坩埚 crucible | 放坩埚，下面放点燃的营火/火/熔炉，放入圆石 | 按 39 条熔化表出熔融物，9 种热源都认 |
| 堆肥 compost | 用可堆肥物喂桶/堆肥流程 | 按 67 条堆肥表产出 |
| 网耐久 | 木网(10) / 丝网(64) / 铁网(256) 连续筛 | 次数用尽后网消失 |
| 机器界面 | 打开桶/坩埚/fuel machine GUI | 能开（screen 已注册）；放取物品/按钮交互待验；**内部内容可能不显示**（BE 渲染器未重放，见 §4） |

## 4. 未覆盖 / 已知

- **多人**：两端「登录前热加」未验证；连接后热加仍不支持（注册表快照）。
- **删除 / 更新**：仍是 add-only。
- 桶/坩埚/堆肥只有手动清单，没有自动断言（需要流体/热量/时间的组合状态）。
- **客户端注册还剩两项没重放**：`RegisterClientReloadListenersEvent`（客户端侧机器数据，主要给 JEI 用；
  本环境没装 JEI，且真 manager 里会重复注册 listener、无法区分新旧，需要临时 manager + diff）、
  `EntityRenderersEvent.RegisterLayerDefinitions`（模型层在启动时已 bake，热加渲染器若需要新 layer 会失败；
  众星捧月的机器/桶/筛用的是现成模型，没踩到）。`RegisterColorHandlersEvent.ColorResolvers`（群系着色 resolver）同样没重放。
- **实体渲染器**：事件已重放、`EntityRenderDispatcher` 也重建了，但众星捧月没有实体，未实测。
- **机器 GUI 交互**：screen 已注册且能开，但放取物品/按钮没有自动断言（手动清单里）。
- `DEFAULT_TABS` 是 NeoForge 私有字段；如果将来改名，`reset` 只会 warn，
  创造物品栏会退回「重复页签 → 越界」的旧行为（`-PverifyCreative` 会在 dev 抓到）。
