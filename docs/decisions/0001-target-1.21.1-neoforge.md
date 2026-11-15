# ADR-0001：目标锁定 Minecraft 1.21.1，运行时首发 NeoForge

- 状态：Accepted
- 日期：2026-11-14
- 决策者：项目所有者 + agent（建议）

## 背景

1. **生态数据**（Modrinth，2026-11-14，`total_hits` 近似）：

   | 版本 | 全部 | Fabric | NeoForge |
   |---|---:|---:|---:|
   | 1.21.1 | 33,455 | 20,330 | 22,518 |
   | 1.21.11 | 20,416 | 18,604 | 8,561 |
   | 26.3 | 10,778 | 10,208 | 5,736 |

   1.21.1 是唯一双加载器都超过 2 万的版本；NeoForge 侧甚至大于 Fabric。

2. **作者自有项目**：`Ex-Nihilo-Satellitium` 是 NeoForge 1.21.1 / Java 21 / ModDevGradle，正好是 T4 内容模组，可 dogfood。

3. **首轮实测**：该项目的 `run/mods` 有 9 个模组，其中 6 个是 T4/T5，只有 Jade 是 T3。Fabric 式 T3 热开关在这个生态里覆盖不足。

4. **痛点可量化**：该 dev 整合包从 ModLauncher 启动到进世界约 83 秒。

5. **prior art**：Hotwire 是 Fabric 客户端 T3；NHMJ 是 NeoForge 客户端只加不删。NeoForge 的“卸载 / 软重启”仍是空白。

## 决策

- **Minecraft 固定 1.21.1。**
- **分析器**支持 Fabric + NeoForge，但语料和校准只锁 1.21.1。
- **运行时首发 NeoForge 1.21.1**（不是 Fabric）。
- **首个 dogfood** 是 Ex-Nihilo-Satellitium。
- 多版本 / 多加载器运行时支持推迟到核心跑通之后。

## 后果

正面：

- 可以和自己的 mod 开发循环绑定，验证成本低。
- 直接面对最大的 NeoForge 1.21.1 市场。
- 避开 Hotwire 已占据的 Fabric T3 生态位。
- T4 软重启是真正的差异化。

负面：

- FML / ModLauncher / `ModuleLayer` 比 Fabric `Knot` 难，首版周期更长。
- NeoForge 版本升级可能破坏内部假设，需要适配层。
- 放弃（推迟）Fabric 的现成可行性验证，需要自己趟 FML。

缓解：

- 先做 **T4 整层重建**，绕开 FML 内部细节，而不是外科手术式类替换。
- 分析器保持双加载器，Fabric 只作为兼容项，不阻塞首发。

## 备选（未采用）

- **Fabric 1.21.1 首发**：技术最容易、Hotwire 已验证；但差异化最小，且无法 dogfood 作者自己的 NeoForge mod。
- **1.21.11 / 26.x 首发**：更新，但生态约为 1.21.1 的 1/2–1/3，版本漂移快。
- **1.20.1 首发**：存量仍大，但偏老、Java 17，未来迁移成本高。
- **纯加载器无关分析器，不做运行时**：最稳，但放弃核心价值（T4 软重启）。
