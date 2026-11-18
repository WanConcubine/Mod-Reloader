#!/usr/bin/env python3
"""Find an installed JetBrains Runtime 21 and check a Java binary.

Read-only. No game launch, no file writes. The launcher's chosen JVM and its
game JVM arguments cannot be seen from here, so the script prints the exact
values you need to enter in the launcher.

    python3 tools/jbr_check.py
    python3 tools/jbr_check.py --java /path/to/jbr/bin/java
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Iterable, List, Optional, Tuple


def _read_release(home: Path) -> Tuple[str, str, str]:
    release = home / "release"
    version = implementor = vendor = ""
    try:
        text = release.read_text(encoding="utf-8", errors="replace")
    except OSError:
        return version, implementor, vendor
    for line in text.splitlines():
        if line.startswith("JAVA_VERSION="):
            version = line.split("=", 1)[1].strip().strip('"')
        elif line.startswith("IMPLEMENTOR="):
            implementor = line.split("=", 1)[1].strip().strip('"')
        elif line.startswith("JAVA_VENDOR="):
            vendor = line.split("=", 1)[1].strip().strip('"')
    return version, implementor, vendor


def _major(version: str) -> int:
    match = re.match(r"(\d+)", version)
    return int(match.group(1)) if match else 0


def _candidate_homes() -> List[Path]:
    homes: List[Path] = []
    home = Path.home()
    if sys.platform == "darwin":
        homes += [home / "Library/Java/JavaVirtualMachines", Path("/Library/Java/JavaVirtualMachines")]
    elif os.name == "nt":
        for env in ("LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)"):
            root = os.environ.get(env)
            if root:
                homes += [Path(root) / "Programs", Path(root) / "JetBrains", Path(root) / "Java"]
        homes.append(Path("C:/Java"))
    else:
        homes += [Path("/usr/lib/jvm"), Path("/opt"), home / ".jdks",
                  home / ".local/share/JetBrains"]
    return homes


def _find_homes() -> List[Path]:
    found: List[Path] = []
    for root in _candidate_homes():
        if not root.is_dir():
            continue
        try:
            entries = list(root.iterdir())
        except OSError:
            continue
        for entry in entries:
            if not entry.is_dir():
                continue
            for candidate in (entry, entry / "Contents/Home"):
                if (candidate / "release").is_file() and candidate not in found:
                    found.append(candidate)
    return found


def _java_version(java: Path) -> Optional[str]:
    try:
        result = subprocess.run(
            [str(java), "-version"], capture_output=True, text=True, timeout=15
        )
    except (OSError, subprocess.SubprocessError):
        return None
    return (result.stderr + result.stdout).strip()


def _describe(home: Path) -> Tuple[str, str, str, str]:
    version, implementor, vendor = _read_release(home)
    java = home / "bin" / ("java.exe" if os.name == "nt" else "java")
    return version, implementor, vendor, str(java)


def _is_jbr(version: str, implementor: str, vendor: str) -> bool:
    blob = (implementor + " " + vendor).lower()
    return "jetbrains" in blob or "jbr" in blob or "jbr" in version.lower()


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--java", help="check this java executable instead of searching")
    args = parser.parse_args(argv)

    java = Path(args.java).expanduser() if args.java else None
    if java:
        if not java.is_file():
            print("not a file: %s" % java, file=sys.stderr)
            return 2
        output = _java_version(java)
        print("java: %s" % java)
        print(output or "  (could not run java -version)")
        print()
        print("next: put this path in the launcher's Java setting, then add")
        print("      -XX:+AllowEnhancedClassRedefinition to the GAME JVM arguments.")
        print("      The flag only exists on JBR; on a stock JVM it prevents startup.")
        return 0 if output and "JBR" in output.upper() else 1

    current = shutil.which("java")
    print("current java on PATH: %s" % (current or "(not found)"))
    if current:
        output = _java_version(Path(current))
        print(output or "  (could not run java -version)")
    print()

    candidates: List[Tuple[Path, str, str, str]] = []
    for home in _find_homes():
        version, implementor, vendor, java_path = _describe(home)
        if _major(version) >= 21 and _is_jbr(version, implementor, vendor):
            candidates.append((home, version, implementor, java_path))

    if not candidates:
        print("No JBR 21 installation found in the usual locations.")
        print("Download one from https://github.com/JetBrains/JetBrainsRuntime/releases")
        print("(choose a 21.0.x build for your OS/arch), extract it, then select")
        print("<extracted>/bin/java in the launcher.")
        return 1

    print("JBR 21 installations found:")
    for home, version, implementor, java_path in candidates:
        print("  %s" % java_path)
        print("    home: %s" % home)
        print("    version: %s, implementor: %s" % (version or "?", implementor or "?"))
    print()
    print("Next steps:")
    print("  1. In the launcher, select one of the java paths above.")
    print("  2. Add -XX:+AllowEnhancedClassRedefinition to the GAME JVM arguments")
    print("     (not the launcher's own JVM).")
    print("  3. Restart the game and run /modreloader jbr or look for")
    print("     '[ModReloader] ... enhancedRedefinition=true' in the log.")
    print("  4. Remove the flag if you switch back to a stock JVM: a stock JVM")
    print("     rejects that option and will not start.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
