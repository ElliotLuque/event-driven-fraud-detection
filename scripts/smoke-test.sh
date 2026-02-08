#!/usr/bin/env bash

set -euo pipefail

TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
ALERT_API_BASE="${ALERT_API_BASE:-http://localhost:8082}"
PROCESSING_WAIT_SECONDS="${PROCESSING_WAIT_SECONDS:-4}"

USER_ID="smoke-user-$(date +%s)"

echo "[1/3] Creating high-risk transaction for ${USER_ID}"

CREATE_RESPONSE=$(curl -fsS -X POST "${TRANSACTION_API_BASE}/api/v1/transactions" \
  -H "Content-Type: application/json" \
  -d "{\"userId\":\"${USER_ID}\",\"amount\":15000,\"currency\":\"USD\",\"merchantId\":\"MRC-999\",\"country\":\"US\",\"paymentMethod\":\"CARD\"}")

echo "Transaction created: ${CREATE_RESPONSE}"

echo "[2/3] Waiting for asynchronous processing"
sleep "${PROCESSING_WAIT_SECONDS}"

echo "[3/3] Fetching alerts for ${USER_ID}"
ALERTS_RESPONSE=$(curl -fsS "${ALERT_API_BASE}/api/v1/alerts/users/${USER_ID}")
echo "Alerts: ${ALERTS_RESPONSE}"

echo "Smoke test finished"
