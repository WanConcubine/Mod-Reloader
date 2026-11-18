#!/usr/bin/env bash
# Stage-0 spike: implement access-transformer effects by rewriting the *caller*.
# No game, no JBR: plain JDK 21 + ASM (taken from the Gradle cache).
set -euo pipefail
cd "$(dirname "$0")"

ASM_ROOT="${ASM_ROOT:-$HOME/.gradle/caches/modules-2/files-2.1/org.ow2.asm}"
ASM_JAR="$(find "$ASM_ROOT/asm" -name 'asm-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1)"
ASM_TREE_JAR="$(find "$ASM_ROOT/asm-tree" -name 'asm-tree-*.jar' ! -name '*sources*' 2>/dev/null | sort -V | tail -1)"
if [[ -z "$ASM_JAR" || -z "$ASM_TREE_JAR" ]]; then
  echo "ASM jars not found under $ASM_ROOT; set ASM_ROOT or put asm/asm-tree jars there" >&2
  exit 2
fi

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"

rm -rf out
mkdir -p out/stub out/vanilla out/modonly out/spike

# 1. The widened stub: compile-time only, never on the runtime classpath.
"$JAVAC" -d out/stub stub/demo/Target.java

# 2. The real "vanilla" class: private members, the target we must not change.
"$JAVAC" -d out/vanilla src/demo/Target.java

# 3. "Mod" code compiled against the widened stub -> direct accesses in its bytecode.
"$JAVAC" -cp out/stub -d out/modonly src/demo/mod/ModCode.java src/demo/mod/BadCode.java

# 4. The rewrite spike itself.
"$JAVAC" -cp "out/vanilla:$ASM_JAR:$ASM_TREE_JAR" -d out/spike src/demo/spike/*.java

# 5. Run: mod classes are loaded from bytes (rewritten), never from the classpath.
"$JAVA" -cp "out/spike:out/vanilla:$ASM_JAR:$ASM_TREE_JAR" demo.spike.SpikeMain "$PWD/out/modonly/demo/mod"
