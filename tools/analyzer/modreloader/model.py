"""Data model shared by the analyzer, rules and reporters."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Set


@dataclass
class Analysis:
    path: Path
    loader: str
    mod_id: str
    name: str
    version: str
    jar_size: int = 0
    class_count: int = 0
    mixin_config_count: int = 0
    mixin_class_count: int = 0
    mixin_targets: Set[str] = field(default_factory=set)
    native_methods: int = 0
    static_mutable_fields: int = 0
    registry_refs: Set[str] = field(default_factory=set)
    registration_refs: Set[str] = field(default_factory=set)
    content_refs: Set[str] = field(default_factory=set)
    core_injection_refs: Set[str] = field(default_factory=set)
    thread_refs: Set[str] = field(default_factory=set)
    reflection_refs: Set[str] = field(default_factory=set)
    entrypoint_count: int = 0
    has_assets: bool = False
    has_data: bool = False
    access_widener: bool = False
    at_rules: int = 0
    at_targets: Set[str] = field(default_factory=set)
    bundled_jars: int = 0
    errors: List[str] = field(default_factory=list)


@dataclass
class ModReport:
    path: Path
    loader: str
    mod_id: str
    name: str
    version: str
    tier: str
    confidence: float
    verdict: str
    reasons: List[str]
    signals: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "path": str(self.path),
            "loader": self.loader,
            "id": self.mod_id,
            "name": self.name,
            "version": self.version,
            "tier": self.tier,
            "confidence": round(self.confidence, 2),
            "verdict": self.verdict,
            "reasons": list(self.reasons),
            "signals": dict(self.signals),
        }
