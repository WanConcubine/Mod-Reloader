"""Hot-add compatibility pipeline (read-only corpus, opt-in game runs).

The pipeline is deliberately built on top of two things that already exist:

* the Python analyzer (``tools/analyzer``) for tier/AT/JiJ/loader facts, and
* the dev harness ``-PverifyCompat`` for the actual title-screen hot-add.

It adds the parts a matrix needs to be trustworthy: a pinned corpus, a
dependency-closure/order check ("前置"), per-jar expected outcomes (pass *and*
clean refusal), a log parser that turns harness lines into JSON, and a runner
that writes an evidence bundle instead of asking a human to copy log lines.
"""

__all__ = ["__version__"]

__version__ = "0.1.0"
