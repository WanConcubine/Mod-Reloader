"""Run ``-PverifyCompat`` and turn its log lines into structured evidence.

The harness already exists and is the only place where a real title-screen
hot-add happens. This module does not duplicate it; it makes the result
machine-readable and compares it with the corpus expectation (pass *or* a clean
refusal with a specific FailureKind).
"""

from __future__ import annotations

import os
import queue
import re
import signal
import subprocess
import threading
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Tuple

from .corpus import Scenario
from .facts import Facts
from .plan import Plan

RESULT_RE = re.compile(
    r"\[CompatCheck\] RESULT (?P<file>.+?) predicted=(?P<tier>\S*) "
    r"ok=(?P<ok>true|false) stage=(?P<stage>\S*) kind=(?P<kind>\S*) "
    r"isLoaded=(?P<loaded>true|false) blocks (?P<bb>\d+)->(?P<ba>\d+) "
    r"items (?P<ib>\d+)->(?P<ia>\d+) msg=(?P<msg>.*)$"
)
START_RE = re.compile(r"\[CompatCheck\] START (?P<file>.+?) predicted=(?P<tier>\S*)")
DONE_RE = re.compile(r"\[CompatCheck\] DONE (?P<done>\d+)/(?P<total>\d+)")
FOCUS_RE = re.compile(r"\[CompatCheck\] window focused=(?P<focused>true|false) "
                      r"\(noFocus=(?P<no_focus>true|false)\)")

PROGRESS_MARKERS = (
    "[CompatCheck]",
    "BUILD SUCCESSFUL",
    "BUILD FAILED",
    "FAILURE:",
    "Exception",
    "OutOfMemoryError",
)


@dataclass
class HarnessResult:
    file: str
    tier: str = "?"
    ok: bool = False
    stage: str = ""
    kind: str = ""
    is_loaded: bool = False
    blocks_before: int = 0
    blocks_after: int = 0
    items_before: int = 0
    items_after: int = 0
    message: str = ""
    line: str = ""


@dataclass
class JarVerdict:
    fact: Facts
    expected: Dict[str, object]
    actual: Optional[HarnessResult]
    verdict: str  # MATCH | MISMATCH | RECORDED | NOT_RUN
    tier_expected: str = ""
    tier_actual: str = ""
    note: str = ""

    def to_dict(self) -> Dict[str, object]:
        return {
            "id": self.fact.entry.id,
            "name": self.fact.entry.name,
            "jar": self.fact.path.name if self.fact.path else self.fact.entry.file,
            "sha256": self.fact.sha256,
            "predicted_tier": self.tier_actual or (self.actual.tier if self.actual else ""),
            "expected": dict(self.expected),
            "actual": None if self.actual is None else {
                "ok": self.actual.ok,
                "stage": self.actual.stage,
                "kind": self.actual.kind,
                "isLoaded": self.actual.is_loaded,
                "blocks": [self.actual.blocks_before, self.actual.blocks_after],
                "items": [self.actual.items_before, self.actual.items_after],
                "message": self.actual.message,
            },
            "verdict": self.verdict,
            "tier_match": (not self.tier_expected
                           or self.tier_expected == self.tier_actual),
            "note": self.note,
        }


@dataclass
class RunOutcome:
    command: List[str]
    exit_code: Optional[int]
    timed_out: bool
    duration_s: float
    log_path: Path
    results: Dict[str, HarnessResult] = field(default_factory=dict)
    starts: List[str] = field(default_factory=list)
    done: Optional[Tuple[int, int]] = None
    window_focused: Optional[bool] = None
    log_text: str = ""

    @property
    def harness_finished(self) -> bool:
        return self.done is not None and self.done[0] == self.done[1]


def parse_focus(text: str) -> Optional[bool]:
    match = FOCUS_RE.search(text)
    return (match.group("focused") == "true") if match else None


def parse_compat_log(text: str) -> Tuple[Dict[str, HarnessResult], List[str], Optional[Tuple[int, int]]]:
    results: Dict[str, HarnessResult] = {}
    starts: List[str] = []
    done: Optional[Tuple[int, int]] = None
    for raw in text.splitlines():
        start = START_RE.search(raw)
        if start:
            starts.append(start.group("file"))
            continue
        match = RESULT_RE.search(raw)
        if match:
            file = match.group("file")
            results[file] = HarnessResult(
                file=file,
                tier=match.group("tier"),
                ok=match.group("ok") == "true",
                stage=match.group("stage"),
                kind=match.group("kind"),
                is_loaded=match.group("loaded") == "true",
                blocks_before=int(match.group("bb")),
                blocks_after=int(match.group("ba")),
                items_before=int(match.group("ib")),
                items_after=int(match.group("ia")),
                message=match.group("msg"),
                line=raw,
            )
            continue
        finished = DONE_RE.search(raw)
        if finished:
            done = (int(finished.group("done")), int(finished.group("total")))
    return results, starts, done


def build_command(repo: Path, facts: List[Facts], jbr: bool,
                  jbr_java: Optional[str] = None, no_focus: bool = True,
                  late_at: bool = False) -> List[str]:
    paths = [str(f.path) for f in facts if f.path is not None]
    command = [
        "./gradlew",
        "runClient",
        "-PverifyCompat=" + ",".join(paths),
        "--console=plain",
    ]
    if no_focus:
        # Dev-only agent: creates the GLFW window without taking foreground
        # focus. `--visible` is the escape hatch for interactive runs.
        command.append("-PnoFocus=true")
    if late_at:
        # Experimental: apply the mod's ATs to loaded classes via JBR
        # redefinition before its classes load.
        command.append("-PlateAt=true")
    if not jbr:
        command.append("-PnoJbr=true")
    elif jbr_java:
        command.append("-PjbrJava=" + jbr_java)
    return command


def _reader(stream, sink: "queue.Queue[Optional[str]]") -> None:
    try:
        for line in iter(stream.readline, ""):
            sink.put(line)
    finally:
        sink.put(None)


def run_gradle(
    repo: Path,
    command: List[str],
    log_path: Path,
    timeout_s: float,
) -> RunOutcome:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    started = time.time()
    process = subprocess.Popen(
        command,
        cwd=str(repo),
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        start_new_session=True,
    )
    assert process.stdout is not None
    sink: "queue.Queue[Optional[str]]" = queue.Queue()
    thread = threading.Thread(target=_reader, args=(process.stdout, sink), daemon=True)
    thread.start()

    lines: List[str] = []
    timed_out = False
    deadline = started + timeout_s
    while True:
        remaining = deadline - time.time()
        if remaining <= 0:
            timed_out = True
            break
        try:
            line = sink.get(timeout=min(1.0, remaining))
        except queue.Empty:
            if process.poll() is not None and sink.empty():
                break
            continue
        if line is None:
            break
        lines.append(line)
        stripped = line.rstrip("\n")
        if any(marker in stripped for marker in PROGRESS_MARKERS):
            print(stripped, flush=True)
    if timed_out:
        _terminate(process)
    try:
        exit_code = process.wait(timeout=30)
    except subprocess.TimeoutExpired:
        process.kill()
        exit_code = process.wait(timeout=10)
    thread.join(timeout=5)

    text = "".join(lines)
    log_path.write_text(text, encoding="utf-8", errors="replace")
    results, starts, done = parse_compat_log(text)
    return RunOutcome(
        command=command,
        exit_code=exit_code,
        timed_out=timed_out,
        duration_s=round(time.time() - started, 1),
        log_path=log_path,
        results=results,
        starts=starts,
        done=done,
        window_focused=parse_focus(text),
        log_text=text,
    )


def _terminate(process: subprocess.Popen) -> None:
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGTERM)
    except (ProcessLookupError, PermissionError):
        return
    try:
        process.wait(timeout=15)
        return
    except subprocess.TimeoutExpired:
        pass
    try:
        os.killpg(os.getpgid(process.pid), signal.SIGKILL)
    except (ProcessLookupError, PermissionError):
        pass


def classify(scenario: Scenario, plan: Plan, outcome: RunOutcome) -> List[JarVerdict]:
    verdicts: List[JarVerdict] = []
    for fact in plan.add:
        expected = scenario.expected_for(fact.entry)
        actual = _match_result(fact, outcome.results)
        verdict = JarVerdict(
            fact=fact,
            expected=expected,
            actual=actual,
            verdict="NOT_RUN",
            tier_expected=str(expected.get("tier") or ""),
            tier_actual=(actual.tier if actual else ""),
        )
        if actual is None:
            if outcome.timed_out:
                verdict.note = "game run timed out before this jar"
            elif not outcome.harness_finished:
                verdict.note = "harness did not finish (crash or BUILD failure)"
            else:
                verdict.note = "no RESULT line in the log"
            verdicts.append(verdict)
            continue
        hotadd = str(expected.get("hotadd") or "unknown")
        if hotadd == "unknown":
            verdict.verdict = "RECORDED"
        elif hotadd == "pass":
            if actual.ok and actual.is_loaded:
                verdict.verdict = "MATCH"
            else:
                verdict.verdict = "MISMATCH"
                verdict.note = ("expected pass, got ok=%s isLoaded=%s kind=%s"
                                % (actual.ok, actual.is_loaded, actual.kind))
        elif hotadd == "refuse":
            expected_kind = str(expected.get("kind") or "")
            if actual.ok:
                verdict.verdict = "MISMATCH"
                verdict.note = "expected a clean refusal, got ok=true"
            elif expected_kind and actual.kind != expected_kind:
                verdict.verdict = "MISMATCH"
                verdict.note = ("expected FailureKind %s, got %s"
                                % (expected_kind, actual.kind))
            else:
                verdict.verdict = "MATCH"
        else:
            verdict.verdict = "MISMATCH"
            verdict.note = "unknown corpus expectation %r" % hotadd
        verdicts.append(verdict)
    return verdicts


def _match_result(fact: Facts, results: Dict[str, HarnessResult]) -> Optional[HarnessResult]:
    if fact.path is not None and fact.path.name in results:
        return results[fact.path.name]
    wanted = fact.entry.file
    for name, result in results.items():
        if name == wanted or name.endswith(wanted):
            return result
    return None


def overall_verdict(verdicts: List[JarVerdict], outcome: RunOutcome,
                    no_focus: bool = False) -> str:
    if outcome.timed_out:
        return "TIMEOUT"
    if no_focus and outcome.window_focused is True:
        # The whole point of -PnoFocus=true is not to steal the foreground.
        # If the hint did not apply (game update, early splash, OS change),
        # fail loudly instead of silently interrupting the user.
        return "FOCUS_STOLEN"
    if any(v.verdict in ("MISMATCH", "NOT_RUN") for v in verdicts):
        return "FAIL"
    if not outcome.harness_finished or outcome.exit_code not in (0, None):
        # Every jar did what was expected, but the process did not reach DONE
        # (a mod crashed after its RESULT line, or Gradle failed). That is a
        # finding, not a pass.
        return "INCOMPLETE"
    if any(v.verdict == "MATCH" for v in verdicts):
        return "PASS"
    return "RECORDED"
