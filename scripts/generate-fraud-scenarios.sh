#!/usr/bin/env bash

set -euo pipefail

TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
ALERT_API_BASE="${ALERT_API_BASE:-http://localhost:8082}"
PROCESSING_WAIT_SECONDS="${PROCESSING_WAIT_SECONDS:-4}"
SCENARIO="${1:-${SCENARIO:-mixed}}"

USER_ID="scenario-user-$(date +%s)"

post_tx() {
  local amount="$1"
  local merchant="$2"
  local country="$3"
  local payment_method="${4:-CARD}"

  curl -fsS -X POST "${TRANSACTION_API_BASE}/api/v1/transactions" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"${USER_ID}\",\"amount\":${amount},\"currency\":\"USD\",\"merchantId\":\"${merchant}\",\"country\":\"${country}\",\"paymentMethod\":\"${payment_method}\"}" >/dev/null
}

run_high_amount() {
  echo "- Triggering HIGH_AMOUNT"
  post_tx 15000 "MRC-101" "US" "CARD"
}

run_velocity() {
  echo "- Triggering HIGH_VELOCITY"
  for _ in $(seq 1 7); do
    post_tx 120 "MRC-101" "US" "CARD"
    sleep 0.1
  done
}

run_country_change() {
  echo "- Triggering COUNTRY_CHANGE_IN_SHORT_WINDOW"
  post_tx 90 "MRC-101" "US" "CARD"
  sleep 0.2
  post_tx 90 "MRC-101" "BR" "CARD"
}

run_high_risk_merchant() {
  echo "- Triggering HIGH_RISK_MERCHANT"
  post_tx 200 "MRC-999" "US" "CARD"
}

echo "Running scenario '${SCENARIO}' for user ${USER_ID}"

case "${SCENARIO}" in
  high-amount)
    run_high_amount
    ;;
  velocity)
    run_velocity
    ;;
  country-change)
    run_country_change
    ;;
  high-risk-merchant)
    run_high_risk_merchant
    ;;
  mixed)
    run_high_amount
    run_velocity
    run_country_change
    run_high_risk_merchant
    ;;
  *)
    echo "Unknown scenario: ${SCENARIO}"
    echo "Supported values: high-amount | velocity | country-change | high-risk-merchant | mixed"
    exit 1
    ;;
esac

echo "Waiting ${PROCESSING_WAIT_SECONDS}s for async processing"
sleep "${PROCESSING_WAIT_SECONDS}"

echo "Alerts for ${USER_ID}:"
curl -fsS "${ALERT_API_BASE}/api/v1/alerts/users/${USER_ID}"
echo

echo "Done"
