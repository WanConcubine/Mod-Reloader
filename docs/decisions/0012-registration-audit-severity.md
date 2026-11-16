# ADR-0012：注册审计分级——崩溃类变更前拒绝，行为类只 warn

- 状态：**Proposed**（实现与证据已就绪，待所有者确认后改 Accepted）
- 日期：2026-11-16
- 决策者：项目所有者（要求「漏了的事件不能靠用户反馈兜底」；分级与实现由 agent 完成）

## 背景

热加模组会错过 NeoForge 的启动期一次性事件（`IModBusEvent`）。我们已经用手写清单补了 14 个左右，
但 2026-11-16 的 AppleSkin 悬停崩溃证明**漏一个就是一类 bug**，而且失败形态从「静默少个 HUD」到「点一下崩」不等。

[features/client-registration-audit.md](../features/client-registration-audit.md) 的提案是：扫 jar 的类引用 →
找出它注册的 `IModBusEvent` → 减去「已重放 / 非一次性 / datagen」→ 在 `VALIDATE` 打清单，**已知致命项像 AT 那样变更前拒绝**。
提案把 `RegisterCapabilitiesEvent` 也算作「已知致命」。

但实测语料（16 个真实 jar）显示 `RegisterCapabilitiesEvent` 出现在 **5/16** 个模组里：众星捧月、ZeroCore2、AE2、
Mekanism、ExtremeReactors2——包括我们的 dogfood。它们注册 capability provider，但方块/物品/配方仍然可用；
真正坏掉的是「桶倒不进去 / 机器接不了管道」这类局部行为。按提案全部拒绝，会让内容模组在热加里几乎不可用。

## 决策

1. **审计在 `VALIDATE`、module 注入之前执行**，只读：不加载模组自己的类，只 `Class.forName(name, false)` 判
   `IModBusEvent.isAssignableFrom`。拒绝发生在任何状态变更之前，因此零副作用。
2. **四级处理**（`inspect/RegistrationAudit.Severity`）：
   - `crash`（粒子 / 配方书 / shader / sprite source）：**变更前拒绝**，`FailureKind.CLIENT_REGISTRATION`；
   - `behavior`（capabilities、默认组件、自注册 registry、属性/刷怪/POI、模型层）：**大声 warn，不拒绝**；
   - `silent`（HUD 层、物品装饰、维度特效…）：warn；
   - `covered` / `recurring` / `datagen` / `base` / `other-side`：只记录（`base` 是抽象基类，真正 fire 的是具体子类）。
3. **`covered` 是代码契约**：清单里的名字必须对应真实的重放实现（`ClientRegistrationReplay` / `FmlEvents` /
   `NetworkAdaptation` / `RegistryStage`），注释写明出处；`RegistrationAudit` 不 import 游戏类，规则可无游戏单测。
4. **启发式明确不追求完备**：只看类文件引用（含签名/指令/lambda），不看反射与字符串；只看外层 jar（不扫 JiJ）；
   事件类加载失败记 `unresolved`、不致命。它回答的是「这个模组注册面覆盖哪些启动期事件」，不是「它一定坏」。
5. **UI 预检暂不接审计**：拒绝发生在 `/modreloader add` 时，失败屏给出事件清单；把它接进 `ModInspection` 是后续工作。

## 后果

- 16 个真实 jar、129 处引用：61 covered / 24 recurring / 6 datagen / 19 silent / **15 behavior** / **3 crash** / 1 base。
  3 处 crash 全在 AE2 / Mekanism（本来就因 AT 被拒）→ **没有任何当前 PASS 的模组被新规则拒掉**，dogfood 仍可热加。
- 审计立刻指出一个既有真实缺口：众星捧月的 `RegisterCapabilitiesEvent` 从未重放，桶/坩埚/机器的 capability 没有登记
  （status 已知问题 26）。它现在是 warn，不是拒绝——用户可能忽略，所以重放被列为下一步第一项。
- 清单仍是手写的；审计只保证「漏了哪个是可报告、可排期的」，不保证「不会再漏」。
- 若未来把某个 `behavior` 事件补上重放，只需把名字从 `BEHAVIOR` 移到 `COVERED`；反之把确认会崩的移到 `CRASH` 即可。

## 备选（未采用）

1. **所有「已知致命」都拒绝（提案原文）**：会挡掉 5/16 个内容模组（含 dogfood），代价远大于收益。
2. **只报告、一律不拒绝**：AT/粒子这类失败会留到用户点开配方书/粒子生成时才爆，且那时已经无法干净回滚。
3. **反射把事件全重放一遍**：注册面各不相同、往真身重放会重复注册、有些事件启动后已关闭（见 feature 文档 §2）。
4. **静态白名单**（在代码里列死事件名，不扫 jar）：无法回答「这个模组用了哪些事件」，也就无法在变更前拦住。
