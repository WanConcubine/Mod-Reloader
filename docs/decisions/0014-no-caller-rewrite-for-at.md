# ADR-0014：不改写调用方来“模拟 AT”（Stage-0 否决）

- 状态：**Accepted**（所有者 2026-11-17 决定：收益不足以覆盖仍有 class-level 的 jar）
- 日期：2026-11-17
- 决策者：项目所有者（评估与实验由 agent 完成）

## 背景

stock JVM 不允许事后修改目标类的访问标志，所以“不靠 JBR 自己实现 AT”只剩一条路：
改写**调用方**（被热加 mod 的字节码）——把对私有成员的直接访问换成生成的静态访问器 +
缓存的 `MethodHandle`（[experiments/access-rewrite-poc](../../experiments/access-rewrite-poc/README.md)）。

Stage-0 fixture 已经证明这条路**在字段/方法/构造器上可行**（control 被 JVM 拒绝、改写后正确、
目标类保持 private、边界用例失败、热循环与直接调用同量级）。但它同时钉死了另一件事：

- **class-level AT（把 package-private 嵌套类变 public）无解**。那是类型引用，不是调用点；
  JVM 解析类型时按包检查，改调用方救不了。
- 语料 376 条 AT 规则里 337 条（89%）是字段/方法，但**按 jar 看**，大模组几乎都带 class-level：
  Jade 4、AE2 6、Mekanism 8（+2 构造器）、JourneyMap 7、JEI 2（+1 构造器）。**只要 jar 里有一条，
  整个 jar 在 stock JVM 上仍然没戏**。
- 即使没有 class-level，还有方法引用/`invokedynamic`、super 调用、写 final、描述符里的
  package-private 类型要检测和拒绝——这些是“改写第三方字节码”的长期风险，而 JBR late AT 不需要碰模组字节码。

## 决策

**不实现“改写调用方模拟 AT”**。AT 模组的处理维持现状：

1. stock JVM：AT 是硬边界，提示“把 jar 放进 `mods/` 后重启”；
2. JBR 21 + `-Dmodreloader.lateAt=true`：实验性 late AT，仅对已经是 JBR 的实例/开发 harness 有意义（默认关闭）；
3. 产品文案不承诺“支持 AT 模组”，也不把 late AT 当作玩家默认路径（玩家侧“重启装 jar”严格更优）。

实验代码保留在 `experiments/access-rewrite-poc/`，作为**结论的证据**与未来重做时的起点；**不接进运行时**。

## 后果

- stock JVM 上的热加覆盖面保持不变：无 AT 的嫔/妃、贵妃级 add-only 注册表、datapack、命令/payload 重放。
- 少了一整类风险：不再改写第三方模组的字节码（`invokedynamic`、栈帧、反射、性能都不需要再验证）。
- AT 模组的体验仍是“一次重启”（S2 路径），这在“反正要重启”的前提下与 late AT 等价甚至更优。
- 代价：那份 89% 的规则覆盖不会被兑现；含 class-level 的 jar 永远需要重启/JBR。

## 何时重新考虑

- 语料/用户诉求里出现**成规模**的“AT 只有字段/方法”的 jar（不是一个两个），且重启成本确实高；
- 或平台层出现变化（例如 JBR 成为默认 JVM、或出现官方支持的访问放宽机制），使“改目标类”不再需要 JVM 参数；
- 或改写层能在**检测阶段**就把不支持的形状全部拒掉，而不是“改完才发现”。

## 备选（未采用）

- 只支持字段/方法、含 class-level 的 jar 直接拒绝：覆盖面太小，且给玩家造成“有时能热加 AT、有时不能”的不一致预期。
- 用 bytecode 改写顺便处理类层次（继承 final 类）：需要重定义目标类，stock JVM 做不到。
- 把 JBR 打进发行物：体积/许可/更新成本不合适（此前已否）。
