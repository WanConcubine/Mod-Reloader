# 无焦点启动客户端（dev-only JVM agent）

> 状态：**2026-11-17 已实现并在 macOS 实测**：`-PnoFocus=true` 下
> `[CompatCheck] window focused=false (noFocus=true)`，`hud` 场景仍 PASS。
> 还没有做逐秒前台 App 监测；**Windows/Linux 未实测**。
> 只影响 dev harness，不进 mod jar；默认不开，必须显式加参数。

## 1. 为什么

`./gradlew runClient` 会打开一个真实的 GLFW 窗口，GLFW 默认在显示窗口时把它设为前台
（`GLFW_FOCUS_ON_SHOW`），于是每次自动化验证都会把用户正在用的 App 抢走焦点。
连续跑多个 L1 场景时尤其烦。

目标不是“隐藏窗口”，而是：**窗口仍然可见、仍然渲染，但不成为前台 App**，这样：

- 自动化可以继续跑；
- 截图仍然有内容（窗口可见）；
- 用户的前台 App 不被切走。

## 2. 怎么做

- `src/noFocus/java/dev/modreloader/nofocus/NoFocusAgent.java`：dev-only JVM agent。
  - `premain` 注册 `ClassFileTransformer`；
  - 只处理 `com/mojang/blaze3d/platform/Window`；
  - 在构造器里，**紧跟在 `GLFW.glfwDefaultWindowHints()` 之后**插入
    `GLFW.glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE)`（131084, 0）。
    必须放在 `glfwDefaultWindowHints()` 之后，因为它会重置所有 hint；
  - 其余窗口 hint 不碰，窗口仍然可见。
- `build.gradle`
  - `-PnoFocus=true` → `runClient` 加 `-javaagent:build/noFocus/modreloader-no-focus-agent.jar`、
    `-Dmodreloader.noFocus=true`、`-Dfml.earlyprogresswindow=false`（early splash 是另一个可能抢焦点的窗口）；
  - agent jar 只由独立 source set 生成，**不在 `mods` block 里**，不进发布 jar；
  - agent 需要的 ASM 直接使用 Gradle 自带的 `asm-9.7.jar`，不联网解析依赖。
- `pipeline/run.py`
  - `run` 默认加 `-PnoFocus=true`；
  - `--visible` 是逃生口：交互调试、需要焦点/输入/截图的场景用它；
  - `report.json` 记录 `no_focus` / `window_focused` / `focus_ok`；
  - 如果请求了 no-focus 但窗口仍 `focused=true`，整体 verdict 是 **`FOCUS_STOLEN`**（失败），
    不会静默地认为“跑完了”。
- `CompatCheck` 在标题屏稳定后打印一次：
  `[CompatCheck] window focused=<bool> (noFocus=<bool>)`。

## 3. 实测证据

`python3 tools/pipeline/run.py run --scenario hud --mods-dir /tmp/modreloader-corpus`：

```
runClient uses the no-focus agent (window stays out of focus)
[no-focus] agent active: Window will be created without focus
[no-focus] patched com/mojang/blaze3d/platform/Window
[CompatCheck] window focused=false (noFocus=true)
[CompatCheck] RESULT appleskin... ok=true ... isLoaded=true
[CompatCheck] DONE 1/1
verdict: PASS
```

另外有一个纯单测 `NoFocusAgentTest`：用合成的 `Window` 字节码，断言
`glfwWindowHint(131084, 0)` 被插在 `glfwDefaultWindowHints` 之后；不需要游戏/GLFW。
构建时用 `./gradlew noFocusAgentJar` 可以单独产出 agent jar。

## 4. 自动化会不会带来意想不到的 bug？

会，主要不是“agent 把游戏改坏”，而是**无焦点改变运行环境**。已知风险与对策：

| 风险 | 表现 | 对策 |
|---|---|---|
| 暂停/失去焦点逻辑 | 单人世界可能因 `pauseOnLostFocus` 暂停，tick 不走，L2 断言超时 | L1 标题屏不受影响；L2/进世界用 `--visible`，或在测试配置里明确关闭 pause |
| 模组按焦点决定是否渲染/更新 | 小地图/HUD 可能跳过绘制，出现“PASS 但没画” | L2 功能/截图必须 `--visible`；L1 只断言热加路径 |
| 输入/按键/鼠标测试 | 无焦点窗口收不到真实输入 | 真实输入场景强制 `--visible`，不要用 no-focus |
| 截图 | 后台/被遮挡窗口可能不刷新或渲染节流 | 截图场景用 `--visible`；L1 不依赖截图 |
| agent 与游戏版本漂移 | 类名/构造器变了，hint 没插进去，焦点又被抢 | 单测 + 实跑日志 + pipeline `FOCUS_STOLEN` 失败；升级 MC 后先跑一次 no-focus 冒烟 |
| early splash 抢焦点 | 主窗口没焦点，但启动器 splash 先抢了一下 | 已加 `-Dfml.earlyprogresswindow=false`；这个无法从主窗口状态检测，升级 loader 后留意 |
| macOS 以外未验证 | Windows/Linux 的 GLFW 行为可能不同 | 只声称 macOS 实测；其它平台先跑一次冒烟 |
| 自动化本身 | 连续起客户端、内存/文件锁、残留进程 | pipeline `.run.lock` + 超时 + 一次只跑一个场景；仍然不要批量跑 |

**结论**：

- no-focus 适合 **L1（标题屏热加路径、构造、注册、资源包注入）**；
- **L2（进世界玩法、HUD 渲染、真实输入、截图）不要默认 no-focus**，用 `--visible`；
- 自动化能替代的是“重复点击/等待/抄日志”，不能替代“玩家真的在用这个窗口”的语义；
  断言要按这个前提写，否则会出现“机器 PASS、玩家打开是坏的”这种假阳性。

## 5. 用法

```bash
# 默认：无焦点（自动化）
python3 tools/pipeline/run.py run --scenario minimap --mods-dir /tmp/modreloader-corpus

# 交互/焦点敏感：正常窗口（会抢焦点）
python3 tools/pipeline/run.py run --scenario minimap --mods-dir /tmp/modreloader-corpus --visible

# 直接跑 Gradle
./gradlew runClient -PverifyCompat=<jar> -PnoFocus=true
./gradlew noFocusAgentJar          # 只构建 agent
```

## 6. 边界

- agent 是 dev-only：不在 mod jar、不在 `mods` block、不影响玩家；
- 只改 `GLFW_FOCUS_ON_SHOW`，不隐藏窗口、不改窗口大小/位置/渲染；
- 只验证 macOS（JBR 21）；Windows/Linux 未实测；
- `focused=false` 是 GLFW 的输入焦点状态；这次只断言了它，没有做逐秒前台 App 监测，
  所以不要把它扩大成“绝对没有任何瞬间激活”；
- 后续一次 late AT 运行里出现了 `focused=true`（pipeline 判 `FOCUS_STOLEN`）；原因未定位，
  可能是用户/OS 在窗口显示后主动激活。agent 只阻止“show 时主动抢焦点”，不阻止用户点击；
  自动化不要依赖它做输入/截图，且 `FOCUS_STOLEN` 必须当失败处理；
- 如果 agent 失败，pipeline 会以 `FOCUS_STOLEN` 失败，而不是继续跑；这是有意的 fail-closed。
