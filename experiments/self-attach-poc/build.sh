#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

rm -rf out
mkdir -p out/v1 out/v2 out/v3

javac -d out/v1 src/Agent.java src/Main.java src/Target.java
javac -d out/v2 patches/v2/Target.java
javac -d out/v3 patches/v3/Target.java

cat > out/manifest.txt <<'EOF'
Premain-Class: Agent
Agent-Class: Agent
Launcher-Agent-Class: Agent
Can-Redefine-Classes: true
Can-Retransform-Classes: true
EOF

jar cfm out/agent.jar out/manifest.txt \
  -C out/v1 Agent.class \
  -C out/v1 Main.class \
  -C out/v1 Target.class

java -cp out/v1 Main \
  "$PWD/out/agent.jar" \
  "$PWD/out/v2/Target.class" \
  "$PWD/out/v3/Target.class"
