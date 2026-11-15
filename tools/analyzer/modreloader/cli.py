"""Command line entry point."""

from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path
from typing import List, Optional

from . import analyzer, report, rules
from .model import ModReport

VERSION = "0.1.0"


def _collect(path: Path, recursive: bool) -> List[Path]:
    if path.is_file():
        return [path]
    if not path.is_dir():
        return []
    pattern = "**/*.jar" if recursive else "*.jar"
    return sorted(p for p in path.glob(pattern) if p.is_file())


def _fallback(path: Path, exc: Exception) -> ModReport:
    return ModReport(
        path=path,
        loader="unknown",
        mod_id=path.stem,
        name=path.stem,
        version="?",
        tier="T5",
        confidence=0.0,
        verdict="analysis failed",
        reasons=["analyzer error: %s" % exc],
    )


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="modreloader",
        description="Read-only Minecraft mod reloadability analyzer (v0).",
    )
    parser.add_argument("--version", action="version", version="%(prog)s " + VERSION)
    sub = parser.add_subparsers(dest="command", required=True)

    scan = sub.add_parser("scan", help="scan a mods folder or a single jar")
    scan.add_argument("path", help="directory containing jars, or one jar")
    scan.add_argument("--json", metavar="FILE", help="write machine-readable JSON")
    scan.add_argument("--md", metavar="FILE", help="write a Markdown report")
    scan.add_argument("--verbose", action="store_true", help="show reasons for every mod")
    scan.add_argument("--recursive", action="store_true", help="recurse into subdirectories")
    scan.add_argument("--limit", type=int, default=0, help="only scan the first N jars")

    args = parser.parse_args(argv)
    if args.command != "scan":
        parser.error("unknown command")

    root = Path(args.path).expanduser()
    jars = _collect(root, args.recursive)
    if args.limit:
        jars = jars[: args.limit]
    if not jars:
        print("no jars found at %s" % root, file=sys.stderr)
        return 2

    started = time.time()
    reports: List[ModReport] = []
    total = len(jars)
    for index, jar in enumerate(jars, 1):
        if total > 10:
            sys.stderr.write("\r[%d/%d] %-50s" % (index, total, jar.name[:50]))
            sys.stderr.flush()
        try:
            analysis = analyzer.analyze(jar)
            reports.append(rules.classify(analysis))
        except Exception as exc:  # keep scanning; one bad jar must not stop the run
            print("\n! %s: %s" % (jar.name, exc), file=sys.stderr)
            reports.append(_fallback(jar, exc))
    if total > 10:
        sys.stderr.write("\r" + " " * 80 + "\r")
        sys.stderr.flush()

    elapsed = time.time() - started
    if args.json:
        Path(args.json).write_text(report.render_json(reports), encoding="utf-8")
    if args.md:
        Path(args.md).write_text(report.render_markdown(reports), encoding="utf-8")

    sys.stdout.write(report.render_text(reports, verbose=args.verbose))
    sys.stdout.write("\nFinished in %.2fs\n" % elapsed)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
