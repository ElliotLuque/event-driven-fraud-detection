#!/usr/bin/env bash

set -euo pipefail

PROMETHEUS_BASE="${PROMETHEUS_BASE:-http://localhost:9090}"
INTERVAL_SECONDS="${INTERVAL_SECONDS:-5}"

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

echo "Watching fraud metrics every ${INTERVAL_SECONDS}s"
echo "Press Ctrl+C to stop"

while true; do
  now="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
  total="$(prom_value 'sum(fraud_alerts_total)')"
  last_1m="$(prom_value 'sum(increase(fraud_alerts_total[1m]))')"
  rate="$(prom_value 'sum(rate(fraud_alerts_total[1m])) * 60')"
  rule="$(prom_value 'max(ALERTS{alertname="FraudAlertDetected",alertstate="firing"}) OR on() vector(0)')"
  risk="$(prom_value 'sum(increase(fraud_alert_risk_score_sum[5m])) / clamp_min(sum(increase(fraud_alert_risk_score_count[5m])), 1)')"

  echo "${now} | total=${total} | last1m=${last_1m} | rate/min=${rate} | rule=${rule} | avgRisk5m=${risk}"
  sleep "${INTERVAL_SECONDS}"
done
