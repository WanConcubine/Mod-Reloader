"""Command line entry point for the hot-add compatibility pipeline."""

from __future__ import annotations

import argparse
import contextlib
import datetime as dt
import json
import os
import sys
from pathlib import Path
from typing import Dict, List, Optional

from . import __version__
from .corpus import Corpus, CorpusError, select_scenario
from .facts import Facts, inspect_entry, preloaded_mods
from .fetch import fetch
from .plan import Plan, plan_scenario
from .report import check_table, render_run, write_report
from .runner import build_command, classify, overall_verdict, run_gradle

try:
    import fcntl
except ImportError:  # Windows: no advisory lock; the user must not start two runs
    fcntl = None

REPO = Path(__file__).resolve().parents[3]
DEFAULT_CORPUS = REPO / "tools" / "pipeline" / "corpus.json"
DEFAULT_MODS = Path("/tmp/modreloader-corpus")
DEFAULT_REPORTS = REPO / "tools" / "pipeline" / "reports"


def _load(args) -> Corpus:
    return Corpus.load(Path(args.corpus).expanduser())


def _facts(corpus: Corpus, mods_dir: Path) -> Dict[str, Facts]:
    return {entry.id: inspect_entry(entry, mods_dir) for entry in corpus.entries.values()}


def _plans(corpus: Corpus, facts: Dict[str, Facts], run_mods_dir: Path) -> Dict[str, Plan]:
    preloaded = preloaded_mods(run_mods_dir)
    return {
        scenario.id: plan_scenario(corpus, scenario, facts, preloaded)
        for scenario in corpus.scenarios.values()
    }


def cmd_check(args) -> int:
    corpus = _load(args)
    mods_dir = Path(args.mods_dir).expanduser()
    facts = _facts(corpus, mods_dir)
    plans = _plans(corpus, facts, Path(args.run_mods_dir).expanduser())
    print(check_table(corpus, facts, plans))

    errors: List[str] = []
    warnings: List[str] = []
    for entry in corpus.entries.values():
        fact = facts[entry.id]
        if not fact.present:
            (warnings if args.skip_missing else errors).append(
                "%s: %s" % (entry.id, fact.errors[0] if fact.errors else "missing jar"))
            continue
        if not entry.sha256:
            warnings.append("%s: sha256 is not pinned (run `fetch --pin`)" % entry.id)
        if fact.mod_id and fact.mod_id != entry.id:
            warnings.append("%s: jar modId is %r, corpus id is %r"
                            % (entry.id, fact.mod_id, entry.id))
        for error in fact.errors:
            errors.append("%s: %s" % (entry.id, error))

    for scenario in corpus.scenarios.values():
        plan = plans[scenario.id]
        scenario_has_at = any(f.access_transformer for f in plan.add + plan.preloaded)
        for fact in plan.add + plan.preloaded:
            spec = scenario.expected_for(fact.entry)
            hotadd = str(spec.get("hotadd") or "unknown")
            if hotadd == "pass" and fact.access_transformer and not scenario.late_at:
                errors.append(
                    "%s/%s: expectation is pass, but the jar ships %d access transformer "
                    "rule(s) and the scenario does not enable late_at"
                    % (scenario.id, fact.entry.id, fact.at_rules))
            if scenario.late_at and not scenario_has_at:
                warnings.append("%s: late_at is set but none of the jars has an AT"
                                % scenario.id)
            kind = str(spec.get("kind") or "")
            if hotadd == "refuse" and kind == "ACCESS_TRANSFORMER" and not fact.access_transformer:
                warnings.append("%s/%s: expects ACCESS_TRANSFORMER but the jar has no AT"
                                % (scenario.id, fact.entry.id))
            tier = str(spec.get("tier") or "")
            if tier and fact.present and fact.tier != tier:
                warnings.append("%s/%s: expected tier %s, analyzer says %s"
                                % (scenario.id, fact.entry.id, tier, fact.tier))
        errors.extend("%s: %s" % (scenario.id, p) for p in plan.problems)

    if warnings:
        print("\nwarnings:")
        for warning in warnings:
            print("  ~ " + warning)
    if errors:
        print("\nblocking:")
        for error in errors:
            print("  ! " + error)
        return 1
    print("\ncheck: OK (%d scenario(s) runnable)" % len(corpus.scenarios))
    return 0


def cmd_plan(args) -> int:
    corpus = _load(args)
    facts = _facts(corpus, Path(args.mods_dir).expanduser())
    plans = _plans(corpus, facts, Path(args.run_mods_dir).expanduser())
    selected = ([select_scenario(corpus, args.scenario)] if args.scenario
                else list(corpus.scenarios.values()))
    failed = False
    for scenario in selected:
        plan = plans[scenario.id]
        print("scenario: %s — %s" % (scenario.id, scenario.title))
        print("  jbr: %s" % ("yes" if scenario.jbr else "no (-PnoJbr=true)"))
        for index, fact in enumerate(plan.add, 1):
            deps = ", ".join(d.mod_id for d in fact.required_deps) or "-"
            print("  %d. %-18s %-7s needs: %s" % (index, fact.entry.id, fact.tier, deps))
        for fact in plan.preloaded:
            print("     %-18s preloaded (run/mods), not hot-added" % fact.entry.id)
        for warning in plan.warnings:
            print("  ~ " + warning)
        for problem in plan.problems:
            print("  ! " + problem)
        if plan.add:
            command = build_command(REPO, plan.add, scenario.jbr, _jbr_java(args),
                    late_at=scenario.late_at)
            print("  command: " + " ".join(_quote(part) for part in command))
        print()
        failed = failed or not plan.ok
    return 1 if failed else 0


def cmd_run(args) -> int:
    corpus = _load(args)
    scenario = select_scenario(corpus, args.scenario)
    mods_dir = Path(args.mods_dir).expanduser()
    facts = {jar: inspect_entry(corpus.entries[jar], mods_dir) for jar in scenario.jars}
    missing = [jar for jar, fact in facts.items() if not fact.present]
    if missing:
        print("missing jar(s) in %s: %s" % (mods_dir, ", ".join(missing)), file=sys.stderr)
        print("run `python3 tools/pipeline/run.py fetch --mods-dir %s` first" % mods_dir,
              file=sys.stderr)
        return 2

    run_mods_dir = Path(args.run_mods_dir).expanduser()
    plan = plan_scenario(corpus, scenario, facts, preloaded_mods(run_mods_dir))
    if not plan.ok:
        print("scenario is not runnable:", file=sys.stderr)
        for problem in plan.problems:
            print("  ! " + problem, file=sys.stderr)
        return 2
    for warning in plan.warnings:
        print("~ " + warning, file=sys.stderr)

    jbr_java = _jbr_java(args)
    if scenario.jbr and not jbr_java:
        print("JBR is required for this scenario but no JBR 21 was found.\n"
              "  expected: %s\n"
              "  set JBR_JAVA=/path/to/jbr/bin/java or pass --jbr-java"
              % _default_jbr_java(), file=sys.stderr)
        return 2
    if scenario.jbr:
        print("JBR: %s" % jbr_java)

    no_focus = not args.visible
    command = build_command(REPO, plan.add, scenario.jbr, jbr_java, no_focus=no_focus,
            late_at=scenario.late_at)
    if args.dry_run:
        print("dry run: " + " ".join(_quote(part) for part in command))
        return 0

    stamp = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = Path(args.report_dir).expanduser() / ("%s-%s" % (stamp, scenario.id))
    suffix = 1
    while run_dir.exists():
        suffix += 1
        run_dir = run_dir.with_name("%s-%s-%d" % (stamp, scenario.id, suffix))
    run_dir.mkdir(parents=True, exist_ok=True)
    print("run dir: %s" % run_dir)
    print("command: " + " ".join(_quote(part) for part in command))
    print("window: %s" % ("no-focus (agent)" if no_focus else "visible/interactive"))
    if scenario.late_at:
        print("late AT: enabled (experimental, JBR redefinition)")

    with _exclusive_run() as acquired:
        if not acquired:
            return 2
        outcome = run_gradle(REPO, command, run_dir / "run.log", args.timeout)
    verdicts = classify(scenario, plan, outcome)
    report = write_report(run_dir, scenario, plan, outcome,
                          verdicts, dt.datetime.now().isoformat(timespec="seconds"),
                          no_focus)
    print()
    print(render_run(report))
    print()
    print("verdict: %s" % report["verdict"])
    if report["verdict"] == "FOCUS_STOLEN":
        print("focus guard failed: -PnoFocus=true was requested but the window took focus; "
              "treat this run as invalid and fix the agent before relying on it", file=sys.stderr)
    return 0 if report["verdict"] in ("PASS", "RECORDED") else 1


def cmd_fetch(args) -> int:
    corpus = _load(args)
    results = fetch(corpus, Path(args.mods_dir).expanduser(), args.group, args.pin)
    for result in results:
        entry = result["entry"]
        print("%-18s %-11s %s %s" % (entry.id, result["status"],
                                     result["sha256"][:12], result["path"]))
    if args.pin:
        print("\ncorpus updated: %s" % corpus.path)
    return 0


def cmd_scenarios(args) -> int:
    corpus = _load(args)
    for scenario in corpus.scenarios.values():
        print("%-22s jbr=%-5s %-45s %s"
              % (scenario.id, str(scenario.jbr).lower(),
                 " -> ".join(scenario.jars), scenario.title))
    return 0


def cmd_report(args) -> int:
    if args.file:
        path = Path(args.file).expanduser()
    else:
        candidates = sorted(Path(args.report_dir).expanduser().glob("*/report.json"))
        if not candidates:
            print("no reports yet; run a scenario first", file=sys.stderr)
            return 2
        path = candidates[-1]
    report = json.loads(path.read_text(encoding="utf-8"))
    print("report: %s" % path)
    print(render_run(report))
    return 0


@contextlib.contextmanager
def _exclusive_run():
    """Refuse to start two dev clients at once: they share run/ and the display."""
    lock_path = REPO / "tools" / "pipeline" / ".run.lock"
    handle = lock_path.open("w")
    if fcntl is not None:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            handle.close()
            print("another pipeline run is active (tools/pipeline/.run.lock); "
                  "wait for it to finish", file=sys.stderr)
            yield False
            return
    try:
        yield True
    finally:
        if fcntl is not None:
            fcntl.flock(handle, fcntl.LOCK_UN)
        handle.close()


def _default_jbr_java() -> Path:
    home = Path.home()
    return home / "Library" / "Java" / "JavaVirtualMachines" / "jbr-21.jdk" / "Contents" / "Home" / "bin" / "java"


def _jbr_java(args) -> Optional[str]:
    explicit = getattr(args, "jbr_java", None) or os.environ.get("JBR_JAVA")
    if explicit:
        path = Path(explicit).expanduser()
        return str(path) if path.is_file() else None
    default = _default_jbr_java()
    return str(default) if default.is_file() else None


def _quote(part: str) -> str:
    return part if " " not in part else "'" + part.replace("'", "'\\''") + "'"


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        prog="pipeline",
        description="Hot-add compatibility pipeline (read-only corpus + opt-in game runs).",
    )
    parser.add_argument("--version", action="version", version="%(prog)s " + __version__)
    sub = parser.add_subparsers(dest="command", required=True)

    def common(p):
        p.add_argument("--corpus", default=str(DEFAULT_CORPUS))
        p.add_argument("--mods-dir", default=str(DEFAULT_MODS))

    fetch_cmd = sub.add_parser("fetch", help="download pinned jars from Modrinth")
    common(fetch_cmd)
    fetch_cmd.add_argument("--group", action="append", help="only entries in this group")
    fetch_cmd.add_argument("--pin", action="store_true", default=True,
                           help="write sha256 back into the corpus (default)")
    fetch_cmd.add_argument("--no-pin", dest="pin", action="store_false")
    fetch_cmd.set_defaults(func=cmd_fetch)

    check = sub.add_parser("check", help="static check: jars, deps, tier, AT, order")
    common(check)
    check.add_argument("--run-mods-dir", default=str(REPO / "run" / "mods"))
    check.add_argument("--skip-missing", action="store_true",
                       help="missing jars are warnings (partial corpus checkout)")
    check.set_defaults(func=cmd_check)

    plan = sub.add_parser("plan", help="show add order + exact harness command")
    common(plan)
    plan.add_argument("--scenario")
    plan.add_argument("--run-mods-dir", default=str(REPO / "run" / "mods"))
    plan.add_argument("--jbr-java")
    plan.set_defaults(func=cmd_plan)

    run = sub.add_parser("run", help="run one scenario through -PverifyCompat")
    common(run)
    run.add_argument("--scenario", required=True)
    run.add_argument("--run-mods-dir", default=str(REPO / "run" / "mods"))
    run.add_argument("--report-dir", default=str(DEFAULT_REPORTS))
    run.add_argument("--timeout", type=float, default=900.0)
    run.add_argument("--jbr-java")
    run.add_argument("--dry-run", action="store_true")
    run.add_argument("--visible", action="store_true",
                     help="show the client window and let it take focus (interactive)")
    run.set_defaults(func=cmd_run)

    scenarios = sub.add_parser("scenarios", help="list scenarios")
    common(scenarios)
    scenarios.set_defaults(func=cmd_scenarios)

    report = sub.add_parser("report", help="print the latest (or a given) report")
    report.add_argument("--report-dir", default=str(DEFAULT_REPORTS))
    report.add_argument("--file")
    report.set_defaults(func=cmd_report)

    args = parser.parse_args(argv)
    try:
        return args.func(args)
    except CorpusError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
