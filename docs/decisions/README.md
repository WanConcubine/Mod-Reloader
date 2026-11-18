# 决策记录（ADR）

> 记录“为什么这样定”，不记录“现在做到哪”（那在 [status.md](../status.md)）。
> 新决定新增编号；旧 ADR 不改结论，只加 `Superseded by ADR-XXXX`。

| 编号 | 标题 | 状态 |
|---|---|---|
| [0001](0001-target-1.21.1-neoforge.md) | 目标锁定 1.21.1，运行时首发 NeoForge | Accepted |
| [0002](0002-tier-model.md) | 采用 T0–T5 分层模型 | Accepted |
| [0003](0003-analyzer-first.md) | 先只读分析器，再游戏内解释，最后 T4 软重启 | Accepted |
| [0004](0004-no-paper-short-term.md) | 短期不做论文，先做工具 | Accepted |
| [0005](0005-runtime-not-a-mod.md) | 我们是 runtime，不是 mod；A 优先，B 仅 fallback | Accepted |
| [0006](0006-t4-managed-relaunch.md) | T4 走 managed relaunch，不注入注册表 | Accepted（注册表部分被 0008 修订） |
| [0007](0007-dual-side-server-hotadd.md) | 双端热加：client 与 dedicated server 都要支持 | Accepted |
| [0008](0008-t4-registry-adaptation-poc.md) | T4 先做「注册表适配」评估；产品仍走 managed relaunch | Accepted（datapack 条款被 0009 取代） |
| [0009](0009-datapack-hotadd.md) | datapack 热加载走「注入 pack + 原版 `/reload`」 | Proposed（已实现并验证） |
| [0010](0010-post-connect-registry-sync.md) | 连接后的注册表 id 同步走「运行时自有 play 通道 + 复用 `applySnapshot`」 | Proposed（已实现并跨进程验证） |
| [0011](0011-hotadd-must-inform-clients.md) | 热加必须主动告知在线客户端（id/版本/jar/hash + 该怎么办） | Proposed（已实现并跨进程验证） |
| [0012](0012-registration-audit-severity.md) | 注册审计分级：崩溃类变更前拒绝，行为类只 warn | Proposed（已实现并用 16 个真实 jar 校准） |
| [0013](0013-late-coremod.md) | 热加 jar 的 coremod（`ICoreMod`）在独立 module layer 里晚注入；JBR 只在「已加载类 + 布局变更」时必需 | Proposed（jecharacters 拼音搜索功能验证通过） |
| [0014](0014-no-caller-rewrite-for-at.md) | 不改写调用方来“模拟 AT”（Stage-0 否决） | Accepted（所有者 2026-11-17） |
| [0015](0015-player-side-zero-tinkering.md) | 玩家侧零手工：能力由运行时自动准备，不删能力 | Accepted（所有者 2026-11-18 修正） |

## 模板

```markdown
# ADR-XXXX：标题

- 状态：Proposed / Accepted / Superseded by ADR-YYYY
- 日期：YYYY-MM-DD
- 决策者：

## 背景
## 决策
## 后果
## 备选（未采用）
```
