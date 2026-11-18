"""Download the pinned corpus from Modrinth (jars stay out of the repo).

The corpus pins an exact ``version_number`` plus a file name; this module only
resolves that version, never "latest". That keeps a green pipeline run
reproducible instead of silently testing a different jar next week.
"""

from __future__ import annotations

import hashlib
import json
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Dict, List, Optional

from .corpus import Corpus, CorpusError, Entry

API = "https://api.modrinth.com/v2"
USER_AGENT = "modreloader-pipeline/0.1 (read-only compatibility harness)"


def _get_json(url: str):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        raise CorpusError("Modrinth %s: HTTP %s %s" % (url, exc.code, exc.reason))
    except urllib.error.URLError as exc:
        raise CorpusError("cannot reach Modrinth (%s): %s" % (url, exc.reason))


def resolve_version(entry: Entry, mc: str, loader: str) -> Dict:
    query = "?loaders=%s&game_versions=%s" % (
        urllib.parse.quote(json.dumps([loader])),
        urllib.parse.quote(json.dumps([mc])),
    )
    versions = _get_json("%s/project/%s/version%s" % (API, entry.project, query))
    if not isinstance(versions, list):
        raise CorpusError("unexpected Modrinth response for %s" % entry.project)
    for version in versions:
        if version.get("version_number") == entry.version:
            return version
    seen = ", ".join(str(v.get("version_number")) for v in versions[:8])
    raise CorpusError(
        "%s: version %r is not available for %s/%s (latest: %s)"
        % (entry.project, entry.version, loader, mc, seen or "none"))


def download(entry: Entry, mods_dir: Path, mc: str, loader: str,
             pin: bool, corpus_path: Path) -> Dict[str, object]:
    version = resolve_version(entry, mc, loader)
    files = [f for f in version.get("files") or [] if f.get("filename", "").endswith(".jar")]
    if not files:
        raise CorpusError("%s %s: version has no jar file" % (entry.project, entry.version))
    chosen = next((f for f in files if f.get("filename") == entry.file), None)
    if chosen is None:
        names = ", ".join(str(f.get("filename")) for f in files)
        raise CorpusError(
            "%s %s: expected file %r, version has %s (update the corpus pin)"
            % (entry.project, entry.version, entry.file, names))
    if not chosen.get("primary") and len(files) > 1:
        raise CorpusError("%s %s: %r is not the primary file" % (entry.project, entry.version, entry.file))

    mods_dir.mkdir(parents=True, exist_ok=True)
    target = mods_dir / entry.file
    expected_sha512 = str(chosen.get("hashes", {}).get("sha512") or "").lower()

    if target.is_file():
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        if entry.sha256 and digest == entry.sha256:
            return {"entry": entry, "status": "cached", "sha256": digest, "path": target}
        if expected_sha512:
            current = hashlib.sha512(target.read_bytes()).hexdigest()
            if current == expected_sha512:
                return {"entry": entry, "status": "cached", "sha256": digest, "path": target}

    url = str(chosen.get("url") or "")
    if not url:
        raise CorpusError("%s %s: file has no download url" % (entry.project, entry.version))
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    part = target.with_suffix(target.suffix + ".part")
    sha512 = hashlib.sha512()
    with urllib.request.urlopen(request, timeout=120) as response, part.open("wb") as handle:
        while True:
            chunk = response.read(1024 * 1024)
            if not chunk:
                break
            handle.write(chunk)
            sha512.update(chunk)
    if expected_sha512 and sha512.hexdigest().lower() != expected_sha512:
        part.unlink(missing_ok=True)
        raise CorpusError("%s %s: sha512 mismatch from Modrinth" % (entry.project, entry.version))
    part.replace(target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()

    if pin:
        _pin(corpus_path, entry.id, digest, str(version.get("id") or ""))
    return {"entry": entry, "status": "downloaded", "sha256": digest, "path": target}


def _pin(corpus_path: Path, entry_id: str, sha256: str, version_id: str) -> None:
    data = json.loads(corpus_path.read_text(encoding="utf-8"))
    for raw in data.get("entries") or []:
        if raw.get("id") == entry_id:
            raw["sha256"] = sha256
            if version_id:
                raw["modrinth_version_id"] = version_id
            break
    corpus_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                           encoding="utf-8")


def fetch(corpus: Corpus, mods_dir: Path, groups: Optional[List[str]],
          pin: bool) -> List[Dict[str, object]]:
    entries = corpus.select(groups) if groups else list(corpus.entries.values())
    results: List[Dict[str, object]] = []
    for entry in entries:
        result = download(entry, mods_dir, corpus.mc, corpus.loader, pin, corpus.path)
        results.append(result)
        time.sleep(0.2)
    return results
