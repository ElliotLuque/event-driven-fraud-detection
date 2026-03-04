#!/usr/bin/env bash

set -euo pipefail

KAFKA_SERVICE="${KAFKA_SERVICE:-kafka}"
KAFKA_BOOTSTRAP="${KAFKA_BOOTSTRAP:-localhost:9092}"

TRANSACTIONS_DLQ_TOPIC="${TRANSACTIONS_DLQ_TOPIC:-transactions.created.dlq}"
FRAUD_DLQ_TOPIC="${FRAUD_DLQ_TOPIC:-fraud.detected.dlq}"

FRAUD_METRICS_URL="${FRAUD_METRICS_URL:-http://localhost:8081/actuator/prometheus}"
ALERT_METRICS_URL="${ALERT_METRICS_URL:-http://localhost:8082/actuator/prometheus}"

WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-90}"
POLL_INTERVAL_SECONDS="${POLL_INTERVAL_SECONDS:-2}"

metric_total_name() {
  local metric_base="$1"
  printf '%s_total' "${metric_base}"
}

get_metric_total() {
  local url="$1"
  local metric_base="$2"
  local metric

  metric="$(metric_total_name "${metric_base}")"

  curl -fsS "${url}" | awk -v metric="${metric}" '
    BEGIN { sum = 0 }
    $1 ~ ("^" metric "({|$)") { sum += $2 }
    END { printf "%.0f\n", sum }
  '
}

wait_for_delta() {
  local url="$1"
  local metric_base="$2"
  local baseline="$3"
  local min_delta="$4"
  local timeout="$5"

  local elapsed=0
  while (( elapsed <= timeout )); do
    local current delta
    current="$(get_metric_total "${url}" "${metric_base}")"
    delta=$(( current - baseline ))

    if (( delta >= min_delta )); then
      return 0
    fi

    sleep "${POLL_INTERVAL_SECONDS}"
    elapsed=$(( elapsed + POLL_INTERVAL_SECONDS ))
  done

  return 1
}

send_kafka_event() {
  local topic="$1"
  local payload="$2"

  docker compose exec -T "${KAFKA_SERVICE}" bash -lc \
    "kafka-console-producer --bootstrap-server ${KAFKA_BOOTSTRAP} --topic ${topic}" <<<"${payload}"
}

assert_stack_ready() {
  echo "[0/5] Verificando servicios y métricas"
  curl -fsS "${FRAUD_METRICS_URL}" >/dev/null
  curl -fsS "${ALERT_METRICS_URL}" >/dev/null
}

main() {
  local run_id now_utc
  run_id="$(date +%s)"
  now_utc="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"

  assert_stack_ready

  local fraud_received_before fraud_failed_before fraud_reprocessed_before
  local alert_received_before alert_failed_before alert_reprocessed_before

  fraud_received_before="$(get_metric_total "${FRAUD_METRICS_URL}" "kafka_dlq_events_received")"
  fraud_failed_before="$(get_metric_total "${FRAUD_METRICS_URL}" "kafka_dlq_events_failed")"
  fraud_reprocessed_before="$(get_metric_total "${FRAUD_METRICS_URL}" "kafka_dlq_events_reprocessed")"

  alert_received_before="$(get_metric_total "${ALERT_METRICS_URL}" "kafka_dlq_events_received")"
  alert_failed_before="$(get_metric_total "${ALERT_METRICS_URL}" "kafka_dlq_events_failed")"
  alert_reprocessed_before="$(get_metric_total "${ALERT_METRICS_URL}" "kafka_dlq_events_reprocessed")"

  echo "[1/5] Enviando evento valido a ${TRANSACTIONS_DLQ_TOPIC}"
  send_kafka_event "${TRANSACTIONS_DLQ_TOPIC}" "{\"eventId\":\"evt-dlq-reprocess-fraud-${run_id}\",\"occurredAt\":\"${now_utc}\",\"transactionId\":\"txn-dlq-reprocess-fraud-${run_id}\",\"traceId\":\"trace-dlq-reprocess-fraud-${run_id}\",\"userId\":\"user-dlq-reprocess-fraud-${run_id}\",\"amount\":15000,\"currency\":\"USD\",\"merchantId\":\"MRC-999\",\"country\":\"US\",\"paymentMethod\":\"CARD\"}"

  wait_for_delta "${FRAUD_METRICS_URL}" "kafka_dlq_events_received" "${fraud_received_before}" 1 "${WAIT_TIMEOUT_SECONDS}" || {
    echo "ERROR: No aumento kafka_dlq_events_received en fraud-detection-service"
    exit 1
  }

  wait_for_delta "${FRAUD_METRICS_URL}" "kafka_dlq_events_reprocessed" "${fraud_reprocessed_before}" 1 "${WAIT_TIMEOUT_SECONDS}" || {
    echo "ERROR: No aumento kafka_dlq_events_reprocessed en fraud-detection-service"
    exit 1
  }

  echo "[2/5] Enviando evento valido a ${FRAUD_DLQ_TOPIC}"
  send_kafka_event "${FRAUD_DLQ_TOPIC}" "{\"eventId\":\"evt-dlq-reprocess-alert-${run_id}\",\"occurredAt\":\"${now_utc}\",\"transactionId\":\"txn-dlq-reprocess-alert-${run_id}\",\"traceId\":\"trace-dlq-reprocess-alert-${run_id}\",\"userId\":\"user-dlq-reprocess-alert-${run_id}\",\"riskScore\":90,\"reasons\":[\"HIGH_AMOUNT\",\"HIGH_RISK_MERCHANT\"],\"ruleVersion\":\"v-test\"}"

  wait_for_delta "${ALERT_METRICS_URL}" "kafka_dlq_events_received" "${alert_received_before}" 1 "${WAIT_TIMEOUT_SECONDS}" || {
    echo "ERROR: No aumento kafka_dlq_events_received en alert-service"
    exit 1
  }

  wait_for_delta "${ALERT_METRICS_URL}" "kafka_dlq_events_reprocessed" "${alert_reprocessed_before}" 1 "${WAIT_TIMEOUT_SECONDS}" || {
    echo "ERROR: No aumento kafka_dlq_events_reprocessed en alert-service"
    exit 1
  }

  local fraud_received_after fraud_failed_after fraud_reprocessed_after
  local alert_received_after alert_failed_after alert_reprocessed_after

  fraud_received_after="$(get_metric_total "${FRAUD_METRICS_URL}" "kafka_dlq_events_received")"
  fraud_failed_after="$(get_metric_total "${FRAUD_METRICS_URL}" "kafka_dlq_events_failed")"
  fraud_reprocessed_after="$(get_metric_total "${FRAUD_METRICS_URL}" "kafka_dlq_events_reprocessed")"

  alert_received_after="$(get_metric_total "${ALERT_METRICS_URL}" "kafka_dlq_events_received")"
  alert_failed_after="$(get_metric_total "${ALERT_METRICS_URL}" "kafka_dlq_events_failed")"
  alert_reprocessed_after="$(get_metric_total "${ALERT_METRICS_URL}" "kafka_dlq_events_reprocessed")"

  echo "[3/5] Resultado"
  echo "fraud-detection-service: received +$(( fraud_received_after - fraud_received_before )), reprocessed +$(( fraud_reprocessed_after - fraud_reprocessed_before )), failed +$(( fraud_failed_after - fraud_failed_before ))"
  echo "alert-service:          received +$(( alert_received_after - alert_received_before )), reprocessed +$(( alert_reprocessed_after - alert_reprocessed_before )), failed +$(( alert_failed_after - alert_failed_before ))"

  if (( fraud_failed_after - fraud_failed_before > 0 )) || (( alert_failed_after - alert_failed_before > 0 )); then
    echo "[4/5] WARN - hubo fallos durante reproceso (revisar logs)"
  else
    echo "[4/5] OK - reproceso sin fallos"
  fi

  echo "[5/5] PASS - ambas DLQ reprocesaron al menos 1 evento"
}

main "$@"
