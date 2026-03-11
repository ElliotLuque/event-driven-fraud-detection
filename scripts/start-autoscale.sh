#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${ROOT_DIR}/.env.scaling"

INTERACTIVE_MODE="auto"
SKIP_UP=0

usage() {
  cat <<'EOF'
Usage: ./scripts/start-autoscale.sh [--interactive|--non-interactive] [--render-only]

Genera .env.scaling con parametros de escalado calculados automaticamente y,
por defecto, levanta la plataforma con docker compose.

Opciones:
  --interactive       Fuerza preguntas interactivas.
  --non-interactive   Usa variables de entorno/defaults sin prompts.
  --render-only       Solo genera .env.scaling (no ejecuta docker compose up).
  -h, --help          Muestra esta ayuda.

Variables de entorno opcionales:
  TRANSACTION_SERVICE_INSTANCES
  FRAUD_SERVICE_INSTANCES
  ALERT_SERVICE_INSTANCES
  FRAUD_KAFKA_LISTENER_CONCURRENCY
  ALERT_KAFKA_LISTENER_CONCURRENCY
  APP_KAFKA_REPLICAS
  TRANSACTION_DB_MAX_CONNECTIONS
  FRAUD_DB_MAX_CONNECTIONS
  ALERT_DB_MAX_CONNECTIONS
  TRANSACTION_DB_CONNECTION_BUDGET
  FRAUD_DB_CONNECTION_BUDGET
  ALERT_DB_CONNECTION_BUDGET
  APP_INBOX_PROCESSOR_WORKERS
  APP_INBOX_PROCESSOR_BATCH_SIZE
  APP_INBOX_PROCESSOR_CLAIM_SIZE
  APP_INBOX_PROCESSOR_INTERVAL_MS
  APP_INBOX_PROCESSOR_INITIAL_DELAY_MS
  APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS

Regla de paralelismo Kafka (por consumer group):
  consumers = instances * listener_concurrency
  concurrencia_efectiva = min(partitions, consumers)

Calculo aplicado para compartir APP_KAFKA_PARTITIONS entre fraud y alert:
  fraud_consumers = fraud_instances * fraud_listener_concurrency
  alert_consumers = alert_instances * alert_listener_concurrency
  partitions = max(fraud_consumers, alert_consumers)
EOF
}

ask_integer() {
  local __var_name="$1"
  local label="$2"
  local default_value="$3"
  local min_value="$4"
  local max_value="$5"
  local input

  while true; do
    read -r -p "${label} [${default_value}]: " input
    input="${input:-${default_value}}"

    if [[ "${input}" =~ ^[0-9]+$ ]] && (( input >= min_value && input <= max_value )); then
      printf -v "${__var_name}" '%s' "${input}"
      return
    fi

    echo "Valor invalido. Ingresa un numero entre ${min_value} y ${max_value}."
  done
}

clamp() {
  local value="$1"
  local min="$2"
  local max="$3"

  if (( value < min )); then
    echo "${min}"
    return
  fi

  if (( value > max )); then
    echo "${max}"
    return
  fi

  echo "${value}"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --interactive)
      INTERACTIVE_MODE="on"
      ;;
    --non-interactive)
      INTERACTIVE_MODE="off"
      ;;
    --render-only)
      SKIP_UP=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Argumento no reconocido: $1"
      usage
      exit 1
      ;;
  esac
  shift
done

TRANSACTION_SERVICE_INSTANCES="${TRANSACTION_SERVICE_INSTANCES:-6}"
FRAUD_SERVICE_INSTANCES="${FRAUD_SERVICE_INSTANCES:-6}"
ALERT_SERVICE_INSTANCES="${ALERT_SERVICE_INSTANCES:-6}"
FRAUD_KAFKA_LISTENER_CONCURRENCY="${FRAUD_KAFKA_LISTENER_CONCURRENCY:-3}"
ALERT_KAFKA_LISTENER_CONCURRENCY="${ALERT_KAFKA_LISTENER_CONCURRENCY:-3}"
APP_KAFKA_REPLICAS="${APP_KAFKA_REPLICAS:-3}"

APP_INBOX_PROCESSOR_WORKERS="${APP_INBOX_PROCESSOR_WORKERS:-12}"
APP_INBOX_PROCESSOR_BATCH_SIZE="${APP_INBOX_PROCESSOR_BATCH_SIZE:-1024}"
APP_INBOX_PROCESSOR_CLAIM_SIZE="${APP_INBOX_PROCESSOR_CLAIM_SIZE:-16}"
APP_INBOX_PROCESSOR_INTERVAL_MS="${APP_INBOX_PROCESSOR_INTERVAL_MS:-10}"
APP_INBOX_PROCESSOR_INITIAL_DELAY_MS="${APP_INBOX_PROCESSOR_INITIAL_DELAY_MS:-0}"
APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS="${APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS:-50}"

TRANSACTION_DB_CONNECTION_BUDGET="${TRANSACTION_DB_CONNECTION_BUDGET:-180}"
FRAUD_DB_CONNECTION_BUDGET="${FRAUD_DB_CONNECTION_BUDGET:-90}"
ALERT_DB_CONNECTION_BUDGET="${ALERT_DB_CONNECTION_BUDGET:-90}"

TRANSACTION_DB_MAX_CONNECTIONS="${TRANSACTION_DB_MAX_CONNECTIONS:-300}"
FRAUD_DB_MAX_CONNECTIONS="${FRAUD_DB_MAX_CONNECTIONS:-300}"
ALERT_DB_MAX_CONNECTIONS="${ALERT_DB_MAX_CONNECTIONS:-300}"

should_prompt=0
if [[ "${INTERACTIVE_MODE}" == "on" ]]; then
  should_prompt=1
elif [[ "${INTERACTIVE_MODE}" == "auto" && -t 0 ]]; then
  should_prompt=1
fi

if (( should_prompt )); then
  echo "Configuracion interactiva de escalado (Enter para defaults)"
  ask_integer TRANSACTION_SERVICE_INSTANCES "Instancias transaction-service" "${TRANSACTION_SERVICE_INSTANCES}" 1 60
  ask_integer FRAUD_SERVICE_INSTANCES "Instancias fraud-detection-service" "${FRAUD_SERVICE_INSTANCES}" 1 60
  ask_integer ALERT_SERVICE_INSTANCES "Instancias alert-service" "${ALERT_SERVICE_INSTANCES}" 1 60
  ask_integer FRAUD_KAFKA_LISTENER_CONCURRENCY "Concurrency listener fraud (threads/instancia)" "${FRAUD_KAFKA_LISTENER_CONCURRENCY}" 1 64
  ask_integer ALERT_KAFKA_LISTENER_CONCURRENCY "Concurrency listener alert (threads/instancia)" "${ALERT_KAFKA_LISTENER_CONCURRENCY}" 1 64
  ask_integer APP_KAFKA_REPLICAS "Replication factor Kafka" "${APP_KAFKA_REPLICAS}" 1 3
  ask_integer TRANSACTION_DB_MAX_CONNECTIONS "max_connections transaction-db" "${TRANSACTION_DB_MAX_CONNECTIONS}" 50 1000
  ask_integer FRAUD_DB_MAX_CONNECTIONS "max_connections fraud-db" "${FRAUD_DB_MAX_CONNECTIONS}" 50 1000
  ask_integer ALERT_DB_MAX_CONNECTIONS "max_connections alert-db" "${ALERT_DB_MAX_CONNECTIONS}" 50 1000
  ask_integer TRANSACTION_DB_CONNECTION_BUDGET "Budget conexiones transaction-db" "${TRANSACTION_DB_CONNECTION_BUDGET}" 30 500
  ask_integer FRAUD_DB_CONNECTION_BUDGET "Budget conexiones fraud-db" "${FRAUD_DB_CONNECTION_BUDGET}" 20 300
  ask_integer ALERT_DB_CONNECTION_BUDGET "Budget conexiones alert-db" "${ALERT_DB_CONNECTION_BUDGET}" 20 300
  ask_integer APP_INBOX_PROCESSOR_WORKERS "Workers inbox fraud" "${APP_INBOX_PROCESSOR_WORKERS}" 1 64
  ask_integer APP_INBOX_PROCESSOR_BATCH_SIZE "Batch size inbox fraud" "${APP_INBOX_PROCESSOR_BATCH_SIZE}" 32 4096
  ask_integer APP_INBOX_PROCESSOR_CLAIM_SIZE "Claim size inbox fraud" "${APP_INBOX_PROCESSOR_CLAIM_SIZE}" 1 512
  ask_integer APP_INBOX_PROCESSOR_INTERVAL_MS "Intervalo inbox fraud (ms)" "${APP_INBOX_PROCESSOR_INTERVAL_MS}" 1 1000
  ask_integer APP_INBOX_PROCESSOR_INITIAL_DELAY_MS "Initial delay inbox fraud (ms)" "${APP_INBOX_PROCESSOR_INITIAL_DELAY_MS}" 0 60000
  ask_integer APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS "Frecuencia backlog inbox fraud (runs)" "${APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS}" 1 10000
fi

for value in \
  "${TRANSACTION_SERVICE_INSTANCES}" \
  "${FRAUD_SERVICE_INSTANCES}" \
  "${ALERT_SERVICE_INSTANCES}" \
  "${FRAUD_KAFKA_LISTENER_CONCURRENCY}" \
  "${ALERT_KAFKA_LISTENER_CONCURRENCY}" \
  "${APP_KAFKA_REPLICAS}" \
  "${TRANSACTION_DB_MAX_CONNECTIONS}" \
  "${FRAUD_DB_MAX_CONNECTIONS}" \
  "${ALERT_DB_MAX_CONNECTIONS}" \
  "${TRANSACTION_DB_CONNECTION_BUDGET}" \
  "${FRAUD_DB_CONNECTION_BUDGET}" \
  "${ALERT_DB_CONNECTION_BUDGET}" \
  "${APP_INBOX_PROCESSOR_WORKERS}" \
  "${APP_INBOX_PROCESSOR_BATCH_SIZE}" \
  "${APP_INBOX_PROCESSOR_CLAIM_SIZE}" \
  "${APP_INBOX_PROCESSOR_INTERVAL_MS}" \
  "${APP_INBOX_PROCESSOR_INITIAL_DELAY_MS}" \
  "${APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS}"; do
  if ! [[ "${value}" =~ ^[0-9]+$ ]]; then
    echo "Error: todos los parametros deben ser enteros positivos."
    exit 1
  fi
done

if (( APP_KAFKA_REPLICAS > 3 )); then
  echo "Error: APP_KAFKA_REPLICAS no puede ser mayor a 3 con este cluster local."
  exit 1
fi

FRAUD_TOTAL_CONSUMERS=$(( FRAUD_SERVICE_INSTANCES * FRAUD_KAFKA_LISTENER_CONCURRENCY ))
ALERT_TOTAL_CONSUMERS=$(( ALERT_SERVICE_INSTANCES * ALERT_KAFKA_LISTENER_CONCURRENCY ))

if (( FRAUD_TOTAL_CONSUMERS >= ALERT_TOTAL_CONSUMERS )); then
  APP_KAFKA_PARTITIONS="${FRAUD_TOTAL_CONSUMERS}"
else
  APP_KAFKA_PARTITIONS="${ALERT_TOTAL_CONSUMERS}"
fi

if (( FRAUD_TOTAL_CONSUMERS <= APP_KAFKA_PARTITIONS )); then
  FRAUD_EFFECTIVE_CONCURRENCY="${FRAUD_TOTAL_CONSUMERS}"
else
  FRAUD_EFFECTIVE_CONCURRENCY="${APP_KAFKA_PARTITIONS}"
fi

if (( ALERT_TOTAL_CONSUMERS <= APP_KAFKA_PARTITIONS )); then
  ALERT_EFFECTIVE_CONCURRENCY="${ALERT_TOTAL_CONSUMERS}"
else
  ALERT_EFFECTIVE_CONCURRENCY="${APP_KAFKA_PARTITIONS}"
fi

TRANSACTION_DB_POOL_MAX_RAW=$(( TRANSACTION_DB_CONNECTION_BUDGET / TRANSACTION_SERVICE_INSTANCES ))
FRAUD_DB_POOL_MAX_RAW=$(( FRAUD_DB_CONNECTION_BUDGET / FRAUD_SERVICE_INSTANCES ))
ALERT_DB_POOL_MAX_RAW=$(( ALERT_DB_CONNECTION_BUDGET / ALERT_SERVICE_INSTANCES ))

TRANSACTION_DB_POOL_MAX="$(clamp "${TRANSACTION_DB_POOL_MAX_RAW}" 8 40)"
FRAUD_DB_POOL_MAX="$(clamp "${FRAUD_DB_POOL_MAX_RAW}" 6 30)"
ALERT_DB_POOL_MAX="$(clamp "${ALERT_DB_POOL_MAX_RAW}" 6 30)"

TRANSACTION_DB_POOL_MIN_IDLE="$(clamp $(( TRANSACTION_DB_POOL_MAX / 3 )) 2 12)"
FRAUD_DB_POOL_MIN_IDLE="$(clamp $(( FRAUD_DB_POOL_MAX / 3 )) 2 8)"
ALERT_DB_POOL_MIN_IDLE="$(clamp $(( ALERT_DB_POOL_MAX / 3 )) 2 8)"

cat > "${ENV_FILE}" <<EOF
# Auto-generated by scripts/start-autoscale.sh
# Generated at: $(date -u +"%Y-%m-%dT%H:%M:%SZ")

TRANSACTION_SERVICE_INSTANCES=${TRANSACTION_SERVICE_INSTANCES}
FRAUD_SERVICE_INSTANCES=${FRAUD_SERVICE_INSTANCES}
ALERT_SERVICE_INSTANCES=${ALERT_SERVICE_INSTANCES}

APP_KAFKA_PARTITIONS=${APP_KAFKA_PARTITIONS}
APP_KAFKA_REPLICAS=${APP_KAFKA_REPLICAS}
FRAUD_KAFKA_LISTENER_CONCURRENCY=${FRAUD_KAFKA_LISTENER_CONCURRENCY}
ALERT_KAFKA_LISTENER_CONCURRENCY=${ALERT_KAFKA_LISTENER_CONCURRENCY}
APP_INBOX_PROCESSOR_WORKERS=${APP_INBOX_PROCESSOR_WORKERS}
APP_INBOX_PROCESSOR_BATCH_SIZE=${APP_INBOX_PROCESSOR_BATCH_SIZE}
APP_INBOX_PROCESSOR_CLAIM_SIZE=${APP_INBOX_PROCESSOR_CLAIM_SIZE}
APP_INBOX_PROCESSOR_INTERVAL_MS=${APP_INBOX_PROCESSOR_INTERVAL_MS}
APP_INBOX_PROCESSOR_INITIAL_DELAY_MS=${APP_INBOX_PROCESSOR_INITIAL_DELAY_MS}
APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS=${APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS}

TRANSACTION_DB_POOL_MAX=${TRANSACTION_DB_POOL_MAX}
TRANSACTION_DB_POOL_MIN_IDLE=${TRANSACTION_DB_POOL_MIN_IDLE}
FRAUD_DB_POOL_MAX=${FRAUD_DB_POOL_MAX}
FRAUD_DB_POOL_MIN_IDLE=${FRAUD_DB_POOL_MIN_IDLE}
ALERT_DB_POOL_MAX=${ALERT_DB_POOL_MAX}
ALERT_DB_POOL_MIN_IDLE=${ALERT_DB_POOL_MIN_IDLE}

TRANSACTION_DB_MAX_CONNECTIONS=${TRANSACTION_DB_MAX_CONNECTIONS}
FRAUD_DB_MAX_CONNECTIONS=${FRAUD_DB_MAX_CONNECTIONS}
ALERT_DB_MAX_CONNECTIONS=${ALERT_DB_MAX_CONNECTIONS}
TRANSACTION_DB_CONNECTION_BUDGET=${TRANSACTION_DB_CONNECTION_BUDGET}
FRAUD_DB_CONNECTION_BUDGET=${FRAUD_DB_CONNECTION_BUDGET}
ALERT_DB_CONNECTION_BUDGET=${ALERT_DB_CONNECTION_BUDGET}
EOF

echo "Archivo de escalado generado en ${ENV_FILE}"
echo
echo "Resumen de capacidad calculada"
echo "- transaction-service instances: ${TRANSACTION_SERVICE_INSTANCES}"
echo "- fraud-detection-service instances: ${FRAUD_SERVICE_INSTANCES}"
echo "- alert-service instances: ${ALERT_SERVICE_INSTANCES}"
echo "- kafka partitions: ${APP_KAFKA_PARTITIONS}"
echo "- fraud listener concurrency/instance: ${FRAUD_KAFKA_LISTENER_CONCURRENCY}"
echo "- fraud total consumers (instances*concurrency): ${FRAUD_TOTAL_CONSUMERS}"
echo "- fraud effective concurrency min(P, I*C): ${FRAUD_EFFECTIVE_CONCURRENCY}"
echo "- alert listener concurrency/instance: ${ALERT_KAFKA_LISTENER_CONCURRENCY}"
echo "- alert total consumers (instances*concurrency): ${ALERT_TOTAL_CONSUMERS}"
echo "- alert effective concurrency min(P, I*C): ${ALERT_EFFECTIVE_CONCURRENCY}"
echo "- fraud inbox workers: ${APP_INBOX_PROCESSOR_WORKERS}"
echo "- fraud inbox batch size: ${APP_INBOX_PROCESSOR_BATCH_SIZE}"
echo "- fraud inbox claim size: ${APP_INBOX_PROCESSOR_CLAIM_SIZE}"
echo "- fraud inbox interval ms: ${APP_INBOX_PROCESSOR_INTERVAL_MS}"
echo "- fraud inbox backlog sample runs: ${APP_INBOX_PROCESSOR_BACKLOG_SAMPLE_INTERVAL_RUNS}"
echo "- transaction-db max_connections: ${TRANSACTION_DB_MAX_CONNECTIONS}"
echo "- fraud-db max_connections: ${FRAUD_DB_MAX_CONNECTIONS}"
echo "- alert-db max_connections: ${ALERT_DB_MAX_CONNECTIONS}"
echo "- transaction DB pool max/minIdle: ${TRANSACTION_DB_POOL_MAX}/${TRANSACTION_DB_POOL_MIN_IDLE}"
echo "- fraud DB pool max/minIdle: ${FRAUD_DB_POOL_MAX}/${FRAUD_DB_POOL_MIN_IDLE}"
echo "- alert DB pool max/minIdle: ${ALERT_DB_POOL_MAX}/${ALERT_DB_POOL_MIN_IDLE}"

if (( SKIP_UP )); then
  echo
  echo "Modo render-only: no se ejecuto docker compose up."
  exit 0
fi

echo
echo "Levantando plataforma con docker compose usando .env.scaling"
docker compose --env-file "${ENV_FILE}" up -d --build
