#!/usr/bin/env bash

set -euo pipefail

TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
ALERT_API_BASE="${ALERT_API_BASE:-http://localhost:8082}"
USERS="${USERS:-6}"
PROCESSING_WAIT_SECONDS="${PROCESSING_WAIT_SECONDS:-6}"

post_tx() {
  local user_id="$1"
  local amount="$2"
  local merchant_id="$3"
  local country="$4"
  local payment_method="$5"

  curl -fsS -X POST "${TRANSACTION_API_BASE}/api/v1/transactions" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"${user_id}\",\"amount\":${amount},\"currency\":\"USD\",\"merchantId\":\"${merchant_id}\",\"country\":\"${country}\",\"paymentMethod\":\"${payment_method}\"}" >/dev/null
}

PREFIX="journey-$(date +%s)"
echo "Generating user journeys with prefix: ${PREFIX}"

for i in $(seq 1 "${USERS}"); do
  user_id="${PREFIX}-user-${i}"
  echo "- ${user_id}"

  # Normal behavior
  post_tx "${user_id}" 80 "MRC-101" "US" "CARD"
  post_tx "${user_id}" 140 "MRC-202" "US" "WALLET"

  # High amount
  post_tx "${user_id}" 18000 "MRC-101" "US" "CARD"

  # High-risk merchant
  post_tx "${user_id}" 320 "MRC-999" "US" "TRANSFER"

  # Country change in short window
  post_tx "${user_id}" 110 "MRC-303" "US" "CARD"
  sleep 0.2
  post_tx "${user_id}" 130 "MRC-303" "AR" "CARD"
done

echo "Waiting ${PROCESSING_WAIT_SECONDS}s for async processing"
sleep "${PROCESSING_WAIT_SECONDS}"

echo
echo "Fetching alerts by generated user:"
for i in $(seq 1 "${USERS}"); do
  user_id="${PREFIX}-user-${i}"
  echo "${user_id}:"
  curl -fsS "${ALERT_API_BASE}/api/v1/alerts/users/${user_id}"
  echo
done

echo "Done"
