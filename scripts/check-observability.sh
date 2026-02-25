#!/usr/bin/env bash

set -euo pipefail

TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
FRAUD_API_BASE="${FRAUD_API_BASE:-http://localhost:8081}"
ALERT_API_BASE="${ALERT_API_BASE:-http://localhost:8082}"
PROMETHEUS_BASE="${PROMETHEUS_BASE:-http://localhost:9090}"
GRAFANA_BASE="${GRAFANA_BASE:-http://localhost:3000}"

check_http() {
  local name="$1"
  local url="$2"

  if curl -fsS "${url}" >/dev/null; then
    echo "[OK] ${name} -> ${url}"
  else
    echo "[FAIL] ${name} -> ${url}"
    return 1
  fi
}

prom_value() {
  local query="$1"
  local response
  local value

  response=$(curl -fsS --get "${PROMETHEUS_BASE}/api/v1/query" --data-urlencode "query=${query}")
  value=$(printf '%s' "${response}" | sed -n 's/.*"value":\[[^,]*,"\([^"]*\)"\].*/\1/p')

  if [[ -n "${value}" ]]; then
    printf '%s' "${value}"
  else
    printf 'NO_DATA'
  fi
}

echo "== Service reachability =="
check_http "transaction-service health" "${TRANSACTION_API_BASE}/actuator/health"
check_http "fraud-detection-service health" "${FRAUD_API_BASE}/actuator/health"
check_http "alert-service health" "${ALERT_API_BASE}/actuator/health"
check_http "prometheus health" "${PROMETHEUS_BASE}/-/healthy"
check_http "grafana health" "${GRAFANA_BASE}/api/health"

echo
echo "== Fraud dashboard queries =="
echo "Fraud alerts (total): $(prom_value 'sum(fraud_alerts_total)')"
echo "Fraud alerts (last 1m): $(prom_value 'sum(increase(fraud_alerts_total[1m]))')"
echo "Fraud alert rate (alerts/min): $(prom_value 'sum(rate(fraud_alerts_total[1m])) * 60')"
echo "Prometheus rule state (0=OK,1=FIRING): $(prom_value 'max(ALERTS{alertname="FraudAlertDetected",alertstate="firing"}) OR on() vector(0)')"
echo "Average risk score (5m): $(prom_value 'sum(increase(fraud_alert_risk_score_sum[5m])) / clamp_min(sum(increase(fraud_alert_risk_score_count[5m])), 1)')"

echo
echo "Done"
