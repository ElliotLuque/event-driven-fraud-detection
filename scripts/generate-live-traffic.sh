#!/usr/bin/env bash

set -euo pipefail

TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
DURATION_SECONDS="${DURATION_SECONDS:-180}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-0.20}"
USERS="${USERS:-20}"
FRAUD_RATIO="${FRAUD_RATIO:-35}"
WEBHOOK_RATIO="${WEBHOOK_RATIO:-30}"

declare -a SAFE_MERCHANTS=("MRC-101" "MRC-202" "MRC-303")
declare -a HIGH_RISK_MERCHANTS=("MRC-999" "MRC-666" "MRC-404")
declare -a COUNTRIES=("US" "MX" "AR" "BR" "CL")
declare -a PAYMENT_METHODS=("CARD" "TRANSFER" "WALLET")

pick() {
  local -n arr_ref="$1"
  echo "${arr_ref[$((RANDOM % ${#arr_ref[@]}))]}"
}

random_amount() {
  local min="$1"
  local max="$2"
  echo $((min + RANDOM % (max - min + 1)))
}

post_tx() {
  local user_id="$1"
  local amount="$2"
  local merchant_id="$3"
  local country="$4"
  local payment_method="$5"

  local endpoint="/api/v1/transactions"
  if (( RANDOM % 100 < WEBHOOK_RATIO )); then
    endpoint="/api/v1/webhooks/transactions"
  fi

  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" -X POST "${TRANSACTION_API_BASE}${endpoint}" \
    -H "Content-Type: application/json" \
    -d "{\"userId\":\"${user_id}\",\"amount\":${amount},\"currency\":\"USD\",\"merchantId\":\"${merchant_id}\",\"country\":\"${country}\",\"paymentMethod\":\"${payment_method}\"}")

  if [[ "${status}" =~ ^2 ]]; then
    return 0
  fi

  return 1
}

declare -A USER_LAST_COUNTRY
START_TS="$(date +%s)"
END_TS=$((START_TS + DURATION_SECONDS))

TOTAL=0
OK=0
FAIL=0
FRAUDISH=0

echo "Generating live traffic for ${DURATION_SECONDS}s (users=${USERS}, fraud_ratio=${FRAUD_RATIO}%)"

while (( $(date +%s) < END_TS )); do
  user_id="live-user-$((1 + RANDOM % USERS))"
  payment_method="$(pick PAYMENT_METHODS)"

  if (( RANDOM % 100 < FRAUD_RATIO )); then
    FRAUDISH=$((FRAUDISH + 1))
    case $((RANDOM % 3)) in
      0)
        amount="$(random_amount 12000 30000)"
        merchant_id="$(pick SAFE_MERCHANTS)"
        country="$(pick COUNTRIES)"
        ;;
      1)
        amount="$(random_amount 200 3000)"
        merchant_id="$(pick HIGH_RISK_MERCHANTS)"
        country="$(pick COUNTRIES)"
        ;;
      *)
        amount="$(random_amount 100 1200)"
        merchant_id="$(pick SAFE_MERCHANTS)"
        prev_country="${USER_LAST_COUNTRY[${user_id}]:-US}"
        country="$(pick COUNTRIES)"
        if [[ "${country}" == "${prev_country}" ]]; then
          country="BR"
        fi
        ;;
    esac
  else
    amount="$(random_amount 20 1200)"
    merchant_id="$(pick SAFE_MERCHANTS)"
    country="$(pick COUNTRIES)"
  fi

  USER_LAST_COUNTRY["${user_id}"]="${country}"

  if post_tx "${user_id}" "${amount}" "${merchant_id}" "${country}" "${payment_method}"; then
    OK=$((OK + 1))
  else
    FAIL=$((FAIL + 1))
  fi

  TOTAL=$((TOTAL + 1))

  if (( TOTAL % 25 == 0 )); then
    elapsed=$(( $(date +%s) - START_TS ))
    echo "sent=${TOTAL} ok=${OK} fail=${FAIL} fraud_like=${FRAUDISH} elapsed=${elapsed}s"
  fi

  sleep "${INTERVAL_SECONDS}"
done

echo "Done. sent=${TOTAL} ok=${OK} fail=${FAIL} fraud_like=${FRAUDISH}"
echo "Open Grafana: http://localhost:3000 (Fraud Detection Observability / Fraud Alerting Live)"
