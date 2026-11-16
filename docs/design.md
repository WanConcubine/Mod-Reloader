# 设计：分层重载（Tiered Reload）

> 本文写“架构与为什么”；当前做到哪一步看 [status.md](status.md)；术语看 [glossary.md](glossary.md)；真实场景看 [use-cases.md](use-cases.md)。

## 1. 目标与非目标

### 目标

让 Minecraft 模组在游戏运行时**安全地**增删 / 更新，并让用户知道**代价与理由**。

### 非目标（至少首发阶段）

- 不做 Modrinth 客户端 / 模组管理器（Hotwire 已经做得很好）。
- **不做功能模组集合**：不替代 LAN/语音/手柄模组的功能；B（LAN Doctor）仅 fallback（ADR-0005）。
- 不做作弊客户端、不绕过服务端校验。
- 不承诺多版本、多加载器同时支持。
- 不承诺“所有模组都能热换”——贵妃/皇后级就是要明确说“不行”（贵妃级目前是实验性部分支持）。

## 2. 目标

| 项 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| 分析器 | Fabric + NeoForge（语料锁 1.21.1） |
| 运行时首发 | NeoForge 1.21.1 |
| 首个 dogfood | Ex-Nihilo-Satellitium（贵妃级） |
| Java | 21 |

为什么是 NeoForge 1.21.1，见 [ADR-0001](decisions/0001-target-1.21.1-neoforge.md)。

## 3. 分级（嫔 / 妃 / 贵妃 / 皇后）

**分级 = 让这个变更生效所需的最低安全机制**，不是“成功保证”。

T0/T1（只有资源 / 只有数据）属于**原版能力**（资源重载、`/reload`），不进入本项目的分级；我们只处理「加/删/换模组」。

| 等级 | 含义 | 机制 | 典型 |
|---|---|---|---|
| **嫔** | 代码，无 mixin、无注册表 | 类加载 + 入口点 | 纯逻辑/命令 |
| **妃** | 代码 + Mixin，无注册表内容 | 类重定义 + 离线 re-mix + 布局拆分 | HUD、按键、JEI |
| **贵妃** | 注册表内容 | 实验性 add-only 注册表热加；删除/更新/完整生效仍需重启 | 方块/物品/维度 |
| **皇后** | 不支持热换 | 完整重启 | native、核心渲染、Sodium/Lithium、Fabric API |
| **T?** | 分析失败 | 人工检查 | 坏 jar |

关键区分：

- **贵妃 vs 皇后**：贵妃是“部分可热加（实验性 add-only），但删除/更新/存档一致性仍需重启”；皇后是“连软重启也不建议”。
- **等级是最低机制**：妃级的模组也可能因为静态状态/线程而失败；等级不替代 dry-run。
- **等级不是永久标签**：同一模组在 1.21.1 和 26.x 可能不同；先锁 1.21.1。
- 旧文档里的 T2/T3/T4/T5 = 嫔/妃/贵妃/皇后（历史记录不改写）。

## 4. 架构

### 当前（v0，只读）

```
mods/*.jar
   │
   ▼
[analyzer]  读 jar：元数据 / class 文件 / mixin 注解 / 方法引用 / JiJ
   │
   ▼
[Analysis]  信号：class 数、mixin 数、targets、registration、native、static、threads…
   │
   ▼
[rules]     → ModReport：tier + confidence + reasons
   │
   ├── CLI 表格 / JSON / Markdown
   └── 未来：游戏内 `/modreload why <mod>`
```

### A0（已实现，仅嫔级）

```
[ModReloader] → 读 jar（ModFile.identifyMods/identifyLanguage/compileContent）
              → Module 注入 game layer（boot+game reads）
              → 重建 ModList（ModList.of + setLoadedMods）
              → constructMod + 客户端生命周期事件
```

限制：无 mixin、无注册表内容、无依赖校验、无回滚。详见 [features/a0-hot-add.md](features/a0-hot-add.md)。

### A1.2（已实现，dev；JBR 路线）

```
[ModReloader] → 读 jar / identifyMods（含 [[mixins]]）
              → VALIDATE：AT / 缺依赖 / 注册审计（未重放的启动期事件；已知崩溃四类拒绝）
              → 抽取 META-INF/jarjar 嵌套 jar（JarInJar）
              → Module 注入 game layer（mod 的 module + 嵌套库的 module）
              → MixinSupport：addConfiguration → onSelect → prepare → postInitialise
              → 已加载 target：transform + JBR redefine
              → 未加载 target：首载织入（Class.forName(..., false)）
              → 重建 ModList → constructMod + 客户端生命周期事件
              → 资源包选中 + reloadResourcePacks()
```

失败回滚：`Rollback` LIFO undo；module / ModList / mixin config / target 原始字节 / 资源包各自注册 undo。post-ModList 与 post-pack 强制失败已验证；构造后的 `@Mod` 构造器/事件监听器不能注销，JVM 不能 undefine module（Module 对象缓存供重试，所以嵌套 jar 抽到按内容哈希命名的稳定路径，重试才能复用同一个 module）。

限制：只验证了 `lanserverproperties`（1 个妃级模组）；需要 JBR + MixinRuntime（第三方前置、`mr` 为 optional）；构造后 listener 回滚、存档回读、依赖校验、注册表内容未做。详见 [features/a1-mixin-hotadd.md](features/a1-mixin-hotadd.md)。

### 贵妃级（实验性 add-only：注册表 + datapack）

```
[ModReloader] → …（同上到 construct）
              → RegistryStage.begin   （GameData.unfreezeData，构造之前）
              → constructMod + 注册（DeferredRegister 挂监听）
              → RegistryStage.commit  （按注册表重放 RegisterEvent → 清 BlockState id → freeze）
              → 客户端：资源包 + 创造栏重建
              → RegistrySync.broadcastAfterCommit（推 id 快照给已连接客户端；客户端 commit 后反向拉）
              → DatapackStage.apply   （注入 pack → MinecraftServer.reloadResources）
              → 广播 recipe/tag 给已连接玩家
```

- **顺序不变量**：datapack 阶段必须在注册表提交之后——数据里的 item id 要对着已提交的注册表解析。
- **端无关**：`PlatformHooks.reloadDataPacks` 由两端各自取服务器（集成 / 专用）；不在服务器线程时投递过去再等。
- **线程不变量**：注册表的「清 `BLOCKSTATE_TO_ID_MAP` + `GameData.freezeData()`」**必须在服务器线程**上执行
  （`hotadd/ServerThreadHop`：已在服务器线程/还没有服务器时直跑，否则 `execute` + 等完成，超时回退 + warn）。
  那段清空到 bake 重建之间，`IdMapper` 对任何 BlockState 都给 -1，而区块序列化在服务器线程；
  在 render 线程上重建会写出带 `-1` 的 palette（status 已知问题 28）。`RegisterEvent` 重放不在这个约束里。
- **幂等**：pack id = `mod/<modId>`；已有数据（标题屏热加 + 世界加载已发现）直接短路。
- **客户端注册重放**：`ClientRegistrationReplay` 重放 key mappings / menu screens / renderers（并重建两个 dispatcher）/颜色 / 客户端扩展；`DEFAULT_TABS` 先清空再 `sortTabs()`（该方法非幂等）。
- **id 同步（多人）**：NeoForge 只在登录 configuration 阶段同步注册表，所以运行时自己开一条 play 通道（推 + 拉），
  客户端先用「两端条目集合一致」把关，再复用 `RegistryManager.applySnapshot`（登录期那段代码，含 blockstate bake）。
  两端必须热加同一批 jar；客户端不装 jar 的模式不支持。见 [ADR-0010](decisions/0010-post-connect-registry-sync.md)。
- **单向提交**：注册表无删除接口，datapack 只 add；失败后运行时标记 dirty、只能重启。详见 [features/t4-registry-hotadd.md](features/t4-registry-hotadd.md) / [features/t4-datapack-hotadd.md](features/t4-datapack-hotadd.md)、[ADR-0008](decisions/0008-t4-registry-adaptation-poc.md) / [ADR-0009](decisions/0009-datapack-hotadd.md)。

### Watcher / GUI（dev 初版）

```
[ModsFolderWatcher] 轮询 mods + modreloader/inbox，稳定指纹，启动基线
        │  新 jar
        ▼
[ModInspector] 读 mods.toml/依赖/mixin/registry/native → 预测嫔–皇后级
        │
        ▼
[ModReloaderScreen] 主菜单自动弹 / 世界中仅 toast；皇后级 Load 禁用（贵妃级带警告可加载）
        │  用户确认
        ▼
[LoadController] 单飞 → HotAddEngine.add → 成功 toast / LoadFailureScreen
```

配置在独立 JSON（`config/modreloader/watcher.json`；ignored/failed 指纹）。永不自动加载；皇后级只提示重启，贵妃级带警告实验性加载。UI 文本走 `en_us` / `zh_cn` 语言文件（`Component.translatable`）；Phase 3/4 再补批量/依赖排序和更多自动化。

### 双端化与引擎边界（2026-11-15）

```
[ModReloader]（两端都加载）
   ├── client/ClientBootstrap → ClientPlatformHooks · UI/命令/toast/资源包/tick → ServerCommands.install()
   └── server/ServerBootstrap → ServerPlatformHooks · 控制台命令 · ServerStartedEvent
                                    │
                     hotadd/HotAddEngine（不 import client/server）
                                    │
                 PlatformHooks：keyMappingCount / acceptClientLifecycle /
                                injectResourcePack / replayCommands / extraConnections
```

- **依赖方向单向**：引擎不认识两端，端把实现注册进来；`ActiveConnections` 只枚举两端共有的服务端玩家连接，客户端连接由 hook 追加。
- **注册审计（`VALIDATE`）**：`inspect/RegistrationAuditScanner` 只读扫 jar 的类引用，`Class.forName(...,false)` 判 `IModBusEvent`，
  按 covered / recurring / datagen / crash / behavior / silent / base 分级；`crash` 四类（粒子/配方书/shader/sprite source）在注入 module 之前拒绝
  （`FailureKind.CLIENT_REGISTRATION`），capabilities 等只 warn。分级规则本身是纯函数（`RegistrationAudit`），可无游戏单测。
  见 [features/client-registration-audit.md](features/client-registration-audit.md)。
- **Capabilities 先声明后使用**：启动时只读探测 module / modlist / network / mixin / registry / datapack / resource pack 的反射锚点，缺失时对应阶段 fail-fast（带 `data/` 的 jar 在变更前就检查 datapack 锚点），资源包只 warn + 跳过。
- **dev 不入发布包**：`src/dev/java` 是独立 source set；主源集只按名字反射 `DevEntry.install()`，所以生产 jar 里没有 harness，也不会被顺手调用。

### 未来（v1，可写）

```
[agent]      启动期：原始字节、mixin 落点、注册 journal
   │
   ▼
[reloader]   嫔/妃级：redefine / 离线 re-mix / sidecar / holder / indy
[restarter]  贵妃级：boot 层 + game 层，丢弃 game 层重建
[guard]      dry-run → 备份 → 应用 → 健康检查 → 失败回滚
```

### 模块边界

| 模块 | 职责 | 不做什么 |
|---|---|---|
| `analyzer` | 读 jar、提信号 | 不启动游戏、不写文件 |
| `rules` | 信号 → tier + 理由 | 不做 I/O |
| `agent` | 拿 `Instrumentation`、记账 | 不做业务判断 |
| `reloader` | 嫔/妃级字节码 | 不处理注册表内容 |
| `restarter` | 贵妃级整层重建 | 不原地改注册表 |
| `guard` | dry-run、备份、回滚 | 不决定 tier |

## 5. 不变量

1. **只读优先**：能用静态分析回答的，不启动游戏。
2. **写前必备份**：任何写操作（切 jar、改存档、换代际）前必须有可回滚点。
3. **不删注册表条目**：删除会移动数值 ID，直接威胁存档。
4. **等级必须解释**：不能只给“贵妃级”，要给“因为 `DeferredRegister#register` / 9 个 content 类 / 33 个 static”。
5. **版本/加载器/许可证变更需所有者同意**。
6. **预测 ≠ 验证**：文档和 UI 必须区分“静态预测”和“游戏内验证”。
7. **能力先声明后使用**：反射锚点缺失时提前失败并说人话，不静默跳过；锚点清单本身也要靠游戏内 harness 回归。

## 6. 技术约束（为什么不能更简单）

- **JVMTI 重定义**只能改方法体/常量池/属性；不能增删字段/方法、改继承。→ sidecar / holder / trampoline / JBR。
- **单个类不能卸载**；只有定义它的 ClassLoader 可死。→ 贵妃级完整生效必须整层重建。
- **Mixin 不可逆**：没有原始字节就无法还原。→ 启动期记账 + 离线 re-mix。
- **注册表冻结**：数值 ID 进存档/网络；删除条目的风险不可接受。
- **对象世界**：静态字段、线程、native、缓存会把旧模组钉住。→ journal + tripwire + leak 检测。

## 7. 为什么首发 NeoForge 1.21.1

- 作者自有 Ex Nihilo 就是 NeoForge 1.21.1（贵妃级），可 dogfood。
- 其 dev 整合包 9 个模组里 6 个贵妃/皇后级；妃级工具覆盖不足。
- NeoForge 1.21.1 是 1.21.1 上最大的模组生态（Modrinth 约 22.5k vs Fabric 20.3k）。
- NHMJ 只加不删、只做客户端；卸载/软重启仍是空白。
- 代价：FML/ModLauncher/ModuleLayer 更难。缓解：先做贵妃级整层重建，绕开 FML 内部细节。

## 8. 开放问题

1. **NeoForge `ModuleLayer` 能否在同一 JVM 里再建一代**？FML 的 `ModuleLayerHandler` 是否假设单例？
2. **客户端软重启的 GL/窗口所有权**：Window/RenderSystem 在 game 层，如何让新层复用旧窗口？
3. **native 库**：LWJGL/zstd 等能否在第二代 ClassLoader 中重新加载？是否需要提到 boot 层？
4. **注册表 ID 稳定性**：新代际重建后，旧存档的 block state ID 如何保证不漂移？
5. **ground truth**：如何低成本获得“这个模组真的能热换”的标注？dry-run + 自动化 harness？
6. **规则共享**：Python CLI 与 Java 模组如何共用同一套 tier 规则，避免漂移？
7. **服务端优先？** 专用服务端没有 GL/窗口，贵妃级软重启可能更容易，是否先做服务端？
