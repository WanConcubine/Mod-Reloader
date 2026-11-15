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


def read_configs(jar_path, config_names: List[str]) -> List[MixinConfig]:
    configs: List[MixinConfig] = []
    try:
        with zipfile.ZipFile(jar_path) as zf:
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
                        if "." in entry:
                            full = entry
                        elif package:
                            full = "%s.%s" % (package, entry)
                        else:
                            full = entry
                        classes.append(full)
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
