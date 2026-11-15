#!/usr/bin/env bash
# Builds a minimal, server-safe fixture mod used to verify the server-side
# hot-add success path. No client classes, registries, mixins or payloads.
#
# Usage: ./build.sh [output-dir]        (default output: /tmp/servertest)
# Prerequisite: run ./gradlew once so build/moddev/serverLegacyClasspath.txt exists.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CP_FILE="$ROOT/build/moddev/serverLegacyClasspath.txt"
OUT="${1:-/tmp/servertest}"

if [ ! -f "$CP_FILE" ]; then
  echo "missing $CP_FILE; run ./gradlew runServer once first" >&2
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

CP="$(paste -sd: "$CP_FILE")"
rm -rf "$OUT/classes" && mkdir -p "$OUT/classes"
"$JDK_HOME/bin/javac" -proc:none -cp "$CP" -d "$OUT/classes" \
  "$(dirname "$0")"/src/dev/modreloader/servertest/ServerTestMod.java
"$JDK_HOME/bin/jar" cf "$OUT/servertest-1.0.0.jar" \
  -C "$OUT/classes" . -C "$(dirname "$0")/resources" .
echo "built $OUT/servertest-1.0.0.jar"
