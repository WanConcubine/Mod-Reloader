# Worldgen fixture

一个**只有数据**的夹具：`data/wgfixture/dimension/wgflat.json`（超平坦、石头+土+草）+ 对应的
`dimension_type`。用来量「维度这类 WORLDGEN 注册表内容，热加之后到底需要重启还是只要新世界」。

```bash
./build.sh                       # -> /tmp/wgfixture/wgfixture-1.0.0.jar

cd ../..
# 标题屏热加 → 新建世界 → 断言维度是否可进（见 docs/features/t4-datapack-hotadd.md §10）
./gradlew runClient \
  -PverifyAddCommand=/tmp/wgfixture/wgfixture-1.0.0.jar \
  -PverifyAddCommandModId=wgfixture \
  -PverifyDatapackAtTitle=true \
  -PverifyWorldgen=wgfixture:wgflat
```

维度的 `effects` 用 `minecraft:overworld`，所以客户端不需要额外天空渲染就能进。
`generator` 用 `minecraft:flat` 的最小 settings：不依赖生物群系源数据。
