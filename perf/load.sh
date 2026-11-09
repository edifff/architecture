#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUT="$ROOT/perf/target/classes"
mkdir -p "$OUT"
javac -d "$OUT" "$ROOT/perf/src/main/java/ru/archlabs/perf/HttpLoadClient.java"

USERS=${1:-10}
MESSAGES=${2:-20}
BASE=${3:-http://localhost:8080}
java -cp "$OUT" ru.archlabs.perf.HttpLoadClient \
  --base "$BASE" --users "$USERS" --messages "$MESSAGES"
