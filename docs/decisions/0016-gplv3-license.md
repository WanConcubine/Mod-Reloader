# ADR-0016：许可证改为 GPL-3.0-only（0.1.0-beta 起）

- 状态：**Accepted**（所有者 2026-11-22 指示）
- 日期：2026-11-22
- 决策者：项目所有者

## 背景

项目自首个发布起使用 MIT（`v0.1.0` tag 仍为 MIT）。所有者决定从 **0.1.0-beta** 起改用 GPLv3，
让运行时的修改与再分发保持开源。

## 决策

1. **0.1.0-beta 及以后使用 GPL-3.0-only**：`LICENSE` 放 GPLv3 全文（含版权声明），
   `gradle.properties` / `neoforge.mods.toml` / README 同步为 `GPL-3.0-only`。
2. **不追溯旧版本**：已发布的 `v0.1.0` tag 仍是 MIT；本次变更只影响 0.1.0-beta 及以后。
3. **发布 jar 内含许可证**：`META-INF/LICENSE`（构建时从仓库根复制）。
4. **不改变既有边界**：不分发 Minecraft / 第三方模组本体；第三方模组与依赖保留各自许可证；
   Hotwire 等只作 prior art，不复制代码。

## 后果

- 再分发本运行时或其衍生作品时，需要按 GPLv3 提供对应源码与许可证文本。
- MIT 与 GPLv3 兼容：本项目自己的代码可以整体改为 GPLv3；旧 MIT 版本不受影响。
- 运行时依赖的 NeoForge / MixinRuntime 等仍按各自许可证使用，不因本项目换证而改变。

## 备选（未采用）

- 继续 MIT（所有者选择换证）。
- `GPL-3.0-or-later`（本次明确为 `only`）。
- LGPL（运行时不作为库链接，采用 GPL 更直接）。