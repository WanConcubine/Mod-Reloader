"""Mixin config discovery and target resolution.

A mixin config lists *mixin classes*, not target classes. To find targets we
look at the constant pool of each mixin class: `@Mixin(Minecraft.class)` leaves
a `net/minecraft/client/Minecraft` class reference behind, which is good enough
for a v0 classifier and needs no annotation decoding.
"""

from __future__ import annotations

import zipfile
from dataclasses import dataclass, field
from typing import Dict, List, Set

from . import metadata


@dataclass
class MixinConfig:
    path: str
    package: str = ""
    mixin_classes: List[str] = field(default_factory=list)
    client: bool = False
    server: bool = False
    plugin: str = ""
    refmap: str = ""
    compatibility: str = ""


def _qualify(entry: str, package: str, class_names) -> str:
    """Resolve a config entry against the config's `package`, by presence.

    Mixin resolves every entry relative to `package`; entries may still carry
    sub-package dots (`client.Foo`, `server.Bar`), which is the common shape in
    real NeoForge mods. Treating any dotted entry as already-qualified (the old
    heuristic) turned all of JourneyMap's 26 mixins into "0 mixin classes" and
    classified a mixin mod as PIN — the dangerous direction.

    Candidates are tried by whether the class really exists in this jar, so a
    fully-qualified or unusual entry is not lost either; without evidence the
    spec-conformant relative form wins.
    """
    candidates = []
    if package and entry != package and not entry.startswith(package + "."):
        candidates.append("%s.%s" % (package, entry))
    candidates.append(entry)
    if class_names:
        for candidate in candidates:
            if candidate.replace(".", "/") + ".class" in class_names:
                return candidate
    return candidates[0]


def read_configs(jar_path, config_names: List[str]) -> List[MixinConfig]:
    configs: List[MixinConfig] = []
    try:
        with zipfile.ZipFile(jar_path) as zf:
            class_names = {name for name in zf.namelist() if name.endswith(".class")}
            for name in config_names:
                data = metadata._read_json(zf, name)
                if not isinstance(data, dict):
                    continue
                package = str(data.get("package") or "").replace("/", ".").strip(".")
                classes: List[str] = []
                for key in ("mixins", "client", "server"):
                    entries = data.get(key)
                    if not isinstance(entries, list):
                        continue
                    for entry in entries:
                        if not isinstance(entry, str):
                            continue
                        classes.append(_qualify(entry, package, class_names))
                configs.append(MixinConfig(
                    path=name,
                    package=package,
                    mixin_classes=classes,
                    client=bool(data.get("client")),
                    server=bool(data.get("server")),
                    plugin=str(data.get("plugin") or ""),
                    refmap=str(data.get("refmap") or ""),
                    compatibility=str(data.get("compatibilityLevel") or ""),
                ))
    except (zipfile.BadZipFile, OSError):
        pass
    return configs


def resolve_targets(configs: List[MixinConfig], classes: Dict[str, object]) -> Set[str]:
    """Return internal names of likely Minecraft targets.

    `classes` maps internal name (a/b/C) to a ParsedClass. We only keep refs
    into Minecraft's own namespaces so mod-library refs do not pollute the set.
    """
    targets: Set[str] = set()
    prefixes = (
        "net/minecraft/",
        "net/neoforged/",
        "net/minecraftforge/",
        "com/mojang/",
    )
    for config in configs:
        for mixin_class in config.mixin_classes:
            internal = mixin_class.replace(".", "/")
            parsed = classes.get(internal)
            if parsed is None:
                continue
            found = getattr(parsed, "mixin_targets", None)
            if found:
                for ref in found:
                    if ref.startswith(prefixes):
                        targets.add(ref)
                continue
            # Fallback for class files whose annotations could not be decoded.
            for ref in getattr(parsed, "class_refs", ()):
                if ref.startswith(prefixes):
                    targets.add(ref)
    return targets
