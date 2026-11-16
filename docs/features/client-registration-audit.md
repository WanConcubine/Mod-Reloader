# 客户端注册审计（启动期事件：可报告 / 可拒绝）

> 状态：**已实现并验证（2026-11-16）**。起因：热加 AppleSkin 后悬停食物崩溃（`Unknown TooltipComponent`），
> 根因是**又漏了一个启动期一次性事件**。同类缺口在真实模组里必然还有，而且失败形态从「静默」到「崩」不等。
> 代码：[inspect/RegistrationAudit](../../src/main/java/dev/modreloader/inspect/RegistrationAudit.java)（纯规则）+
> [inspect/RegistrationAuditScanner](../../src/main/java/dev/modreloader/inspect/RegistrationAuditScanner.java)（扫 jar）+
> [dev/RegAuditCheck](../../src/dev/java/dev/modreloader/dev/RegAuditCheck.java)（harness）。

## 1. 问题

NeoForge 的 mod bus 上有一批**只在启动时 fire 一次**的注册事件。热加的模组永远错过它们，
所以它的注册要么不存在、要么在第一次用到时炸。我们一直是**手写枚举**：漏一个就是一类 bug。

代价已经付过六次：`RegisterMenuScreensEvent`（机器 GUI 打不开）、`EntityRenderersEvent.RegisterRenderers`（内部内容不渲染）、
`RegisterColorHandlersEvent`（瓷桶不染色）、`RegisterClientExtensionsEvent`（流体外观）、`RegisterClientReloadListenersEvent`（客户端表不读），
以及 2026-11-16 的 `RegisterClientTooltipComponentFactoriesEvent`（**悬停食物直接崩**）。

失败形态分级（这决定了它不能靠「用户反馈」兜底）：

| 形态 | 例子 | 用户怎么看到 |
|---|---|---|
| 静默 | `RegisterGuiLayersEvent`（已补）、`RegisterItemDecorationsEvent` | 少了个 HUD 元素 / 装饰，没人知道 |
| 点一下崩 | `RegisterClientTooltipComponentFactoriesEvent`、`RegisterRecipeBookCategoriesEvent`、`RegisterParticleProvidersEvent`、`RegisterShadersEvent` | 悬停/开配方书/粒子/自定义渲染时崩 |
| 行为变了 | `RegisterCapabilitiesEvent`、`ModifyDefaultComponentsEvent` | 机器不工作、组件缺失 |
| 加载期崩 | `RegisterSpriteSourceTypesEvent`（资源重载） | 热加当次就崩 |

## 2. 为什么不能「把事件全重放一遍」

- 每个事件的注册面不同（map / registry / listener list / lambda），构造实参也不一样；
- 往**真身**重放会给已加载模组重复注册 → 我们已经踩过（`RegisterClientExtensionsEvent` 的静态 map 重复 key 直接抛）；
- 有些事件只在特定生命周期有效（`NewRegistryEvent` / `DataPackRegistryEvent` 在启动后关闭），重放只会炸；
- 有些事件**不是一次性**的（`RenderLevelStageEvent` 每帧、`TextureAtlasStitchedEvent` 每次重载、`BuildCreativeModeTabContentsEvent` 每次重建页签），
  它们本来就会再次 fire，不需要重放。

现有做法（临时 sink → 只合并新条目 → 换回真 map + `container.acceptEvent` 只投给新容器）是对的，但**逐事件**，不能反射一把梭。

所以分工是：**审计负责把「漏」变成清单，replay 仍然逐个事件手写**。

## 3. 实现

### 3.1 扫描：常量池引用 → `IModBusEvent`

`RegistrationAuditScanner`（有 ASM、有游戏类）对 jar 里每个 `.class` 跑 `ClassReader`，收集：

- 类头（自身、父类、接口）、字段/方法描述符、方法签名、异常表；
- 注解（类/字段/方法/参数/类型注解，含嵌套注解与 enum 值）；
- 指令：`typeInsn` / `fieldInsn` / `methodInsn` 的 owner 与描述符、`invokeDynamic`（lambda 的方法类型与 bootstrap 参数）、`ldc`（`Type` / `Handle` / `ConstantDynamic`）、`try-catch` 类型；
- 签名里的泛型参数（`Consumer<SomeEvent>` 这种只在 signature 里出现的引用也会被抓到）。

然后只保留 `net/neoforged/**`、且简单类名含 `Event` 的名字，`Class.forName(name, false, loader)`（**不初始化**）+ `IModBusEvent.isAssignableFrom` 判定。
**不加载任何模组自己的类**；加载失败的记为 `unresolved`（通常是可选依赖缺失），只报告、不致命。

三个实现时踩到/修掉的坑：

1. **`InnerClasses` 属性里的外层类不是引用**。引用了 `EntityRenderersEvent$RegisterRenderers` 的类文件，其 `InnerClasses` 表还会写出
   `EntityRenderersEvent`（抽象基类）——第一版把外层也收进来，于是每个用嵌套事件的模组都多报一个「未覆盖」。
   现在 `visitInnerClass` 只收内层名字。
2. **`IModBusEvent` 本身是 marker 接口**，模组自定义事件类会 `implements` 它；接口不是事件，直接跳过。
3. **抽象基类**（`EntityRenderersEvent`、`RegisterColorHandlersEvent`、`DataPackRegistryEvent`…）是真实出现的东西：
   Mekanism 有个工具方法直接收 `RegisterColorHandlersEvent`（基类）再分发给 `$Block`/`$Item`。基类永远不会被 fire，
   单独给它一个 `base` 分级，既不当缺口也不藏起来。

### 3.2 分级：三张名单之外还有「严重度」

`RegistrationAudit`（纯规则：不 import ASM、不 import 游戏类，所以能在只有 JUnit 的测试 JVM 里跑）把每个事件归到一类：

| 分级 | 含义 | 处理 |
|---|---|---|
| `covered` | 我们已经重放/构造（`ClientRegistrationReplay` / `FmlEvents` / `NetworkAdaptation` / `RegistryStage`） | 无 |
| `recurring` | 启动后还会再 fire（`ModelEvent.*`、`TextureAtlasStitchedEvent`、`BuildCreativeModeTabContentsEvent`、`SoundEngineLoadEvent`、`RegisterConfigurationTasksEvent`、`RenderLevelStageEvent`） | 无 |
| `datagen` | `GatherDataEvent` / `RegisterGameTestsEvent` | 无 |
| `crash` | 用到就崩：`RegisterParticleProvidersEvent`、`RegisterRecipeBookCategoriesEvent`、`RegisterShadersEvent`、`RegisterSpriteSourceTypesEvent` | **变更前拒绝**（`FailureKind.CLIENT_REGISTRATION`） |
| `behavior` | 能跑但功能变了：默认组件、自注册 registry、属性/刷怪/POI、模型层（已 bake） | 大声 warn，**不拒绝** |
| `silent` | 没有已知症状（物品装饰、维度特效、地图装饰…；HUD 层已移出，见下） | warn |
| `base` | 抽象事件基类（`EntityRenderersEvent`、`RegisterColorHandlersEvent`、`DataPackRegistryEvent`）；真正 fire 的是具体子类 | 仅记录 |
| `other-side` | 专用服上的纯客户端事件（那里本来就不会 fire） | 仅记录 |

**与初版提案的差别（有意为之）**：提案把 `RegisterCapabilitiesEvent` 也列为「变更前拒绝」。实测语料里
**5/16 个模组**（众星捧月、ZeroCore2、AE2、Mekanism、ExtremeReactors2）都注册 capabilities，
其中众星捧月是我们的 dogfood——直接拒绝会让内容模组全线不可热加，而它们的方块/物品/配方仍然能用。
当时先把它归到 `behavior`（warn）并列为下一优先级；**2026-11-16 已补上重放**（`hotadd/CapabilityReplay`），
现在归 `covered`。拒绝仍然只留给「一用就崩」的四类。决策记录见 [ADR-0012](../decisions/0012-registration-audit-severity.md)。

### 3.3 接进引擎

`HotAddEngine` 的 `Stage.VALIDATE`、依赖/AT 预检之后、**module 注入之前**：

```
registration audit <ids>: N mod-bus event(s); covered [...]; crash [...]; behavior [...]; silent [...]
```

- `crash` 非空 → `FailureKind.CLIENT_REGISTRATION`，文案「该模组会注册 X，本运行时还没有重放它；用到就会崩，带 jar 重启」；
- `behavior` / `silent` → 只记日志（`behavior` 的文案里点名事件，方便判断「机器为什么不动」）；
- 审计自身抛异常只 warn，不影响热加。

拒绝点在注入之前，所以**没有任何副作用**：合成 jar 的实测里 `blocks/items` 不变、`isLoaded=false`。

### 3.4 用真实语料校准：`-PverifyRegAudit`

```bash
./gradlew runClient -PverifyRegAudit=/abs/a.jar[,/abs/b.jar]
# [RegAudit] <jar> / <severity> = [...] / fatal path / RESULT: PASS
```

它做两件只读的事：① 扫描给定 jar 并逐条打印分级（**不热加**，所以 AT/缺依赖的 jar 也能进语料）；
② 现造两个最小 mod jar（`neoforge.mods.toml` + 一个字段引用事件），
一个引用 `RegisterParticleProvidersEvent`（必须被 `HotAddEngine` 以 `CLIENT_REGISTRATION` 拒绝在 `VALIDATE`），
一个只引用仍然没有重放的 `RegisterItemDecorationsEvent`（必须**不**被判致命）。

## 4. 事件清单

2026-11-16 从 `neoforge-21.1.265-sources.jar` 扫出 **44 个顶层 `IModBusEvent` 实现**（另有嵌套类：`EntityRenderersEvent.RegisterRenderers/RegisterLayerDefinitions/AddLayers`、
`RegisterColorHandlersEvent.Block/.Item/.ColorResolvers`、`ModelEvent.*`、`DataPackRegistryEvent.NewRegistry` 等）。
扫描口径是「文件里提到 `IModBusEvent`」，**可能含误报**；运行期以审计的 `Class.forName + isAssignableFrom` 为准。

**已覆盖（重放或构造）**：`FMLConstructModEvent`、`FMLCommonSetupEvent`、`FMLLoadCompleteEvent`、`FMLClientSetupEvent`、
`RegisterKeyMappingsEvent`、`RegisterMenuScreensEvent`、`EntityRenderersEvent.RegisterRenderers`、
`RegisterColorHandlersEvent.Block/.Item`、`RegisterClientExtensionsEvent`、`RegisterClientReloadListenersEvent`、
`RegisterClientTooltipComponentFactoriesEvent`、`RegisterPayloadHandlersEvent`、`RegisterEvent`、
`RegisterCapabilitiesEvent`（`CapabilityReplay`，2026-11-16）、`RegisterGuiLayersEvent`（`client/GuiLayerReplay`，2026-11-16）。

**已知崩溃（拒绝）**：`RegisterParticleProvidersEvent`、`RegisterRecipeBookCategoriesEvent`、`RegisterShadersEvent`、`RegisterSpriteSourceTypesEvent`。

**行为类（warn）**：`ModifyDefaultComponentsEvent`、`NewRegistryEvent`、`DataPackRegistryEvent.*`、
`ModifyRegistriesEvent`、`EntityAttributeCreationEvent`、`EntityAttributeModificationEvent`、`RegisterSpawnPlacementsEvent`、
`BlockEntityTypeAddBlocksEvent`、`RegisterDataMapTypesEvent`、`RegisterCauldronFluidContentEvent`、`AddPackFindersEvent`、
`ExtendPoiTypesEvent`、`EntityRenderersEvent.RegisterLayerDefinitions`。

**无已知症状（warn）**：`RegisterItemDecorationsEvent`、`RegisterDimensionSpecialEffectsEvent`、
`RegisterDimensionTransitionScreenEvent`、`RegisterEntitySpectatorShadersEvent`、`RegisterJsonAnimationTypesEvent`、
`RegisterMaterialAtlasesEvent`、`RegisterNamedRenderTypesEvent`、`RegisterPresetEditorsEvent`、`RegisterRenderBuffersEvent`、
`RegisterMapDecorationRenderersEvent`、`RegisterTicketControllersEvent`。

## 5. 真实语料（2026-11-16 19:05 补 capability 重放后复跑，16 个 jar，只读）

`-PverifyRegAudit` 跑工具/场景语料 7 个（AppleSkin 3.0.9、Searchables 1.0.2、Controlling 19.0.5、EasyLAN 1.6a、
lanserverproperties 1.13.2、mcwifipnp 1.9.0、众星捧月 1.5.0）+ 兼容性矩阵 9 个（ZeroCore2、Jade、jecharacters、JEI、AE2、guideme、
Mekanism、MekanismGenerators、ExtremeReactors2），共 **129 处事件引用**：

| 计数 | covered | recurring | datagen | silent | behavior | crash | base |
|---|---|---|---|---|---|---|---|
| 129 | **68** | 24 | 6 | **17** | 10 | **3** | 1 |

（2026-11-16 19:48 复跑：`RegisterGuiLayersEvent` 从 `silent` 移入 `covered`，2 处引用 / AppleSkin 与 Mekanism 各 1 个 jar；
总数仍是 129，没有任何当前 PASS 的模组变拒绝。）

逐 jar（只列非空项）：

| jar | 事件数 | 结果 |
|---|---|---|
| AppleSkin 3.0.9 | 4 | **4 covered**（含 `RegisterGuiLayersEvent`）→ 仍 PASS；HUD 层已有 `-PverifyGuiLayers` 断言 |
| Searchables 1.0.2 / EasyLAN / lanserverproperties / mcwifipnp | 0 | 没有启动期事件 |
| Controlling 19.0.5 | 1 | covered（`FMLClientSetupEvent`） |
| 众星捧月 1.5.0 | 10 | **9 covered（含 capabilities）** + datagen |
| ZeroCore2 2.4.9 | 7 | 2 covered（含 capabilities）+ 4 recurring + datagen |
| Jade 15.10.6 / JEI 19.27.0 | 4 / 4 | 全 covered |
| jecharacters 4.5.15 | 3 | 1 covered + silent `ModConfigEvent.Loading/.Reloading`（config 已由 `ModConfigs` 直接加载，事件本身不 fire） |
| AE2 19.2.18 | 24 | 11 covered + 1 **crash**（粒子）+ 2 behavior + 5 silent + 3 recurring + 2 datagen |
| guideme 21.1.22 | 10 | 5 covered + 4 recurring + datagen |
| Mekanism 10.7.19 | 38 | 13 covered（含 HUD 层）+ **2 crash**（粒子、shader）+ 7 behavior + 7 silent + 8 recurring + 1 base |
| MekanismGenerators | 13 | 7 covered + behavior 模型层 + 2 silent + 3 recurring |
| ExtremeReactors2 2.4.9 | 11 | 7 covered（含 capabilities）+ 1 silent + 2 recurring + datagen |

结论：

- **审计没有改变任何当前 PASS 的结论**：3 处 `crash` 全在 AE2 / Mekanism，而这两个模组本来就因 access transformer 在 VALIDATE 被拒。
  AppleSkin、ZeroCore2、jecharacters、Jade、guideme、众星捧月仍然可热加。
- **审计第一次把 dogfood 的一个真实缺口变成可报告，并且已经补上**：众星捧月有
  `@EventBusSubscriber @SubscribeEvent registerCapabilities(RegisterCapabilitiesEvent)`（javap 验证），
  给桶（item + fluid）、坩埚（fluid）、机器（item + energy）和流体容器物品注册 provider。
  我们从来没有重放这个事件 → 热加会话里这些 capability 没登记。2026-11-16 补了 `hotadd/CapabilityReplay`，
  `-PverifyCapabilities` 断言五项 provider 全 present，`-PskipCapabilityReplay=true` 反证全 MISSING（见 [dogfood-gameplay.md](dogfood-gameplay.md) §1.1）；
  本表随之把该事件从 `behavior` 移入 `covered`（61→66 covered，15→10 behavior）。
- 2026-11-16 已补 `RegisterGuiLayersEvent`（实测语料里 2/16 个 jar 引用：AppleSkin、Mekanism；HUD 静默）：
  `client/GuiLayerReplay` 对 live 层表副本重放再换入，harness `-PverifyAddCommand=<appleskin> -PverifyGuiLayers=true`
  断言 25 → 31 层、6 个 `appleskin:*` 层、原有层顺序不变、渲染 60 tick 不崩。
- 下一批收益：`RegisterItemDecorationsEvent`（物品装饰，静默；`ItemDecoratorHandler.DECORATOR_LOOKUP` 是静态
  `ImmutableMap`，可照 `GuiLayerReplay` 的「读 live → 副本重放 → 换回」做法）、模型层
  `EntityRenderersEvent.RegisterLayerDefinitions`（已 bake，要重建 `EntityModelSet`，成本更高）。
- 没有 `unresolved`：16 个 jar 里所有 `net.neoforged.**Event` 都能加载（客户端）。

## 6. 验证

```bash
# 纯规则（无游戏、无 ASM）：9 个用例
./gradlew test --tests 'dev.modreloader.inspect.RegistrationAuditTest'

# 真实语料 + 合成致命 jar（2026-11-16 15:20 = PASS）
./gradlew runClient -PverifyRegAudit=<16 jars>

# 引擎集成回归：真实热加仍 PASS，且日志出现 audit 行
./gradlew runClient -PverifyCompat=/tmp/user-mods/appleskin-neoforge-mc1.21-3.0.9.jar
#   registration audit [appleskin]: 4 mod-bus event(s); covered [..., client.event.RegisterGuiLayersEvent, ...]
#   RESULT ... ok=true stage=NONE kind=NONE isLoaded=true

# HUD 层重放的实测断言（AppleSkin 注册 6 个 appleskin:* 层）
./gradlew runClient -PverifyAddCommand=/tmp/user-mods/appleskin-neoforge-mc1.21-3.0.9.jar \
  -PverifyAddCommandModId=appleskin -PverifyGuiLayers=true
#   [AddCmdCheck] GUI layers: 25 -> 31 layer(s); 6 new, 6 in namespace appleskin; ...
#   [AddCmdCheck] GUI layers RESULT: PASS (replayed, order preserved, 60 ticks rendered with the new layers)
```

专用服路径（2026-11-16 15:25，`runServer -PautoAdd=<appleskin>`，日志原文）：

```
registration audit [appleskin]: 4 mod-bus event(s); covered [fml.event.lifecycle.FMLClientSetupEvent, network.event.RegisterPayloadHandlersEvent];
  other-side [client.event.RegisterClientTooltipComponentFactoriesEvent, client.event.RegisterGuiLayersEvent]   # 客户端事件在专用服不 fire，仅记录
```

纯客户端事件在专用服上归入 `other-side`，服务端事件照常分级——`Class.forName` 在服务端不会因为事件类引用了客户端类型而失败。

`RegAuditCheck` 的致命路径实测（同一行日志）：

```
[RegAudit] synthetic crash jar: 1 mod-bus event(s); crash [client.event.RegisterParticleProvidersEvent]
[ModReloader] registration audit [regauditcrash]: 1 mod-bus event(s); crash [...]
[RegAudit] engine on crash jar: ok=false stage=VALIDATE kind=CLIENT_REGISTRATION msg=[regauditcrash] registers [...]
[RegAudit] synthetic silent jar: 1 mod-bus event(s); silent [client.event.RegisterItemDecorationsEvent]
[RegAudit] fatal path graded=true refused=true silentNotFatal=true -> PASS
# （合成 silent jar 的示例事件从 RegisterGuiLayersEvent 换成 RegisterItemDecorationsEvent：
#   前者 2026-11-16 起是 covered，实例必须是仍然没重放的事件）
```

## 7. 边界与下一步

- **启发式**：只看「类文件里出现了这个事件类型」，把「在 helper 方法签名里收下事件」也算进来（Mekanism 的基类就是这样）；
  反过来，通过反射/字符串拿到的事件类抓不到。它回答的是「这个模组注册面覆盖哪些启动期事件」，不是「它一定漏了」。
- **只看外层 jar**：`META-INF/jarjar` 里的嵌套库不扫（内嵌**mod** 本来也不构造）。
- **`unresolved` 不致命**：事件类加载失败（可选依赖/客户端类跑在服务端）只记录，不拒绝。
- **UI 预检还没接审计**：`ModInspection` / GUI 不会提前提示「这个模组有未重放事件」，拒绝发生在 `/modreloader add` 时（失败屏里有事件清单）。
- **服务端**：纯客户端事件在专用服上记为 `other-side`（那里本来就不 fire）；服务端事件（capabilities、属性、registry）照常审计。
- **还没重放的仍然没重放**：审计只是把清单变成数据。**`RegisterCapabilitiesEvent` 已于 2026-11-16 重放**（`hotadd/CapabilityReplay`，
  只投新容器；`-PverifyCapabilities` + A/B）。剩下的按收益排序：
  1. `RegisterGuiLayersEvent`（3/16，HUD，静默）；
  2. `RegisterItemDecorationsEvent`、`EntityRenderersEvent.RegisterLayerDefinitions`（模型层已 bake，需要重建 `EntityModelSet`，成本高）。
