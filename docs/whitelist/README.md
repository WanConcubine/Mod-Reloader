# 模组白名单（只读视图）

> 由 `python3 tools/whitelist.py render --write` 从 [mods.tsv](mods.tsv) 生成，**不要手改**。
> 规则与流转见 [mod-whitelist.md](../features/mod-whitelist.md)。

## 怎么读

| 桶 | 含义 |
|---|---|
| **T1** | stock 可热加，未发现 bug |
| **T2** | stock 可热加，但有已记录的 bug（修好→T1，修不动留 T2） |
| **T3** | 默认判不可热加，分析后实际可热加（处理成功后转 T2） |
| **J1 / J2** | 同上，但只在 JBR 下成立 |
| **BLACK** | 结构性不可热加；按 JVM 分别记，bug 列写原因、证据列写替代路径 |

- `state`：`candidate` = 只过了 harness（待所有者实例确认）；`verified` = 已确认；
  `fixing` / `fixed` / `wontfix` = 修 bug 的状态。
- 同一个 mod 在 stock 与 JBR 下**各占一行**；两行结论可以不同（例：stock BLACK / JBR J1）。
- J1/J2 里的 **AT 模组**需要 **JBR + `-Dmodreloader.lateAt=true`**；只开
  `-XX:+AllowEnhancedClassRedefinition` 不够（late AT 是单独的实验开关）。
- tier 是运行期**实际用到**的 tier（pin/fei/guifei/huanghou），不是预测。

## 总览

| 环境 | 可热加、无 bug | 可热加、有 bug | 判不可加但可救 | 结构性不可加 |
|---|---|---|---|---|
| stock | T1 × 7 | T2 × 0 | T3 × 0 | BLACK × 23 |
| JBR | J1 × 17 | J2 × 0 | — | BLACK × 7 |

## stock JVM（不靠 JBR）

### T1 · 可热加，未发现 bug（7）

| 模组 | jar | tier | state | bug | 证据 |
|---|---|---|---|---|---|
| EasyLAN | EasyLAN-neoforge-1.21.1-v1.6a.jar | PIN | candidate | - | stock harness 2026-11-17 CompatCheck ok=true isLoaded=true |
| JECharacters | jecharacters-neoforge-4.5.15.jar | PIN | candidate | - | stock harness 2026-11-17 ok=true isLoaded=true（coremod 晚注入） |
| Rhino | rhino-2101.2.7-build.81+Rhino-1.21.jar | PIN | candidate | - | stock harness 2026-11-17 ok=true isLoaded=true |
| Searchables | Searchables-neoforge-1.21.1-1.0.2.jar | FEI | candidate | - | stock harness 2026-11-17 CompatCheck ok=true isLoaded=true (title screen) |
| Simple Voice Chat | voicechat-neoforge-1.21.1-2.6.27.jar | HUANGHOU | candidate | - | stock harness 2026-11-18 ok=true isLoaded=true |
| 众星捧月（Ex Nihilo: Satellitium） | exnihilosatellitium-1.21.1-1.5.0.jar | GUIFEI | verified | - | owner 2026-11-17：实际可用，加入一等；stock harness 2026-11-17 ok=true isLoaded=true（注册表 add-only + datapack；存档会绑定该 mod、不可回滚） |
| 苹果皮（AppleSkin） | appleskin-neoforge-mc1.21-3.0.9.jar | PIN | candidate | - | stock harness 2026-11-17 CompatCheck ok=true isLoaded=true |

### BLACK · 结构性不可热加（23）

| 模组 | jar | tier | state | bug | 证据 |
|---|---|---|---|---|---|
| Architectury API | architectury-13.0.12-neoforge.jar | GUIFEI | verified | access transformer（311 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（JBR 仍卡 RegisterShadersEvent） |
| Balm | balm-neoforge-1.21.1-21.0.67.jar | GUIFEI | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：重启 / JBR |
| Cloth Config API | cloth-config-15.0.140-neoforge.jar | PIN | verified | access transformer（3 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT |
| Comforts | comforts-neoforge-9.0.5+1.21.1.jar | GUIFEI | verified | 布局 mixin（Player 加接口）需 JBR（Balm 预装） | stock harness 2026-11-18 kind=JBR_REQUIRED；Balm 预置 run/mods；替代：JBR / 重启 |
| Controlling | Controlling-neoforge-1.21.1-19.0.5.jar | FEI | verified | access transformer（1 条） | stock harness 2026-11-17 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT |
| CraftTweaker | CraftTweaker-neoforge-1.21.1-21.0.39.jar | GUIFEI | verified | access transformer（20 条） | stock harness 2026-11-17 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT |
| Embeddium | embeddium-1.0.15+mc1.21.1.jar | GUIFEI | verified | access transformer（19 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT |
| GeckoLib | geckolib-neoforge-1.21.1-4.9.3.jar | GUIFEI | verified | access transformer（27 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（JBR redefine 仍失败） |
| ModernFix | modernfix-neoforge-5.27.27+mc1.21.1.jar | GUIFEI | verified | access transformer（74 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（JBR 仍失败且崩溃） |
| Moonlight Lib | moonlight-1.21.1-3.8.3-neoforge.jar | GUIFEI | verified | 嵌套 mod codecui 自带 AT | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR（late AT 不覆盖嵌套） |
| Supplementaries | supplementaries-1.21.1-4.1.1-neoforge.jar | GUIFEI | fixing | JiJ 已修；stock 仍缺 Moonlight 依赖，待重测 | stock harness 2026-11-18 kind=JAR_IN_JAR（修复前）；JiJ 修复后未复跑 stock |
| Xaero 世界地图（Xaero's World Map） | xaeroworldmap-neoforge-1.21.1-1.47.0.jar | HUANGHOU | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：重启 / JBR |
| Xaero 小地图（Xaero's Minimap） | xaerominimap-neoforge-1.21.1-26.6.0.jar | HUANGHOU | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：重启 / JBR |
| ZeroCore2 | ZeroCore2-1.21.1-2.4.9.jar | GUIFEI | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：JBR / 重启 |
| 传送石碑（Waystones） | waystones-neoforge-1.21.1-21.1.47.jar | GUIFEI | verified | 布局 mixin（Entity 加接口）需 JBR（Balm 预装） | stock harness 2026-11-18 kind=JBR_REQUIRED；Balm 预置 run/mods；替代：JBR / 重启 |
| 农夫乐事（Farmer's Delight） | FarmersDelight-1.21.1-1.3.4.jar | GUIFEI | verified | access transformer（16 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（JBR 仍卡 RegisterRecipeBookCategoriesEvent） |
| 帕秋莉手册（Patchouli） | Patchouli-1.21.1-93-NEOFORGE.jar | GUIFEI | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-18 kind=JBR_REQUIRED；替代：JBR / 重启 |
| 旅行地图（JourneyMap） | journeymap-neoforge-1.21.1-6.0.9.jar | FEI | verified | access transformer（39 条） | stock harness 2026-11-17 kind=ACCESS_TRANSFORMER；替代：重启 / JBR（仍卡 RegisterShadersEvent） |
| 物品管理器（JEI） | jei-1.21.1-neoforge-19.70.0.469.jar | GUIFEI | verified | access transformer（34 条） | stock harness 2026-11-17 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（L1 通过） |
| 玉（Jade） | Jade-1.21.1-NeoForge-15.10.6.jar | FEI | verified | access transformer（57 条） | stock harness 2026-11-17 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（L1 通过） |
| 铁氧体核心（FerriteCore） | ferritecore-7.0.3-neoforge.jar | FEI | verified | 布局 mixin（StateHolder 等）需 JBR | stock harness 2026-11-18 kind=JBR_REQUIRED；替代：JBR / 重启 |
| 饰品栏（Curios） | curios-neoforge-9.5.1+1.21.1.jar | GUIFEI | verified | access transformer（16 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT |
| 鼠标手势（Mouse Tweaks） | MouseTweaks-neoforge-mc1.21-2.26.1.jar | FEI | verified | 布局 mixin（AbstractContainerScreen 加接口）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：重启 / JBR |

## JBR（enhanced redefinition）

### J1 · JBR 下可热加，未发现 bug（17）

| 模组 | jar | tier | state | bug | 证据 |
|---|---|---|---|---|---|
| Balm | balm-neoforge-1.21.1-21.0.67.jar | GUIFEI | candidate | - | JBR harness 2026-11-17 ok=true isLoaded=true（含 JiJ kuma_api） |
| Cloth Config API | cloth-config-15.0.140-neoforge.jar | PIN | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（late AT 3 rule） |
| Comforts | comforts-neoforge-9.0.5+1.21.1.jar | GUIFEI | candidate | - | JBR harness 2026-11-18 ok=true isLoaded=true（Balm 先热加） |
| Controlling | Controlling-neoforge-1.21.1-19.0.5.jar | FEI | candidate | - | JBR+lateAt harness 2026-11-17 ok=true；late AT 1 rule / 1 target |
| Embeddium | embeddium-1.0.15+mc1.21.1.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（late AT 19 rule） |
| GeckoLib | geckolib-neoforge-1.21.1-4.9.3.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（MixinAgentGuard） |
| ModernFix | modernfix-neoforge-5.27.27+mc1.21.1.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18：单独与批次（GeckoLib→FerriteCore→ModernFix→MouseTweaks）均 ok=true isLoaded=true；MixinAgentGuard + 新 config 隔离 |
| Simple Voice Chat | voicechat-neoforge-1.21.1-2.6.27.jar | HUANGHOU | candidate | - | JBR harness 2026-11-18 ok=true isLoaded=true（无 AT；lateAt 批次） |
| Xaero 小地图（Xaero's Minimap） | xaerominimap-neoforge-1.21.1-26.6.0.jar | HUANGHOU | candidate | - | JBR harness 2026-11-17 ok=true isLoaded=true（含 JiJ xaerolib） |
| ZeroCore2 | ZeroCore2-1.21.1-2.4.9.jar | GUIFEI | candidate | - | JBR harness 2026-11-17 ok=true isLoaded=true |
| 农夫乐事（Farmer's Delight） | FarmersDelight-1.21.1-1.3.4.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（MixinAgentGuard；blocks 1060→1192 items 1333→1518） |
| 帕秋莉手册（Patchouli） | Patchouli-1.21.1-93-NEOFORGE.jar | GUIFEI | candidate | - | JBR harness 2026-11-18 ok=true isLoaded=true（无 AT；redefined SmithingTrimRecipe 等） |
| 物品管理器（JEI） | jei-1.21.1-neoforge-19.70.0.469.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-17 ok=true（hot-added [mezz_config, jei]，34 rule） |
| 玉（Jade） | Jade-1.21.1-NeoForge-15.10.6.jar | FEI | candidate | - | JBR+lateAt harness 2026-11-17：reload 串行化修复后 4-jar 连加（Searchables→Controlling→Jade→JEI）无崩溃、屏幕正常恢复；原崩溃 crash-2026-11-17_20.13.47-client.txt（config translation） |
| 铁氧体核心（FerriteCore） | ferritecore-7.0.3-neoforge.jar | FEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（MixinAgentGuard） |
| 饰品栏（Curios） | curios-neoforge-9.5.1+1.21.1.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（late AT 16 rule） |
| 鼠标手势（Mouse Tweaks） | MouseTweaks-neoforge-mc1.21-2.26.1.jar | FEI | candidate | - | JBR harness 2026-11-17 ok=true isLoaded=true（redefined AbstractContainerScreen） |

### BLACK · 结构性不可热加（7）

| 模组 | jar | tier | state | bug | 证据 |
|---|---|---|---|---|---|
| Architectury API | architectury-13.0.12-neoforge.jar | GUIFEI | verified | CLIENT_REGISTRATION：RegisterShadersEvent 未重放（late AT 311 rule 已应用，耗时约 70s） | JBR+lateAt harness 2026-11-18 kind=CLIENT_REGISTRATION；替代：重启 / 等事件重放补齐 |
| CraftTweaker | CraftTweaker-neoforge-1.21.1-21.0.39.jar | GUIFEI | verified | 自定义 registry 在 NewRegistryEvent 之后注册（crafttweaker:transformer_serializer） | JBR+lateAt harness 2026-11-17 kind=CONSTRUCT（villager trade converters → 自定义 registry 太晚）；替代：重启 |
| Moonlight Lib | moonlight-1.21.1-3.8.3-neoforge.jar | GUIFEI | verified | 嵌套 mod codecui 的 AT（late AT 不覆盖嵌套 jar） | JBR+lateAt harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 |
| Supplementaries | supplementaries-1.21.1-4.1.1-neoforge.jar | GUIFEI | fixing | 缺 Moonlight 依赖（JiJ 已修；Moonlight 预装 dev 启动崩 codecui.Schema） | JBR+lateAt harness 2026-11-18：越过 MODULE/JiJ，kind=MISSING_DEPENDENCY；nested 2，containers [sablecompanion] |
| Xaero 世界地图（Xaero's World Map） | xaeroworldmap-neoforge-1.21.1-1.47.0.jar | HUANGHOU | verified | 构造期失败：config channel 注册太晚 + XaeroWorldMapCore ClassNotFound | JBR harness 2026-11-17 kind=CONSTRUCT；替代：重启（可能是运行时可修的缺口） |
| 传送石碑（Waystones） | waystones-neoforge-1.21.1-21.1.47.jar | GUIFEI | verified | EVENT：post-construct 失败且注册已部分生效（+44 方块 / +55 物品），需重启 | JBR harness 2026-11-18 kind=EVENT（Balm 先热加成功）；替代：重启（注册不可回滚） |
| 旅行地图（JourneyMap） | journeymap-neoforge-1.21.1-6.0.9.jar | FEI | verified | CLIENT_REGISTRATION：RegisterShadersEvent 未重放 | JBR+lateAt harness 2026-11-17 kind=CLIENT_REGISTRATION；替代：重启 |
