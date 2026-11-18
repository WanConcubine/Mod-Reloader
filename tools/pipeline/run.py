#!/usr/bin/env python3
"""Run the hot-add compatibility pipeline without installing it.

    python3 tools/pipeline/run.py check --mods-dir /tmp/modreloader-corpus
"""

import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

from modreloader_pipeline.cli import main  # noqa: E402

if __name__ == "__main__":
    raise SystemExit(main())
