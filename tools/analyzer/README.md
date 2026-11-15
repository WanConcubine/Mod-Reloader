# modreloader analyzer (v0)

> 校准目标：**Minecraft 1.21.1**；当前状态与表尺见 [docs/status.md](../../docs/status.md)。

只读的 Minecraft 模组「可重载性」分析器。它不启动游戏、不改字节码、不碰存档，只回答一个问题：

> **这个模组如果要在游戏运行时关掉/更新/加上，最低需要付到什么代价？**

输出是一个分级（T0–T5）加理由，例如：

```text
Scanned 7 jars
  loaders: fabric: 5  neoforge: 2
  tiers:   T3: 3  T4: 1  T5: 3
  hot-swappable (T0-T3): 3/7 (43%)
  needs restart (T4-T5): 4/7 (57%)

MOD                        VERSION    LOADER   TIER CONF  VERDICT
appleskin                  3.0.6+mc1… fabric   T3   0.59  hot-swap with caveats
jade                       15.10.6+n… neoforge T3   0.75  hot-swap with caveats
jei                        19.70.0.4… fabric   T3   0.65  hot-swap with caveats
sophisticatedbackpacks     3.26.21    neoforge T4   0.84  needs world/game-layer restart
fabric-api                 0.116.18+… fabric   T5   0.85  needs full restart
lithium                    0.15.4+mc… fabric   T5   0.95  needs full restart
sodium                     0.8.13+mc… fabric   T5   0.85  needs full restart
```

（真实语料上的首轮结果。T4 的 Sophisticated Backpacks 被抓到 `DeferredRegister#register`；Jade 虽然读注册表，但没有注册调用，所以留在 T3。）

## 运行

要求 Python 3.9+，无第三方依赖。

```bash
# 扫描一个 mods 目录
python3 tools/analyzer/run.py scan /path/to/instance/mods

# 只扫一个 jar，输出 JSON / Markdown
python3 tools/analyzer/run.py scan /path/to/mod.jar --json report.json --md report.md

# 看每个模组的理由
python3 tools/analyzer/run.py scan /path/to/mods --verbose

# 递归扫描（例如 Prism/MultiMC 的实例目录）
python3 tools/analyzer/run.py scan /path/to/instance --recursive
```

## Tier 定义

| Tier | 含义 | 判定依据（v0） | 结论 |
|---|---|---|---|
| **T0** | 只有资源（assets） | 无类、无 data | 资源重载即可 |
| **T1** | 只有数据（data） | 无类、有 data | `/reload` 即可 |
| **T2** | 只有代码，无 mixin、无注册表 | 有类，但无 mixin/registry 信号 | 可热加/更新；移除受类卸载限制 |
| **T3** | 代码 + Mixin，无注册表内容 | 有 mixin 类，registry/content 信号不足 | 可热换，但有兼容性风险 |
| **T4** | 注册表内容（方块/物品/实体/维度） | registry + content 信号 | 需要世界/游戏层重启（软重启候选） |
| **T5** | 不支持热换 | native、核心渲染 mixin、已知性能/库模组 | 必须完整重启 |
| **T?** | 分析失败 | 不是有效 jar / 无法读取 | 需要人工检查 |

> Tier 是「最低安全代价」，不是「一定能成功」。真正的切换还要做 dry-run、注册清理、健康检查——这些是后续版本的事。

## 它看哪些信号

从每个 jar 里提取：

- **加载器元数据**：`fabric.mod.json`、`quilt.mod.json`、`META-INF/neoforge.mods.toml`、`META-INF/mods.toml`、`mcmod.info`
- **Mixin**：config 列表、mixin 类、`@Mixin` 注解里的 target（直接解析 class 文件注解，不靠类名猜）
- **注入方式**：`@Redirect` / `@Overwrite` / `@ModifyVariable` / `@ModifyArg` / `@ModifyConstant` / `@Accessor` 等
- **注册表信号**：`BuiltInRegistries`、`DeferredRegister`、`RegisterEvent`、`RegistryObject` 等类引用
- **内容信号**：`Block`、`Item`、`BlockEntity`、`EntityType`、`MenuType`、`RecipeType`、`SoundEvent`、`Fluid` 等
- **对象世界风险**：可变静态字段数量、`Thread`/`Executor` 引用、`MethodHandles`/反射引用、native 方法
- **打包方式**：Jar-in-Jar（Fabric API 这类 bundle 会递归拆开分析）
- **资源/数据**：`assets/`、`data/` 是否存在

## 已知限制（v0，别当成结论）

1. **启发式，不是证明。** 规则是静态信号 + 小规模种子列表，会误判。唯一的 ground truth 是真实切换测试。
2. **Fabric 生产 jar 是 intermediary 名**（`net.minecraft.class_310`），核心类识别会退化；需要 yarn/intermediary 映射表才能精确。目前会把这类目标标出来并降低置信度。
3. **Jar-in-Jar 只拆一层**，嵌套再嵌套的 bundle 不会继续展开。
4. **不运行游戏**，因此看不到运行时注册、线程、native 实际加载、跨模组引用。
5. **种子列表是策略，不是测量**：`sodium`、`lithium` 等性能模组和 `fabric-api` 这类库模组被直接标成 T5，因为社区经验认为它们不该热换。
6. **不覆盖 NeoForge 的 FML 内部模块**，只按元数据识别。

## 测试

```bash
cd tools/analyzer
python3 -m unittest discover -s tests -v
```

## 下一步

- v0.1：加 yarn/intermediary 映射（至少核心类），提升 Fabric 目标识别
- v0.2：跑 200+ 模组语料，人工标注 50 个，输出准确率/混淆矩阵
- v0.3：把 analyzer 接进游戏内命令（`/modreload why <mod>`），复用同一套规则
- v0.4：dry-run 集成——用运行时证据修正静态预测

## 目录

```
tools/analyzer/
  run.py                  # 入口（无需安装）
  modreloader/
    classfile.py          # 最小 class 文件解析（常量池/字段/方法/注解）
    metadata.py           # 各加载器元数据 + mixin config 发现 + Jar-in-Jar
    mixins.py             # mixin config 读取、@Mixin target 解析
    analyzer.py           # 遍历 jar、聚合信号
    rules.py              # 启发式分级
    report.py             # 文本/JSON/Markdown 输出
    cli.py                # 命令行
    tomlmini.py           # mods.toml 的最小 TOML 子集解析
```
