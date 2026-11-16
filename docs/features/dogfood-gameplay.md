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
| 5 | 创造物品栏渲染 40 tick（`-PverifyCreative`） | 不崩，`defaults=4`，PASS |

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

## 3. 手动清单（待所有者）

用 `-PverifyGameplayKeepOpen=true` 起一个热加后的世界（检查完留在世界里；该世界已开 LAN/作弊，
可以直接 `/gamemode` 与 `/give`）：

| 项 | 做法 | 期望 |
|---|---|---|
| 桶 barrel | 放桶、倒水、放入可堆肥物（如泥土/树叶），等待 | 按 `data/exnihilosatellitium/barrel` 的 16 条转换表变化 |
| 坩埚 crucible | 放坩埚，下面放点燃的营火/火/熔炉，放入圆石 | 按 39 条熔化表出熔融物，9 种热源都认 |
| 堆肥 compost | 用可堆肥物喂桶/堆肥流程 | 按 67 条堆肥表产出 |
| 网耐久 | 木网(10) / 丝网(64) / 铁网(256) 连续筛 | 次数用尽后网消失 |
| 机器界面 | 打开桶/坩埚 GUI 并交互 | 能开、能放取 |

## 4. 未覆盖 / 已知

- **多人**：两端「登录前热加」未验证；连接后热加仍不支持（注册表快照）。
- **删除 / 更新**：仍是 add-only。
- 桶/坩埚/堆肥只有手动清单，没有自动断言（需要流体/热量/时间的组合状态）。
- `DEFAULT_TABS` 是 NeoForge 私有字段；如果将来改名，`reset` 只会 warn，
  创造物品栏会退回「重复页签 → 越界」的旧行为（`-PverifyCreative` 会在 dev 抓到）。
