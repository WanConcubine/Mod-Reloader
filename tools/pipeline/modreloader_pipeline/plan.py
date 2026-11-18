"""Scenario planning: dependency closure, order ("前置先加") and preflight.

The runtime refuses a jar whose required dependency is not loaded
(``FailureKind.MISSING_DEPENDENCY``). The pipeline therefore has to do the same
check *before* starting a game: a scenario is only runnable when every required
dependency is either hot-added earlier in the same scenario or already present
in ``run/mods``.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional, Set

from .corpus import Corpus, Scenario
from .facts import Facts


@dataclass
class Plan:
    scenario: Scenario
    add: List[Facts] = field(default_factory=list)
    preloaded: List[Facts] = field(default_factory=list)
    missing_deps: Dict[str, List[str]] = field(default_factory=dict)
    problems: List[str] = field(default_factory=list)
    warnings: List[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.problems

    @property
    def expected_missing(self) -> Set[str]:
        """Jars whose scenario expectation is a clean MISSING_DEPENDENCY."""
        expected: Set[str] = set()
        for jar in self.scenario.jars:
            spec = (self.scenario.expect.get("jars") or {}).get(jar) or {}
            if spec.get("kind") == "MISSING_DEPENDENCY":
                expected.add(jar)
        return expected


def plan_scenario(
    corpus: Corpus,
    scenario: Scenario,
    facts: Dict[str, Facts],
    preloaded_ids: Dict[str, object],
) -> Plan:
    plan = Plan(scenario=scenario)

    for jar in scenario.jars:
        fact = facts[jar]
        if jar in preloaded_ids:
            plan.preloaded.append(fact)
        else:
            plan.add.append(fact)

    if plan.preloaded:
        names = ", ".join(f.entry.id for f in plan.preloaded)
        plan.warnings.append(
            "already loaded from run/mods (will not be hot-added): " + names)

    for jar in scenario.requires_absent:
        if jar in preloaded_ids:
            plan.problems.append(
                "scenario requires %s to be absent, but run/mods already loads it "
                "(the negative case would become a PASS; remove it or skip this scenario)"
                % jar)

    in_scenario = {f.entry.id for f in plan.add}
    positions = {f.entry.id: index for index, f in enumerate(plan.add)}

    for fact in plan.add:
        missing: List[str] = []
        for dep in fact.required_deps:
            dep_id = dep.mod_id
            if dep_id in preloaded_ids:
                continue
            if dep_id in in_scenario:
                if positions[dep_id] > positions[fact.entry.id]:
                    plan.problems.append(
                        "%s needs %s first, but %s is listed after it"
                        % (fact.entry.id, dep_id, dep_id))
                continue
            if dep_id in fact.jij_mods:
                # Normal install: JarJar creates the container. Hot-add: since
                # 2026-11-17 hotadd/NestedMods does the same and registers it
                # before the outer mod, so the dependency is satisfied.
                plan.warnings.append(
                    "%s needs %s, which is bundled via jar-in-jar (%s); the nested "
                    "mod gets its own container and pack"
                    % (fact.entry.id, dep_id, fact.jij_mods[dep_id]))
                continue
            missing.append(dep_id + (" " + dep.version_range if dep.version_range else ""))
            if dep_id in corpus.entries:
                plan.warnings.append(
                    "%s needs %s; add it to the scenario (it exists in the corpus)"
                    % (fact.entry.id, dep_id))
        if missing:
            plan.missing_deps[fact.entry.id] = missing

    # A missing dependency is only acceptable when the scenario *expects* the
    # engine to refuse this jar; otherwise the game run would just record a
    # known setup error.
    expected_missing = plan.expected_missing
    for jar, missing in plan.missing_deps.items():
        if jar in expected_missing:
            plan.warnings.append(
                "%s is expected to be refused with MISSING_DEPENDENCY: %s"
                % (jar, ", ".join(missing)))
        else:
            plan.problems.append(
                "%s needs %s, which is neither earlier in the scenario nor loaded "
                "from run/mods" % (jar, ", ".join(missing)))

    cycle = _cycle(plan.add)
    if cycle:
        plan.problems.append("dependency cycle among scenario jars: " + " -> ".join(cycle))
    return plan


def _cycle(facts: List[Facts]) -> List[str]:
    """Return one cycle in the scenario's dependency graph, or an empty list."""
    ids = {fact.entry.id for fact in facts}
    graph = {
        fact.entry.id: [d.mod_id for d in fact.required_deps if d.mod_id in ids]
        for fact in facts
    }
    state: Dict[str, int] = {}
    stack: List[str] = []

    def visit(node: str) -> Optional[List[str]]:
        state[node] = 1
        stack.append(node)
        for dep in graph.get(node, []):
            if state.get(dep) == 1:
                index = stack.index(dep)
                return stack[index:] + [dep]
            if state.get(dep, 0) == 0:
                found = visit(dep)
                if found:
                    return found
        stack.pop()
        state[node] = 2
        return None

    for node in graph:
        if state.get(node, 0) == 0:
            found = visit(node)
            if found:
                return found
    return []
