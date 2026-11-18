"""Corpus model and validation.

The corpus is the human-owned part of the pipeline: which real jars represent a
scenario, which of them are prerequisites ("前置"), and what the engine is
expected to do with each one. Everything else (tier, AT, JiJ, dependency ids)
comes from the jar itself, so a pinned corpus cannot drift silently.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, List, Optional

EXPECT_HOTADD = ("pass", "refuse", "unknown")


class CorpusError(ValueError):
    pass


@dataclass
class Entry:
    id: str
    name: str
    project: str
    version: str
    file: str
    sha256: str = ""
    groups: List[str] = field(default_factory=list)
    expect: Dict[str, Any] = field(default_factory=dict)
    notes: str = ""

    @property
    def expected_hotadd(self) -> str:
        return str(self.expect.get("hotadd", "unknown"))

    @property
    def expected_kind(self) -> str:
        return str(self.expect.get("kind", ""))


@dataclass
class Scenario:
    id: str
    title: str
    jars: List[str]
    jbr: bool = True
    late_at: bool = False
    expect: Dict[str, Any] = field(default_factory=dict)
    requires_absent: List[str] = field(default_factory=list)
    notes: str = ""

    def expected_for(self, entry: Entry) -> Dict[str, Any]:
        """Scenario override -> entry default -> unknown."""
        merged: Dict[str, Any] = {"hotadd": entry.expected_hotadd}
        if entry.expected_kind:
            merged["kind"] = entry.expected_kind
        if entry.expect.get("tier"):
            merged["tier"] = entry.expect["tier"]
        default = self.expect.get("default")
        if isinstance(default, str):
            merged["hotadd"] = default
        jars = self.expect.get("jars")
        if isinstance(jars, dict) and isinstance(jars.get(entry.id), dict):
            merged.update(jars[entry.id])
        return merged

    @property
    def negative(self) -> bool:
        return any(
            isinstance(spec, dict) and spec.get("kind")
            for spec in (self.expect.get("jars") or {}).values()
        )


@dataclass
class Corpus:
    path: Path
    mc: str
    loader: str
    entries: Dict[str, Entry]
    scenarios: Dict[str, Scenario]

    @classmethod
    def load(cls, path: Path) -> "Corpus":
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise CorpusError("cannot read corpus %s: %s" % (path, exc))
        if not isinstance(data, dict):
            raise CorpusError("corpus root must be an object")
        if data.get("schema") != 1:
            raise CorpusError("unsupported corpus schema: %r" % data.get("schema"))

        entries: Dict[str, Entry] = {}
        for raw in data.get("entries") or []:
            entry = _entry(raw)
            if entry.id in entries:
                raise CorpusError("duplicate entry id %r" % entry.id)
            entries[entry.id] = entry

        scenarios: Dict[str, Scenario] = {}
        for raw in data.get("scenarios") or []:
            scenario = _scenario(raw, entries)
            if scenario.id in scenarios:
                raise CorpusError("duplicate scenario id %r" % scenario.id)
            scenarios[scenario.id] = scenario

        if not entries:
            raise CorpusError("corpus has no entries")
        if not scenarios:
            raise CorpusError("corpus has no scenarios")
        return cls(
            path=path,
            mc=str(data.get("mc") or ""),
            loader=str(data.get("loader") or ""),
            entries=entries,
            scenarios=scenarios,
        )

    def select(self, groups: Iterable[str]) -> List[Entry]:
        wanted = set(groups)
        return [e for e in self.entries.values() if wanted & set(e.groups)]


def _entry(raw: Any) -> Entry:
    if not isinstance(raw, dict):
        raise CorpusError("entry must be an object")
    for key in ("id", "name", "project", "version", "file"):
        if not isinstance(raw.get(key), str) or not raw[key].strip():
            raise CorpusError("entry is missing %r: %r" % (key, raw))
    expect = raw.get("expect") or {}
    if not isinstance(expect, dict):
        raise CorpusError("entry %s: expect must be an object" % raw["id"])
    if expect.get("hotadd") and expect["hotadd"] not in EXPECT_HOTADD:
        raise CorpusError("entry %s: bad hotadd expectation %r"
                          % (raw["id"], expect["hotadd"]))
    return Entry(
        id=raw["id"],
        name=raw["name"],
        project=raw["project"],
        version=raw["version"],
        file=raw["file"],
        sha256=str(raw.get("sha256") or ""),
        groups=[str(g) for g in raw.get("groups") or []],
        expect=dict(expect),
        notes=str(raw.get("notes") or ""),
    )


def _scenario(raw: Any, entries: Dict[str, Entry]) -> Scenario:
    if not isinstance(raw, dict):
        raise CorpusError("scenario must be an object")
    for key in ("id", "title"):
        if not isinstance(raw.get(key), str) or not raw[key].strip():
            raise CorpusError("scenario is missing %r: %r" % (key, raw))
    jars = raw.get("jars")
    if not isinstance(jars, list) or not jars:
        raise CorpusError("scenario %s: jars must be a non-empty list" % raw["id"])
    for jar in jars:
        if jar not in entries:
            raise CorpusError("scenario %s: unknown entry %r" % (raw["id"], jar))
    if len(set(jars)) != len(jars):
        raise CorpusError("scenario %s: duplicate jar" % raw["id"])

    expect = raw.get("expect") or {}
    if not isinstance(expect, dict):
        raise CorpusError("scenario %s: expect must be an object" % raw["id"])
    if expect.get("default") and expect["default"] not in EXPECT_HOTADD:
        raise CorpusError("scenario %s: bad default expectation %r"
                          % (raw["id"], expect["default"]))
    per_jar = expect.get("jars") or {}
    if not isinstance(per_jar, dict):
        raise CorpusError("scenario %s: expect.jars must be an object" % raw["id"])
    for jar, spec in per_jar.items():
        if jar not in jars:
            raise CorpusError("scenario %s: expectation for %r which is not in jars"
                              % (raw["id"], jar))
        if not isinstance(spec, dict):
            raise CorpusError("scenario %s: expectation for %r must be an object"
                              % (raw["id"], jar))
        if spec.get("hotadd") and spec["hotadd"] not in EXPECT_HOTADD:
            raise CorpusError("scenario %s: bad hotadd for %r: %r"
                              % (raw["id"], jar, spec["hotadd"]))

    requires_absent = raw.get("requires_absent") or []
    if not isinstance(requires_absent, list) or any(
            jar not in entries for jar in requires_absent):
        raise CorpusError("scenario %s: requires_absent must list known entries"
                          % raw["id"])

    return Scenario(
        id=raw["id"],
        title=raw["title"],
        jars=[str(j) for j in jars],
        jbr=bool(raw.get("jbr", True)),
        late_at=bool(raw.get("late_at", False)),
        expect=dict(expect),
        requires_absent=[str(j) for j in requires_absent],
        notes=str(raw.get("notes") or ""),
    )


def select_scenario(corpus: Corpus, scenario_id: str) -> Scenario:
    if scenario_id not in corpus.scenarios:
        raise CorpusError("unknown scenario %r (known: %s)"
                          % (scenario_id, ", ".join(sorted(corpus.scenarios))))
    return corpus.scenarios[scenario_id]
