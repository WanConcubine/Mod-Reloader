# ADR-0013：热加 jar 的 coremod（`ICoreMod`）在引擎里晚注入

- 状态：**Proposed**（已实现，jecharacters 拼音搜索功能验证通过；待所有者确认后改 Accepted）
- 日期：2026-11-16
- 决策者：项目所有者（实测「通用拼音搜索可加载但没有实际生效」；实现与验证由 agent 完成）

## 背景

NeoForge 的 coremod 走 `META-INF/services/net.neoforged.neoforgespi.coremod.ICoreMod`：
ModLauncher 在**类加载时**调用 `ICoreMod.getTransformers()`，把返回的 `ITransformer` 应用在目标类首次 define 之前。
热加的 jar 没有走这条路——启动时它不在 module path 上——所以它的 coremod 变换一次都没跑。

后果是**静默半失效**：`jecharacters` 4.5.15 能热加（module / ModList / 构造 / 资源包全 PASS，
见 [兼容性矩阵](../features/hotadd-compatibility-matrix.md) §3.2），但 JEI 的搜索类没被改写成
`Match.contains(...)`，拼音搜索完全不生效。这是「引擎 PASS ≠ 功能生效」最典型的一例。

同时实测发现 JBR 检查也过严：`HotAddEngine` 对**任何** mixin 都要求 JBR，而真正需要 enhanced redefinition 的
只有「已加载类的布局变更」。Mouse Tweaks 2.26.1 被这句提示挡住，用户看到的「需要 JBR 21+」本身没错，
但门禁的位置和说法都过头了。

## 决策

1. **coremod 晚注入**（`hotadd/LateCoremod`）：
   - 扫外层 jar **和** `META-INF/jarjar` 抽出的嵌套 jar（jecharacters 的 `ICoreMod` 在嵌套 jar 里）；
   - 每个声明 coremod 的 jar **复制**一份、写入唯一 `Automatic-Module-Name`，在**独立 `ModuleLayer`**里加载
     （子层、父层是 game layer，因此 FML/Minecraft/ASM/gson 可读）。这一步必须发生在 `ModuleInjector` 之前：
     同一个 jar 先注进 game layer 再解析子层会因同包冲突失败（`ResolutionException: ... exports package ...`，实测）；
     先建子层则完全不冲突（game layer 后加 module 不会再解析子层）。独立 layer 同时也修掉了「嵌套 coremod jar 与
     外层 mod 共享 `me.towdium.jecharacters` 包 → `targets.json` 查不到 → `JechCoreMod` 构造失败」的问题。
   - 目标应用：**已加载** → 读当前字节、变换、`redefineClasses`（回滚时 redefine 回去）；
     **未加载** → 安装 `ClassFileTransformer`，在该类 define 时变换（变换前字节也记下来，回滚同样 redefine 回去）。
     两条路径都记录到 `TRANSFORMED` 供 harness 断言；实测回滚把 4 个 at-load 目标全部恢复原字节。
   - 只覆盖 **class 级** `ITransformer`（节点类型 `ClassNode` / `byte[]`）；method/field 级、目标在别的 class loader
     的情况只报告 `skipped`，不假装成功。
   - 变换后布局发生变化且 JVM 不能增强重定义时，与 mixin 同一条规则：拒绝并说明目标。
2. **JBR 门禁收窄**：JBR 只在「已加载类 + 布局变更」时必需。删掉「有 mixin 就需要 JBR」的前置检查，
   逐目标判定放在 `MixinSupport`（与 coremod 共用规则）；错误消息点名目标与具体布局变更，并在本机找得到 JBR 时给出安装路径
   （`JbrSupport.hint()`）。`ModInspection` / 预检 / `HotAddWhy` 同步改为「可以尝试」。
3. **功能验证属于 coremod 的一部分**：每个 coremod 需要自己的功能断言（jecharacters 用 JEI 的私有 `matches`
   做 A/B：变换前 false、变换后 true）。「能热加」仍不等于「功能可用」。

## 后果

- 正面：一类原本静默失效的模组（coremod 类，语料里是 jecharacters）变成真的可用；JBR 不再误拦不需要它的 mixin 模组；
  失败消息从「需要 JBR」升级为「哪个目标、什么变更、JBR 在哪」。
- 代价：coremod jar 多一份模块层与一份复制文件（`<gamedir>/modreloader/coremods/<hash>/`），JVM 不能 undefine，
  重试按内容哈希复用；`ICoreMod` 构造/`getTransformers()` 仍在调用线程执行，异常会干净失败并回滚。
- 未覆盖：运行期**新增**的类才会走 on-load；若目标在热加前已加载且变换改布局，仍需 JBR；
  method/field 级 coremod、AT 式改写、coremod 依赖的其它启动期服务都不在范围内。

## 备选（未采用）

- **把嵌套 coremod jar 直接从 game layer 排除**：只对「coremod jar 不提供 mod 运行期需要的类」成立，
  jecharacters 恰好如此，但不能推广；且仍解决不了 `targets.json` 与同包模块的问题。
- **临时改 `ModuleClassLoader.packageLookup`** 让资源命中嵌套模块：能修 jecharacters 这一例，
  但依赖内部 map 的时序，且对「coremod 需要自己 layer」这一点没有帮助。
- **只文档化「coremod 不生效」**：与用户实测报告的期望不符；实测已证明晚注入可行。
