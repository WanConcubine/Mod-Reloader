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
| [0006](0006-t4-managed-relaunch.md) | T4 走 managed relaunch，不注入注册表 | Accepted |

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
