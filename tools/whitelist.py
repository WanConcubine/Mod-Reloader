#!/usr/bin/env python3
"""Read, validate and render the mod whitelist.

The data lives in ``docs/whitelist/mods.tsv`` (one row per mod *and* JVM).
This tool is the readable view: it groups rows by environment and bucket, and
``--write`` regenerates ``docs/whitelist/README.md`` so the table never drifts
from the data.

Usage:

    python3 tools/whitelist.py render            # print the Markdown view
    python3 tools/whitelist.py render --write    # regenerate docs/whitelist/README.md
    python3 tools/whitelist.py check             # validate the TSV, exit 1 on problems
"""

from __future__ import annotations

import argparse
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TSV = ROOT / "docs" / "whitelist" / "mods.tsv"
VIEW = ROOT / "docs" / "whitelist" / "README.md"

COLUMNS = ["name", "jar", "sha256", "jvm", "bucket", "tier", "bug", "state", "evidence", "updated"]
BUCKETS = {"stock": ["T1", "T2", "T3", "BLACK"], "jbr": ["J1", "J2", "BLACK"]}
STATES = ["candidate", "verified", "fixing", "fixed", "wontfix"]
TIERS = ["PIN", "FEI", "GUIFEI", "HUANGHOU"]
BUCKET_TITLES = {
    "T1": "T1 · 可热加，未发现 bug",
    "T2": "T2 · 可热加，但有 bug",
    "T3": "T3 · 默认判不可加，分析后实际可加",
    "J1": "J1 · JBR 下可热加，未发现 bug",
    "J2": "J2 · JBR 下可热加，但有 bug",
    "BLACK": "BLACK · 结构性不可热加",
}
ENV_TITLES = {"stock": "stock JVM（不靠 JBR）", "jbr": "JBR（enhanced redefinition）"}


def load(path: pathlib.Path):
    if not path.is_file():
        raise SystemExit("no whitelist at %s" % path)
    rows, errors = [], []
    header = None
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip("\n")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        cells = [cell.strip() for cell in line.split("\t")]
        if header is None:
            header = cells
            if header != COLUMNS:
                errors.append("line %d: header must be %s" % (number, "\\t".join(COLUMNS)))
            continue
        if len(cells) != len(COLUMNS):
            errors.append("line %d: expected %d columns, got %d" % (number, len(COLUMNS), len(cells)))
            continue
        rows.append(dict(zip(COLUMNS, cells)))
    if header is None:
        errors.append("no header row")
    return rows, errors


def check(rows, errors):
    if errors:
        return errors
    seen = {}
    for index, row in enumerate(rows, 1):
        label = row["name"] or ("row %d" % index)
        if row["jvm"] not in BUCKETS:
            errors.append("%s: jvm must be stock or jbr, got %r" % (label, row["jvm"]))
            continue
        if row["bucket"] not in BUCKETS[row["jvm"]]:
            errors.append("%s: bucket %r is not valid for jvm %r (allowed: %s)"
                          % (label, row["bucket"], row["jvm"], ", ".join(BUCKETS[row["jvm"]])))
        if row["state"] not in STATES:
            errors.append("%s: state %r is not one of %s" % (label, row["state"], ", ".join(STATES)))
        if row["tier"] not in TIERS and row["tier"] != "-":
            errors.append("%s: tier %r is not one of %s or '-'" % (label, row["tier"], ", ".join(TIERS)))
        if len(row["sha256"]) < 8:
            errors.append("%s: sha256 is too short (need >= 8 hex chars)" % label)
        if len(row["updated"]) != 10 or row["updated"][4] != "-" or row["updated"][7] != "-":
            errors.append("%s: updated must be YYYY-MM-DD, got %r" % (label, row["updated"]))
        key = (row["name"], row["jvm"])
        if key in seen:
            errors.append("%s: duplicate row for jvm %s (first at row %d)" % (label, row["jvm"], seen[key]))
        else:
            seen[key] = index
    return errors


def counts(rows):
    summary = {jvm: {bucket: 0 for bucket in BUCKETS[jvm]} for jvm in BUCKETS}
    for row in rows:
        if row["jvm"] in summary and row["bucket"] in summary[row["jvm"]]:
            summary[row["jvm"]][row["bucket"]] += 1
    return summary


def render(rows) -> str:
    summary = counts(rows)
    out = []
    out.append("# 模组白名单（只读视图）")
    out.append("")
    out.append("> 由 `python3 tools/whitelist.py render --write` 从 [mods.tsv](mods.tsv) 生成，**不要手改**。")
    out.append("> 规则与流转见 [mod-whitelist.md](../features/mod-whitelist.md)。")
    out.append("")
    out.append("## 怎么读")
    out.append("")
    out.append("| 桶 | 含义 |")
    out.append("|---|---|")
    out.append("| **T1** | stock 可热加，未发现 bug |")
    out.append("| **T2** | stock 可热加，但有已记录的 bug（修好→T1，修不动留 T2） |")
    out.append("| **T3** | 默认判不可热加，分析后实际可热加（处理成功后转 T2） |")
    out.append("| **J1 / J2** | 同上，但只在 JBR 下成立 |")
    out.append("| **BLACK** | 结构性不可热加；按 JVM 分别记，bug 列写原因、证据列写替代路径 |")
    out.append("")
    out.append("- `state`：`candidate` = 只过了 harness（待所有者实例确认）；`verified` = 已确认；")
    out.append("  `fixing` / `fixed` / `wontfix` = 修 bug 的状态。")
    out.append("- 同一个 mod 在 stock 与 JBR 下**各占一行**；两行结论可以不同（例：stock BLACK / JBR J1）。")
    out.append("- J1/J2 里的 **AT 模组**需要 **JBR + `-Dmodreloader.lateAt=true`**；只开")
    out.append("  `-XX:+AllowEnhancedClassRedefinition` 不够（late AT 是单独的实验开关）。")
    out.append("- tier 是运行期**实际用到**的 tier（pin/fei/guifei/huanghou），不是预测。")
    out.append("")
    out.append("## 总览")
    out.append("")
    out.append("| 环境 | 可热加、无 bug | 可热加、有 bug | 判不可加但可救 | 结构性不可加 |")
    out.append("|---|---|---|---|---|")
    out.append("| stock | T1 × %d | T2 × %d | T3 × %d | BLACK × %d |" % (
        summary["stock"]["T1"], summary["stock"]["T2"],
        summary["stock"]["T3"], summary["stock"]["BLACK"]))
    out.append("| JBR | J1 × %d | J2 × %d | — | BLACK × %d |" % (
        summary["jbr"]["J1"], summary["jbr"]["J2"], summary["jbr"]["BLACK"]))
    out.append("")
    for jvm in ["stock", "jbr"]:
        out.append("## " + ENV_TITLES[jvm])
        out.append("")
        empty = True
        for bucket in BUCKETS[jvm]:
            group = sorted((r for r in rows if r["jvm"] == jvm and r["bucket"] == bucket),
                           key=lambda r: r["name"].lower())
            if not group:
                continue
            empty = False
            out.append("### " + BUCKET_TITLES[bucket] + "（%d）" % len(group))
            out.append("")
            out.append("| 模组 | jar | tier | state | bug | 证据 |")
            out.append("|---|---|---|---|---|---|")
            for row in group:
                out.append("| %s | %s | %s | %s | %s | %s |" % (
                    _cell(row["name"]), _cell(row["jar"]), _cell(row["tier"]),
                    _cell(row["state"]), _cell(row["bug"]), _cell(row["evidence"])))
            out.append("")
        if empty:
            out.append("（暂无记录）")
            out.append("")
    return "\n".join(out).rstrip() + "\n"


def _cell(value: str) -> str:
    return (value or "-").replace("|", "\\|")


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("action", choices=["render", "check"], nargs="?", default="render")
    parser.add_argument("--tsv", default=str(TSV))
    parser.add_argument("--write", action="store_true", help="write the view to docs/whitelist/README.md")
    args = parser.parse_args(argv)

    rows, errors = load(pathlib.Path(args.tsv))
    errors = check(rows, errors)
    if args.action == "check":
        if errors:
            for error in errors:
                print("ERROR " + error)
            return 1
        summary = counts(rows)
        print("ok: %d row(s); stock %s; jbr %s" % (
            len(rows),
            " ".join("%s=%d" % (b, summary["stock"][b]) for b in ["T1", "T2", "T3", "BLACK"]),
            " ".join("%s=%d" % (b, summary["jbr"][b]) for b in ["J1", "J2", "BLACK"])))
        return 0

    if errors:
        for error in errors:
            print("ERROR " + error, file=sys.stderr)
        print("not rendering: fix the data first", file=sys.stderr)
        return 1
    text = render(rows)
    if args.write:
        VIEW.write_text(text, encoding="utf-8")
        print("wrote %s" % VIEW.relative_to(ROOT))
    else:
        print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
