# hot-add compatibility pipeline

> **注意：`run` 会启动真实 dev client。** 默认使用 no-focus agent（窗口可见但不抢前台），
> `--visible` 才会正常抢焦点（交互/需要输入/截图的场景）。不要连续批量跑；一次只跑一个场景。
> L0 `check` 和单测不需要游戏。设计/风险见 [no-focus.md](../../docs/features/no-focus.md)。
>
> Read-only corpus + opt-in game runs for real NeoForge 1.21.1 jars. The design,
findings and limits live in [docs/features/hotadd-test-pipeline.md](../../docs/features/hotadd-test-pipeline.md);
this file is just the command reference.

```bash
# 1. download the pinned corpus (jars stay in /tmp, never in the repo)
python3 tools/pipeline/run.py fetch --mods-dir /tmp/modreloader-corpus

# 2. static check: hashes, deps ("前置"), add order, AT vs expectation
python3 tools/pipeline/run.py check --mods-dir /tmp/modreloader-corpus

# 3. inspect one scenario + the exact Gradle command
python3 tools/pipeline/run.py plan --scenario input

# 4. run it (needs a display; JBR is used by default; --no-jbr scenarios
#    automatically pass -PnoJbr=true; no-focus agent is default, --visible opts out;
#    `late_at` scenarios add -PlateAt=true, experimental JBR-only)
python3 tools/pipeline/run.py run --scenario input --mods-dir /tmp/modreloader-corpus
# --visible: show/focus the window (interactive or focus-sensitive scenario)

# 5. show the newest report
python3 tools/pipeline/run.py report
```

Reports go to `tools/pipeline/reports/<timestamp>-<scenario>/` (`run.log`,
`report.json`, `report.md`). They are git-ignored; keep screenshots/logs only
when they are evidence for a doc.

Add a mod: append an entry (exact `version` + `file`; `fetch --pin` writes
`sha256`) and a scenario; then run `check`. Add a scenario that **expects a clean
refusal** when the engine is not supposed to support the jar — a refusal with
the right `FailureKind` is a passing result.

Unit tests (no game):

```bash
cd tools/pipeline && python3 -m unittest discover -s tests -v
```
