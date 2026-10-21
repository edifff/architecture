#!/usr/bin/env bash
# Матрица масштабирования: гоняет k6 по слою при 1/2/4 репликах и собирает CSV.
#
#   perf/scale.sh chat    [VUS] [DURATION]
#   perf/scale.sh storage [VUS] [DURATION]
#
# Результат: perf/results-<layer>.csv
set -euo pipefail

LAYER="${1:-chat}"
VUS="${2:-40}"
DURATION="${3:-25s}"
REPLICAS="${REPLICAS:-1 2 4}"
COMPOSE=(docker compose -f compose.yaml -f perf/compose.scale.yaml)
NETWORK="arch-labs_default"
OUT="perf/results-${LAYER}.csv"

cd "$(dirname "$0")/.."

echo "layer,replicas,iterations,rps,avg_ms,p50_ms,p95_ms,p99_ms" > "$OUT"

"${COMPOSE[@]}" up -d --build db storage chat
sleep 8

for n in $REPLICAS; do
  echo "=== ${LAYER}: ${n} replica(s) ==="
  "${COMPOSE[@]}" up -d --no-recreate --scale "${LAYER}=${n}" "$LAYER"
  sleep 8

  # Одинаковый стартовый объём данных для каждого прогона: иначе History
  # возвращает всё больше строк и латентность растёт от прогона к прогону.
  docker compose exec -T db psql -U chat -d chat \
    -c "TRUNCATE TABLE messages, users RESTART IDENTITY CASCADE" >/dev/null

  SUMMARY="/scripts/summary-${LAYER}-${n}.json"
  docker run --rm --network "$NETWORK" \
    --user "$(id -u):$(id -g)" \
    -v "$PWD/proto/src/main/proto:/proto:ro" \
    -v "$PWD/perf/k6:/scripts" \
    -e "REPLICAS=${n}" -e "VUS=${VUS}" -e "DURATION=${DURATION}" \
    grafana/k6 run --summary-export="$SUMMARY" "/scripts/${LAYER}.js" >/dev/null

  python3 - "$LAYER" "$n" "perf/k6/summary-${LAYER}-${n}.json" "$OUT" <<'PY'
import json, sys
layer, replicas, path, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
with open(path) as f:
    m = json.load(f)["metrics"]
dur = m["grpc_req_duration"]
iters = m["iterations"]
row = [
    layer,
    replicas,
    int(iters["count"]),
    f'{iters["rate"]:.1f}',
    f'{dur["avg"]:.1f}',
    f'{dur["p(50)"]:.1f}',
    f'{dur["p(95)"]:.1f}',
    f'{dur["p(99)"]:.1f}',
]
with open(out, "a") as f:
    f.write(",".join(str(v) for v in row) + "\n")
print(",".join(str(v) for v in row))
PY
done

"${COMPOSE[@]}" up -d --no-recreate --scale "${LAYER}=1" "$LAYER"
echo
echo "Сводка: $OUT"
