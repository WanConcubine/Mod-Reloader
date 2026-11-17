# 托管重启：保存世界 → 用同一套参数重新拉起游戏

> 状态：**A′ 已实现并验证 plan/spawn 路径**（macOS dev）；**B（用 JBR 重启）已实现，plan/spawn 验证过，端到端未验**。
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
   - 读取当前进程的 argv（`ProcessHandle.current().info().arguments()`），
     去掉上一次的 relaunch 标记，追加
     `-Dmodreloader.relaunched=true` 与 `-Dmodreloader.relaunched.waitForPid=<pid>`；
   - 在 `<gamedir>/modreloader/relaunch/relaunch-<ts>.sh|.cmd` 写脚本：
     **等待 2 秒 → cd 到游戏目录 → exec 原 java + 原参数**；
   - 用 `setsid /bin/sh`（有则用）或 `cmd /c start "" /min` 启动脚本，输出重定向到同名 `.log`；
   - **确认替代进程真的出现**：扫描进程命令行里带 `-Dmodreloader.relaunched.waitForPid=<pid>` 的 JVM，
     最多等 6 秒；没出现就**不退出**，把子进程日志尾部显示出来；
   - 成功后 `System.exit(0)`；
3. 子进程在 `ClientBootstrap.install()` 里调 `Relauncher.awaitPreviousProcess()`：
   看到 `waitForPid` 就轮询旧 PID，最多等 15 秒，确保不会两个 JVM 同时写实例目录。

## 3. B：用 JBR 重启（需显式确认）

- 只有当 `JbrSupport.installation()` 在本机找到 JBR 21、且当前 JVM 不是“JBR + enhanced redefinition”时，
  失败界面多一个「用 JBR 重启」按钮；
- 点击 → 确认屏（说明：只影响**本次会话**，下次启动仍由启动器决定 JVM）→ 用 `jbr/bin/java`
  + `-XX:+AllowEnhancedClassRedefinition`（若当前没有）重启；
- 不自动下载 JBR：体积、许可证、信任成本都不合适；找不到就只给路径/指引。

## 4. 边界（诚实）

- **启动器可能杀进程树**：若启动器用 Job Object（Windows）/进程组清理，新进程可能被一起带走。
  我们用 `start`/`setsid` 尽量脱离，并靠“扫描带标记的 JVM”确认；**Windows 未端到端验证**。
- **拿不到 argv**：某些平台 `ProcessHandle.info().arguments()` 为空 → 不显示按钮，退化为手动重启（文案说明原因）。
- **cmd.exe 引号**：参数含 `"`/换行时拒绝（Windows），避免拼出坏命令。
- **状态丢失**：单人已保存；多人只是断开。**“重启后回到原存档”没做**（ADR-0006 的第二步）。
- **B 不持久**：只影响本次会话；长期要让启动器把实例 Java 设为 JBR。
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

单元测试：`RelaunchScriptsTest`（POSIX 单引号/元字符、Windows 双引号与 `%` 翻倍、Windows 不可表示时拒绝）。

**未验证**：真实的“新游戏窗口起来、旧进程退出”全链路（需要人工在 Windows/PCL2 上点一次；它会退出客户端，不适合自动 harness）。
