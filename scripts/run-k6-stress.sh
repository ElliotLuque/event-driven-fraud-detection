#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
K6_SCRIPT="${SCRIPT_DIR}/generate-k6-stress.js"

usage() {
  cat <<'EOF'
Usage: ./run-k6-stress.sh [--interactive|--non-interactive]

Options:
  --interactive      Force interactive mode.
  --non-interactive  Skip prompts and use env/default values.
  -h, --help         Show this help message.

Environment variables still work in both modes.
Usa ERROR5XX_WEIGHT para inyectar requests que buscan provocar 5xx.
EOF
}

ask_text() {
  local __var_name="$1"
  local label="$2"
  local default_value="$3"
  local input

  read -r -p "${label} [${default_value}]: " input
  input="${input:-${default_value}}"
  printf -v "${__var_name}" '%s' "${input}"
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

ask_duration() {
  local __var_name="$1"
  local label="$2"
  local default_value="$3"
  local input

  while true; do
    read -r -p "${label} [${default_value}]: " input
    input="${input:-${default_value}}"

    if [[ "${input}" =~ ^[0-9]+(ms|s|m|h)$ ]]; then
      printf -v "${__var_name}" '%s' "${input}"
      return
    fi

    echo "Valor invalido. Usa un formato como 45s, 4m o 1h."
  done
}

choose_test_type() {
  local input

  while true; do
    echo
    echo "Tipo de prueba:"
    echo "  1) stress  - rampa normal + carga objetivo"
    echo "  2) spike   - pico fuerte por corto tiempo"
    echo "  3) soak    - carga sostenida"
    echo "  4) smoke   - validacion rapida"
    read -r -p "Seleccion [1]: " input

    case "${input:-1}" in
      1)
        TEST_TYPE="stress"
        return
        ;;
      2)
        TEST_TYPE="spike"
        return
        ;;
      3)
        TEST_TYPE="soak"
        return
        ;;
      4)
        TEST_TYPE="smoke"
        return
        ;;
      *)
        echo "Opcion invalida. Elige 1, 2, 3 o 4."
        ;;
    esac
  done
}

choose_payload_profile() {
  local input

  while true; do
    echo
    echo "Perfil de trafico:"
    echo "  1) balanced      - 45/40/10/5/0"
    echo "  2) mostly-normal - 75/15/7/3/0"
    echo "  3) fraud-focus   - 20/65/10/5/0"
    echo "  4) validation    - 20/20/10/50/0"
    echo "  5) chaos-5xx     - 30/25/10/5/30"
    echo "  6) custom        - definir manualmente"
    echo "     (normal/fraude/velocity/invalid/5xx)"
    read -r -p "Seleccion [1]: " input

    case "${input:-1}" in
      1)
        TEST_PROFILE="balanced"
        NORMAL_WEIGHT=45
        FRAUD_WEIGHT=40
        VELOCITY_WEIGHT=10
        INVALID_WEIGHT=5
        ERROR5XX_WEIGHT=0
        return
        ;;
      2)
        TEST_PROFILE="mostly-normal"
        NORMAL_WEIGHT=75
        FRAUD_WEIGHT=15
        VELOCITY_WEIGHT=7
        INVALID_WEIGHT=3
        ERROR5XX_WEIGHT=0
        return
        ;;
      3)
        TEST_PROFILE="fraud-focus"
        NORMAL_WEIGHT=20
        FRAUD_WEIGHT=65
        VELOCITY_WEIGHT=10
        INVALID_WEIGHT=5
        ERROR5XX_WEIGHT=0
        return
        ;;
      4)
        TEST_PROFILE="validation"
        NORMAL_WEIGHT=20
        FRAUD_WEIGHT=20
        VELOCITY_WEIGHT=10
        INVALID_WEIGHT=50
        ERROR5XX_WEIGHT=0
        return
        ;;
      5)
        TEST_PROFILE="chaos-5xx"
        NORMAL_WEIGHT=30
        FRAUD_WEIGHT=25
        VELOCITY_WEIGHT=10
        INVALID_WEIGHT=5
        ERROR5XX_WEIGHT=30
        return
        ;;
      6)
        TEST_PROFILE="custom"
        while true; do
          ask_integer NORMAL_WEIGHT "Peso normal" "${NORMAL_WEIGHT}" 0 100
          ask_integer FRAUD_WEIGHT "Peso fraude" "${FRAUD_WEIGHT}" 0 100
          ask_integer VELOCITY_WEIGHT "Peso velocity" "${VELOCITY_WEIGHT}" 0 100
          ask_integer INVALID_WEIGHT "Peso invalid" "${INVALID_WEIGHT}" 0 100
          ask_integer ERROR5XX_WEIGHT "Peso 5xx" "${ERROR5XX_WEIGHT}" 0 100

          if (( NORMAL_WEIGHT + FRAUD_WEIGHT + VELOCITY_WEIGHT + INVALID_WEIGHT + ERROR5XX_WEIGHT == 100 )); then
            return
          fi

          echo "La suma debe ser 100. Intenta de nuevo."
        done
        ;;
      *)
        echo "Opcion invalida. Elige 1, 2, 3, 4, 5 o 6."
        ;;
    esac
  done
}

INTERACTIVE_MODE="auto"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --interactive)
      INTERACTIVE_MODE="on"
      ;;
    --non-interactive)
      INTERACTIVE_MODE="off"
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

export TRANSACTION_API_BASE="${TRANSACTION_API_BASE:-http://localhost:8080}"
export STRESS_RPS="${STRESS_RPS:-700}"
export STRESS_DURATION="${STRESS_DURATION:-4m}"
export PREALLOCATED_VUS="${PREALLOCATED_VUS:-350}"
export MAX_VUS="${MAX_VUS:-2500}"
export USERS="${USERS:-600}"
export WEBHOOK_RATIO="${WEBHOOK_RATIO:-35}"
export REQUEST_TIMEOUT="${REQUEST_TIMEOUT:-4s}"
export TEST_TYPE="${TEST_TYPE:-stress}"
export TEST_PROFILE="${TEST_PROFILE:-balanced}"

export NORMAL_WEIGHT="${NORMAL_WEIGHT:-45}"
export FRAUD_WEIGHT="${FRAUD_WEIGHT:-40}"
export VELOCITY_WEIGHT="${VELOCITY_WEIGHT:-10}"
export INVALID_WEIGHT="${INVALID_WEIGHT:-5}"
export ERROR5XX_WEIGHT="${ERROR5XX_WEIGHT:-0}"

should_prompt=0
if [[ "${INTERACTIVE_MODE}" == "on" ]]; then
  should_prompt=1
elif [[ "${INTERACTIVE_MODE}" == "auto" && -t 0 ]]; then
  should_prompt=1
fi

if (( should_prompt )); then
  echo "Configuracion interactiva para k6 (Enter para usar valor por defecto)"
  ask_text TRANSACTION_API_BASE "Base URL del API" "${TRANSACTION_API_BASE}"
  choose_test_type
  ask_integer STRESS_RPS "Target RPS" "${STRESS_RPS}" 1 200000
  ask_duration STRESS_DURATION "Duracion de prueba" "${STRESS_DURATION}"
  ask_integer PREALLOCATED_VUS "preAllocatedVUs" "${PREALLOCATED_VUS}" 1 200000
  ask_integer MAX_VUS "maxVUs" "${MAX_VUS}" "${PREALLOCATED_VUS}" 500000
  ask_integer USERS "Cantidad de usuarios" "${USERS}" 1 10000000
  ask_integer WEBHOOK_RATIO "Porcentaje webhook" "${WEBHOOK_RATIO}" 0 100
  ask_duration REQUEST_TIMEOUT "Timeout por request" "${REQUEST_TIMEOUT}"
  choose_payload_profile
fi

if (( MAX_VUS < PREALLOCATED_VUS )); then
  echo "maxVUs no puede ser menor que preAllocatedVUs"
  exit 1
fi

echo "Running k6 stress test"
echo "- test type: ${TEST_TYPE}"
echo "- traffic profile: ${TEST_PROFILE} (${NORMAL_WEIGHT}/${FRAUD_WEIGHT}/${VELOCITY_WEIGHT}/${INVALID_WEIGHT}/${ERROR5XX_WEIGHT})"
echo "- target rps: ${STRESS_RPS}"
echo "- duration: ${STRESS_DURATION}"
echo "- preAllocatedVUs: ${PREALLOCATED_VUS}"
echo "- maxVUs: ${MAX_VUS}"
echo "- base url: ${TRANSACTION_API_BASE}"
echo "- request timeout: ${REQUEST_TIMEOUT}"

if command -v k6 >/dev/null 2>&1; then
  k6 run "${K6_SCRIPT}"
else
  echo "k6 not found locally, using docker image grafana/k6"
  docker run --rm --network host \
    -e TRANSACTION_API_BASE \
    -e STRESS_RPS \
    -e STRESS_DURATION \
    -e PREALLOCATED_VUS \
    -e MAX_VUS \
    -e USERS \
    -e WEBHOOK_RATIO \
    -e REQUEST_TIMEOUT \
    -e TEST_TYPE \
    -e TEST_PROFILE \
    -e NORMAL_WEIGHT \
    -e FRAUD_WEIGHT \
    -e VELOCITY_WEIGHT \
    -e INVALID_WEIGHT \
    -e ERROR5XX_WEIGHT \
    -v "${SCRIPT_DIR}:/scripts:ro" \
    grafana/k6:0.54.0 run /scripts/generate-k6-stress.js
fi
