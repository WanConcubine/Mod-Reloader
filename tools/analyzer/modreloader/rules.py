"""Heuristic v0 classifier: signals -> tier, confidence and human reasons."""

from __future__ import annotations

from typing import List

from .model import Analysis, ModReport

# Curated seed list for 2026-era core/performance mods. This is intentionally
# small and explicit: the v0 classifier should say "known heavy" rather than
# pretend it can infer every rendering mod from bytecode alone.
NEVER_SWAP_IDS = {
    "fabric-api", "fabric-language-kotlin", "architectury", "forge", "neoforge",
    "quilt_loader", "minecraft",
}

HEAVY_IDS = {
    "sodium", "lithium", "iris", "phosphor", "ferritecore", "c2me", "modernfix",
    "vulkanmod", "embeddium", "rubidium", "optifine", "entityculling", "indium",
    "sodium-extra", "krypton", "starlight",
}

# Tier ids are names, not numbers. RESOURCE/DATA are vanilla capabilities
# (resource pack / datapack reload); the four ranks below are the project's own
# hot-add tiers: pin < fei < guifei < huanghou.
VERDICTS = {
    "RESOURCE": "vanilla resource reload (F3+T)",
    "DATA": "vanilla data reload (/reload)",
    "PIN": "code reload; removal limited",
    "FEI": "hot-swap with caveats",
    "GUIFEI": "experimental add-only; restart for full effect",
    "HUANGHOU": "needs full restart",
    "T?": "analysis failed",
}

RENDER_CORE_PREFIXES = (
    "net/minecraft/client/renderer/",
    "com/mojang/blaze3d/",
)

GAME_CORE_TARGETS = {
    "net/minecraft/client/Minecraft",
    "net/minecraft/world/level/chunk/LevelChunk",
    "net/minecraft/server/level/ServerLevel",
}


def _pretty(name: str) -> str:
    return name.replace("/", ".")


def _is_render_core(target: str) -> bool:
    return any(target.startswith(prefix) for prefix in RENDER_CORE_PREFIXES)


def classify(analysis: Analysis) -> ModReport:
    reasons: List[str] = []

    if (analysis.class_count == 0 and not analysis.has_assets and not analysis.has_data
            and analysis.errors and not analysis.bundled_jars):
        return ModReport(
            path=analysis.path,
            loader=analysis.loader,
            mod_id=analysis.mod_id,
            name=analysis.name,
            version=analysis.version,
            tier="T?",
            confidence=0.0,
            verdict=VERDICTS["T?"],
            reasons=["could not read jar: %s" % analysis.errors[0]],
            signals={"errors": list(analysis.errors)},
        )

    registry_n = len(analysis.registry_refs)
    registration_n = len(analysis.registration_refs)
    has_registration = registration_n > 0 or "DeferredRegister" in analysis.registry_refs
    content_n = len(analysis.content_refs)
    injection_n = len(analysis.core_injection_refs)
    mixin_n = analysis.mixin_class_count
    native_n = analysis.native_methods
    render_core = [t for t in analysis.mixin_targets if _is_render_core(t)]
    game_core = [t for t in analysis.mixin_targets if t in GAME_CORE_TARGETS]

    known_heavy = analysis.mod_id.lower() in HEAVY_IDS
    never_swap = analysis.mod_id.lower() in NEVER_SWAP_IDS

    if analysis.class_count == 0:
        tier = "DATA" if analysis.has_data else "RESOURCE"
        confidence = 0.9
        if analysis.has_assets:
            reasons.append("contains assets/ (resource pack content)")
        if analysis.has_data:
            reasons.append("contains data/ (data pack content)")
        if not analysis.has_assets and not analysis.has_data:
            reasons.append("no classes and no assets/data (empty or metadata-only jar)")
    elif never_swap or native_n > 0 or known_heavy:
        tier = "HUANGHOU"
        confidence = 0.95 if (known_heavy or never_swap) else 0.85
        if never_swap:
            reasons.append("loader-adjacent/library mod (v0 policy list: %s)" % analysis.mod_id)
        if known_heavy:
            reasons.append("known core/performance mod (v0 seed list: %s)" % analysis.mod_id)
        if native_n > 0:
            reasons.append("native methods / JNI detected (%d)" % native_n)
    elif render_core and injection_n > 0:
        tier = "HUANGHOU"
        confidence = 0.88
        reasons.append("mixin targets core render classes: %s"
                       % ", ".join(sorted(_pretty(t) for t in render_core)[:4]))
        reasons.append("uses invasive injections (%d kinds)" % injection_n)
    elif game_core and injection_n >= 3:
        tier = "HUANGHOU"
        confidence = 0.8
        reasons.append("mixin heavily targets core game classes: %s"
                       % ", ".join(sorted(_pretty(t) for t in game_core)[:4]))
        reasons.append("uses %d kinds of invasive injections" % injection_n)
    elif has_registration:
        tier = "GUIFEI"
        confidence = min(0.95, 0.7 + 0.08 * (registration_n > 0) + 0.06 * bool(mixin_n))
        if registration_n:
            reasons.append("registration calls: %s" % ", ".join(sorted(analysis.registration_refs)[:4]))
        if "DeferredRegister" in analysis.registry_refs:
            reasons.append("uses DeferredRegister")
        if content_n:
            reasons.append("block/item/entity/... classes referenced: %d" % content_n)
    elif mixin_n > 0:
        tier = "FEI"
        confidence = min(0.95, 0.55 + 0.1 + 0.06 * bool(injection_n) + 0.04 * (analysis.class_count >= 100))
        reasons.append("%d mixin classes in %d configs" % (mixin_n, analysis.mixin_config_count))
        if injection_n:
            reasons.append("uses invasive injections (%d kinds)" % injection_n)
    else:
        tier = "PIN"
        confidence = min(0.9, 0.55 + 0.08 * (analysis.class_count >= 50) + 0.05 * bool(analysis.entrypoint_count))
        reasons.append("no mixins and no registry content (%d classes)" % analysis.class_count)

    if analysis.mixin_targets and tier in ("FEI", "GUIFEI"):
        shown = sorted(_pretty(t) for t in analysis.mixin_targets)
        reasons.append("mixin targets (%d): %s%s" % (
            len(shown), ", ".join(shown[:3]), "…" if len(shown) > 3 else ""))
    if any(target.startswith("net/minecraft/class_") for target in analysis.mixin_targets):
        confidence = max(0.3, confidence - 0.1)
        reasons.append("mixin targets use Fabric intermediary names (class_XXX); core-class detection is limited")
    if analysis.bundled_jars:
        reasons.append("%d bundled jar(s) (Jar-in-Jar)" % analysis.bundled_jars)
    if analysis.static_mutable_fields:
        reasons.append("%d mutable static fields (state may survive a reload)" % analysis.static_mutable_fields)
    if analysis.thread_refs:
        reasons.append("thread/executor references: %s" % ", ".join(sorted(analysis.thread_refs)[:3]))
    if analysis.reflection_refs:
        reasons.append("reflection/method-handle references")
    if analysis.entrypoint_count:
        reasons.append("%d entrypoint(s)" % analysis.entrypoint_count)
    if analysis.access_widener:
        reasons.append("ships an access widener")
    if analysis.at_rules:
        reasons.append(
            "%d access-transformer rule(s) over %d target class(es): ATs apply only when a "
            "class loads, so a hot-add needs a restart (not content tier)"
            % (analysis.at_rules, len(analysis.at_targets)))
    if analysis.errors:
        reasons.append("metadata warning: %s" % analysis.errors[0])

    signals = {
        "class_count": analysis.class_count,
        "mixin_configs": analysis.mixin_config_count,
        "mixin_classes": mixin_n,
        "mixin_targets": sorted(_pretty(t) for t in analysis.mixin_targets)[:20],
        "native_methods": native_n,
        "static_mutable_fields": analysis.static_mutable_fields,
        "registry_refs": sorted(analysis.registry_refs),
        "registration_refs": sorted(analysis.registration_refs),
        "content_refs": sorted(analysis.content_refs),
        "core_injection_refs": sorted(analysis.core_injection_refs),
        "thread_refs": sorted(analysis.thread_refs),
        "reflection_refs": sorted(analysis.reflection_refs),
        "entrypoints": analysis.entrypoint_count,
        "assets": analysis.has_assets,
        "data": analysis.has_data,
        "bundled_jars": analysis.bundled_jars,
        "at_rules": analysis.at_rules,
        "at_targets": sorted(analysis.at_targets)[:20],
    }

    return ModReport(
        path=analysis.path,
        loader=analysis.loader,
        mod_id=analysis.mod_id,
        name=analysis.name,
        version=analysis.version,
        tier=tier,
        confidence=confidence,
        verdict=VERDICTS[tier],
        reasons=reasons[:5],
        signals=signals,
    )
