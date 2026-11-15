# Minecraft 模组热重载 / 热增删 可行性调研

- 调研日期：2026-11-14
- 调研范围：Java 版 Minecraft（Fabric / NeoForge / Forge），客户端与服务端
- 结论口径：**“能不能做到” + “代价多大” + “哪条路值得走”**

> **状态提示（2026-11-14）：** 本文是路线调研，部分建议已被后续决策取代。
> 当前目标（1.21.1 + NeoForge 首发）见 [status.md](status.md) 与 [ADR-0001](decisions/0001-target-1.21.1-neoforge.md)。
> 第 7 节的“先选目标”建议保留为历史背景，实际选择以 ADR 为准。

---

## 0. 结论摘要（TL;DR）

1. **能做到，而且已经有可用的真实实现。**
   - **Hotwire**（Fabric，客户端）：在游戏运行时关闭/开启/新增/更新模组，官方描述“大多数模组约 1 秒关闭、开启近乎瞬时”，已发布 `0.9.2-beta`，支持 1.21.1–1.21.11 与 26.1–26.3。
   - **NeoHotModInjector / NHMJ**（NeoForge 1.21，客户端）：运行时把新模组“注入”进已启动的游戏，重建 ModList、跑完 FML 生命周期、触发注册事件，但**只支持加，不支持卸载**。

2. **“热重载”不是一个二值问题，而是一个能力阶梯。**
   资源/数据 < 纯客户端逻辑模组 < 带 Mixin 布局变更的模组 < 注册表内容模组（方块/物品/维度）< 性能核心模组。
   前三级已经可以做到秒级；第四级在现有架构下**基本不可行**（除非换一条“整层重载”的路线）；第五级应直接拉黑。

3. **真正的难点不是“加载一个 jar”，而是四件事：**
   - JVM 只允许改方法体，**不允许增删字段/方法、改继承**；
   - Mixin 在类加载时把代码织进 MC 类，**没有原始字节就无法还原**；
   - 注册表在启动后被冻结，**删除条目会移动数值 ID，直接威胁存档与网络协议**；
   - 活对象、静态字段、线程、native 库、缓存会**把旧模组钉死在内存里**。

4. **三条可行路线**（详见第 4 节）：
   - **A. 类级热替换**：Hotwire 路线，秒级、兼容性有边界、工程量极大（Hotwire 约 11 万行）。
   - **B. 进程内软重启（classloader 代际）**：丢弃整个“游戏层”再新建一代，兼容性最好，代价约 5–30 秒；目前**没有**成熟的 MC 实现，是空白区。
   - **C. 数据/脚本层热重载**：资源包 + 数据包 + KubeJS/CraftTweaker + 选择性重载，秒级、成本最低、能力最窄。

5. **对“Mod-Reloader”这个项目的建议：**
   - 如果目标是**给玩家用的通用工具**：不要在 Fabric 上从零复刻 Hotwire；要么做 NeoForge 侧/跨加载器的互补品，要么只做“安全子集”。
   - 如果目标是**给自己/团队提效**：做“自己开发的模组”的热重载（A0/A1），2–4 周可出可用版本。
   - 如果目标是**研究/差异化**：B 路线（尤其是**专用服务端**的进程内软重启）是空白、价值最高、风险也最高。

---

## 1. 先把问题拆开：一次 Minecraft 启动到底做了什么

要判断“哪些能热、哪些不能”，先看启动流水线。下表把每个阶段与“运行时能否重做”对齐：

| # | 阶段 | 具体内容 | 运行时能否重做 | 备注 |
|---|---|---|---|---|
| 1 | JVM + LWJGL/GLFW + GL 上下文 | JVM 启动、窗口、OpenGL 上下文、音频设备 | 进程内可复用，但不能“换 JVM” | 这是重启里**最不该付**的成本 |
| 2 | 模组发现与依赖解析 | 扫描 `mods/`、读元数据、构建依赖图、构造 mod 对象 | 可以（NHMJ 已证明） | Fabric 用 `Knot`，NeoForge 用 ModLauncher/ModuleLayer |
| 3 | Mixin 应用 | 目标类**首次加载时**做字节码织入 | 只能“重跑一遍”，不能撤销 | 需要原始字节 + 全部 mixin 配置 |
| 4 | 注册表 bootstrap + freeze | 方块/物品/实体/音效/粒子等注册，然后冻结 | 加：极难；删：几乎不可行 | 数值 ID 会进入存档与网络包 |
| 5 | 资源加载 | 纹理、模型、blockstate、声音、语言、字体、shader | **可以**（F3+T / 资源重载） | 秒级到十几秒 |
| 6 | 数据包加载 | 配方、战利品、标签、函数、进度、数据包注册表 | **可以**（`/reload`） | 大型包 10 秒级；可选择性重载 |
| 7 | 世界加载 | 区块、实体、光照、存档 | 可以（存/读档） | 单人要重新进世界 |

**关键观察：**

- 重启耗时的大头通常在 **5/6/7 和 2/3**，而不是 JVM 本身。也就是说，“保留进程、只重做游戏层”在理论上有很大的收益空间。
- **2/3/4 是“一次性”的**：它们假设“启动期”存在，运行期没有对应的“撤销/重做”协议。所有热重载方案本质上都是在**伪造一个局部启动期**。
- **5/6 本来就有运行期协议**（资源重载、`/reload`），所以它们是所有方案里最便宜、最稳的部分。

---

## 2. 为什么“直接卸载一个模组”在 JVM 上不成立

### 2.1 硬约束一：类重定义只能改方法体

`java.lang.instrument.Instrumentation#redefineClasses` 的规范把细节交给了 JVMTI。JVMTI 的 `RedefineClasses` 明确写着：

> The redefinition may change method bodies, the constant pool and attributes (unless explicitly prohibited). **The redefinition must not add, remove or rename fields or methods, change the signatures of methods, change modifiers, or change inheritance.** The redefinition must not change the `NestHost`, `NestMembers`, `Record`, or `PermittedSubclasses` attributes.

翻译过来：

- ✅ 可以改：方法体、常量池、属性；
- ❌ 不可以：增删字段、增删方法、改方法签名、改修饰符、改父类/接口；
- ❌ 不可以：改 `NestHost` / `NestMembers` / `Record` / `PermittedSubclasses`。

这条约束是后面所有“sidecar / holder / trampoline”技巧的根源：**既然不能改布局，那就把“多出来的布局”搬到旁边的类里去。**

> 本机实测（Temurin 17, macOS arm64）：改方法体成功，单类约 **0.7–5 ms**（首次调用含一次性的校验/初始化开销，热身后亚毫秒级）；给类加一个方法被拒绝，异常为
> `UnsupportedOperationException: class redefinition failed: attempted to add a method`。
> 复现代码见 `experiments/self-attach-poc/`。

### 2.2 硬约束二：单个类不能被卸载，只有 ClassLoader 能“死”

JVM 卸载类的条件是：**定义它的 ClassLoader 不可达，且该 Class 的所有实例、Class 对象都不可达**。

因此：

- 想“卸载一个模组”，前提是**这个模组有自己独立的 ClassLoader**；
- 但 Fabric 把所有模组和游戏放进同一个 `Knot` ClassLoader；
- NeoForge/Forge 用 JPMS `ModuleLayer`，一个 layer 里的模块共享同一个 `ModuleClassLoader` 体系，且 layer 在启动时固定。

**结论：在现有加载器结构下，“卸载单个模组”没有干净的语义。** 要么做类级“假装卸载”（Hotwire），要么把整个游戏层一起换掉（路线 B）。

### 2.3 硬约束三：Mixin 是不可逆的织入

Mixin 在目标类**首次加载**时修改字节码，JVM 定义的是修改后的版本。原始字节**不会**被 JVM 保留。

要“把这一个模组的织入去掉”，你必须：

1. 在启动时**留底**每个类的原始字节；
2. 记录**哪个 mixin 落在哪个类的哪个方法/字段**（Mixin 会在合并成员上留下 `@MixinMerged` 注解，可以作为线索，但不足以反向还原）；
3. 用“原始字节 + 除该模组外的全部 mixin 配置”**重新跑一遍 Mixin**，得到“假装该模组从未安装”的字节码；
4. 再想办法让这份字节码通过 2.1 的布局检查。

Hotwire 的 `AppliedMixins` 就是读 `@MixinMerged` 来识别“这个类被哪些 mixin 改过”；`MixinCopy` 则是在一个**独立的、与游戏隔离的 ClassLoader** 里加载一份 Mixin，离线重跑织入。这是整个方案里最“脏”也最核心的部分。

### 2.4 硬约束四：注册表冻结与数值 ID

现代 Minecraft 的注册表（`BuiltInRegistries`）在 bootstrap 后 `freeze()`。冻结后：

- 再注册会抛 `IllegalStateException`（“Registry is already frozen”）；
- 注册表条目的**数值 ID** 会被网络同步、被存档引用、被其他模组缓存；
- 删除一个条目会让它后面的 ID 全部前移 → 存档里的方块状态、网络包、`Holder` 引用全部错位。

**方块/物品尤其致命**：区块里的方块状态是以“调色板 + 数值 ID”持久化的。删一个方块，等于让旧存档里的 ID 指向别的方块。

数据包注册表（`damage_type`、`enchantment`、`trim_pattern`、`banner_pattern` 等）是例外：它们由数据包加载，`/reload` 时可以重建；但 **worldgen 类注册表**（biome、structure、noise settings、dimension_type 等）在生成时已经“固化”进区块，重载无法追溯修改已生成的地形。

> 这一点在 ROD（Reload Only Data）的文档里有很清楚的边界说明：只支持“运行时查询型”注册表，worldgen 固化型直接拉黑，且客户端注册表需要重新连接才能看到变化。

### 2.5 硬约束五：静态状态、线程、缓存与“看不见的引用”

就算类能换，还有一层“对象世界”的问题：

- **注册型副作用**：事件监听器、按键绑定、包处理器、HUD 层、命令、配置屏幕、资源重载监听器……这些都被别的对象持有；
- **静态字段**：模组自己的 `static` 缓存、单例、`ThreadLocal`；
- **线程**：定时器、网络连接、异步 IO、协程；不能直接 `kill`，否则可能留下半完成状态；
- **native 库**：JNI 库绑定到 ClassLoader，换 loader 后重复加载会报 “already loaded in another classloader”；
- **反射/方法句柄/lambda**：缓存了 `Method`、`MethodHandle`、`LambdaMetafactory` 生成的类，指向旧类。

Hotwire 的应对是三层：

1. **RegistrationJournal**：把每个模组做过的注册逐条记下来，关闭时逐条 `remove()`；
2. **Tripwire**：在每个模组方法的入口插一段“绊线”，当已关闭模组的代码仍被调用时能识别调用方并拦截/记录；
3. **OffWindow / LiveObjects**：处理“模组关闭期间产生的对象”，在重新开启时补做初始化。

这说明：**热重载的工程量，90% 不在“换字节码”，而在“收拾对象世界”。**

---

## 3. 现有实现解剖

### 3.1 Hotwire（Fabric，目前最完整）

- 平台：Fabric（另有 Ornithe 构建），**仅客户端**
- 版本：`0.9.2-beta`（2026-11-11），43 个版本，支持 1.21.1–1.21.11 / 26.1–26.3
- 规模：仓库约 **11.2 万行** Java/Kotlin（含完整 UI、Modrinth 商店、开发者模式）
- 许可：Modified Polyform Strict（源码可见，但**不是 OSI 开源许可，复用前必须逐条读许可**）

**工作流程（按代码结构归纳）：**

1. **拿到 `Instrumentation`**
   jar 的 manifest 声明 `Launcher-Agent-Class` 与 `Can-Redefine-Classes: true`；启动后用 `IMPL_LOOKUP` 调
   `appendToClassPathForInstrumentation`，再直接调 `sun.instrument.InstrumentationImpl.loadAgent`。
   这样绕开了 `jdk.attach` 的自附加限制，不需要 `-javaagent`，也不需要 `-Djdk.attach.allowAttachSelf`。

2. **启动时“记账”**
   - 每个类的原始字节；
   - 每个类被哪些 mixin 改过（`@MixinMerged`）；
   - 每个模组做过的注册（`RegistrationJournal`）；
   - 每个模组的类、依赖、入口点。

3. **关闭一个模组**
   用原始字节 + “除该模组外的全部 mixin”重跑 Mixin，得到“从未安装该模组”的类字节。

4. **让新字节通过 JVM 的布局检查**
   - 能对上的部分：普通 `redefineClasses`；
   - **多出来的方法**：搬到旁边的 hidden class（`Gui$hotwire`），用 nestmate 关系访问私有成员；
   - **多出来的字段**：搬到 holder（每个实例配一个伴生对象，weak map 持有）；
   - 所有引用这些成员的地方：用 `invokedynamic` 重写到新位置。

5. **清理注册 + 绊线**
   先应用字节码，再清理 `RegistrationJournal` 里的注册；`Tripwire` 负责发现“已关闭模组还在跑”。

6. **重新开启 / 新增**
   反向重建；新增时还要 `FabricLauncherBase.getLauncher().addToClassPath(jar)`，补跑入口点、资源与依赖。

**能力边界（官方 README 明确写了）：**

- 不碰 jar 文件（只有用户主动触发的更新，且退出时才写盘）；
- 不保证“模组写出的文件、加载的 native、写进别的模组的值”能还原；
- 自动拉黑：Fabric API、Hotwire 自身、Sodium、Lithium、Phosphor、FerriteCore、Ixeris、C2ME、ModernFix、VulkanMod
  （原因：这些模组大量变换核心类/渲染管线，重跑 Mixin 的代价与风险不可接受）；
- 注册表内容（方块/物品等）不在支持范围。

**性能（官方口径）：** 关闭约 0.5 秒，开启近乎瞬时；在 348 个模组的世界里对 AppleSkin 连续开关 500 次、对 Simple Voice Chat 连续开关 200 次，内存不增长、类数量只增加 JVM 自己生成的 lambda。

**它证明了什么：** 在**纯客户端、无注册表内容、非性能核心**的范围内，“秒级热增删模组”是工程上可交付的，不是论文。

### 3.2 NeoHotModInjector / NHMJ（NeoForge，只加不删）

- 平台：NeoForge 1.21，客户端；约 **2300 行**
- 许可：MIT（但 NATIVE 模式依赖闭源的 `JVMFlagHook.dll`）
- 依赖：MixinRuntime、SuperAccessTransformer、Better ModList

**工作流程：**

1. 用 FML 自己的 `JarModsDotTomlModFileReader` 读 jar，构造 `IModFile`；
2. 走 `ModValidator` → `BackgroundScanHandler` → `LoadingModList`，拿到“这次要加的模组”；
3. 把它们塞进 FML 的 game `ModuleLayer` / `ModuleClassLoader`；
4. 反射重建 `ModList`、`ModContainer`，逐个 `constructMod()`；
5. **手工补跑** FML 生命周期事件：`FMLConstructModEvent`、`FMLCommonSetupEvent`、`FMLClientSetupEvent`、`InterModEnqueue/Process`、`FMLLoadCompleteEvent`，以及 `RegisterPayloadHandlersEvent`、`RegisterMenuScreensEvent`、`RegisterKeyMappingsEvent`、`RegisterClientReloadListenersEvent` 等一长串客户端事件；
6. 触发 `RegisterEvent`（逐个注册表）、`NewRegistryEvent`、`DataPackRegistryEvent`、`ModifyRegistriesEvent`；
7. `Minecraft.reloadResourcePacks()`。

**Mixin 的两种模式：**

- **TRAMPOLINE（默认，纯 HotSpot）**：先尝试直接 `redefineClasses`；如果因为“新增方法/字段”被拒，就把新增成员抽到一个新定义的 `Owner$$MixinExt$N` 类里，重写 owner 的字节码去调用它。实例字段用 `WeakHashMap` 侧表，静态字段直接放在 ext 类；`super` 调用用 `MethodHandles.findSpecial` 包一层。
- **NATIVE（JBR）**：如果检测到 JetBrains Runtime，尝试启用 `-XX:+AllowEnhancedClassRedefinition`，然后直接整类重定义。**仅 Windows x64**（因为要在运行时改 JVM flag，用了闭源的 `JVMFlagHook.dll`），且要求 JBR 21.0.11。

**它证明了什么：** 在 NeoForge 上，“把 FML 的启动流水线在运行期重放一遍”是可行的；但**卸载**没有做，注册表内容不支持，且强依赖 FML 内部实现（版本升级会碎）。

### 3.3 MixinRuntime

NeoForge 的“运行时 Mixin 注册”基础设施：

- 自附加 agent 拿 `Instrumentation`；
- 打补丁修改 `MixinProcessor`、`ModuleClassLoader`、`ModLauncher`、`ClassTracker`；
- 把新的 `IMixinConfig` 动态塞进正在运行的 Mixin processor，并对已加载类 `retransform`。

它是 NHMJ 的前置，也说明“运行时注册 mixin 配置”本身已经是一个被解决的小问题。

### 3.4 旁证：三个“别人怎么做的”

| 系统 | 做法 | 对我们的意义 |
|---|---|---|
| **Bukkit / Plugman** | 每个插件一个 ClassLoader，靠反射调 `PluginManager` 卸载 | 插件有隔离才能卸载；MC 模组没有这层隔离 |
| **Spring Boot DevTools** | 基类加载器 + restart 类加载器；重启 = 丢弃 restart loader 再建一个 | **路线 B 的直接先例**：换一层 ClassLoader 比冷启动快得多 |
| **ROD（Reload Only Data）** | 把数据包内容拆成 recipes/tags/loot/functions/registry，按需重载 | 路线 C 的成熟形态；也量化了“哪些注册表能重载”的边界 |

---

## 4. 三条技术路线对比

### 4.1 路线 A：类级热替换（Hotwire 式）

**核心思想：** 不卸载模组，而是把“当前类”重建成“没有该模组时应该长成的样子”，再用 sidecar/holder/trampoline 把布局差异藏到 JVM 看不见的地方。

**能力：**

- 关闭/开启/更新/新增**纯客户端逻辑模组**；
- 支持新增方法、字段、接口（接口有额外限制）；
- 支持 mixin 增删（离线重跑）；
- 单次 0.5–2 秒。

**做不到：**

- 注册表内容（方块/物品/维度/生物群系）；
- 性能核心模组（Sodium/Lithium/…）；
- native 库、模组写出的文件、跨模组写入的值；
- 强依赖加载器内部实现，MC 版本升级需要持续适配。

**工程量：** Hotwire 约 11 万行（含 UI）。一个“只做安全子集”的版本，保守估计 **1.5–3 万行 + 6–12 人月**；一个“只给自己模组做开发期热重载”的版本，**3–5 千行 + 1–2 人月**。

**适合：** 有长期维护能力的团队，做玩家工具；或者开发者做自己的提效工具。

### 4.2 路线 B：进程内软重启（ClassLoader 代际）

**核心思想：** 把进程分成两层：

- **Boot 层**（父加载器）：窗口、GL 上下文、音频设备、输入、日志、崩溃上报、agent、看门狗；
- **Game 层**（子加载器）：Minecraft + 所有模组 + Mixin + 注册表 + 资源管理 + 集成服务器。

换模组时：**保存世界 → 丢弃整个 Game 层 → 新建一代 ClassLoader → 重新跑加载器 → 复用同一个窗口/GL 上下文重新初始化渲染 → 重载资源与世界。**

**为什么可能成：**

- 它等价于 Spring Boot DevTools 的 restart 模式：基类加载器已经预热，重启只重建应用层；
- GL 上下文和窗口可以留在 Boot 层，避免“重新创建窗口/重新编译 shader”的最大开销；
- 资源可以从磁盘/GPU 缓存快速重载；
- 世界存档可以快速重载（单人要重新进世界，但不用退游戏）。

**代价：** 约 **5–30 秒**（取决于资源包大小、世界大小、模组数量），仍然远小于完整重启（几十秒到几分钟），而且**兼容全部模组**（包括注册表内容）。

**难点（按风险排序）：**

1. **GL/窗口的所有权**：Minecraft 的 `RenderSystem`/`Window` 在 Game 层；要让新一层复用旧窗口，必须把窗口/上下文管理提到 Boot 层，并在新层里“接管”而不是“重建”。这需要 ASM/Mixin 改 `Window`/`RenderSystem` 的初始化路径。
2. **native 库**：LWJGL、zstd、sqlite 等 JNI 库绑定 ClassLoader；同一库在第二个 loader 里加载会失败。要么把 native 加载提到 Boot 层，要么用 `-Xbootclasspath`/共享库方案。
3. **Boot 层不能持有 Game 对象**：任何静态引用（回调、监听器、缓存）都会把旧一代钉住，导致类无法卸载、内存翻倍。
4. **线程与关闭钩子**：旧一代的线程必须优雅停止；`Runtime.addShutdownHook` 会累积。
5. **Forge/NeoForge 的 ModuleLayer**：FML 的 game layer 是启动时创建的；要能够“再建一层同名模块”，需要绕开 `ModuleLayerHandler` 的单例假设。Fabric 的 ClassLoader 模型反而更容易。
6. **单例与全局状态**：`Minecraft.getInstance()`、`BuiltInRegistries`、`SharedConstants` 都是静态单例；在新一层里它们是“新的一套”，这既是优点（干净），也是难点（Boot 层不能混用）。

**现状：** 截至调研，**没有**发现成熟的 MC 实现。这是一个空白区，也是一个高风险研究项目。

**降级版本（更务实）：只重启“集成服务器 + 资源”**，不换客户端类加载器。它可以覆盖“服务端逻辑 + 数据包注册表”类模组；但客户端注册表（方块/物品）仍然是全局的，所以**不能**覆盖新增方块/物品。适合做服务端工具，不适合做客户端通用工具。

### 4.3 路线 C：数据/脚本层热重载

**核心思想：** 不动代码，只重载“数据”和“脚本”。

- 资源包：`F3+T` / `Minecraft.reloadResourcePacks()`；
- 数据包：`/reload`（配方、战利品、标签、函数、进度、数据包注册表）；
- 选择性重载：ROD 的 `/reloadonly <target>`，避免全量 `/reload`；
- 脚本：KubeJS / CraftTweaker 自己的 reload；
- 模组配置：多数模组支持配置热重载。

**能力：** 内容型更新（配方、掉落、标签、进度、函数、部分注册表），秒级。
**做不到：** 新代码、新方块/物品、新维度、新机制。
**成本：** 最低；一个 `F3+T` + `/reload` 的封装可能只需要几百行。

**适合：** 整合包作者、服主、内容更新；也可以作为 A/B 路线的“第一层”——先热数据，再决定要不要动代码。

### 4.4 路线 D：蓝绿进程（补充）

预启动第二个 JVM（带新模组），加载完成后切换窗口/端口，关掉旧的。

- 对**服务器**：可行，停机时间 ≈ 存档保存 + 世界加载；但两个进程不能同时持有同一个世界锁。
- 对**客户端**：窗口/GL 上下文不能跨进程迁移，只能“新窗口顶掉旧窗口”，内存翻倍，体验割裂。
- 不是热重载，但是“明显小于重启”的工程兜底方案。

### 4.5 对比表

| 维度 | A 类级热替换 | B 进程内软重启 | C 数据/脚本 | D 蓝绿进程 |
|---|---|---|---|---|
| 单次耗时 | 0.5–2 s | 5–30 s | 0.5–10 s | 10–60 s（但可并行预热） |
| 新增/删除任意模组 | 部分（客户端、非注册表） | **可以**（理论上） | 不可以 | 可以 |
| 方块/物品/维度 | 不可以 | 可以（理论上） | 仅数据包注册表 | 可以 |
| 性能核心模组 | 拉黑 | 可以 | 不涉及 | 可以 |
| 实现复杂度 | 极高（10 万行级） | 高（研究型） | 低 | 中 |
| 主要风险 | 兼容性、版本适配、存档安全 | GL/native/单例、类卸载 | 能力有限 | 资源翻倍、世界锁 |
| 现有实现 | Hotwire、NHMJ | 截至调研未发现成熟实现 | ROD、KubeJS | 无通用实现 |
| 适合 | 玩家工具、开发提效 | 研究、服务端 | 整合包/服主 | 服务器运维 |

---

## 5. 关键工程难点清单（按难度排序）

| # | 难点 | 说明 | 难度 |
|---|---|---|---|
| 1 | 拿到 `Instrumentation` | 自附加；`IMPL_LOOKUP` + `InstrumentationImpl.loadAgent`；已在 PoC 验证 | ★★ |
| 2 | 原始字节与 mixin 归属 | 启动期 transformer/agent 记账；`@MixinMerged` 可辅助 | ★★★ |
| 3 | 离线重跑 Mixin | 需要独立 Mixin 副本、refmap、mapping、plugin、AW、版本适配 | ★★★★★ |
| 4 | 布局差异重写 | sidecar（nestmate）、holder（weak map）、`invokedynamic` 重定向 | ★★★★★ |
| 5 | 注册 journal 与清理 | 事件/按键/包/HUD/命令/配置/资源监听器 | ★★★★ |
| 6 | 线程、异步、泄漏 | 不能 kill；需要协作式停止 + 泄漏检测 | ★★★★ |
| 7 | 加载器集成 | Fabric `addToClassPath` + entrypoint；NeoForge ModuleLayer/ModList | ★★★★ |
| 8 | 注册表内容 | 冻结、数值 ID、存档调色板、网络同步 | ★★★★★（基本不可行） |
| 9 | 性能模组 | 深度变换核心类，重跑成本/风险不可控 | 拉黑 |
| 10 | 崩溃隔离与回滚 | 切换失败要能原子回滚，不能把游戏留在半死状态 | ★★★★ |

---

## 6. 成本估算

| 目标 | 范围 | 预估代码量 | 预估工期（1–2 人） |
|---|---|---|---|
| 只做资源/数据/配置热重载 | 路线 C | 0.5–2 k 行 | 1–2 周 |
| 只给自己开发的模组做热重载 | A0/A1 + JBR | 3–5 k 行 | 3–6 周 |
| 关闭/开启第三方客户端模组（安全子集） | A2/A3 | 1.5–3 万行 | 3–6 人月 |
| Hotwire 级（含 UI、商店、开发者模式） | A 全量 | 8–12 万行 | 9–18 人月 |
| 进程内软重启（客户端） | B | 2–4 万行 + 大量试错 | 6–12 人月（研究型） |
| 服务端软重启 | B 降级 | 1–2 万行 | 3–6 人月 |

> 这些是量级估算，不是承诺。Hotwire 作者的原话大意是：这个想法在技术上一直可行，只是没人做，因为它太疯了。

---

## 7. 建议：先选目标，再选路线

> 调研时的建议；2026-11-14 已拍板为 **1.21.1 + NeoForge 首发**，见 [ADR-0001](decisions/0001-target-1.21.1-neoforge.md)。

### 7.1 如果你想要“玩家可用的通用工具”

- **Fabric 客户端**：Hotwire 已经占了生态位，且是源码可见但许可严格。不要在它上面重复造轮子；可以考虑：
  - 做 **NeoForge 侧**的对应物（NHMJ 只加不删，且 NATIVE 仅 Windows，macOS/Linux 是空白）；
  - 做**安全子集**：只支持“无注册表、无核心 mixin”的模组，换取更高成功率；
  - 做**切换前 dry-run + 失败回滚 + 报告**，把“敢用”做成差异点。
- **服务端**：优先考虑路线 B 的降级版（重启集成服务器/服务端层），以及路线 D（蓝绿）。服主对“停机 10 秒”比对“热拔方块模组”更买账。

### 7.2 如果你想给自己/团队提效

走 **A0/A1**：

1. 用 JBR（macOS 有 aarch64 构建）+ `-XX:+AllowEnhancedClassRedefinition`；
2. 用 Mixin 自带的 java agent（`-javaagent:mixin.jar`）让 mixin 可热替换；
3. 只支持“方法体变更 + 资源/数据重载”，新增字段/方法交给 JBR；
4. 不做第三方模组，只做自己的模组——这样可以要求代码“热重载友好”（少用静态缓存、把状态放进可重建的 holder）。

这是投入产出比最高的路径，2–4 周可落地。

### 7.3 如果你想做研究/差异化

走 **B**，但把范围缩到**专用服务端**：

- 没有 GL/窗口/音频，最大的三个坑直接消失；
- 注册表内容模组是服务端最需要的（科技/魔法/维度）；
- 停机时间对服主是硬指标；
- 可以先做“进程内重启集成服务器 + 重建注册表”，再逐步扩展。

**90 天验证计划（服务端软重启）：**

| 阶段 | 时间 | 目标 | 验收 |
|---|---|---|---|
| P0 | 第 1–2 周 | 把服务端拆成 boot/game 两层，能启动、能停 | 原版服务端可跑；停止后类可卸载（`jcmd GC.class_histogram` 验证） |
| P1 | 第 3–4 周 | 同一 JVM 内重建 game 层，重载世界 | 第二次启动成功；无 native 重复加载错误 |
| P2 | 第 5–8 周 | 加/减一个无注册表模组 | 切换 < 30 s；世界无损 |
| P3 | 第 9–12 周 | 加/减一个带方块/物品的模组 | 注册表 ID 稳定；旧存档可读；客户端能同步 |

如果 P1 就卡在 native/单例上，及时止损，转 A 或 C。

### 7.4 如果只想“少重启几次”

路线 C + 一些工程优化就能拿到 80% 的体验：

- `F3+T` / `/reload` 的快捷入口；
- ROD 式选择性重载（配方/标签/战利品分开）；
- 模组配置热重载；
- JBR + Mixin agent 的开发期热替换；
- 启动优化（AppCDS、资源缓存、并行加载）把冷启动本身压下来。

---

## 8. 已完成的实验（本机）

**环境：** macOS arm64，Temurin 17.0.20.1，无任何 `-javaagent` / `-Djdk.attach.allowAttachSelf`。

> 环境提示：Minecraft 1.21.x 需要 Java 21；26.x 的工具链按 Hotwire 的配置使用 Java 25。系统注册的 JDK 只有 17/8，但 Gradle 的 foojay resolver 已把 Temurin 21 下载到 `~/.gradle/jdks/eclipse_adoptium-21-aarch64-os_x/jdk-21.0.12.1+1/Contents/Home`（Ex Nihilo 项目使用）；`/usr/libexec/java_home` 看不到它。要在 Gradle 之外启动 1.21.1，需要显式使用这个路径，或安装系统级 JDK 21 / JetBrains Runtime。

**实验内容：**

1. 用 `Launcher-Agent-Class` + `IMPL_LOOKUP` + `sun.instrument.InstrumentationImpl.loadAgent` 自附加，拿到 `Instrumentation`；
2. 对已加载类做方法体替换；
3. 尝试给类新增一个方法，观察 JVM 拒绝。

**结果：**

```
[agent] agentmain called, redefineSupported=true
instrumentation = sun.instrument.InstrumentationImpl@2ff4acd0
before: V1 add=5
after : V2-hotswapped add=105  redefine took 5.420417 ms
schema change rejected: java.lang.UnsupportedOperationException: class redefinition failed: attempted to add a method
```

（毫秒数会随机器与是否首次调用浮动，量级在亚毫秒到几毫秒。）

**结论：**

- 自附加在 stock JDK 上可行，不需要启动参数；
- 方法体替换是毫秒级（热身后可到亚毫秒），**“换字节码”本身不是瓶颈**；
- 布局变更确实被 JVM 拒绝，必须用 sidecar/holder/trampoline 或 JBR。

复现代码与运行脚本见 `experiments/self-attach-poc/`。

---

## 9. 风险与注意事项

| 风险 | 说明 | 缓解 |
|---|---|---|
| **JDK 政策收紧** | JEP 451 起，动态加载 agent 会告警；未来版本可能默认禁止 | 关注 JEP；准备 `-javaagent` 启动方案作为兜底 |
| **加载器内部实现漂移** | NHMJ 大量反射 FML 内部类，MC/NeoForge 升级即碎 | 锁定版本；做适配层；尽量用公开 API |
| **存档损坏** | 删除注册表条目、错误的 ID 重排 | 禁止删除注册表内容；切换前强制备份/存盘 |
| **崩溃不可回滚** | 类改到一半失败，游戏处于半死状态 | 先 dry-run；批量 `redefineClasses` 原子失败；健康检查 + revert |
| **许可** | Hotwire 是 Modified Polyform Strict，不能直接复用代码 | 只参考思路；自己实现 |
| **反作弊/平台** | 自附加、动态 agent 可能被某些平台视为可疑 | 明确告知用户；提供关闭开关 |
| **JBR 依赖** | NATIVE 模式需要 JBR + 启动参数；NHMJ 的运行时改 flag 仅 Windows | 在启动器层面加参数；macOS 不要依赖运行时改 flag |

---

## 10. 参考资料

**规范与文档**

- JVMTI `RedefineClasses`（类重定义约束）：<https://docs.oracle.com/en/java/javase/21/docs/specs/jvmti.html>
- `java.lang.instrument.Instrumentation`：<https://docs.oracle.com/en/java/javase/21/docs/api/java.instrument/java/lang/instrument/Instrumentation.html>
- JEP 451（动态加载 agent 的限制）：<https://openjdk.org/jeps/451>
- Fabric Wiki：应用更改而无需重启：<https://wiki.fabricmc.net/tutorial:hotswapping>
- Fabric Wiki：热替换 Mixin：<https://wiki.fabricmc.net/tutorial:mixin_hotswaps>
- Fabric 文档：Launching the Game：<https://docs.fabricmc.net/develop/getting-started/launching-the-game>
- Spring Boot DevTools（双 ClassLoader 重启）：<https://docs.spring.io/spring-boot/reference/using/devtools.html>
- JetBrains Runtime（`-XX:+AllowEnhancedClassRedefinition`）：<https://github.com/JetBrains/JetBrainsRuntime>
- DCEVM：<https://dcevm.github.io/>
- SpongePowered Mixin：<https://github.com/SpongePowered/Mixin>

**现有实现**

- Hotwire（Modrinth）：<https://modrinth.com/mod/irenehotwire>
- Hotwire（源码）：<https://github.com/dearIrene/hotwire-public>
- NeoHotModInjector（源码）：<https://github.com/yiyuyan/NeoHotModInjector>
- NeoHotModInjector（MC 百科）：<https://www.mcmod.cn/class/28611.html>
- MixinRuntime：<https://modrinth.com/mod/mixinruntime>
- Reload Only Data（ROD）：<https://www.mcmod.cn/class/28624.html>

---

## 附录 A：术语表

| 术语 | 含义 |
|---|---|
| **Mixin** | SpongePowered 的字节码织入框架；在类加载时把模组代码合并进目标类 |
| **AW / Access Widener** | Fabric 的访问拓宽机制，修改可见性/ final |
| **Knot** | Fabric 的自定义 ClassLoader / 启动器 |
| **ModLauncher / ModuleLayer** | Forge/NeoForge 的模块化启动框架 |
| **JVMTI** | JVM Tool Interface；`RedefineClasses` / `RetransformClasses` 的底层规范 |
| **DCEVM / JBR** | 支持“增强类重定义”的 JVM；可增删方法/字段 |
| **sidecar** | Hotwire 术语：存放新增方法的伴生类（nestmate） |
| **holder** | Hotwire 术语：存放新增字段的伴生对象（weak map） |
| **trampoline** | NHMJ 术语：把新增成员抽到 `Owner$$MixinExt$N` 并重写调用 |
| **tripwire** | 在模组方法入口插入的检查点，用于发现“已关闭模组仍在运行” |
| **dry-run** | 切换前预演，验证能否安全应用 |

## 附录 B：一页纸决策树

```
你想解决谁的痛点？
├─ 玩家：想在整合包里少重启
│  ├─ 只改配方/资源/数据 ──────────────→ 路线 C（1–2 周）
│  ├─ 想开关客户端模组 ───────────────→ 路线 A（3–6 人月，或直接用 Hotwire）
│  └─ 想加方块/维度模组 ──────────────→ 路线 B（研究型，6–12 人月）
├─ 模组开发者：想改代码不重启
│  └─ 自己的模组 + JBR + Mixin agent ───→ A0/A1（2–4 周）
└─ 服主：想减少停机
   ├─ 数据/配置 ─────────────────────→ 路线 C
   ├─ 无注册表模组 ──────────────────→ B 降级版（3–6 人月）
   └─ 任意模组 ──────────────────────→ D 蓝绿（最稳，但资源翻倍）
```
