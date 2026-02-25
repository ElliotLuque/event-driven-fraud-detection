#!/usr/bin/env bash

set -euo pipefail

TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
TOTAL_REQUESTS="${TOTAL_REQUESTS:-300}"
ALERT_PERCENT="${ALERT_PERCENT:-18}"
FOUR_XX_PERCENT="${FOUR_XX_PERCENT:-10}"
FIVE_XX_PERCENT="${FIVE_XX_PERCENT:-10}"
USERS="${USERS:-40}"
SLEEP_SECONDS="${SLEEP_SECONDS:-0.06}"
KAFKA_SERVICE_NAME="${KAFKA_SERVICE_NAME:-kafka}"
COMPOSE_CMD="${COMPOSE_CMD:-docker compose}"

SAFE_MERCHANTS=("MRC-101" "MRC-202" "MRC-303")
RISKY_MERCHANTS=("MRC-999" "MRC-666" "MRC-404")

count_4xx=$((TOTAL_REQUESTS * FOUR_XX_PERCENT / 100))
count_5xx=$((TOTAL_REQUESTS * FIVE_XX_PERCENT / 100))
count_valid=$((TOTAL_REQUESTS - count_4xx - count_5xx))
count_alert=$((TOTAL_REQUESTS * ALERT_PERCENT / 100))

if (( count_valid <= 0 )); then
  echo "Invalid distribution: valid requests must be > 0"
  exit 1
fi

if (( count_alert > count_valid )); then
  count_alert="${count_valid}"
fi

count_normal=$((count_valid - count_alert))

kafka_stopped=0

cleanup() {
  if (( kafka_stopped == 1 )); then
    echo "Restarting Kafka (${KAFKA_SERVICE_NAME})..."
    ${COMPOSE_CMD} start "${KAFKA_SERVICE_NAME}" >/dev/null || true
  fi
}

trap cleanup EXIT

pick_safe_merchant() {
  echo "${SAFE_MERCHANTS[$((RANDOM % ${#SAFE_MERCHANTS[@]}))]}"
}

pick_risky_merchant() {
  echo "${RISKY_MERCHANTS[$((RANDOM % ${#RISKY_MERCHANTS[@]}))]}"
}

post_payload() {
  local payload="$1"
  curl -s -o /dev/null -w "%{http_code}" -X POST "${TRANSACTION_API_BASE}/api/v1/transactions" \
    -H "Content-Type: application/json" \
    -d "${payload}"
}

build_valid_payload() {
  local user_id="$1"
  local amount="$2"
  local merchant_id="$3"
  local country="$4"
  printf '{"userId":"%s","amount":%s,"currency":"USD","merchantId":"%s","country":"%s","paymentMethod":"CARD"}' \
    "${user_id}" "${amount}" "${merchant_id}" "${country}"
}

sent_total=0
ok_2xx=0
err_4xx=0
err_5xx=0
other_status=0

register_status() {
  local code="$1"
  sent_total=$((sent_total + 1))

  case "${code}" in
    2*) ok_2xx=$((ok_2xx + 1)) ;;
    4*) err_4xx=$((err_4xx + 1)) ;;
    5*) err_5xx=$((err_5xx + 1)) ;;
    *)  other_status=$((other_status + 1)) ;;
  esac

  if (( sent_total % 25 == 0 )); then
    echo "progress: sent=${sent_total}/${TOTAL_REQUESTS} 2xx=${ok_2xx} 4xx=${err_4xx} 5xx=${err_5xx} other=${other_status}"
  fi
}

send_normal_transactions() {
  local count="$1"
  local i
  for i in $(seq 1 "${count}"); do
    local user_id="mix-user-$((1 + RANDOM % USERS))"
    local amount="$((30 + RANDOM % 970))"
    local merchant_id
    merchant_id="$(pick_safe_merchant)"
    local payload
    payload="$(build_valid_payload "${user_id}" "${amount}" "${merchant_id}" "US")"
    register_status "$(post_payload "${payload}")"
    sleep "${SLEEP_SECONDS}"
  done
}

send_alert_transactions() {
  local count="$1"
  local i
  for i in $(seq 1 "${count}"); do
    local user_id="mix-fraud-user-$((1 + RANDOM % USERS))"
    local mode=$((RANDOM % 2))
    local amount
    local merchant_id

    if (( mode == 0 )); then
      amount="$((12000 + RANDOM % 14000))"
      merchant_id="$(pick_safe_merchant)"
    else
      amount="$((200 + RANDOM % 1200))"
      merchant_id="$(pick_risky_merchant)"
    fi

    local payload
    payload="$(build_valid_payload "${user_id}" "${amount}" "${merchant_id}" "US")"
    register_status "$(post_payload "${payload}")"
    sleep "${SLEEP_SECONDS}"
  done
}

send_4xx_requests() {
  local count="$1"
  local i
  for i in $(seq 1 "${count}"); do
    local payload='{"userId":"","amount":-50,"currency":"usd","merchantId":"","country":"USA","paymentMethod":"CARD"}'
    register_status "$(post_payload "${payload}")"
    sleep "${SLEEP_SECONDS}"
  done
}

send_5xx_requests() {
  local count="$1"
  local i

  echo "Stopping Kafka to induce some 5xx responses..."
  ${COMPOSE_CMD} stop "${KAFKA_SERVICE_NAME}" >/dev/null
  kafka_stopped=1
  sleep 2

  for i in $(seq 1 "${count}"); do
    local user_id="mix-err-user-$((1 + RANDOM % USERS))"
    local amount="$((500 + RANDOM % 2000))"
    local merchant_id="$(pick_safe_merchant)"
    local payload
    payload="$(build_valid_payload "${user_id}" "${amount}" "${merchant_id}" "US")"
    register_status "$(post_payload "${payload}")"
    sleep "${SLEEP_SECONDS}"
  done

  echo "Starting Kafka again..."
  ${COMPOSE_CMD} start "${KAFKA_SERVICE_NAME}" >/dev/null
  kafka_stopped=0
  sleep 5
}

echo "Generating mixed traffic"
echo "- total requests: ${TOTAL_REQUESTS}"
echo "- target valid requests: ${count_valid}"
echo "- target fraud-like transactions: ${count_alert} (~${ALERT_PERCENT}%)"
echo "- target 4xx requests: ${count_4xx}"
echo "- target 5xx requests: ${count_5xx}"

# Order chosen so dashboards show healthy traffic first, then 4xx/5xx spikes.
first_normal=$((count_normal / 2))
second_normal=$((count_normal - first_normal))
first_alert=$((count_alert / 2))
second_alert=$((count_alert - first_alert))

send_normal_transactions "${first_normal}"
send_alert_transactions "${first_alert}"
send_4xx_requests "${count_4xx}"
send_5xx_requests "${count_5xx}"
send_normal_transactions "${second_normal}"
send_alert_transactions "${second_alert}"

echo
echo "Finished"
echo "actual: 2xx=${ok_2xx} 4xx=${err_4xx} 5xx=${err_5xx} other=${other_status} total=${sent_total}"
echo "Open Grafana and inspect: HTTP Throughput, HTTP 5xx (5m), Fraud Alerts (1m), Fraud Alert Rate, Fraud Alert Logs"
