# JBR 使用指引（整合包作者 / 管理员 / 进阶）

> **普通玩家不需要读这篇**：游戏内点「自动准备 JBR」即可（运行时下载/校验/解压/重启，参数自动带）；
> **让玩家重启（切到 JBR 本身除外）是产品失败**：JBR 就位后仍只能靠重启加载的模组，按缺口记录/修复；「放 mods/ 重启」只是最后手段，不是正常路径（[ADR-0015](../decisions/0015-player-side-zero-tinkering.md)）。
> 面向整合包作者 / 服务端管理员 / 开发者；这篇只用于离线、手动或包作者预置的场景。技术细节与产品路线见
> [jbr-support.md](../features/jbr-support.md)；late AT 的边界见 [late-at.md](../features/late-at.md)。
>
> **玩家侧只有一句**：用 JBR 重启一次，之后需要 JBR 的模组就能热加；玩家不需要知道 flag/lateAt。
> 下面的能力表是**包作者/内部视角**，不是玩家界面。
>
> 一句话：**JBR 不是玩游戏的必需品，只在“热加需要改已加载类布局/访问标志”时才需要。**
> 如果你不热加这类模组，普通 JDK 21 完全够用。

## 可选扩展：MixinRuntime（mr）

Mixin/妃级热加可选启用 **MixinRuntime（modId `mr`，版本 >= 1.0.2）**。它是**可选扩展，不是前置**：

- 发布件**不打包、也不代下载**它；请把它放进 `mods/`（整合包作者应预置）。
- 缺失时：运行时照常启动（嫔级/普通热加不受影响）；妃级/Mixin 能力不可用，菜单右上角红字提示，
  点 Load 会在预检阶段被拦下，不会走到一半再失败。
- “为热加载增强环境做准备”只负责 JBR 与增强重定义，**不包含** MixinRuntime。


## 0. 先判断：你到底需不需要 JBR

| 你要做的事 | 需要 JBR 吗 |
|---|---|
| 正常启动、进世界、玩模组 | 不需要 |
| 热加无 mixin 的模组（嫔级） | 不需要 |
| 热加只改方法体、或目标类还没加载的 mixin | 不需要 |
| 热加**给已加载类加字段/接口/方法**的 mixin（如 Mouse Tweaks、Searchables） | **需要**：JBR 21 + `-XX:+AllowEnhancedClassRedefinition` |
| 热加带 **access transformer（AT）** 的模组 | **stock JVM：只能重启**；**JBR + late AT 是实验性可行**（Jade/JEI/Controlling 已 L1 通过，见 [late-at.md](../features/late-at.md)）。玩家不必手写参数：热加失败的界面里有「用 JBR 重启」，会在本次会话带上 `-XX:+AllowEnhancedClassRedefinition` 和 `-Dmodreloader.lateAt=true`，并先显示实验性警告；重启后自动把那个 jar 重新选中。只影响本次会话 |
| 热加注册表内容（方块/物品） | 不需要 JBR，但需要接受“存档会依赖该 mod、不可回滚” |
| 一次性启动事件（shader、recipe book 等） | JBR 也救不了；只能重启或等运行时补重放 |

**版本对应**：

- Minecraft **1.21.1** → **JBR 21**（Java 21）。
- Minecraft 1.20.1 → JBR 17（Java 17）。
- 选错大版本，游戏根本起不来；先看版本，再下载。

## 1. 五分钟快速路径

1. 从 JetBrains 官方 GitHub Releases 下载 **JBR 21**，解压到固定目录。
2. 找到 `<JBR>/bin/java`（Windows 是 `java.exe`）。解压到 `.minecraft` **同层**或 `.minecraft` 里面都行：
   游戏会先扫游戏目录和它的父目录（每次 `/modreloader jbr` 重新扫，不用重启就能看到新解压的目录）。
   **只认 Java 21**：当前 release 页默认给的是 JBR 25，Minecraft 1.21.1 不认，请到 releases 里找 `jbr-release-21.0.x`。
3. 在启动器里把**实例的 Java** 指到它。
4. 在**游戏 JVM 参数**里加 `-XX:+AllowEnhancedClassRedefinition`。
5. 完全重启游戏，用 `/modreloader jbr` 或日志确认 `enhancedRedefinition=true`。

> 第 3 步和第 4 步的顺序很重要：**先选 JBR，再加参数**。
> 参数只有 JBR 认识；加到普通 JDK 上，游戏会以
> `Unrecognized VM option 'AllowEnhancedClassRedefinition'` 直接起不来。

## 1.2 把 JBR 放在哪（让 Mod Reloader 能找到）

判断标准只有一条：**看 `mods/` 在哪**。有 `mods/` 的那个文件夹就是“游戏目录 / 实例目录”。

下面两种放法都能被自动检测到（`/modreloader jbr` 会显示路径）：

| 放法 | 例子（Windows） | 说明 |
|---|---|---|
| 放在实例目录**里面** | `%APPDATA%\.minecraft\jbr-21.0.11\` | 和 `mods/`、`config/` 同级 |
| 放在实例目录**旁边** | `...\minecraft\jbr-21.0.11\`（与 `.minecraft` 同层） | 适合不想改实例目录的人 |

- **不要**放进 `mods/`（那是模组目录，游戏不会把它当 JVM）。
- 目录名随意，但解压后里面必须有 `release` 文件；启动器里要选的是 `<JBR>/bin/java`（Windows `java.exe`）。
- 启动器示例（都指“实例目录”这一级）：
  - 官方启动器：`%APPDATA%\.minecraft\`
  - PCL2：默认 `.minecraft\`；开了版本隔离则是 `.minecraft\versions\<版本>\`
  - HMCL：你设置的游戏目录（默认 `.minecraft\`）
  - Prism / Modrinth App：`instances\<实例名>\.minecraft\` 或 `profiles\<实例名>\`
- 只认 **Java 21**：目录里 `release` 的 `JAVA_VERSION` 必须是 `21.x`；当前 release 页默认给 JBR 25，1.21.1 不认。
- 检测会看游戏目录的**祖辈目录**（最多 3 层），所以版本隔离（`.minecraft\versions\<版本>`）下也能找到同层的 JBR；
  zip 解压多套了一层（`jbr-21.../jbr-21.../release`）也会往下找一层。
- 终极兜底：JVM 参数加 `-Dmodreloader.jbr=<JBR 目录>`（或指向 `bin\java`），跳过自动扫描。

检测是**实时**的：游戏跑着再解压也行，下一次 `/modreloader jbr` 就能看到，不需要为了“被发现”重启。
但要让启动器真正用上它，还是要改实例 Java 并重启一次。

## 1.5 游戏内指引（找不到 JBR 时）

不用离开游戏也能拿到步骤。三个入口：

- **模组热加载列表**（主菜单右上角 logo）：当候选里有带 AT / 需要改类布局的 jar、而本机没有 JBR 时，
  底部会多出一个「如何安装 JBR」按钮。
- **加载失败界面**：本机没有 JBR 时，「用 JBR 重启」的位置会换成「如何安装 JBR」；有 JBR 时仍是
  「用 JBR 重启」（一键切换，见 [managed-relaunch.md](../features/managed-relaunch.md)）。
  **Windows 上也能用**：JDK 有时不暴露当前进程的命令行，运行时会用 PowerShell/WMIC 读 `Win32_Process.CommandLine`
  兜底；两者都被禁用/拦截时才会退回“请手动重启”。
- **`/modreloader jbr`**：诊断行里直接打印官方下载地址、要加的参数，以及“不装 JBR 就放 mods/ 重启”的兜底。

指引页里有「打开下载页」按钮（官方 GitHub Releases），并**始终写明兜底**：不装 JBR 也可以把 jar 放进
`mods/` 后重启，它会按正常模组加载——只是这次不能热加。这样玩家不会卡在“要 JBR”这四个字上。

## 2. 下载

- 官方来源：**JetBrains/JetBrainsRuntime 的 GitHub Releases**
  <https://github.com/JetBrains/JetBrainsRuntime/releases>
- **注意**：GitHub Release 页面本身不托管二进制资产；发布说明里的下载链接指向
  `cache-redirector.jetbrains.com/intellij-jbr/`（项目固定的是
  `jbr-release-21.0.11b1163.125`）。GitHub 页面用于人工核验版本/链接，不是第二个镜像。
- 选 **21.0.x** 的最新 release；不要选 17 或 11。
- 运行时自动准备**不会静默下载**：先显示官方发布页、实际下载 URL、固定 SHA-512 和安装目录，
  玩家点“允许并继续”后才下载、解压、重启；无固定资产的平台（Intel macOS）只能手动安装。
- 资源名一般长这样（以 release 页为准）：
  - macOS Apple Silicon：`jbrsdk-21.0.x-osx-aarch64-b....tar.gz`
  - macOS Intel：`jbrsdk-21.0.x-osx-x64-b....tar.gz`
  - Windows x64：`jbrsdk-21.0.x-windows-x64-b....zip`
  - Linux x64：`jbrsdk-21.0.x-linux-x64-b....tar.gz`
  - Linux aarch64：`jbrsdk-21.0.x-linux-aarch64-b....tar.gz`
- `jbrsdk` 包含 JDK，体积大一点；只跑游戏用 `jbr` 也够。拿不准就选 `jbrsdk`。
- **不要**从网盘/整合包里的“神秘 java”拿；JBR 是 JetBrains 的 OpenJDK 构建，
  但第三方便携包可能被改过。官方 release 页通常提供校验值，下载后可以核对。

解压后目录里应该有：

```
<JBR>/bin/java          # macOS/Linux
<JBR>/bin/java.exe      # Windows
<JBR>/release           # 里面有 JAVA_VERSION / IMPLEMENTOR
```

## 3. 安装（按系统）

### macOS（Apple Silicon / Intel）

```bash
# 解压后，目录通常叫 jbrsdk-21.../Contents/Home
# 放到用户级 Java 目录（可选，但启动器更好找）
mkdir -p ~/Library/Java/JavaVirtualMachines
mv /path/to/jbrsdk-21... ~/Library/Java/JavaVirtualMachines/jbr-21.jdk
# 最终 java 路径：
# ~/Library/Java/JavaVirtualMachines/jbr-21.jdk/Contents/Home/bin/java
```

- 如果 macOS 提示“无法打开，因为无法验证开发者”：

```bash
xattr -dr com.apple.quarantine ~/Library/Java/JavaVirtualMachines/jbr-21.jdk
```

- Apple Silicon 选 **aarch64**；Intel 选 **x64**。装错架构会走 Rosetta 或直接崩。

### Windows

1. 解压 zip 到固定目录，例如 `C:\Java\jbr-21`。
2. 确认存在 `C:\Java\jbr-21\bin\java.exe`。
3. 启动器里选这个 `java.exe`（不是文件夹，除非启动器明确要求目录）。

### Linux

```bash
sudo mkdir -p /opt/jbr-21
sudo tar -xzf jbrsdk-21.0.x-linux-x64-b....tar.gz -C /opt/jbr-21 --strip-components=1
/opt/jbr-21/bin/java -version
```

路径：`/opt/jbr-21/bin/java`。没有 root 就放到 `~/.jdks/jbr-21`。

## 4. 在启动器里选 JBR

原则只有一条：**改“这个实例运行游戏用的 Java”，不是启动器自己的 Java。**
不同启动器叫法不同，找这几个词：`Java 路径`、`Java 可执行文件`、`Java executable`、`JVM 参数`、`JVM arguments`。

| 启动器 | Java 路径 | JVM 参数 |
|---|---|---|
| **PCL2** | 设置 → Java → 手动选择 Java 路径；或版本设置里的 Java 路径 | 设置 → 启动设置 → JVM 参数（不同版本叫法可能不同） |
| **HMCL** | 全局设置 / 实例设置 → Java 路径 | 同一设置页的 JVM 参数 |
| **PrismLauncher** | Settings → Java → Add Java（或实例 Edit → Settings → Java） | 同一页的 JVM arguments |
| **官方启动器** | Installations → 实例 → Edit → More options → Java executable | 同一页的 JVM arguments |
| **Modrinth App** | Settings → Java → 自定义 Java 路径 | Settings → Java → JVM arguments |
| **CurseForge** | Settings → Game → Java → 自定义路径 | 同一页的 JVM arguments |

JVM 参数示例（保留你原有的内存参数，只追加 flag）：

```
-Xmx4G -XX:+AllowEnhancedClassRedefinition
```

注意：

1. **参数只加在“游戏 JVM”**，不要加到启动器自身的 JVM、Gradle、`JAVA_TOOL_OPTIONS`。
2. 参数是大小写敏感的；复制时不要带中文引号。
3. 改完**完全退出启动器再启动**，避免旧进程/旧配置。
4. 如果实例有“使用全局 Java 设置”的开关，确认实例没有覆盖成别的 Java。

## 5. 验证

### 5.1 先验证 Java 本身

```bash
<JBR>/bin/java -version
```

输出里应该有 **`JBR-21...`**，例如：

```
OpenJDK Runtime Environment JBR-21.0.11+10-1163.125-nomod
```

如果只有 `OpenJDK ... Temurin` / `Zulu` / `Microsoft`，说明你选的不是 JBR。

### 5.2 用仓库里的检查脚本

```bash
python3 tools/jbr_check.py
# 或检查指定路径
python3 tools/jbr_check.py --java ~/Library/Java/JavaVirtualMachines/jbr-21.jdk/Contents/Home/bin/java
```

它会列出本机找到的 JBR 21、对应的 `bin/java`、以及下一步该填什么。
它**看不到启动器实际选了哪个 JVM，也看不到游戏 JVM 参数**，所以最后仍以启动日志为准。

### 5.3 再验证游戏实际用的 JVM

启动游戏后看日志：

```
[ModReloader] loaded on client; JVM JetBrains s.r.o. 21.0.11; enhancedRedefinition=true
```

或者在游戏里执行：

```
/modreloader jbr
```

输出包括：

```
[JBR] java.home = ...
[JBR] vendor = ..., version = ...
[JBR] isJbr = true, AllowEnhancedClassRedefinition = true, layout redefinition = available
[JBR] next: ...
```

如果 `isJbr=true` 但 `enhancedRedefinition=false`：JBR 选对了，但**参数没加上**。
如果 `isJbr=false`：启动器实际还在用别的 Java。

> `/modreloader jbr` 是启发式（vendor + 参数）。真正的能力证明是热加时逐目标的 redefinition；
> 这个命令只用于快速排查，不要把它当成“JBR 已完美工作”的证明。

## 6. 常见问题

| 现象 | 原因 | 处理 |
|---|---|---|
| `Unrecognized VM option 'AllowEnhancedClassRedefinition'`，游戏起不来 | 参数加到了**非 JBR** 的 JVM 上 | 先删参数，确认启动器选的是 JBR，再加参数 |
| `UnsupportedClassVersionError ... class file version 65` | 用 Java 17 跑 1.21.1 | 换 **JBR 21** |
| 启动器报 Java 路径无效 / 一闪而过 | 选到了目录而不是可执行文件，或路径不存在 | 选 `<JBR>/bin/java`（Windows `java.exe`） |
| 选了 JBR，热加仍提示 `JBR_REQUIRED` | 参数没加、被后面的参数覆盖、或启动器覆盖了 Java | 看日志 `enhancedRedefinition=`；检查实例 Java 与 JVM 参数 |
| 日志 `JBR=yes, enhancedRedefinition=false` | 只选了 JBR，没加 flag | 加 `-XX:+AllowEnhancedClassRedefinition` 后重启 |
| macOS 提示无法验证开发者 | 下载后被 quarantine | `xattr -dr com.apple.quarantine <JBR路径>` |
| Apple Silicon 上很慢 / 崩溃 | 装了 x64 版走 Rosetta | 换 aarch64 版 |
| 改了 `JAVA_HOME`，启动器没变化 | 启动器有自己的 Java 设置，不读 `JAVA_HOME` | 在启动器实例设置里改 |
| 客户端好了，服务端仍失败 | 服务端是另一个 JVM | 服务端也要单独设 JBR + 参数 |
| JBR 已选，late AT 仍失败 | 目标类不可改、JBR 拒绝、或规则重写失败 | 看日志 `late AT failed` 的具体目标；不是所有 AT 都能晚到 |
| 游戏能启动，但某个模组仍要重启 | 不是布局问题，可能是 AT（未开 late AT）、一次性事件、coremod 等 | 看失败 `FailureKind`，不要一律归因于 JBR |

## 7. 服务端

专用服和客户端是两个 JVM，需要**分别**设置。

```bash
<JBR>/bin/java -XX:+AllowEnhancedClassRedefinition -Xmx4G -jar server.jar nogui
```

如果服务端由面板/脚本启动：

- 面板里改 **Java 路径** 为 `<JBR>/bin/java`；
- 在 **JVM 参数/启动参数** 里加 `-XX:+AllowEnhancedClassRedefinition`；
- 完全重启服务端，不要只 reload；
- 看服务端日志里的 `[ModReloader] JVM ... enhancedRedefinition=true`。

不要只改 `JAVA_HOME`：很多面板/脚本不读它。

## 8. 想撤销 / 换回普通 JDK

JBR 只是一个 Java 目录，不会改游戏文件：

1. 启动器实例的 Java 改回原来的 JDK；
2. 从 JVM 参数里删掉 `-XX:+AllowEnhancedClassRedefinition`（**必须删**，普通 JDK 不认识它）；
3. 可选：删除 JBR 目录。

换回普通 JDK 后，普通热加/游戏不受影响；只是“已加载类布局变更”和 late AT 会重新被拒绝。

## 9. FAQ

**Q：我只玩整合包，需要装 JBR 吗？**
不需要。只有你要热加“改已加载类布局”的模组时才需要；正常启动不受影响。

**Q：JBR 安全吗？**
它是 JetBrains 的 OpenJDK 构建（IntelliJ 同源）。从官方 GitHub Releases 下载并核对校验值即可。
ModReloader **不会**自动下载、打包或修改启动器配置。

**Q：为什么别人能热加，我不行？**
可能他用的是 JBR + 参数，或者那个模组的目标类在他那里还没加载。`/modreloader jbr` 能看出 JVM 差异。

**Q：JBR 能解决所有 AT 吗？**
不能。JBR 的 late AT 只覆盖**成员级** access/final（类级 `public-f` 去 final 会被拒绝并提示重启），且是实验性；目标不可改、规则不匹配、注册审计等仍会挡。
stock JVM 上 AT 仍然只能重启。

**Q：加参数后游戏打不开。**
删参数；你当前用的不是 JBR。先换 JBR，再加参数。

**Q：服务端也要装吗？**
如果服务端也要热加这类模组，要。客户端和服务端是两个 JVM，互不替代。

**Q：JBR 会不会影响性能？**
它仍是 OpenJDK 21；对本项目来说主要差异是 enhanced redefinition。没做性能基准，不要以“JBR 更快”为理由安装。

## 10. JBR 在本项目里具体解锁什么

- **妃级布局变更热加**：给已加载类加字段/接口/方法（Mouse Tweaks、Searchables）。
- **实验性 late AT**：改已加载类的 access/final（Jade/JEI/Controlling 已 L1 通过）。
- **不解决**：stock JVM 的 AT、一次性启动事件（如 shader）、coremod 的 method/field 级变换、
  已提交的注册表回滚、datapack 删除/更新。

技术细节与后续计划见 [jbr-support.md](../features/jbr-support.md)；
late AT 的实现、证据与边界见 [late-at.md](../features/late-at.md)；
当前状态以 [status.md](../status.md) 为准。
