"""Facts derived from a jar itself (read-only, no game).

The corpus pins a version and a hash; tier/AT/JiJ/dependencies are measured, not
declared. That is the line between "the pipeline says it should work" and "the
analyzer found this shape".
"""

from __future__ import annotations

import hashlib
import json
import sys
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional

ANALYZER_DIR = Path(__file__).resolve().parents[2] / "analyzer"
if str(ANALYZER_DIR) not in sys.path:
    sys.path.insert(0, str(ANALYZER_DIR))

from modreloader import analyzer as analyzer_mod  # noqa: E402
from modreloader import metadata, rules, tomlmini  # noqa: E402

from .corpus import Entry  # noqa: E402

PLATFORM_IDS = metadata.PLATFORM_DEP_IDS


@dataclass
class DependencyFact:
    mod_id: str
    type: str
    side: str
    version_range: str
    ordering: str
    provided_by: str = "external"

    @property
    def required(self) -> bool:
        return self.type == "required"


@dataclass
class Facts:
    entry: Entry
    path: Optional[Path]
    sha256: str = ""
    mod_id: str = ""
    name: str = ""
    version: str = ""
    loader: str = ""
    tier: str = "T?"
    verdict: str = ""
    at_rules: int = 0
    at_targets: List[str] = field(default_factory=list)
    mixin_classes: int = 0
    mixin_targets: List[str] = field(default_factory=list)
    bundled_jars: int = 0
    jij_mods: Dict[str, str] = field(default_factory=dict)
    dependencies: List[DependencyFact] = field(default_factory=list)
    errors: List[str] = field(default_factory=list)

    @property
    def present(self) -> bool:
        return self.path is not None

    @property
    def required_deps(self) -> List[DependencyFact]:
        return [d for d in self.dependencies
                if d.required and d.mod_id not in PLATFORM_IDS]

    @property
    def optional_deps(self) -> List[DependencyFact]:
        return [d for d in self.dependencies
                if not d.required and d.mod_id not in PLATFORM_IDS]

    @property
    def access_transformer(self) -> bool:
        return self.at_rules > 0


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def locate(entry: Entry, mods_dir: Path) -> Optional[Path]:
    direct = mods_dir / entry.file
    if direct.is_file():
        return direct
    matches = sorted(p for p in mods_dir.glob(entry.file) if p.is_file())
    if len(matches) == 1:
        return matches[0]
    if len(matches) > 1:
        # Ambiguous is a corpus bug: make the user pin the file name.
        return None
    return None


def inspect_entry(entry: Entry, mods_dir: Path) -> Facts:
    path = locate(entry, mods_dir)
    facts = Facts(entry=entry, path=path)
    if path is None:
        facts.errors.append("jar not found in %s (expected %s)" % (mods_dir, entry.file))
        return facts
    facts.sha256 = sha256_of(path)
    try:
        info = metadata.inspect_jar(path)
        facts.mod_id = info.mod_id
        facts.name = info.name
        facts.version = info.version
        facts.loader = info.loader
        facts.at_rules = info.at_rules
        facts.at_targets = list(info.at_targets)
        facts.bundled_jars = 0
        for dep in info.dependencies:
            facts.dependencies.append(DependencyFact(
                mod_id=dep.mod_id,
                type=dep.type,
                side=dep.side,
                version_range=dep.version_range,
                ordering=dep.ordering,
                provided_by="jij" if dep.mod_id in facts.jij_mods else "external",
            ))
        facts.jij_mods = _jij_mods(path)
        if info.errors:
            facts.errors.extend(info.errors)
    except Exception as exc:  # one bad jar must not hide the rest of the corpus
        facts.errors.append("metadata inspection failed: %s" % exc)
    try:
        analysis = analyzer_mod.analyze(path)
        report = rules.classify(analysis)
        facts.tier = report.tier
        facts.verdict = report.verdict
        facts.mixin_classes = analysis.mixin_class_count
        facts.mixin_targets = sorted(analysis.mixin_targets)
        facts.bundled_jars = analysis.bundled_jars
        if analysis.errors:
            # A nested plain library (e.g. JourneyMap's pngj) has no mods.toml;
            # that is not an error for the outer mod or for this pipeline.
            facts.errors.extend(
                error for error in analysis.errors[:3]
                if not ("META-INF/jarjar/" in error
                        and "no supported mod metadata found" in error))
    except Exception as exc:
        facts.errors.append("class analysis failed: %s" % exc)
    if entry.sha256 and entry.sha256 != facts.sha256:
        facts.errors.append(
            "sha256 mismatch: corpus %s, file %s (version drift or wrong jar)"
            % (entry.sha256[:12], facts.sha256[:12]))
    return facts


def _jij_mods(path: Path) -> Dict[str, str]:
    """modId -> nested path for jars bundled with a mods.toml (jar-in-jar).

    A normal install turns these into ModContainers via NeoForge's JarJar
    locator. The hot-add engine currently only exposes them as modules, so a
    required dependency satisfied this way is still MISSING_DEPENDENCY at
    hot-add time. Recording the mapping lets the pipeline say *why* instead of
    reporting a bare missing dep.
    """
    found: Dict[str, str] = {}
    try:
        with zipfile.ZipFile(path) as outer:
            if "META-INF/jarjar/metadata.json" not in outer.namelist():
                return found
            data = json.loads(outer.read("META-INF/jarjar/metadata.json"))
            for jar in data.get("jars") or []:
                nested_path = str(jar.get("path") or "")
                if not nested_path.endswith(".jar") or nested_path not in outer.namelist():
                    continue
                try:
                    import io
                    with zipfile.ZipFile(io.BytesIO(outer.read(nested_path))) as nested:
                        names = nested.namelist()
                        toml = ("META-INF/neoforge.mods.toml"
                                if "META-INF/neoforge.mods.toml" in names
                                else "META-INF/mods.toml" if "META-INF/mods.toml" in names else None)
                        if toml is None:
                            continue
                        doc = tomlmini.parse(nested.read(toml).decode("utf-8", "replace"))
                        mods = doc.get("mods")
                        if isinstance(mods, list) and mods and isinstance(mods[0], dict):
                            mod_id = str(mods[0].get("modId") or "")
                            if mod_id:
                                found[mod_id] = nested_path
                except Exception:
                    continue
    except Exception:
        return found
    return found


def preloaded_mods(run_mods_dir: Path) -> Dict[str, Path]:
    """modId -> jar for mods the game already loaded at startup.

    A jar in ``run/mods`` is not a hot-add candidate; it also satisfies
    dependencies, so scenarios that expect ``MISSING_DEPENDENCY`` must check
    this map first.
    """
    found: Dict[str, Path] = {}
    if not run_mods_dir.is_dir():
        return found
    for jar in sorted(run_mods_dir.glob("*.jar")):
        try:
            info = metadata.inspect_jar(jar)
        except Exception:
            continue
        if info.mod_id and info.mod_id != "unknown":
            found[info.mod_id] = jar
    return found
