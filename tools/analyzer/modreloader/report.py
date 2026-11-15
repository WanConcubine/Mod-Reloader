"""Human and machine readable reports."""

from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Iterable, List

from .model import ModReport

TIER_ORDER = ["RESOURCE", "DATA", "PIN", "FEI", "GUIFEI", "HUANGHOU", "T?"]


def _clip(text: str, width: int) -> str:
    if len(text) <= width:
        return text
    if width <= 1:
        return text[:width]
    return text[: width - 1] + "…"


def _summary(reports: List[ModReport]) -> str:
    by_loader = {}
    by_tier = {tier: 0 for tier in TIER_ORDER}
    for report in reports:
        by_loader[report.loader] = by_loader.get(report.loader, 0) + 1
        by_tier[report.tier] = by_tier.get(report.tier, 0) + 1
    hot = sum(by_tier[t] for t in ("RESOURCE", "DATA", "PIN", "FEI"))
    failed = by_tier.get("T?", 0)
    total = len(reports) or 1
    loaders = "  ".join("%s: %d" % (k, v) for k, v in sorted(by_loader.items()))
    tiers = "  ".join("%s: %d" % (t, by_tier[t]) for t in TIER_ORDER)
    return (
        "Scanned %d jars\n"
        "  loaders: %s\n"
        "  tiers:   %s\n"
        "  no restart needed (RESOURCE-DATA-PIN-FEI): %d/%d (%.0f%%)\n"
        "  restart needed (GUIFEI-HUANGHOU):         %d/%d (%.0f%%)\n"
        "  analysis failed:        %d\n"
        % (len(reports), loaders, tiers, hot, total, 100.0 * hot / total,
           len(reports) - hot - failed, total, 100.0 * (len(reports) - hot - failed) / total,
           failed)
    )


def render_text(reports: List[ModReport], verbose: bool = False) -> str:
    lines = [_summary(reports)]
    header = "%-26s %-10s %-8s %-8s %-5s %s" % ("MOD", "VERSION", "LOADER", "TIER", "CONF", "VERDICT")
    lines.append("")
    lines.append(header)
    lines.append("-" * len(header))
    for report in sorted(reports, key=lambda r: (TIER_ORDER.index(r.tier), r.mod_id)):
        lines.append("%-26s %-10s %-8s %-8s %-5.2f %s" % (
            _clip(report.mod_id, 26),
            _clip(report.version, 10),
            _clip(report.loader, 8),
            report.tier,
            report.confidence,
            report.verdict,
        ))
        if verbose or report.tier in ("GUIFEI", "HUANGHOU"):
            for reason in report.reasons:
                lines.append("    - %s" % reason)

    attention = [r for r in reports if r.tier in ("GUIFEI", "HUANGHOU")]
    if attention:
        lines.append("")
        lines.append("Needs a restart (%d):" % len(attention))
        for report in sorted(attention, key=lambda r: (TIER_ORDER.index(r.tier), r.mod_id)):
            lines.append("  %-24s %-8s %s" % (
                _clip(report.mod_id, 24), report.tier, report.reasons[0] if report.reasons else ""))
    return "\n".join(lines) + "\n"


def render_markdown(reports: List[ModReport]) -> str:
    lines = ["# Mod reload report", ""]
    lines.append("Generated: %s" % datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))
    lines.append("")
    lines.append("```")
    lines.append(_summary(reports).rstrip())
    lines.append("```")
    lines.append("")
    lines.append("| Mod | Version | Loader | Tier | Confidence | Verdict | Top reason |")
    lines.append("|---|---|---|---|---|---|---|")
    for report in sorted(reports, key=lambda r: (TIER_ORDER.index(r.tier), r.mod_id)):
        reason = report.reasons[0].replace("|", "\\|") if report.reasons else ""
        lines.append("| %s | %s | %s | %s | %.2f | %s | %s |" % (
            report.mod_id.replace("|", "\\|"),
            report.version.replace("|", "\\|"),
            report.loader,
            report.tier,
            report.confidence,
            report.verdict,
            reason,
        ))
    lines.append("")
    lines.append("## Details (GUIFEI/HUANGHOU)")
    lines.append("")
    for report in sorted(reports, key=lambda r: (TIER_ORDER.index(r.tier), r.mod_id)):
        if report.tier not in ("GUIFEI", "HUANGHOU"):
            continue
        lines.append("### %s (%s) — %s" % (report.mod_id, report.version, report.tier))
        lines.append("")
        for reason in report.reasons:
            lines.append("- %s" % reason)
        lines.append("")
    return "\n".join(lines)


def render_json(reports: Iterable[ModReport]) -> str:
    payload = {
        "generated_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "tool": "modreloader-analyzer/0.1",
        "count": len(reports),
        "mods": [report.to_dict() for report in reports],
    }
    return json.dumps(payload, indent=2, ensure_ascii=False) + "\n"
