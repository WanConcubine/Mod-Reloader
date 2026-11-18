#!/usr/bin/env python3
"""Calibrate the analyzer's tier prediction against verified runtime outcomes.

The analyzer only reads jars; the "ground truth" comes from the hot-add harnesses
in this repo (docs/status.md 表尺) and is stored as a small TSV so the
calibration is reproducible without committing any mod jars:

    <jar file name> \t <verified tier> \t <runtime outcome>

`verified tier` is the tier the *runtime* needed (pin/fei/guifei/huanghou), or
`-` when the jar was only measured (e.g. refused before any tier ran).
`runtime outcome` is one of: pass, refused:at, refused:dependency,
refused:client_registration, untested.

Usage:

    python3 tools/analyzer/calibrate.py --ground-truth tools/analyzer/calibration/ground-truth.tsv \\
        /tmp/calibration/*.jar /path/to/other.jar …

Exit code is 0 when every labelled jar matches, 1 otherwise (unlabelled jars and
`untested` rows do not fail). Mismatches are split by direction: an *over*-estimate
only adds a warning, an *under*-estimate says "loadable" and then crashes, so
`--allow-overestimate` can be used in CI without accepting the dangerous direction.
"""

from __future__ import annotations

import argparse
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from modreloader import analyzer  # noqa: E402
from modreloader.rules import classify  # noqa: E402

TIER_RANK = {"RESOURCE": 0, "DATA": 1, "PIN": 2, "FEI": 3, "GUIFEI": 4, "HUANGHOU": 5, "T?": -1}
TIER_NAMES = {value: key for key, value in TIER_RANK.items()}


def rank(tier: str):
    """Numeric rank for a tier name; None for '-'/'T?'/unknown.

    A wrong tier is not symmetric: predicting *higher* than reality only adds a
    warning, predicting *lower* tells the user "loadable" and then crashes the
    constructor. So the gate below counts the two directions separately.
    """
    value = TIER_RANK.get((tier or "").strip().upper())
    return value if value is not None and value >= 0 else None


def direction(expected: str, actual: str) -> str:
    """'exact' | 'over' | 'under' | 'unknown' (analysis failed / unrankable)."""
    expected_rank, actual_rank = rank(expected), rank(actual)
    if expected_rank is None or actual_rank is None:
        return "unknown"
    if actual_rank == expected_rank:
        return "exact"
    return "over" if actual_rank > expected_rank else "under"


def exit_code(under: int, unknown: int, over: int, allow_overestimate: bool) -> int:
    """Under-estimates and failed analyses always fail; over-estimates only by default."""
    if under or unknown:
        return 1
    if over and not allow_overestimate:
        return 1
    return 0


def load_ground_truth(path: pathlib.Path):
    truth = {}
    if not path.is_file():
        raise SystemExit("no ground truth at %s" % path)
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            raise SystemExit("bad ground-truth line: %r (need name<TAB>tier<TAB>outcome)" % line)
        truth[parts[0].strip()] = (parts[1].strip().lower(), parts[2].strip())
    return truth


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("jars", nargs="+", help="jar paths or globs")
    parser.add_argument("--ground-truth", required=True)
    parser.add_argument("--verbose", action="store_true", help="print every reason for mismatches")
    parser.add_argument("--allow-overestimate", action="store_true",
                        help="do not fail on an over-estimate (safe direction: it only adds a warning). Under-estimates and failed analyses always fail.")
    args = parser.parse_args(argv)

    truth = load_ground_truth(pathlib.Path(args.ground_truth))
    paths = []
    for pattern in args.jars:
        matches = sorted(pathlib.Path().glob(pattern)) if any(c in pattern for c in "*?[") \
            else [pathlib.Path(pattern)]
        paths.extend(matches)

    rows = []
    labelled = mismatched = over = under = unknown = 0
    for path in paths:
        if not path.is_file():
            print("skip (not a file): %s" % path)
            continue
        report = classify(analyzer.analyze(path))
        at = report.signals.get("at_rules") or 0
        jij = report.signals.get("bundled_jars") or 0
        expected = truth.get(path.name)
        if expected is None:
            state = "unlabelled"
        else:
            tier, outcome = expected
            if outcome == "untested" or tier == "-":
                state = "untested"
            else:
                labelled += 1
                how = direction(tier, report.tier)
                if how == "exact":
                    state = "match"
                else:
                    state = "MISMATCH(%s)" % how
                    mismatched += 1
                    if how == "over":
                        over += 1
                    elif how == "under":
                        under += 1
                    else:
                        unknown += 1
            expected = "%s/%s" % (tier, outcome)
        rows.append((path.name, report.tier, at, jij, expected, state, report))

    width = max(len(row[0]) for row in rows) if rows else 10
    header = "%-*s %-8s %-3s %-3s %-24s %s" % (width, "JAR", "TIER", "AT", "JIJ", "VERIFIED(tier/outcome)", "STATE")
    print(header)
    print("-" * len(header))
    for name, tier, at, jij, expected, state, _report in rows:
        print("%-*s %-8s %-3d %-3d %-24s %s" % (
            width, name, tier, at, jij, expected or "-", state))
    print()
    print("labelled jars: %d, matches: %d, mismatches: %d "
          "(under-estimates: %d, over-estimates: %d, failed analysis: %d)" % (
              labelled, labelled - mismatched, mismatched, under, over, unknown))
    if mismatched:
        print("dangerous direction (under-estimate: 'loadable' then crash): must be 0")
    if args.verbose:
        for name, tier, at, jij, expected, state, report in rows:
            if state == "MISMATCH":
                print("\n%s: analyzer=%s expected=%s" % (name, tier, expected))
                for reason in report.reasons:
                    print("  - %s" % reason)
    code = exit_code(under, unknown, over, args.allow_overestimate)
    if code and over and not args.allow_overestimate:
        print("(over-estimates only: re-run with --allow-overestimate to accept them)")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
