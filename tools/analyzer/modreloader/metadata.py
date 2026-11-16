"""Mod jar inspection: loader metadata, mixin configs, class enumeration."""

from __future__ import annotations

import json
import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Optional

from . import tomlmini

MAX_TEXT = 4 * 1024 * 1024

# NeoForge/Forge access transformers. This is a hot-add boundary, not "content
# shape": FML applies the rules when a class loads, so a mod that ships them can
# only work after a restart (the runtime refuses such mods before injecting
# anything). The runtime side is inspect/AccessTransformerRules + the
# FailureKind.ACCESS_TRANSFORMER gate.
AT_ENTRY = "META-INF/accesstransformer.cfg"


@dataclass
class ModInfo:
    path: Path
    jar_size: int
    loader: str
    mod_id: str
    name: str
    version: str
    entrypoints: List[str] = field(default_factory=list)
    declared_mixins: List[str] = field(default_factory=list)
    access_widener: Optional[str] = None
    at_rules: int = 0
    at_targets: List[str] = field(default_factory=list)
    depends: Dict[str, str] = field(default_factory=dict)
    client_only: bool = False
    namespace: str = ""
    errors: List[str] = field(default_factory=list)


def _read_text(zf: zipfile.ZipFile, name: str) -> Optional[str]:
    try:
        info = zf.getinfo(name)
        if info.file_size > MAX_TEXT:
            return None
        with zf.open(name) as handle:
            return handle.read(MAX_TEXT).decode("utf-8", errors="replace")
    except (KeyError, OSError, RuntimeError):
        return None


def _read_manifest(zf: zipfile.ZipFile) -> Dict[str, str]:
    text = _read_text(zf, "META-INF/MANIFEST.MF")
    if not text:
        return {}
    unfolded: List[str] = []
    for line in text.splitlines():
        if line.startswith(" ") and unfolded:
            unfolded[-1] += line[1:]
        else:
            unfolded.append(line)
    result: Dict[str, str] = {}
    for line in unfolded:
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        result[key.strip()] = value.strip()
    return result


def _read_json(zf: zipfile.ZipFile, name: str) -> Optional[Any]:
    text = _read_text(zf, name)
    if text is None:
        return None
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        # Some mods ship JSON with comments or trailing commas.
        cleaned = _strip_json_noise(text)
        try:
            return json.loads(cleaned)
        except json.JSONDecodeError:
            return None


def _strip_json_noise(text: str) -> str:
    out: List[str] = []
    quote = False
    escaped = False
    i = 0
    while i < len(text):
        ch = text[i]
        if quote:
            out.append(ch)
            if escaped:
                escaped = False
            elif ch == "\\":
                escaped = True
            elif ch == '"':
                quote = False
            i += 1
            continue
        if ch == '"':
            quote = True
            out.append(ch)
            i += 1
            continue
        if ch == "/" and i + 1 < len(text):
            nxt = text[i + 1]
            if nxt == "/":
                i += 2
                while i < len(text) and text[i] not in "\r\n":
                    i += 1
                continue
            if nxt == "*":
                i += 2
                while i + 1 < len(text) and not (text[i] == "*" and text[i + 1] == "/"):
                    i += 1
                i += 2
                continue
        out.append(ch)
        i += 1
    cleaned = "".join(out)
    # Remove trailing commas before ] or }.
    cleaned = re.sub(r",(\s*[}\]])", r"\1", cleaned)
    return cleaned


def _entrypoint_values(raw: Any) -> List[str]:
    values: List[str] = []
    if isinstance(raw, dict):
        for key, entries in raw.items():
            if isinstance(entries, list):
                for item in entries:
                    if isinstance(item, str):
                        values.append("%s:%s" % (key, item))
                    elif isinstance(item, dict) and isinstance(item.get("value"), str):
                        values.append("%s:%s" % (key, item["value"]))
            elif isinstance(entries, str):
                values.append("%s:%s" % (key, entries))
    return values


def _mixin_paths(raw: Any) -> List[str]:
    paths: List[str] = []
    if isinstance(raw, list):
        for item in raw:
            if isinstance(item, str):
                paths.append(item)
            elif isinstance(item, dict):
                for key in ("config", "value"):
                    if isinstance(item.get(key), str):
                        paths.append(item[key])
    elif isinstance(raw, str):
        paths.append(raw)
    return paths


def _read_access_transformer(zf: zipfile.ZipFile):
    """(rule count, dotted target class names) of META-INF/accesstransformer.cfg.

    Mirrors the runtime parser (hotadd/AccessTransformerRules): one rule per
    non-comment line, the second whitespace-separated token is the target class.
    """
    text = _read_text(zf, AT_ENTRY)
    if not text:
        return 0, []
    rules = 0
    targets = set()
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        parts = stripped.split()
        if len(parts) < 2:
            continue
        rules += 1
        targets.add(parts[1].replace("/", "."))
    return rules, sorted(targets)


def _detect_loader(names: set) -> str:
    if "fabric.mod.json" in names:
        return "fabric"
    if "quilt.mod.json" in names:
        return "quilt"
    if "META-INF/neoforge.mods.toml" in names:
        return "neoforge"
    if "META-INF/mods.toml" in names:
        return "forge"
    if "mcmod.info" in names:
        return "forge-legacy"
    return "unknown"


def _from_fabric(zf: zipfile.ZipFile) -> Optional[ModInfo]:
    data = _read_json(zf, "fabric.mod.json")
    if not isinstance(data, dict):
        return None
    mod_id = str(data.get("id") or "unknown")
    entrypoints = _entrypoint_values(data.get("entrypoints"))
    mixins = _mixin_paths(data.get("mixins"))
    depends = data.get("depends") if isinstance(data.get("depends"), dict) else {}
    return ModInfo(
        path=Path(),
        jar_size=0,
        loader="fabric",
        mod_id=mod_id,
        name=str(data.get("name") or mod_id),
        version=str(data.get("version") or "?"),
        entrypoints=entrypoints,
        declared_mixins=mixins,
        access_widener=str(data["accessWidener"]) if data.get("accessWidener") else None,
        depends={str(k): str(v) for k, v in depends.items()},
        client_only=str(data.get("environment") or "").lower() == "client",
    )


def _from_quilt(zf: zipfile.ZipFile) -> Optional[ModInfo]:
    data = _read_json(zf, "quilt.mod.json")
    if not isinstance(data, dict):
        return None
    loader = data.get("quilt_loader") if isinstance(data.get("quilt_loader"), dict) else {}
    mod_id = str(loader.get("id") or "unknown")
    metadata = loader.get("metadata") if isinstance(loader.get("metadata"), dict) else {}
    entrypoints = _entrypoint_values(loader.get("entrypoints"))
    mixins = _mixin_paths(loader.get("mixins"))
    return ModInfo(
        path=Path(),
        jar_size=0,
        loader="quilt",
        mod_id=mod_id,
        name=str(metadata.get("name") or mod_id),
        version=str(loader.get("version") or "?"),
        entrypoints=entrypoints,
        declared_mixins=mixins,
        depends={},
    )


def _from_toml(zf: zipfile.ZipFile, name: str, loader: str) -> Optional[ModInfo]:
    text = _read_text(zf, name)
    if text is None:
        return None
    try:
        doc = tomlmini.parse(text)
    except Exception:
        return None
    mods = doc.get("mods")
    first: Dict[str, Any] = {}
    if isinstance(mods, list) and mods:
        first = mods[0] if isinstance(mods[0], dict) else {}
    mod_id = str(first.get("modId") or first.get("modid") or "unknown")
    mixins = _mixin_paths(doc.get("mixins"))
    depends: Dict[str, str] = {}
    deps = doc.get("dependencies")
    if isinstance(deps, dict):
        for dep_id, entries in deps.items():
            if isinstance(entries, list) and entries and isinstance(entries[0], dict):
                depends[str(dep_id)] = str(entries[0].get("versionRange") or "")
    return ModInfo(
        path=Path(),
        jar_size=0,
        loader=loader,
        mod_id=mod_id,
        name=str(first.get("displayName") or mod_id),
        version=str(first.get("version") or "?"),
        entrypoints=[],
        declared_mixins=mixins,
        depends=depends,
    )


def _from_mcmod(zf: zipfile.ZipFile) -> Optional[ModInfo]:
    data = _read_json(zf, "mcmod.info")
    entry: Dict[str, Any] = {}
    if isinstance(data, list) and data:
        entry = data[0] if isinstance(data[0], dict) else {}
    elif isinstance(data, dict):
        mod_list = data.get("modList")
        if isinstance(mod_list, list) and mod_list:
            entry = mod_list[0] if isinstance(mod_list[0], dict) else {}
    if not entry:
        return None
    mod_id = str(entry.get("modid") or "unknown")
    return ModInfo(
        path=Path(),
        jar_size=0,
        loader="forge-legacy",
        mod_id=mod_id,
        name=str(entry.get("name") or mod_id),
        version=str(entry.get("version") or "?"),
    )


def _discover_mixin_configs(names: List[str], declared: List[str]) -> List[str]:
    found: List[str] = []
    seen = set()

    def add(name: str) -> None:
        name = name.lstrip("/")
        if name and name not in seen:
            seen.add(name)
            found.append(name)

    for name in declared:
        add(name)
    for name in names:
        lower = name.lower()
        if not lower.endswith(".json"):
            continue
        if "refmap" in lower:
            continue
        base = lower.rsplit("/", 1)[-1]
        if base.endswith(".mixins.json") or base.endswith(".mixin.json") or base == "mixins.json":
            add(name)
    return found


def inspect_jar(path: Path) -> ModInfo:
    info = ModInfo(path=path, jar_size=path.stat().st_size, loader="unknown",
                   mod_id="unknown", name=path.stem, version="?")
    try:
        with zipfile.ZipFile(path) as zf:
            names = zf.namelist()
    except (zipfile.BadZipFile, OSError) as exc:
        info.errors.append("cannot open jar: %s" % exc)
        return info

    info.loader = _detect_loader(set(names))
    manifest: Dict[str, str] = {}
    parsed: Optional[ModInfo] = None
    # Reopen the zip: metadata parsers are easier to keep safe when each one
    # gets a fresh handle and can fail without poisoning the rest of the scan.
    at_rules, at_targets = 0, []
    try:
        with zipfile.ZipFile(path) as zf:
            manifest = _read_manifest(zf)
            if info.loader == "fabric":
                parsed = _from_fabric(zf)
            elif info.loader == "quilt":
                parsed = _from_quilt(zf)
            elif info.loader == "neoforge":
                parsed = _from_toml(zf, "META-INF/neoforge.mods.toml", "neoforge")
            elif info.loader == "forge":
                parsed = _from_toml(zf, "META-INF/mods.toml", "forge")
            elif info.loader == "forge-legacy":
                parsed = _from_mcmod(zf)
            at_rules, at_targets = _read_access_transformer(zf)
    except (zipfile.BadZipFile, OSError) as exc:
        info.errors.append("cannot read metadata: %s" % exc)

    if parsed is not None:
        parsed.path = info.path
        parsed.jar_size = info.jar_size
        parsed.errors.extend(info.errors)
        info = parsed
    else:
        info.errors.append("no supported mod metadata found")

    if "${" in info.version and manifest.get("Implementation-Version"):
        info.version = manifest["Implementation-Version"]
    if not info.declared_mixins and manifest.get("MixinConfigs"):
        info.declared_mixins = [part.strip() for part in manifest["MixinConfigs"].split(",") if part.strip()]

    info.declared_mixins = _discover_mixin_configs(names, info.declared_mixins)
    info.at_rules = at_rules
    info.at_targets = list(at_targets)
    return info


def class_entries(path: Path) -> List[str]:
    with zipfile.ZipFile(path) as zf:
        names = zf.namelist()
    result = []
    for name in names:
        if not name.endswith(".class"):
            continue
        if name == "module-info.class":
            continue
        if name.startswith("META-INF/versions/"):
            continue
        result.append(name)
    return result
