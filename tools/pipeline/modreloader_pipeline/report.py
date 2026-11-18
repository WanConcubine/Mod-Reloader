"""Human and machine readable pipeline output."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Dict, List

from .corpus import Corpus
from .facts import Facts
from .plan import Plan
from .runner import JarVerdict, RunOutcome, overall_verdict


def check_table(corpus: Corpus, facts: Dict[str, Facts],
                plans: Dict[str, Plan]) -> str:
    lines: List[str] = []
    lines.append("corpus: %s (MC %s, %s, %d jars, %d scenarios)"
                 % (corpus.path, corpus.mc, corpus.loader,
                    len(corpus.entries), len(corpus.scenarios)))
    lines.append("")
    lines.append("%-16s %-8s %-4s %-5s %-4s %-6s %s"
                 % ("id", "tier", "AT", "mixins", "JiJ", "sha", "required deps"))
    for entry in corpus.entries.values():
        fact = facts[entry.id]
        sha = fact.sha256[:8] if fact.sha256 else "-"
        deps = ", ".join(d.mod_id for d in fact.required_deps) or "-"
        lines.append("%-16s %-8s %-4s %-5d %-4d %-6s %s"
                     % (entry.id, fact.tier,
                        str(fact.at_rules) if fact.at_rules else "-",
                        fact.mixin_classes, fact.bundled_jars, sha, deps))
        for error in fact.errors:
            lines.append("  ! " + error)
    lines.append("")
    for scenario in corpus.scenarios.values():
        plan = plans[scenario.id]
        status = "OK" if plan.ok else "BLOCKED"
        lines.append("scenario %-20s jbr=%-5s %-7s %s"
                     % (scenario.id, str(scenario.jbr).lower(), status,
                        " -> ".join(f.entry.id for f in plan.add) or "(none)"))
        if plan.preloaded:
            lines.append("  preloaded: " + ", ".join(f.entry.id for f in plan.preloaded))
        for warning in plan.warnings:
            lines.append("  ~ " + warning)
        for problem in plan.problems:
            lines.append("  ! " + problem)
    return "\n".join(lines)


def render_run(report: Dict[str, object]) -> str:
    lines: List[str] = []
    run = report["run"]  # type: ignore[index]
    lines.append("scenario: %s — %s" % (run["scenario"], run["title"]))
    focus = run.get("window_focused")
    lines.append("verdict: %s (exit=%s, %.1fs, jbr=%s, window_focused=%s)"
                 % (report["verdict"], run["exit_code"], run["duration_s"],
                    str(run["jbr"]).lower(),
                    "n/a" if focus is None else str(focus).lower()))
    lines.append("log: %s" % run["log"])
    lines.append("")
    lines.append("%-16s %-9s %-8s %-8s %-10s %-8s %s"
                 % ("id", "predicted", "expected", "actual", "kind", "verdict", "note"))
    for item in report["jars"]:  # type: ignore[index]
        actual = item["actual"] or {}
        actual_s = ("ok" if actual.get("ok") else "refused") if actual else "-"
        kind = actual.get("kind", "-")
        expected = item["expected"].get("hotadd", "unknown")
        if item["expected"].get("kind"):
            expected += "/" + item["expected"]["kind"]
        lines.append("%-16s %-9s %-8s %-8s %-10s %-8s %s"
                     % (item["id"], item["predicted_tier"] or "?", expected,
                        actual_s, kind, item["verdict"], item["note"]))
    plan = report["plan"]  # type: ignore[index]
    if plan["warnings"]:
        lines.append("")
        for warning in plan["warnings"]:
            lines.append("~ " + warning)
    if plan["problems"]:
        lines.append("")
        for problem in plan["problems"]:
            lines.append("! " + problem)
    return "\n".join(lines)


def write_report(run_dir: Path, scenario, plan: Plan, outcome: RunOutcome,
                 verdicts: List[JarVerdict], started_at: str,
                 no_focus: bool = True) -> Dict[str, object]:
    report: Dict[str, object] = {
        "schema": 1,
        "started_at": started_at,
        "run": {
            "scenario": scenario.id,
            "title": scenario.title,
            "jbr": scenario.jbr,
            "no_focus": no_focus,
            "late_at": scenario.late_at,
            "command": outcome.command,
            "exit_code": outcome.exit_code,
            "duration_s": outcome.duration_s,
            "timed_out": outcome.timed_out,
            "log": str(outcome.log_path),
            "done": list(outcome.done) if outcome.done else None,
            "harness_finished": outcome.harness_finished,
            "window_focused": outcome.window_focused,
            "focus_ok": (not no_focus) or outcome.window_focused is not True,
        },
        "plan": {
            "jars": [f.entry.id for f in plan.add],
            "preloaded": [f.entry.id for f in plan.preloaded],
            "missing_deps": plan.missing_deps,
            "warnings": plan.warnings,
            "problems": plan.problems,
        },
        "verdict": overall_verdict(verdicts, outcome, no_focus),
        "jars": [v.to_dict() for v in verdicts],
    }
    (run_dir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (run_dir / "report.md").write_text(render_run(report) + "\n", encoding="utf-8")
    return report
