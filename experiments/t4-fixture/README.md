# T4 registry / datapack fixture

一个最小 T4 夹具：通过 `DeferredRegister.Blocks` / `DeferredRegister.Items` 注册
`t4fixture:test_block` 和它的 `BlockItem`，然后在 `FMLCommonSetupEvent` 里从
`BuiltInRegistries` 读回 entry id 与 BlockState id。

它还带一份最小 **datapack**：`data/t4fixture/{recipe,loot_table,tags}`（一条配方、
一张方块战利品表、一个方块标签），用来验证数据包热加载 —— 配方/战利品/标签是否
在不重启的情况下进入当前服务器。

用于验证：
- [T4 注册表热加](../../docs/features/t4-registry-hotadd.md)：热加 → 放置 → 存盘 → 正常启动读档；
- [T4 datapack 热加载](../../docs/features/t4-datapack-hotadd.md)：热加后配方/战利品/标签立即生效，
  且把 jar 放进 `mods/` 重启后仍然生效。

## 构建

```bash
./build.sh            # 输出 /tmp/t4fixture/t4fixture-1.0.0.jar
```

需要先跑一次 `./gradlew build` 生成 `build/moddev/*LegacyClasspath.txt`。

注意：这个 jar **没有** `pack.mcmeta`，正好覆盖「热加 mod 的 data/ 没有 mcmeta」这条路径。

## 自检

```bash
# 1) 热加 + 数据包生效 + 放置 (0,100,0) + 存盘 + 退出
./gradlew runServer -PautoAdd=/tmp/t4fixture/t4fixture-1.0.0.jar -Pt4fixtureSelftest=true

# 2) 放进 run/mods 正常启动，读回那个方块，并确认数据包仍然加载
cp /tmp/t4fixture/t4fixture-1.0.0.jar run/mods/
./gradlew runServer -Pt4fixtureSelftest=true -Pt4fixtureSelftestRead=true
rm run/mods/t4fixture-1.0.0.jar
```

期望：

| 步骤 | 日志 | 含义 |
|---|---|---|
| 1 | `datapack (after hot-add) RESULT: PASS` | 热加后配方/战利品/标签已在运行中的服务器里 |
| 1 | `RESULT: PLACED` | 方块放置并存盘 |
| 2 | `isT4Block=true RESULT: PASS` | 重启后存档仍可读，entryId / stateId 不变 |
| 2 | `datapack (after restart) RESULT: PASS` | jar 在 `mods/` 时数据包正常加载（不依赖热加路径） |
