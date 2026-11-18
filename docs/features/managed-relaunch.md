# 托管重启：保存世界 → 用同一套参数重新拉起游戏

> 状态：**A′ 已实现并验证 plan/spawn 路径**（macOS dev）；**B（用 JBR 重启）已实现，plan/spawn 验证过，端到端未验**。
> 2026-11-17 补 B 的门槛收尾：JBR 重启在 AT 场景会**带 `-Dmodreloader.lateAt=true`**、记住待重试的 jar 并在下次会话**预选中**、
> 确认屏/失败屏加实验性警告；同时修了一个让上面这些**根本到不了子进程**的 bug（见 §3.1）。
> 相关决策：[ADR-0006](../decisions/0006-t4-managed-relaunch.md)（managed relaunch 的原始动机是 T4）。

## 1. 为什么需要

JVM **不能替换自己**。所以“让布局变更/AT/一次性启动事件的模组生效”永远意味着一个新进程：

- 布局变更（`JBR_REQUIRED`）、AT（`ACCESS_TRANSFORMER`）、未重放的启动期事件（`CLIENT_REGISTRATION`）、
  坏 coremod —— 这些失败的修复都是“带 jar 重启”；
- 用户已经在世界里，手动退游戏/找启动器/重新进世界很烦。

托管重启把最后一步自动化：**保存世界 → 生成脚本 → 启动新进程 → 退出当前进程**。
它**不碰 `mods/`**（安装 jar 是玩家/启动器的事），也不改启动器配置。

## 2. 机制（A′：同 JVM）

1. 失败界面在 `FailureAnalyzer.Report.restartRecommended()` 为真时显示「保存并重启游戏」；
2. 点击后：
   - 保存 `options`；单人会调 `IntegratedServer.saveEverything(true, true, true)`；
   - 读取当前进程 argv：`ProcessHandle.current().info().arguments()`；Windows 上它常为空，
     退到 PowerShell/WMIC 的 `Win32_Process.CommandLine`（PowerShell 强制 UTF-8，WMIC 用 native 编码；
     中文路径不会变成 `?`），并丢掉 argv[0]（java 路径）；
   - 去掉上一次的 relaunch 标记，把 `-Dmodreloader.relaunched=true` 与
     `-Dmodreloader.relaunched.waitForPid=<pid>` **插到主类之前**
     （`RelaunchScripts.withJvmFlags`；`--add-opens/--add-exports/--add-reads/--add-modules/-cp/-p` 等
     “值在下一个 token”的选项不会被拆开）；
   - **直接 `ProcessBuilder` 起 JVM**（Windows 用 `javaw.exe`，`directory(gameDir)`），
     输出写 `modreloader/relaunch/relaunch-<ts>.log`；**不再生成 `.cmd`**（cmd 会按 OEM 代码页重读，
     中文/`[]` 路径会变成“系统找不到指定的路径”）；
   - 子进程在 `ClientBootstrap.install()` 一开始写 `started-<pid>.txt`；旧进程看到该标记
     （或命令行里带标记的 JVM）才退出，最多等 12 秒；没看到就**不退出**并显示子进程日志尾部；
   - 成功后 `System.exit(0)`；
3. 子进程先 `Relauncher.signalStarted()` 再 `awaitPreviousProcess()`：
   轮询旧 PID 最多 15 秒，确保不会两个 JVM 同时写实例目录。
   `.sh/.cmd` 的生成仍保留给 dev harness（`-PverifyRelaunchPlan`），产品路径不用。

## 3. B：用 JBR 重启（需显式确认）

- 只有当 `JbrSupport.installation()` 在本机找到 JBR 21、且当前 JVM 不是“JBR + enhanced redefinition”时，
  失败界面多一个「用 JBR 重启」按钮；
- 点击 → 确认屏（说明：只影响**本次会话**，下次启动仍由启动器决定 JVM）→ 用 `jbr/bin/java`
  + `-XX:+AllowEnhancedClassRedefinition`（若当前没有）重启；
- **缺 JBR 时自动准备**（2026-11-18）：失败界面/watcher 显示「自动准备 JBR」→
  `JbrProvisioner` 从官方下载 JBR 21.0.11、校验 SHA-512、解压到 `gameDir/jbr-21.0.11/`，
  然后 `JbrSetupScreen` 用新 JBR 重启；**不需要玩家下载/解压/填参数**。
  下载或校验失败时游戏保持运行，显示阶段与原因；「手动安装」只是次级兜底
  （[JbrGuideScreen](../../src/main/java/dev/modreloader/ui/JbrGuideScreen.java) + 官方下载页）。
- **玩家可见文案只讲“重启一次”**：确认屏说“用 JBR 重启后，需要 JBR 的模组即可热加（仅本次会话）”，不展开 per-mod 参数；开关名只留在内部文档/日志。
- **JBR 重启一律开启实验性 late AT**：`Relauncher.jbrFlags(true)` 额外加
  `-Dmodreloader.lateAt=true`（否则从布局 jar 触发的重启会让 AT 模组在新会话里仍不可加载，
  且它们没法再触发一次 AT 重启）。确认屏会先展示警告（进程级、全局、成功后不还原；
  JBR 21 + L1 验证；只对可信 jar；其它门禁仍可能拒绝）。
- **记住待重试的 jar**：重启前 `WATCHER_CONFIG.clearFailed(jar)`（否则失败指纹会把 jar 从候选里藏掉），
  并写 `modreloader/pending-add.txt`；下次会话在标题屏由 `ClientBootstrap.resumePending` 消费，
  重新扫描并**预选**该 jar（只预选，不自动加载——注册表提交不可回滚），随后删掉文件。
  若该 jar 已在启动时加载（在 `mods/` 里），只弹一条「无需重试」toast。

### 3.1 顺手修掉的几个真 bug

- **JVM 参数的位置**：`--add-opens` 等“值在下一个 token”的选项没被识别时，
  模块说明符会被当成主类，`-Dmodreloader.*` 插到选项和值之间 →
  `Error: --add-opens requires modules to be specified`。`SEPARATE_VALUE` 已补
  `--add-opens/--add-exports/--add-reads`。
- **Windows argv 编码**：PowerShell 输出 GBK 却按 UTF-8 解码 → 中文目录变 `?` →
  `InvalidPathException: Illegal char <?>`。现在 PowerShell 强制 UTF-8，WMIC 用 native 编码。
- **成功判定**：原来扫描别的进程命令行，Windows 读不到 → 误报“replacement did not start”；
  现在由子进程自己写 `started-<pid>.txt`。
- **启动方式**：`.cmd` 被 cmd 的 OEM 代码页重读 → 中文/方括号路径“系统找不到指定的路径”；
  改为 `ProcessBuilder` 直接起 JVM。

> **历史**：`saveAndRestart` 原来把新增的 JVM 参数**追加在启动器命令行末尾**。JVM 在**主类处停止解析选项**，
所以 `-XX:+AllowEnhancedClassRedefinition` 和 `-Dmodreloader.relaunched.waitForPid=...` 都变成了
**程序参数**：子进程没有 enhanced redefinition，`awaitPreviousProcess()` 也永远读不到 `waitForPid`。
（旧 harness 只检查脚本/进程能起来 + 命令行里出现标记，所以没发现。）

现在由纯函数 `RelaunchScripts.withJvmFlags` 把参数**插到主类之前**，并识别 `-cp/--class-path/-p/--module-path/
--add-modules/--patch-module` 这类“值在下一个 token”的选项，以及 `-jar`/`--module` 形态。单测覆盖
vanilla、NeoForge userdev、`-jar`、`--module` 四种命令行。

## 4. 边界（诚实）

- **启动器可能杀进程树**：若启动器用 Job Object（Windows）/进程组清理，新进程可能被一起带走。
  现在用 `ProcessBuilder` 直接起 JVM（Windows `javaw.exe`），靠子进程自己写的
  `started-<pid>.txt` 确认；**Windows 未端到端验证**。
- **拿不到 argv**：某些平台 `ProcessHandle.info().arguments()` 为空 → 不显示按钮，退化为手动重启（文案说明原因）。
  **2026-11-17**：Windows 上 JDK 经常不暴露当前进程的 argv，已加 PowerShell/WMIC 兜底
  （`Win32_Process.CommandLine` → `RelaunchScripts.splitWindowsCommandLine`），两者可用时一键重启就能工作。
- **cmd.exe 引号/编码**：参数含 `"`/换行时拒绝（Windows），避免拼出坏命令。
  **2026-11-17 改为直接启动 JVM**：不再生成 `.cmd`（cmd 会用 OEM 代码页重读文件，
  中文/方括号路径会变成 `系统找不到指定的路径`）；`ProcessBuilder` 直接带 Unicode 路径、
  `directory(gameDir)`、Windows 用 `javaw.exe`（无控制台），输出写 `relaunch-*.log`；
  子进程仍然等旧 PID 退出（`awaitPreviousProcess`），成功判定靠它自己写的 `started-<pid>.txt` 标记。
  `writeScript`/`spawn` 保留给 dev harness（`-PverifyRelaunchPlan`）。
- **状态丢失**：单人已保存；多人只是断开。**“重启后回到原存档”没做**（ADR-0006 的第二步）。
- **B 不持久**：只影响本次会话；长期要让启动器把实例 Java 设为 JBR。`pending-add.txt` 也只消费一次。
- **警告只是文字，不是同意机制**：确认屏的警告不阻止玩家继续；late AT 仍是 L1、仍是进程级全局。
- 只在 **失败需要重启** 时出现；未知失败不自动重启（避免重启进同一个坏状态）。

## 5. 验证

```bash
# 只验证 plan + 脚本/spawn 路径，不会真的重启游戏（跑完自动关客户端）
./gradlew runClient -PverifyRelaunchPlan=true
```

dev（macOS，JBR 21）实测：

```
[RelaunchCheck] canRelaunch=true blocker=null
[RelaunchCheck] parent command line: 12 arg(s), executable .../jbr-21.jdk/Contents/Home/bin/java
[RelaunchCheck] plan script ./modreloader/relaunch/relaunch-<ts>.sh (16644 chars)
[RelaunchCheck] test spawn exited=true code=0 log=openjdk version "21.0.11"
[RelaunchCheck] JBR spawn exited=true code=0 log=openjdk version "21.0.11"
[RelaunchCheck] RESULT: PASS
```

单元测试：`RelaunchScriptsTest`（POSIX 单引号/元字符、Windows 双引号与 `%` 翻倍、Windows 不可表示时拒绝、
JVM 参数插到主类之前、`--add-opens` 空格形式不拆开、Windows 命令行拆分）、
`RelaunchFlagsTest`（stock/JBR/lateAt 的标志组合）、`PendingAddTest`（写/读/消费/空文件）、
`JbrProvisionerTest`（5 个平台资产各有 128 位 SHA-512、官方 URL、安装目录、平台分支）。

自动准备 JBR 的资产校验（macOS aarch64）：下载 94,837,906 字节，SHA-512 与固定值一致；
tar 顶层为 `.../Contents/Home`，`findHome` 深度 3 可覆盖。

**未验证**：真实的“新游戏窗口起来、旧进程退出”全链路；Windows 的下载(zip)/解压/`javaw` 重启/
PowerShell-WMIC 读取也未真机跑过（需要人工在 Windows/PCL2 上点一次；它会退出客户端，不适合自动 harness）。
