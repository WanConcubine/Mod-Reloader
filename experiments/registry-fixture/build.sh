#!/usr/bin/env bash
# Builds the custom-registry fixture: one built-in registry + one entry, created
# in the mod constructor. Exercises the hot-add NewRegistryEvent replay.
#
# Usage: ./build.sh [output-dir]        (default output: /tmp/registryfixture)
# Prerequisite: run ./gradlew once so build/moddev/serverLegacyClasspath.txt exists.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CP_FILE="$ROOT/build/moddev/serverLegacyClasspath.txt"
if [ ! -f "$CP_FILE" ]; then
  CP_FILE="$ROOT/build/moddev/clientLegacyClasspath.txt"
fi
OUT="${1:-/tmp/registryfixture}"

if [ ! -f "$CP_FILE" ]; then
  echo "missing build/moddev/{server,client}LegacyClasspath.txt; run ./gradlew build once" >&2
  exit 1
fi

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

CP="$ROOT/build/moddev/artifacts/neoforge-21.1.265-merged.jar:$(paste -sd: "$CP_FILE")"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
mkdir -p "$OUT/emptysrc"
"$JDK_HOME/bin/javac" -proc:none -sourcepath "$OUT/emptysrc" -cp "$CP" -d "$OUT/classes" \
  "$(dirname "$0")"/src/dev/modreloader/registryfixture/RegistryFixtureMod.java
"$JDK_HOME/bin/jar" cf "$OUT/registryfixture-1.0.0.jar" \
  -C "$OUT/classes" . -C "$(dirname "$0")/resources" .
echo "built $OUT/registryfixture-1.0.0.jar"