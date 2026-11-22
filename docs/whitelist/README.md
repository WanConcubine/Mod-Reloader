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
- **桶位是（JVM 环境, 模组）的组合，不是模组的全局属性**：BLACK 只表示“在**该环境**下结构性不可热加”，换一个环境可能变成 T1/J1/J2（例：Balm 为 stock BLACK / JBR J1）。同一环境内不会重复入桶。
- J1/J2 里的 **AT 模组**需要 **JBR + `-XX:+AllowEnhancedClassRedefinition`**；
  增强 JBR 下 late AT 自 2026-11-20 起自动启用（`-Dmodreloader.lateAt=false` 可关）。
- stock 已可热加（T1/T2）的模组**不单列 J1/J2**：T1/T2 是更强结论，JBR 复验写进同一行的证据。
- tier 是运行期**实际用到**的 tier（pin/fei/guifei/huanghou），不是预测。

## 总览

| 环境 | 可热加、无 bug | 可热加、有 bug | 判不可加但可救 | 结构性不可加 |
|---|---|---|---|---|
| stock | T1 × 7 | T2 × 0 | T3 × 0 | BLACK × 23 |
| JBR | J1 × 21 | J2 × 6 | — | BLACK × 4 |

## stock JVM（不靠 JBR）

### STOCK · T1 · 可热加，未发现 bug（7）

| 模组 | jar | tier | state | bug | 证据 |
|---|---|---|---|---|---|
| EasyLAN | EasyLAN-neoforge-1.21.1-v1.6a.jar | PIN | candidate | - | stock harness 2026-11-17 CompatCheck ok=true isLoaded=true |
| JECharacters | jecharacters-neoforge-4.5.15.jar | PIN | candidate | - | stock harness 2026-11-17 ok=true isLoaded=true（coremod 晚注入） |
| Rhino | rhino-2101.2.7-build.81+Rhino-1.21.jar | PIN | candidate | - | stock harness 2026-11-17 ok=true isLoaded=true |
| Searchables | Searchables-neoforge-1.21.1-1.0.2.jar | FEI | candidate | - | stock harness 2026-11-17 CompatCheck ok=true isLoaded=true (title screen) |
| Simple Voice Chat | voicechat-neoforge-1.21.1-2.6.27.jar | HUANGHOU | candidate | - | stock harness 2026-11-18 ok=true isLoaded=true；JBR harness 2026-11-18 也 ok=true（无 AT；lateAt 批次） |
| 众星捧月（Ex Nihilo: Satellitium） | exnihilosatellitium-1.21.1-1.5.0.jar | GUIFEI | verified | - | owner 2026-11-17：实际可用，加入一等；stock harness 2026-11-17 ok=true isLoaded=true（注册表 add-only + datapack；存档会绑定该 mod、不可回滚） |
| 苹果皮（AppleSkin） | appleskin-neoforge-mc1.21-3.0.9.jar | PIN | candidate | - | stock harness 2026-11-17 CompatCheck ok=true isLoaded=true |

### STOCK · BLACK · 结构性不可热加（23）

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
| Moonlight Lib | moonlight-1.21.1-3.8.3-neoforge.jar | GUIFEI | verified | 外层 126 条 AT（嵌套 codecui 的 AT 文件是空的；空文件误判已修） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR（late AT 覆盖到的成员级规则可应用） |
| Supplementaries | supplementaries-1.21.1-4.1.1-neoforge.jar | GUIFEI | fixing | JiJ 已修；stock 仍缺 Moonlight 依赖，待重测 | stock harness 2026-11-18 kind=JAR_IN_JAR（修复前）；JiJ 修复后未复跑 stock |
| Xaero 世界地图（Xaero's World Map） | xaeroworldmap-neoforge-1.21.1-1.47.0.jar | HUANGHOU | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：重启 / JBR |
| Xaero 小地图（Xaero's Minimap） | xaerominimap-neoforge-1.21.1-26.6.0.jar | HUANGHOU | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：重启 / JBR |
| ZeroCore2 | ZeroCore2-1.21.1-2.4.9.jar | GUIFEI | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：JBR / 重启 |
| 传送石碑（Waystones） | waystones-neoforge-1.21.1-21.1.47.jar | GUIFEI | verified | 布局 mixin（Entity 加接口）需 JBR（Balm 预装） | stock harness 2026-11-18 kind=JBR_REQUIRED；Balm 预置 run/mods；替代：JBR / 重启 |
| 农夫乐事（Farmer's Delight） | FarmersDelight-1.21.1-1.3.4.jar | GUIFEI | verified | access transformer（16 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（JBR 仍卡 RegisterRecipeBookCategoriesEvent） |
| 帕秋莉手册（Patchouli） | Patchouli-1.21.1-93-NEOFORGE.jar | GUIFEI | verified | 布局 mixin（loaded class）需 JBR | stock harness 2026-11-18 kind=JBR_REQUIRED；替代：JBR / 重启 |
| 旅行地图（JourneyMap） | journeymap-neoforge-1.21.1-6.0.9.jar | FEI | verified | access transformer（39 条，含类级 public-f NativeImage/ModelPart） | stock harness 2026-11-17 kind=ACCESS_TRANSFORMER；替代：重启（JBR late AT 也不适用，见 JBR 行） |
| 物品管理器（JEI） | jei-1.21.1-neoforge-19.70.0.469.jar | GUIFEI | verified | access transformer（34 条） | stock harness 2026-11-17 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（L1 通过） |
| 玉（Jade） | Jade-1.21.1-NeoForge-15.10.6.jar | FEI | verified | access transformer（57 条） | stock harness 2026-11-17 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT（L1 通过） |
| 铁氧体核心（FerriteCore） | ferritecore-7.0.3-neoforge.jar | FEI | verified | 布局 mixin（StateHolder 等）需 JBR | stock harness 2026-11-18 kind=JBR_REQUIRED；替代：JBR / 重启 |
| 饰品栏（Curios） | curios-neoforge-9.5.1+1.21.1.jar | GUIFEI | verified | access transformer（16 条） | stock harness 2026-11-18 kind=ACCESS_TRANSFORMER；替代：重启 / JBR late AT |
| 鼠标手势（Mouse Tweaks） | MouseTweaks-neoforge-mc1.21-2.26.1.jar | FEI | verified | 布局 mixin（AbstractContainerScreen 加接口）需 JBR | stock harness 2026-11-17 kind=JBR_REQUIRED；替代：重启 / JBR |

## JBR（enhanced redefinition）

### JBR · J1 · JBR 下可热加，未发现 bug（21）

| 模组 | jar | tier | state | bug | 证据 |
|---|---|---|---|---|---|
| Architectury API | architectury-13.0.12-neoforge.jar | GUIFEI | verified | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（ShaderReplay 事件重放；late AT 311 rule）；owner retest 2026-11-21 Windows/PCL2：311 rule/150 类、2 mixin config、isLoaded=true，热加后 3 分钟无崩溃（问题 64 对照组）；专用服 2026-11-21 serverAddCommand PASS（J1 行同时覆盖 server 路径） |
| Balm | balm-neoforge-1.21.1-21.0.67.jar | GUIFEI | candidate | - | JBR harness 2026-11-17 ok=true isLoaded=true（含 JiJ kuma_api） |
| Cloth Config API | cloth-config-15.0.140-neoforge.jar | PIN | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（late AT 3 rule） |
| Comforts | comforts-neoforge-9.0.5+1.21.1.jar | GUIFEI | candidate | - | JBR harness 2026-11-18 ok=true isLoaded=true（Balm 先热加） |
| Controlling | Controlling-neoforge-1.21.1-19.0.5.jar | FEI | verified | - | JBR+lateAt harness 2026-11-17 ok=true；late AT 1 rule / 1 target；owner retest 2026-11-21 Windows/PCL2：isLoaded=true，无崩溃 |
| e4mc | e4mc-neoforge-6.2.3.jar | HUANGHOU | candidate | - | pipeline lan-e4mc 2026-11-19：ok=true isLoaded=true（模块 kaleido+e4mc）；static tier 的 HUANGHOU 来自内嵌库 native 误判 |
| GeckoLib | geckolib-neoforge-1.21.1-4.9.3.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（MixinAgentGuard） |
| GuideME | guideme-21.1.22.jar | HUANGHOU | verified | - | pipeline ae2-lateat L1 2026-11-19（lateAt 47 条 AT，ok=true）；L2 2026-11-20：AE2 指南书 GuideScreen 正常打开（-PverifyAe2L2） |
| ModernFix | modernfix-neoforge-5.27.27+mc1.21.1.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18：单独与批次（GeckoLib→FerriteCore→ModernFix→MouseTweaks）均 ok=true isLoaded=true；MixinAgentGuard + 新 config 隔离 |
| Moonlight Lib | moonlight-1.21.1-3.8.3-neoforge.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18：NewRegistryEvent 重放已实现（3 个注册表创建、commit 90→92、blocks 1060→1061、items 1333→1335、stoneId 稳定），ok=true isLoaded=true；所有者实例复测后转 verified |
| Supplementaries | supplementaries-1.21.1-4.1.1-neoforge.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18：JiJ 递归 + 自定义注册表 + 模型层重放后 ok=true isLoaded=true；blocks 1061→1306、items 1335→1584、层 213→234。2026-11-19 复跑：MapRenderer$MapInstance 的两次 @WrapOperation 冲突 → 用预混字节 + 全部 configs 重组后 ok=true；所有者实例复测后转 verified |
| Xaero 小地图（Xaero's Minimap） | xaerominimap-neoforge-1.21.1-26.6.0.jar | HUANGHOU | candidate | - | JBR harness 2026-11-17 ok=true isLoaded=true（含 JiJ xaerolib） |
| ZeroCore2 | ZeroCore2-1.21.1-2.4.9.jar | GUIFEI | candidate | - | JBR harness 2026-11-17 ok=true isLoaded=true |
| 农夫乐事（Farmer's Delight） | FarmersDelight-1.21.1-1.3.4.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（MixinAgentGuard；blocks 1060→1192 items 1333→1518） |
| 局域网即插即用（LAN World Plug-n-Play） | mcwifipnp-1.9.0-1.21-neoforge.jar | FEI | candidate | - | pipeline lan-mcwifipnp 2026-11-19：ok=true isLoaded=true（redefine PauseScreen/MinecraftServer/UUIDUtil/PlayerList） |
| 帕秋莉手册（Patchouli） | Patchouli-1.21.1-93-NEOFORGE.jar | GUIFEI | candidate | - | JBR harness 2026-11-18 ok=true isLoaded=true（无 AT；redefined SmithingTrimRecipe 等） |
| 应用能源 2（AE2） | appliedenergistics2-19.2.20.jar | GUIFEI | verified | - | pipeline ae2-lateat L1 2026-11-19（ok=true，blocks→1162/items→1698）；L2 2026-11-20：进世界后 GuideScreen 与 MEStorageScreen 均打开、无崩溃（-PverifyAe2L2）；MP 2026-11-22：客户端连接后热加 GuideME+AE2、服务端后热加，34809 行 id 一致，真实放置 ae2:quartz_block（双端 block state 确认） |
| 物品管理器（JEI） | jei-1.21.1-neoforge-19.71.0.475.jar | GUIFEI | verified | - | 2026-11-17 harness ok=true（19.70.0.469，hot-added [mezz_config, jei]，34 rule）；owner retest 2026-11-21 Windows/PCL2：先热加 mezz_config（standalone 包装拆包，isLoaded=true）再 JEI 19.71.0.475（isLoaded=true、keyMappings 39→79），required 依赖链完整、无崩溃 |
| 铁氧体核心（FerriteCore） | ferritecore-7.0.3-neoforge.jar | FEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（MixinAgentGuard） |
| 饰品栏（Curios） | curios-neoforge-9.5.1+1.21.1.jar | GUIFEI | candidate | - | JBR+lateAt harness 2026-11-18 ok=true isLoaded=true（late AT 16 rule） |
| 鼠标手势（Mouse Tweaks） | MouseTweaks-neoforge-mc1.21-2.26.1.jar | FEI | candidate | - | JBR harness 2026-11-17 ok=true isLoaded=true（redefined AbstractContainerScreen） |

### JBR · J2 · JBR 下可热加，但有 bug（6）

| 模组 | jar | tier | state | bug | 证据 |
|---|---|---|---|---|---|
| Embeddium | embeddium-1.0.15+mc1.21.1.jar | GUIFEI | verified | 热加可用但原版「视频设置」不跳转，必须从模组菜单「配置」进入 Embeddium Options（OptionsScreen mixin 被跳过）；强开 mixin 会因 Minecraft.fences 活实例为 null 崩（问题 61 同族），需重启 | owner retest 2026-11-22 Windows/PCL2：13:01 旧 jar 热加 targets=[]、无崩溃，配置屏可开且原版选项生效；12:52 修复版 targets=80 后 crash-2026-11-22_12.52.54-client.txt（Minecraft.handler$embeddium$preRender 读 fences null）；问题 69 修复本身已验证 |
| Xaero 世界地图（Xaero's World Map） | xaeroworldmap-neoforge-1.21.1-1.47.0.jar | HUANGHOU | candidate | 热加成功；进世界后服务端初始化缺失（ServerStartedEvent 未重放，serverPlayerTickHandler null）；标题屏 loadLater 崩（需已有世界） | JBR harness 2026-11-18：ConfigChannelRegistry frozen 窗口生效，CompatCheck ok=true isLoaded=true；标题屏 tick 抛 NoSuchElementException（模组自身 loadLater）；世界内 add 返回 1 后 integrated server 玩家 tick NPE（WorldMap.serverPlayerTickHandler null）。需重放 ServerStartedEvent / 用已有存档复测；2026-11-18 晚：`ServerStarting/Started` 定向重放已实现但未验证（进入世界复跑卡在 xaerolib mixin 阶段，需 jstack） |
| 机械动力（Create） | create-1.21.1-6.0.10.jar | GUIFEI | verified | 进世界渲染崩溃：Ponder 静态 @Invoker accessor 接口未被 Mixin 改写（AssertionError: Mixin application failed!）；客户端连接后热加还会在 Player.aiStep 读 Ponder 配置时崩（Cannot get config value before config is loaded） | pipeline create-lateat 2026-11-19：ok=true isLoaded=true（L1）；owner retest 2026-11-21 Windows/PCL2：标题屏 add ok=true，进世界崩（crash-2026-11-21_18.33.26-client.txt）；**专用服 2026-11-21 runServer -PserverAddCommand PASS**（isLoaded=true，config common+server，AT/mixin 按 SERVER side 处理）；MP 2026-11-22：客户端连接后热加 ok=true（blocks=1703/items=2032），随后 Ponder 配置未加载崩，未进入 L2 放置 |
| 玉（Jade） | Jade-1.21.1-NeoForge-15.10.6.jar | FEI | verified | 热加后字体变暗（问题 56：Mixin 新增实例字段在旧 Font 实例上为 0，重启后正常） | JBR+lateAt harness 2026-11-17：reload 串行化修复后 4-jar 连加（Searchables→Controlling→Jade→JEI）无崩溃、屏幕正常恢复；原崩溃 crash-2026-11-17_20.13.47-client.txt（config translation）；owner retest 2026-11-21 Windows/PCL2：ok=true、late AT 57 rule、keyMappings 34→39，字体变暗复现（问题 56） |
| 现代化 UI（ModernUI） | [现代化 UI] ModernUI-NeoForge-1.21.1-3.13.0.1-universal.jar | GUIFEI | verified | 需 -Dmodreloader.modernuiAdapter=true（实验适配）；未启用时热加会崩（UI thread/recording context、Font/fontFilterFishy 字段） | owner report 2026-11-21 Windows/PCL2：opt-in 适配 PASS——Core.initialize+initOpenGL、Font 与 fontFilterFishy 回填、TextRenderType.preloadShaders；hot-added + 打开聊天框无崩溃（未启用时 crash-2026-11-21_16.57.15-client.txt） |
| 钠（Sodium） | sodium-neoforge-0.8.13+mc1.21.1.jar | FEI | fixing | 热加 ok=true；已有实例的 mixin 新增字段不初始化（结构边界）：Minecraft.fences NPE → 配置未初始化 → SpriteContents$Ticker.parent NPE；A 预检标注 J2，B 适配为 opt-in 实验 | 2026-11-20：标题屏热加 B 修好 fences/config/ticker.parent/BET 谓词/LevelRenderer.renderer 后不崩；进世界仍停在 BlockColors 状态（sodium$getProviders NPE） |

### JBR · BLACK · 结构性不可热加（4）

| 模组 | jar | tier | state | bug | 证据 |
|---|---|---|---|---|---|
| CraftTweaker | CraftTweaker-neoforge-1.21.1-21.0.39.jar | GUIFEI | fixing | 自定义 registry 已支持（NewRegistryEvent 重放，2026-11-18）；待复跑确认是否还有其它阻碍 | JBR+lateAt harness 2026-11-17 kind=CONSTRUCT（villager trade converters → 自定义 registry 太晚）；替代：重启；2026-11-18：自定义注册表窗口已实现（Moonlight 验证），CraftTweaker 未复跑 |
| Iris 光影 | iris-neoforge-1.8.14-beta.1+mc1.21.1.jar | FEI | fixing | 自身 2 条类级 public-f（OptionInstance、RegistryAccess$RegistryEntry 为 final，JBR 结构边界）；嵌套 AT 已支持 | pipeline iris-after-sodium-lateat 2026-11-19：嵌套 AT 通过，VALIDATE 拒绝 class-level -f |
| 传送石碑（Waystones） | waystones-neoforge-1.21.1-21.1.47.jar | GUIFEI | verified | EVENT：post-construct 失败且注册已部分生效（+44 方块 / +55 物品），需重启 | JBR harness 2026-11-18 kind=EVENT（Balm 先热加成功）；替代：重启（注册不可回滚） |
| 旅行地图（JourneyMap） | journeymap-neoforge-1.21.1-6.0.9.jar | FEI | verified | 类级 AT 去 final 无法在运行期完成：ComparableNativeImage extends NativeImage（NativeImage 必须非 final） | JBR 21.0.11 probe：redefine 去掉类 final 后该类的构造全部返回 null（NativeImage.read → null）；随后再次 redefine 时 JVM SIGSEGV（ConstantPoolCacheEntry::clear_entry）；late AT 现在在改任何字节前拒绝（-PverifyCompat=journeymap -PlateAt=true → stage=VALIDATE kind=ACCESS_TRANSFORMER），需放 mods/ 重启由 FML 在加载期应用 |
