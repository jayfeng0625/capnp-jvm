#!/bin/bash
# Drives BenchHarness across in-process configs, one fresh JVM per config.
# Usage: run.sh <label> [jdk_home]
set -e
LABEL="${1:-run}"
JHOME="${2:-$JAVA_HOME}"
JAVA="$JHOME/bin/java"
CP="runtime/target/classes:benchmark/target/classes"

# case  mode          measureIters  reps  warmupIters
CONFIGS=(
  "carsales object        12000  5 12000"
  "carsales bytes          8000  5 8000"
  "carsales bytes-packed   4000  5 4000"
  "catrank  object         1500  5 1500"
  "catrank  bytes          1200  5 1200"
  "catrank  bytes-packed    800  5 800"
  "eval     object       150000  5 150000"
  "eval     bytes        120000  5 120000"
  "eval     bytes-packed  60000  5 60000"
)

echo "# label=$LABEL java=$($JAVA -version 2>&1 | head -1 | tr -d '\n')"
for cfg in "${CONFIGS[@]}"; do
  # shellcheck disable=SC2086
  $JAVA -cp "$CP" org.capnproto.benchmark.BenchHarness $cfg 2>/dev/null
done
