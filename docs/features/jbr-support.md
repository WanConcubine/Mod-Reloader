# JBR 支持：自动准备 + 诊断与降级路径

> 状态：**已收口为单一动作「为热加载增强环境做准备」**（2026-11-18：`JbrSupport.status()` 三态 + 菜单左上角标识 +
> 复用已装/运行中的 JBR；`JbrProvisioner`/`JbrSetupScreen`/`/modreloader jbr`）。**所有者在 Windows 真机测试中**；
> 参数只能启动时给，本次会话有效（每会话最多一次自动重启，见 status 问题 55）；真实能力探针仍待做。
> 关联：[status.md](../status.md) 已知问题 7/25、[a1-mixin-hotadd.md](a1-mixin-hotadd.md) §10.4、[managed-relaunch.md](managed-relaunch.md)。
> **玩家/服务端操作步骤见 [JBR 使用指引](../guides/jbr.md)**；本文讲检测、边界与产品路线。

## 1. 先给结论

**JBR 不是玩游戏的必需品，只是热加的加速器。**

| 你要做的事 | 需要 JBR 吗 |
|---|---|
| 正常启动、进世界、玩模组 | 不需要 |
| 热加无 mixin 的模组（嫔级） | 不需要 |
| 热加只改方法体的 mixin（目标类还没加载，或布局不变） | 不需要 |
| 热加**给已加载类加字段/接口/方法**的 mixin | **需要**：JBR 21 + `-XX:+AllowEnhancedClassRedefinition` |
| 热加带 access transformer（AT）的模组 | **stock JVM：只能重启（按 ADR-0015 是产品失败）**；**JBR + `-PlateAt=true` 实验性 late AT** 可改已加载类的 access/final（Jade/JEI/Controlling 已 L1 PASS，见 [late-at.md](late-at.md)） |
| 热加注册表内容（方块/物品） | 不需要 JBR，但需要接受“存档会依赖该 mod、不可回滚” |

所以对玩家最重要的一句话是：**看到 `JBR_REQUIRED` 不等于“游戏坏了”，只表示“这个模组要改已加载类的布局，当前 JVM 做不了”**。
可以先跳过这个模组；要装它，再按下面的步骤换 JBR，或者选择重启。

## 1.1 玩家模型（一句话）

玩家只感知：**用 JBR 重启一次 → 需要 JBR 的模组就能热加**。他不区分 `-XX…` 和 `-D…lateAt`，
也不应看到这两个词；「额外参数」只存在于内部白名单证据与包作者文档。

## 2. 玩家决策树

### 2.1 热加失败，提示 `JBR_REQUIRED`

按代价从低到高：

1. **先不用这个模组**：忽略/关掉提示，游戏继续。已加载的其它模组不受影响。
2. **点「准备热加载环境」**（菜单未就绪时常驻；本机没有 JBR 时才下载）：运行时从 JetBrains 官方下载 JBR 21、校验 SHA-512、
   解压到游戏目录，然后自动用它重启；参数由运行时加，玩家不填。
   - 下载 95–200 MB，需要网络；失败保留游戏并显示原因，可重试。
3. **点「用 JBR 重启」**（本机已有 JBR 21 时出现）：只影响这一次会话；会保存世界/设置，确认新进程起来后才退出当前进程。
4. **放 `mods/` 后重启**（最后手段）：这类模组之后按正常模组加载，不再热加。按 ADR-0015 这是**产品失败**（JBR 切换除外），要记入缺口并修，不是正常产品路径。
5. 手动安装/手填参数只作为离线或包作者的兜底（§3），**不是玩家的必经步骤**。

### 2.2 加了 `-XX:+AllowEnhancedClassRedefinition` 后游戏起不来

**这个参数只有 JBR 认识。** 如果启动器实际用的还是普通 JDK，JVM 会在启动时报：

```
Unrecognized VM option 'AllowEnhancedClassRedefinition'
Error: Could not create the Java Virtual Machine.
```

处理：

1. 先把参数删掉，确认游戏能启动；
2. 确认启动器里选的 Java **确实是 JBR**（`java -version` 的输出里要有 `JBR`，见 §3.1）；
3. 再重新加参数。**不要**把参数加到启动器自身的 JVM、Gradle 守护进程或 `JAVA_TOOL_OPTIONS` 里。

### 2.3 选了 JBR，但游戏起不来

按顺序检查：

| 现象 | 原因 | 处理 |
|---|---|---|
| `UnsupportedClassVersionError ... class file version 65` | 用 Java 17 跑 1.21.1 | 换 **JBR 21**（1.21.1 需要 Java 21） |
| 启动器报 “Java 路径无效” / 一闪而过 | 选到了目录而不是可执行文件 | 选 `<JBR>/bin/java`（Windows 是 `java.exe`） |
| macOS：“无法打开，因为无法验证开发者” | 下载后被 quarantine | `xattr -dr com.apple.quarantine <JBR路径>` |
| Apple Silicon 上很慢 / 崩溃 | 装了 x64 版走 Rosetta | 换 **aarch64 / arm64** 版 |
| 游戏启动但热加仍说没有 JBR | 启动器覆盖了 Java；或参数被后面的 `-XX:-...` 覆盖 | 看日志里的 `[ModReloader] JVM ... enhancedRedefinition=` |
| 服务端同样失败 | 服务端是另一个 JVM | 服务端也要单独设 JBR + 参数 |

### 2.4 怎么确认“现在到底行不行”

游戏里执行：

```
/modreloader jbr
```

会打印：

```
[JBR] java.home = ...
[JBR] vendor = ..., version = ...
[JBR] isJbr = true, AllowEnhancedClassRedefinition = true, layout redefinition = available
[JBR] installed JBR 21 = ...（或 not found）
[JBR] next: ...
```

也可以看启动日志里的这一行（每次启动都有）：

```
[ModReloader] loaded on ...; JVM <vendor> <version>; enhancedRedefinition=<true|false>
```

> 这是**启发式**：vendor 是 JetBrains 且参数里出现 `AllowEnhancedClassRedefinition`。它不能证明 JBR 的补丁真的能改布局；
> 真正的证明是热加时逐目标的 redefinition。我们计划加一个“定义合成类 → 尝试加字段”的能力探针（§6 P0），
> 让 `/modreloader jbr` 给出 `supported / unsupported / unknown`，而不是只猜。

## 3. 安装 JBR 21（自动优先 / 手动兜底）

### 3.0 游戏内自动（推荐，玩家零手工）

- 监听列表/失败界面 → **「准备热加载环境」**：已装/运行中的 JBR 直接复用（跳过下载）；否则 `JbrProvisioner` 按平台下载官方 JBR 21.0.11
  （`cache-redirector.jetbrains.com/intellij-jbr/`，SHA-512 固定），解压到 `gameDir/jbr-21.0.11/`，
  然后 `JbrSetupScreen` 用新 JBR + 自动参数重启；失败保留游戏并显示阶段（下载/校验/解压/重启）。
- 已存在 JBR 时走「用 JBR 重启」，不重复下载。
- 整合包作者可以直接预置 `gameDir/jbr-21.0.11/`，玩家连下载都不需要。

### 3.1 手动下载（离线/包作者）

- 来源：JetBrains Runtime 的 GitHub Releases（`JetBrains/JetBrainsRuntime`）。
- 选 **JBR 21**（不是 17；1.21.1 需要 Java 21），并按系统/架构选：
  - Windows x64；
  - macOS **aarch64**（Apple Silicon）或 x64（Intel）；
  - Linux x64 / aarch64。
- 解压到固定目录（例如 `~/Library/Java/JavaVirtualMachines/jbr-21.jdk`、`C:\Java\jbr-21`）。
- 校验：`<JBR>/bin/java -version` 应包含 `JBR-21...`。

> 我们**不打包 JBR**；自动准备只从 JetBrains 官方下载 JBR 21（SHA-512 校验），或由整合包预置；不改启动器配置。

### 3.2 在启动器里选 Java

不同启动器叫法不同，目标都是“实例使用的 Java 可执行文件 = JBR 的 `bin/java`”：

- **PCL2**：设置 → Java → 手动选择 Java 路径；JVM 参数里加 `-XX:+AllowEnhancedClassRedefinition`。
- **HMCL**：全局设置 / 实例设置 → Java 路径；JVM 参数同理。
- **PrismLauncher**：Settings → Java → Add（或 Download）→ 选 JBR；JVM arguments 里加参数。
- **官方启动器**：Installations → 编辑 → More options → Java executable + JVM arguments。

要点：

1. **参数只加在“游戏 JVM”**，不是启动器自己的 JVM；
2. 改完完全退出启动器再启动（避免旧进程/旧配置）；
3. 用 `/modreloader jbr` 或启动日志确认 `enhancedRedefinition=true`；
4. 如果启动器有“使用全局 Java 设置”，确认实例没有覆盖它。

### 3.3 服务端

专用服和客户端是两个 JVM，需要分别设置：

```bash
<JBR>/bin/java -XX:+AllowEnhancedClassRedefinition -jar server.jar nogui
```

如果服务端由面板/脚本启动，改面板里的 Java 路径和 JVM 参数；不要只改 `JAVA_HOME`。

## 4. 我们已经做了什么

| 能力 | 落点 | 现状 |
|---|---|---|
| 玩家侧模型 | 统一能力 | 一次 JBR 重启带两个开关；玩家不感知 per-mod 参数（下表是内部视角） |
| 检测 JBR / 参数 | `inspect/JbrSupport` | vendor + `RuntimeMXBean` 参数；不执行真实 redefinition |
| 找本机 JBR 21 | `JbrSupport.installation()` | 扫 macOS / Windows / Linux 常见目录，校验 `release` 的 JetBrains + 21+ |
| 失败文案带路径 | `JbrSupport.hint()` | 告诉玩家“装在哪、去启动器选它” |
| 失败屏「用 JBR 重启」 | `ui/LoadFailureScreen` + `client/Relauncher` | 只在本机找到 JBR 且当前会话不是 JBR+参数时出现；保存世界 → 新进程 → 确认后才退出 |
| 逐目标判断而不是一刀切 | `hotadd/MixinSupport` | 只有“已加载类 + 布局变更”才拒绝；未加载目标由 Mixin 首载织入，免 JBR |
| 拒绝时点名目标与变更 | `FailureKind.JBR_REQUIRED` | 例：`AbstractContainerScreen` 加接口 |
| 诊断命令 | `/modreloader jbr` | 已实现；打印 JVM/参数/已安装 JBR/下一步 |
| 一键自动准备 | `client/JbrProvisioner` + `ui/JbrSetupScreen` | 官方 JBR 21.0.11、5 平台、SHA-512、zip/tar.gz、`gameDir/jbr-21.0.11/`；失败保留游戏 |
| Windows argv | `Relauncher.currentArguments()` | ProcessHandle → PowerShell/WMIC；PowerShell 强制 UTF-8，WMIC 用 native 编码 |
| 重启方式 | `Relauncher` | 直接 `ProcessBuilder`（`javaw`，cwd=gameDir），不再用 `.cmd`；子进程写 `started-<pid>.txt` |
| 参数位置 | `RelaunchScripts.withJvmFlags` | 插在主类前；识别 `--add-opens/--add-exports/--add-reads` 等带值选项 |
| dev 双路径 | `build.gradle` 的 `useJbr` / `-PnoJbr=true` / `-PjbrJava=` | `runClient`/`runServer` 默认 JBR；`-PnoJbr` 复现 stock JVM 路径 |

**实测（2026-11-17，pipeline L1）**：

- Mouse Tweaks 2.26.1 + JBR：`ok=true`、`isLoaded=true`，`redefined AbstractContainerScreen`。
- 同一 jar + `-PnoJbr=true`（JBR 但不加 flag，等价 stock 布局能力）：
  `ok=false stage=MIXIN kind=JBR_REQUIRED`，消息点名
  `AbstractContainerScreen adds interface(s) [yalter/mousetweaks/mixin/AbstractContainerScreenAccessor]`。
  这说明拒绝点是“布局变更 + 已加载类”，不是“有 mixin 就拒”。

**边界（诚实）**：

- `JbrSupport.available()` 是启发式，不证明真实能力；
- 「用 JBR 重启」只影响本次会话，且真实“新窗口起来、旧进程退出”只在 macOS dev 验证过 plan/spawn，Windows/PCL2 未端到端；
- 我们**不会**把 `-XX:+AllowEnhancedClassRedefinition` 自动加到非 JBR JVM 上（那会让游戏起不来）；
- JBR 的 late AT 只覆盖**成员级** access/final，且是实验路径（类级 `-f` 会被拒绝，见 [late-at.md](late-at.md) §5.1）；一次性启动事件、已提交的注册表回滚仍不能解决。

## 5. 玩家 FAQ

**Q：我只是想玩整合包，需要装 JBR 吗？**
不需要。只有当你热加一个“改已加载类布局”的模组时才需要；正常启动不受影响。

**Q：为什么同一个模组，别人能热加，我不行？**
大概率他用的是 JBR + 参数，或者那个模组在他那里走的是“目标类还没加载”的路径。`/modreloader jbr` 能看出差别。

**Q：JBR 安全吗？**
它是 JetBrains 的 OpenJDK 构建（IntelliJ 同源）。游戏内「自动准备 JBR」会从 JetBrains 官方下载并校验 SHA-512；不修改启动器配置。

**Q：我加了参数，游戏打不开了。**
删掉参数；你当前用的不是 JBR。先换 JBR，再加参数。

**Q：热加失败说 JBR，但我已经装了 JBR。**
看 `java.home` 是不是启动器实际用的那个；再看参数是否在游戏 JVM 上；最后看日志 `enhancedRedefinition=`。

**Q：服务端也要装吗？**
要，如果服务端也要热加这类模组。客户端和服务端是两个 JVM，互不替代。

**Q：JBR 能解决 access transformer 吗？**
**stock JVM 不能**；JBR 的 enhanced redefinition 可以改已加载类的 access/final，`-PlateAt=true` 的 late AT 在 Jade/JEI/Controlling 上已 L1 PASS。
但这仍是实验性、JBR-only、只覆盖成员级 access/final、没有 L2 功能证明；类级 `-f` 与不含 JBR 时这类模组仍必须重启。见 [late-at.md](late-at.md) 与 [matrix §9](hotadd-compatibility-matrix.md)。

## 6. 我们还能做什么（按优先级）

### P0：真实能力探针 + 诊断闭环

- 用 `Instrumentation` 定义一个一次性合成类，再尝试 `redefineClasses` 给它加一个字段/方法：
  - 成功 → **supported**；
  - `UnsupportedOperationException`/`UnsupportedClassVersionError` → **unsupported**；
  - 拿不到 Instrumentation → **unknown**。
- `/modreloader jbr` 输出 supported/unsupported，而不是 vendor+参数的推断；失败屏的「用 JBR 重启」也用它判断是否值得显示。
- 探针只碰一次性类，不碰游戏类；失败无副作用。
- 验收：JBR + 参数 → supported；`-PnoJbr=true` → unsupported；两种都写进 harness。

### P0：一键自动准备 JBR（2026-11-18 已实现，待 Windows 验证）

- 已实现：官方下载 + SHA-512 + 解压 + 用新 JBR 重启；玩家不填参数。
- 已验证：macOS aarch64 tar（94,837,906 字节）与 Windows x64 zip（94,352,754 字节）的 SHA-512 与固定值一致；真实 Windows zip 用 `installArchive` 解压后 `isRuntimeHome` 通过。
- **2026-11-18 macOS dev 端到端 PASS**：stock Temurin → 真实下载 90.44 MB → SHA-512 → 解压 `run/jbr-21.0.11` → `JbrSupport.installation()` 找到 → 用该 JBR 重启 → 子进程热加 Mouse Tweaks/Balm/Cloth Config，全部 `ok=true isLoaded=true`（`-PverifyJbrFlow`，结果文件 `run/modreloader/jbr-flow-result.txt`）。
- 未验证：Windows 真机 `javaw` 启动、PowerShell/WMIC 读取、PCL2 启动器、GUI 按钮点击。
- 仍未做：真实 redefine 能力探针（上一节）。

### P0：「装进 mods/ 并重启」降级路径

对不想装 JBR 的玩家，`JBR_REQUIRED` 的最现实修复不是换 JVM，而是**把 jar 放进 `mods/` 并重启**（AT 模组本来也只能这样）。
建议做成显式、可回滚的操作：

1. 失败屏/预检提供「安装到 mods/ 并重启」；
2. 先 dry-run + 备份同名文件 + 显示将写入的路径与 sha256；
3. 用户确认后写入，调用托管重启（A′）；
4. 失败可恢复原文件（备份清单）；
5. 与「用 JBR 重启」并列，文案说清区别：JBR = 不重启继续当前会话；装 mods/ = 重启一次、之后永久生效。

> 这条比“要求玩家装 JBR”更符合“玩家应该怎么办”：不是每个人都愿意换 JVM，但每个人都会重启。

### P1：持久化与启动器引导

- 把“这个实例建议用 JBR”写成一个可复制的指引文件（路径 + 参数 + 启动器步骤），而不是只弹一次；
- 记住玩家选择“本会话用 JBR”，下次不再重复问；
- 校验 JBR 版本/架构，给对应下载页；macOS 给 quarantine 命令；
- Windows/PCL2 的进程树/参数行为做真实端到端（当前只有 plan/spawn）。

### P1：服务端同路径

- 专用服也可能热加；`/modreloader jbr` 在服务端控制台同样可用；
- 面板场景给“启动命令 + 参数”的复制文本。

### P2：减少对 JBR 的依赖

- 软重启（ADR-0006）覆盖 AT/一次性事件/布局变更，是“大范围”的正解；
- sidecar/holder/trampoline 只对特定形状可行，不作为通用承诺。

## 7. 不做什么

- 不打包 JBR；自动准备只下载官方 JBR（校验），不修改启动器配置；
- 不把 JBR 说成“万能”：它只解决“已加载类 + 布局变更”这一类；
- 不在非 JBR JVM 上加 `-XX:+AllowEnhancedClassRedefinition`；
- 不用“JBR=已安装”推断“能力可用”（P0 探针之前，一律标注启发式）。
