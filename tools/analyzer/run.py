#!/usr/bin/env python3
"""Run the analyzer without installing it.

    python3 tools/analyzer/run.py scan /path/to/mods
"""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from modreloader.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
