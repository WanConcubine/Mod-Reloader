#!/usr/bin/env bash
# Builds the worldgen fixture: one custom dimension as a data pack.
#
# Usage: ./build.sh [output-dir]        (default output: /tmp/wgfixture)
# Prerequisite: run ./gradlew once so build/moddev/serverLegacyClasspath.txt exists.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CP_FILE="$ROOT/build/moddev/serverLegacyClasspath.txt"
if [ ! -f "$CP_FILE" ]; then
  CP_FILE="$ROOT/build/moddev/clientLegacyClasspath.txt"
fi
OUT="${1:-/tmp/wgfixture}"

if [ ! -f "$CP_FILE" ]; then
  echo "missing build/moddev/{server,client}LegacyClasspath.txt; run ./gradlew build once" >&2
  exit 1
fi

# A JDK 21 with both javac and jar. JBR ships javac but no jar, so prefer Temurin.
JDK_HOME="${JAVA_HOME:-}"
if [ -z "$JDK_HOME" ]; then
  for candidate in "$HOME"/.gradle/jdks/eclipse_adoptium-21-*/jdk-21*/Contents/Home \
                   "$HOME"/.gradle/jdks/eclipse_adoptium-21-*/jdk-21*; do
    if [ -x "$candidate/bin/javac" ] && [ -x "$candidate/bin/jar" ]; then
      JDK_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "$JDK_HOME" ] || [ ! -x "$JDK_HOME/bin/javac" ]; then
  echo "no JDK 21 with javac found; set JAVA_HOME" >&2
  exit 1
fi

# The merged jar holds the game classes; it also embeds sources, so point
# -sourcepath elsewhere or javac tries to compile them.
CP="$ROOT/build/moddev/artifacts/neoforge-21.1.265-merged.jar:$(paste -sd: "$CP_FILE")"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
mkdir -p "$OUT/emptysrc"
"$JDK_HOME/bin/javac" -proc:none -sourcepath "$OUT/emptysrc" -cp "$CP" -d "$OUT/classes" \
  "$(dirname "$0")"/src/dev/modreloader/wgfixture/WorldgenFixtureMod.java
"$JDK_HOME/bin/jar" cf "$OUT/wgfixture-1.0.0.jar" \
  -C "$OUT/classes" . -C "$(dirname "$0")/resources" .
echo "built $OUT/wgfixture-1.0.0.jar"
