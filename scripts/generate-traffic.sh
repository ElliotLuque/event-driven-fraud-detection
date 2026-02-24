#!/usr/bin/env bash

set -euo pipefail

TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
ALERT_API_BASE="${ALERT_API_BASE:-http://localhost:8082}"
REQUEST_COUNT="${REQUEST_COUNT:-30}"
SLEEP_SECONDS="${SLEEP_SECONDS:-0.2}"

USER_ID="load-user-$(date +%s)"

echo "Generating ${REQUEST_COUNT} transactions for ${USER_ID}"

for i in $(seq 1 "${REQUEST_COUNT}"); do
  amount=$((100 + (i * 500)))

  curl -fsS -X POST "${TRANSACTION_API_BASE}/api/v1/transactions" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"${USER_ID}\",\"amount\":${amount},\"currency\":\"USD\",\"merchantId\":\"MRC-999\",\"country\":\"US\",\"paymentMethod\":\"CARD\"}" >/dev/null

  if (( i % 10 == 0 )); then
    echo "Sent ${i}/${REQUEST_COUNT}"
  fi

  sleep "${SLEEP_SECONDS}"
done

echo "Waiting for async processing"
sleep 4

echo "Alerts for ${USER_ID}:"
curl -fsS "${ALERT_API_BASE}/api/v1/alerts/users/${USER_ID}"
echo

echo "Done"
