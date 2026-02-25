#!/usr/bin/env bash

set -euo pipefail

TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
WAVES="${WAVES:-8}"
BURST_SIZE="${BURST_SIZE:-30}"
PAUSE_SECONDS="${PAUSE_SECONDS:-12}"
REQUEST_INTERVAL_SECONDS="${REQUEST_INTERVAL_SECONDS:-0.05}"
USERS="${USERS:-10}"

post_tx() {
  local user_id="$1"
  local amount="$2"
  local merchant_id="$3"
  local country="$4"

  curl -fsS -X POST "${TRANSACTION_API_BASE}/api/v1/transactions" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"${user_id}\",\"amount\":${amount},\"currency\":\"USD\",\"merchantId\":\"${merchant_id}\",\"country\":\"${country}\",\"paymentMethod\":\"CARD\"}" >/dev/null
}

echo "Generating ${WAVES} traffic waves for Grafana panels"

for wave in $(seq 1 "${WAVES}"); do
  if (( wave % 2 == 0 )); then
    mode="FRAUD"
    min_amount=12000
    max_amount=25000
    merchant="MRC-999"
  else
    mode="NORMAL"
    min_amount=30
    max_amount=900
    merchant="MRC-101"
  fi

  echo "Wave ${wave}/${WAVES} (${mode})"

  for i in $(seq 1 "${BURST_SIZE}"); do
    user_id="wave-user-$((1 + (i % USERS)))"
    amount=$((min_amount + RANDOM % (max_amount - min_amount + 1)))
    country="US"

    if [[ "${mode}" == "FRAUD" ]]; then
      country="BR"
    fi

    post_tx "${user_id}" "${amount}" "${merchant}" "${country}"
    sleep "${REQUEST_INTERVAL_SECONDS}"
  done

  echo "Wave ${wave} complete. Waiting ${PAUSE_SECONDS}s"
  sleep "${PAUSE_SECONDS}"
done

echo "Done. This pattern is useful for seeing spikes in Throughput, Fraud Alert Rate and logs."
