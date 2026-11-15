# T4 registry fixture

一个最小 T4 夹具：通过 `DeferredRegister.Blocks` / `DeferredRegister.Items` 注册
`t4fixture:test_block` 和它的 `BlockItem`，然后在 `FMLCommonSetupEvent` 里从
`BuiltInRegistries` 读回 entry id 与 BlockState id。

用于验证 [T4 注册表热加](../../docs/features/t4-registry-hotadd.md)：
热加 → 放置 → 存盘 → 正常启动读档。

## 构建

```bash
./build.sh            # 输出 /tmp/t4fixture/t4fixture-1.0.0.jar
```

需要先跑一次 `./gradlew build` 生成 `build/moddev/*LegacyClasspath.txt`。

## 自检

```bash
# 1) 热加 + 放置 (0,100,0) + 存盘 + 退出
./gradlew runServer -PautoAdd=/tmp/t4fixture/t4fixture-1.0.0.jar -Pt4fixtureSelftest=true

# 2) 放进 run/mods 正常启动，读回那个方块
cp /tmp/t4fixture/t4fixture-1.0.0.jar run/mods/
./gradlew runServer -Pt4fixtureSelftest=true -Pt4fixtureSelftestRead=true
rm run/mods/t4fixture-1.0.0.jar
```

期望：第一步 `RESULT: PLACED`，第二步 `isT4Block=true RESULT: PASS`，
且两次的 `entryId` / `stateId` 相同。
