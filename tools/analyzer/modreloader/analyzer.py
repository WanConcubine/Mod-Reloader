"""Jar walker: turns a mod jar into an Analysis record."""

from __future__ import annotations

import tempfile
import zipfile
from pathlib import Path
from typing import Dict, List, Set

from . import metadata, mixins
from .classfile import ClassFileError, ParsedClass, parse_class
from .model import Analysis

MAX_CLASS_BYTES = 2 * 1024 * 1024
MAX_CLASSES = 30000

REGISTRY_MARKERS = (
    "net/minecraft/core/Registry",
    "net/minecraft/core/registries",
    "net/minecraft/core/BuiltInRegistries",
    "net/neoforged/neoforge/registries",
    "net/minecraftforge/registries",
    "net/minecraftforge/event/RegisterEvent",
    "net/neoforged/neoforge/registries/RegisterEvent",
    "net/neoforged/neoforge/registries/DeferredRegister",
    "DeferredRegister",
    "RegistryObject",
    "IForgeRegistry",
)

CONTENT_MARKERS = (
    "net/minecraft/world/level/block/Block",
    "net/minecraft/world/item/Item",
    "net/minecraft/world/level/block/entity/BlockEntity",
    "net/minecraft/world/entity/EntityType",
    "net/minecraft/world/inventory/MenuType",
    "net/minecraft/world/item/crafting/RecipeType",
    "net/minecraft/sounds/SoundEvent",
    "net/minecraft/core/particles/ParticleType",
    "net/minecraft/world/item/CreativeModeTab",
    "net/minecraft/world/level/material/Fluid",
    "net/minecraft/world/effect/MobEffect",
    "net/minecraft/world/entity/ai/attributes/Attribute",
    "net/minecraft/world/level/levelgen/feature/Feature",
    "net/minecraft/world/level/levelgen/structure/Structure",
)

INJECTION_MARKERS = (
    "org/spongepowered/asm/mixin/injection/Redirect",
    "org/spongepowered/asm/mixin/injection/Overwrite",
    "org/spongepowered/asm/mixin/injection/ModifyVariable",
    "org/spongepowered/asm/mixin/injection/ModifyArg",
    "org/spongepowered/asm/mixin/injection/ModifyConstant",
    "org/spongepowered/asm/mixin/injection/ModifyReturnValue",
    "org/spongepowered/asm/mixin/gen/Accessor",
    "org/spongepowered/asm/mixin/gen/Invoker",
)

THREAD_MARKERS = (
    "java/lang/Thread",
    "java/util/concurrent/Executor",
    "java/util/concurrent/ExecutorService",
    "java/util/concurrent/ScheduledExecutorService",
    "java/util/concurrent/ForkJoinPool",
    "java/util/concurrent/CompletableFuture",
)

REFLECTION_MARKERS = (
    "java/lang/reflect/",
    "java/lang/invoke/MethodHandles",
)


def _match(refs: Set[str], markers: tuple) -> Set[str]:
    found: Set[str] = set()
    for ref in refs:
        for marker in markers:
            if ref == marker or ref.startswith(marker + "/") or ref.startswith(marker + "$"):
                found.add(marker)
                break
    return found


def _merge(base: Analysis, sub: Analysis, source: str) -> None:
    base.class_count += sub.class_count
    base.mixin_config_count += sub.mixin_config_count
    base.mixin_class_count += sub.mixin_class_count
    base.mixin_targets |= sub.mixin_targets
    base.native_methods += sub.native_methods
    base.static_mutable_fields += sub.static_mutable_fields
    base.registry_refs |= sub.registry_refs
    base.registration_refs |= sub.registration_refs
    base.content_refs |= sub.content_refs
    base.core_injection_refs |= sub.core_injection_refs
    base.thread_refs |= sub.thread_refs
    base.reflection_refs |= sub.reflection_refs
    base.entrypoint_count += sub.entrypoint_count
    base.has_assets = base.has_assets or sub.has_assets
    base.has_data = base.has_data or sub.has_data
    base.bundled_jars += 1 + sub.bundled_jars
    for error in sub.errors:
        if len(base.errors) < 5:
            base.errors.append("%s: %s" % (source, error))


def _nested_jars(path: Path) -> List[tuple]:
    found = []
    try:
        with zipfile.ZipFile(path) as zf:
            for name in zf.namelist():
                if not name.endswith(".jar"):
                    continue
                if not (name.startswith("META-INF/jars/") or name.startswith("META-INF/jarjar/")):
                    continue
                if zf.getinfo(name).file_size > 64 * 1024 * 1024:
                    continue
                found.append((name, zf.read(name)))
    except (zipfile.BadZipFile, OSError):
        return []
    return found[:32]


def analyze(path: Path, depth: int = 0) -> Analysis:
    info = metadata.inspect_jar(path)
    analysis = Analysis(
        path=path,
        loader=info.loader,
        mod_id=info.mod_id,
        name=info.name,
        version=info.version,
        jar_size=info.jar_size,
        entrypoint_count=len(info.entrypoints),
        access_widener=bool(info.access_widener),
        errors=list(info.errors),
    )

    try:
        names = metadata.class_entries(path)
    except (zipfile.BadZipFile, OSError) as exc:
        analysis.errors.append("cannot list classes: %s" % exc)
        return analysis

    configs = mixins.read_configs(path, info.declared_mixins)
    analysis.mixin_config_count = len(configs)

    classes: Dict[str, ParsedClass] = {}
    registry_refs: Set[str] = set()
    registration_refs: Set[str] = set()
    content_refs: Set[str] = set()
    injection_refs: Set[str] = set()
    thread_refs: Set[str] = set()
    reflection_refs: Set[str] = set()
    native = 0
    static_mutable = 0

    try:
        with zipfile.ZipFile(path) as zf:
            jar_names = zf.namelist()
            analysis.has_assets = any(name.startswith("assets/") for name in jar_names)
            analysis.has_data = any(name.startswith("data/") for name in jar_names)
            for name in names[:MAX_CLASSES]:
                try:
                    entry = zf.getinfo(name)
                    if entry.file_size > MAX_CLASS_BYTES:
                        continue
                    parsed = parse_class(zf.read(name))
                except (ClassFileError, KeyError, OSError, RuntimeError):
                    continue
                classes[parsed.name] = parsed
                native += parsed.native_methods
                static_mutable += parsed.static_mutable_fields
                registry_refs.update(_match(parsed.class_refs, REGISTRY_MARKERS))
                for cls_name, method_name in parsed.method_refs:
                    if method_name not in ("register", "registerSimple"):
                        continue
                    if (cls_name == "net/minecraft/core/Registry"
                            or cls_name.endswith("/DeferredRegister")
                            or cls_name.endswith("/RegisterEvent")
                            or cls_name.endswith("/BuiltInRegistries")):
                        registration_refs.add("%s#%s" % (cls_name, method_name))
                content_refs.update(_match(parsed.class_refs, CONTENT_MARKERS))
                thread_refs.update(_match(parsed.class_refs, THREAD_MARKERS))
                reflection_refs.update(_match(parsed.class_refs, REFLECTION_MARKERS))
                joined = "\n".join(parsed.strings)
                for marker in INJECTION_MARKERS:
                    if marker in joined:
                        injection_refs.add(marker)
                for desc in parsed.annotations:
                    for marker in INJECTION_MARKERS:
                        if marker in desc:
                            injection_refs.add(marker)
                if "loadLibrary" in joined:
                    native += 1
    except (zipfile.BadZipFile, OSError) as exc:
        analysis.errors.append("cannot read classes: %s" % exc)

    targets = mixins.resolve_targets(configs, classes)
    mixin_classes = {name for config in configs for name in config.mixin_classes}
    present_mixins = sum(1 for name in mixin_classes if name.replace(".", "/") in classes)

    analysis.class_count = len(classes)
    analysis.mixin_class_count = present_mixins
    analysis.mixin_targets = targets
    analysis.native_methods = native
    analysis.static_mutable_fields = static_mutable
    analysis.registry_refs = registry_refs
    analysis.registration_refs = registration_refs
    analysis.content_refs = content_refs
    analysis.core_injection_refs = injection_refs
    analysis.thread_refs = thread_refs
    analysis.reflection_refs = reflection_refs

    # Fabric API and many "bundle" mods ship their real code as nested jars.
    if depth == 0:
        nested = _nested_jars(path)
        if nested:
            with tempfile.TemporaryDirectory(prefix="modreloader-") as tmp:
                for index, (name, data) in enumerate(nested):
                    nested_path = Path(tmp) / ("nested-%d.jar" % index)
                    try:
                        nested_path.write_bytes(data)
                        sub = analyze(nested_path, depth + 1)
                    except Exception as exc:  # one bad nested jar must not stop us
                        analysis.errors.append("%s: %s" % (name, exc))
                        continue
                    _merge(analysis, sub, name)
    return analysis
