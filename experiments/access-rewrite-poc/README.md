# access-rewrite-poc：不靠 JBR，自己“实现 AT”（Stage-0）

> **状态：已否决，不接进运行时**（[ADR-0014](../../docs/decisions/0014-no-caller-rewrite-for-at.md)）。
> 这条路在字段/方法/构造器上确实可行，但 class-level AT（package-private 嵌套类）无解，
> 而大模组几乎都带 class-level —— 覆盖面不足以抵消“改写第三方字节码”的长期风险。保留作为证据。

> 结论（先看这里）：**AT 的“字段 / 方法 / 构造器”子集可以在 stock JVM 上实现**——不改目标类，
> 只改写调用方（被热加 mod 自己的字节码）成生成的静态访问器 + 缓存 `MethodHandle`。
> **class-level AT（把 package-private 嵌套类变 public）无解**：那是类型引用，不是调用点，JVM 解析时按包检查。
> 这个实验就是用来钉死这两句话的；**不是**通用 AT 支持，也不能替代 JBR late AT。

## 问题

AT 只改**目标类**的访问标志，而 stock JVM 不允许事后改访问标志。于是只剩一条路：
把**调用方**里非法的直接访问改成合法调用——`GETFIELD C.f` → `INVOKESTATIC Accessors.get_f(C)`，
访问器内部用 `MethodHandles.privateLookupIn(C, lookup())` + `findGetter/findVirtual/...`，缓存后 `invokeExact`。

关键设计是**栈形状不变**：访问器描述符与原调用点完全一致（实例访问带 receiver），所以
不需要装箱、不需要重算 StackMapTable（只 `COMPUTE_MAXS`）。

## 怎么跑

```bash
cd experiments/access-rewrite-poc
./build.sh          # 需要 JDK 21；ASM 从 ~/.gradle/caches 里取（可用 ASM_ROOT 覆盖）
```

无游戏、无 JBR、无网络。

## fixture 结构

| 目录 | 作用 |
|---|---|
| `stub/demo/Target.java` | **编译期**的 AT 展开版（成员全 public）；只在 javac 时可见 |
| `src/demo/Target.java` | **运行期**的真身：private 字段/方法/构造器 + package-private 嵌套类 |
| `src/demo/mod/ModCode.java` | “mod 代码”，用 stub 编译 → 字节码里是对私有成员的直接访问 |
| `src/demo/mod/BadCode.java` | 引用 `Target.Hidden`（stub 里 public、真身 package-private），用来验证硬边界 |
| `src/demo/spike/AccessRewriter.java` | ASM 改写：字段/方法/构造器 + 生成访问器 |
| `src/demo/spike/Access.java` | MethodHandle 缓存 + `privateLookupIn` |
| `src/demo/spike/SpikeMain.java` | 四组断言 + 粗基准 |

## 实测结果（macOS，JDK 21.0.12，ASM 9.10.1）

```
[ok]   control: unrewritten mod is refused — IllegalAccessError: class demo.mod.ModCode tried to access private method 'void demo.Target.<init>(int)'
[ok]   rewritten mod runs — run()=7,12,19,7,27
[ok]   target stays private — field/method/ctor modifiers unchanged
[ok]   boundary: package-private type is refused — IllegalAccessError: failed to access class demo.Target$Hidden
[perf] rewritten private call: 0.60 ns/call (sum=4000000)
[perf] direct public call:      0.79 ns/call
RESULT: PASS
```

- **control 通过**：不洗字节码时，mod 的私有构造器访问被 JVM 拒绝——fixture 是有效的。
- **改写后正确**：私有构造器、实例/静态字段读写、实例/静态方法四类访问都按预期值返回。
- **目标类没变**：`secret` / `secretMethod` / 构造器仍然是 private。
- **边界成立**：`Target.Hidden` 是 package-private 嵌套类型，无论怎么改调用方都 `IllegalAccessError`。
- **性能**：热循环里访问器与直接 public 调用同量级（亚纳秒级，JIT 把 `invokeExact` 内联了；两次运行 0.59–0.60 vs 0.79–0.82 ns/call）；
  没有装箱、没有 megamorphic 分派。数字只作量级参考，不是正式 benchmark。

## 能覆盖 / 不能覆盖

| 形状 | 结果 | 备注 |
|---|---|---|
| 读/写 private/protected 实例或静态字段 | ✅ | 语料里 241/376 条 AT 规则 |
| 调 private/protected 实例或静态方法 | ✅ | 96/376 |
| 私有/受保护构造器（`new X(...)`） | ✅（straight-line） | 需要匹配 `NEW; DUP; args; INVOKESPECIAL`；参数里再 new 同类型需要数据流分析 |
| class-level：package-private 嵌套类型 | ❌ | 类型引用无法改写；`Font$StringRenderOutput`、`ModelPart$Vertex` 这类 |
| 继承 final 类 / 覆写 final 方法 | ❌ | 类层次问题，不是调用点 |
| 方法引用/lambda 指向私有成员（`invokedynamic`） | ❌（未实现） | 访问检查在 bootstrap；要检测并拒绝 |
| 写 static final / 依赖“去 final”的常量折叠 | ⚠️ | 语义脆弱，建议拒绝 |
| `invokespecial` 的 super 调用 | ❌（未实现） | 需要 `findSpecial` + 正确的 specialCaller；spike 里按 virtual 处理 |
| 未加载的目标类 / 非 unnamed module | ⚠️ | `privateLookupIn` 需要包对调用方开放；MC 在 unnamed module 时成立，需在真机确认 |

**按 jar 看**：语料里的大模组几乎都带 class-level 规则（Jade 4、AE2 6、Mekanism 8+2 构造器、
JourneyMap 7、JEI 2+1 构造器）——只要有一条，整个 jar 在 stock JVM 上仍然没戏。
所以这个子集的真实形态是“**AT 只放宽字段/方法**的模组”，不是“大部分 AT 模组”。

## 对 NoRestart 意味着什么

1. 这可以做成 **stock JVM 的第三条路**：`VALIDATE` 时把 AT 规则分类——
   - 只有字段/方法/构造器 → 走“改写调用方”，不要求 JBR、不要求重启；
   - 含 class-level → 明确拒绝，提示“需要重启或 JBR late AT”（不要给半吊子成功）。
2. 接入点：热加 mod 的类加载路径（我们已经有 ASM 和 coremod/transformer 这套基础设施）。
3. 必须补的检测（spike 没做）：`invokedynamic`/方法引用、super 调用、写 final、
   描述符/签名里出现被 AT 的 package-private 类型、目标类不在可开放模块。
4. **仍然不是** JBR late AT 的替代：class-level、类层次、布局变更都还是 JBR 的领域；
   late AT 对“已经是 JBR 的整合包/开发 harness”仍有价值。
5. 风险：我们在改 mod 的字节码（JBR 不改），`invokedynamic`、反射、栈帧、性能都要单独验证；
   spike 只证明“这条路存在且形状可控”，没有证明“改完能跑真实模组”。

## 下一步（如果继续）

- 接一个真实 jar（Controlling，1 条方法规则）在 stock JVM 上跑热加；
- 做规则分类器 + “不支持形状”的拒绝报告（先报告，不改）；
- 拿一个字段/方法较多的 jar（如 ExtremeReactors2 之外的对照语料）测覆盖率与失败点；
- 把 class-level 的 jar 在股票 JVM 上明确判“需要重启”，继续用 JBR late AT / 重启兜底。
