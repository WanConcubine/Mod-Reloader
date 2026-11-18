"""A deliberately small TOML subset parser.

Only what `META-INF/mods.toml` / `META-INF/neoforge.mods.toml` actually uses:
tables, arrays of tables, strings, numbers, booleans, and flat arrays.
It is not a general TOML implementation; unknown syntax is kept as a raw string
so a single odd line cannot break a whole scan.
"""

from __future__ import annotations

import re
from typing import Any, Dict, List, Tuple


def _strip_comment(line: str) -> str:
    out = []
    quote = None
    escaped = False
    for ch in line:
        if escaped:
            out.append(ch)
            escaped = False
            continue
        if ch == "\\" and quote == '"':
            out.append(ch)
            escaped = True
            continue
        if quote:
            out.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ('"', "'"):
            quote = ch
            out.append(ch)
            continue
        if ch == "#":
            break
        out.append(ch)
    return "".join(out)


def _split_array(body: str) -> List[str]:
    parts: List[str] = []
    current: List[str] = []
    quote = None
    escaped = False
    depth = 0
    for ch in body:
        if escaped:
            current.append(ch)
            escaped = False
            continue
        if ch == "\\" and quote == '"':
            current.append(ch)
            escaped = True
            continue
        if quote:
            current.append(ch)
            if ch == quote:
                quote = None
            continue
        if ch in ('"', "'"):
            quote = ch
            current.append(ch)
            continue
        if ch in "[{":
            depth += 1
        elif ch in "]}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append("".join(current).strip())
            current = []
            continue
        current.append(ch)
    tail = "".join(current).strip()
    if tail:
        parts.append(tail)
    return parts


def _parse_scalar(value: str) -> Any:
    value = value.strip()
    if not value:
        return ""
    if value[0] in ('"', "'") and value[-1] == value[0] and len(value) >= 2:
        quote = value[0]
        body = value[1:-1]
        if quote == '"':
            body = body.replace("\\n", "\n").replace("\\t", "\t")
            body = body.replace('\\"', '"').replace("\\\\", "\\")
        return body
    low = value.lower()
    if low == "true":
        return True
    if low == "false":
        return False
    if value.startswith("[") and value.endswith("]"):
        return [_parse_scalar(item) for item in _split_array(value[1:-1])]
    if re.fullmatch(r"[+-]?\d+", value):
        try:
            return int(value)
        except ValueError:
            return value
    if re.fullmatch(r"[+-]?\d+\.\d+", value):
        try:
            return float(value)
        except ValueError:
            return value
    return value


def _navigate(root: Dict[str, Any], path: str) -> Tuple[Dict[str, Any], str]:
    parts = [p.strip() for p in path.split(".") if p.strip()]
    node = root
    for part in parts[:-1]:
        nxt = node.get(part)
        # `[[table]]` followed by `[table.sub]`: TOML attaches the sub-table to
        # the last array element. Replacing the list with a dict would lose the
        # `[[mods]]` entry (XaeroPlus ships `[mods."lithium:options"]`).
        if isinstance(nxt, list) and nxt and isinstance(nxt[-1], dict):
            node = nxt[-1]
            continue
        if not isinstance(nxt, dict):
            nxt = {}
            node[part] = nxt
        node = nxt
    return node, parts[-1]


def parse(text: str) -> Dict[str, Any]:
    root: Dict[str, Any] = {}
    current: Dict[str, Any] = root
    lines = text.splitlines()
    i = 0
    while i < len(lines):
        raw = _strip_comment(lines[i]).strip()
        i += 1
        if not raw:
            continue
        if raw.startswith("[[") and raw.endswith("]]"):
            path = raw[2:-2].strip()
            parent, key = _navigate(root, path)
            existing = parent.get(key)
            if not isinstance(existing, list):
                existing = []
                parent[key] = existing
            entry: Dict[str, Any] = {}
            existing.append(entry)
            current = entry
            continue
        if raw.startswith("[") and raw.endswith("]"):
            path = raw[1:-1].strip()
            parent, key = _navigate(root, path)
            existing = parent.get(key)
            if not isinstance(existing, dict):
                existing = {}
                parent[key] = existing
            current = existing
            continue
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        key = key.strip()
        if len(key) >= 2 and key[0] == key[-1] and key[0] in ('"', "'"):
            key = key[1:-1]
        value = value.strip()
        # Multiline arrays: keep consuming until brackets balance.
        while value.startswith("[") and value.count("[") > value.count("]") and i < len(lines):
            value += " " + _strip_comment(lines[i]).strip()
            i += 1
        current[key] = _parse_scalar(value)
    return root
