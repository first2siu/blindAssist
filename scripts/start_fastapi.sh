#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

LOG_DIR="${LOG_DIR:-${PROJECT_ROOT}/logs}"
PID_DIR="${PID_DIR:-${LOG_DIR}/pids}"

PYTHON_BIN="${PYTHON_BIN:-python}"
HOST="${HOST:-0.0.0.0}"
HEALTH_HOST="${HEALTH_HOST:-127.0.0.1}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-90}"
POLL_INTERVAL="${POLL_INTERVAL:-3}"

TEXT_VLLM_PORT="${TEXT_VLLM_PORT:-8001}"
AUTOGLM_VLLM_PORT="${AUTOGLM_VLLM_PORT:-8000}"
OBSTACLE_VLLM_PORT="${OBSTACLE_VLLM_PORT:-8003}"

PLANNER_PORT="${PLANNER_PORT:-8002}"
NAVIGATION_PORT="${NAVIGATION_PORT:-8081}"
AUTOGLM_API_PORT="${AUTOGLM_API_PORT:-8080}"
OBSTACLE_PORT="${OBSTACLE_PORT:-8004}"

PLANNER_MODEL_NAME="${PLANNER_MODEL_NAME:-Qwen/Qwen2-1.5B-Instruct}"
NAVIGATION_MODEL_NAME="${NAVIGATION_MODEL_NAME:-Qwen/Qwen2-1.5B-Instruct}"
AUTOGLM_MODEL_NAME="${AUTOGLM_MODEL_NAME:-autoglm-phone-9b}"
OBSTACLE_MODEL_NAME="${OBSTACLE_MODEL_NAME:-Qwen/Qwen2-VL-7B-Instruct}"

PLANNER_BASE_URL="${PLANNER_BASE_URL:-http://127.0.0.1:${TEXT_VLLM_PORT}/v1}"
NAVIGATION_MODEL_BASE_URL="${NAVIGATION_MODEL_BASE_URL:-http://127.0.0.1:${TEXT_VLLM_PORT}/v1}"
AUTOGLM_MODEL_BASE_URL="${AUTOGLM_MODEL_BASE_URL:-http://127.0.0.1:${AUTOGLM_VLLM_PORT}/v1}"
OBSTACLE_BASE_URL="${OBSTACLE_BASE_URL:-http://127.0.0.1:${OBSTACLE_VLLM_PORT}/v1}"

PLANNER_API_KEY="${PLANNER_API_KEY:-EMPTY}"
OBSTACLE_API_KEY="${OBSTACLE_API_KEY:-EMPTY}"

NAVIGATION_BASE_URL="${NAVIGATION_BASE_URL:-http://127.0.0.1:${NAVIGATION_PORT}}"
PHONE_CONTROL_BASE_URL="${PHONE_CONTROL_BASE_URL:-http://127.0.0.1:${AUTOGLM_API_PORT}}"
OBSTACLE_TOOL_BASE_URL="${OBSTACLE_TOOL_BASE_URL:-http://127.0.0.1:${OBSTACLE_PORT}}"

SPRING_BOOT_TTS_URL="${SPRING_BOOT_TTS_URL:-http://127.0.0.1:8090/api/tts/enqueue}"
AMAP_API_KEY="${AMAP_API_KEY:-}"

mkdir -p "${LOG_DIR}" "${PID_DIR}"

usage() {
  cat <<'EOF'
Usage:
  bash scripts/start_fastapi.sh                       # start all FastAPI services
  bash scripts/start_fastapi.sh planner navigation   # start selected services

Services:
  planner
  navigation
  autoglm
  obstacle

Important env vars:
  PYTHON_BIN=python
  LOG_DIR=/data/blindassist/logs

  TEXT_VLLM_PORT=8001
  AUTOGLM_VLLM_PORT=8000
  OBSTACLE_VLLM_PORT=8003

  PLANNER_PORT=8002
  NAVIGATION_PORT=8081
  AUTOGLM_API_PORT=8080
  OBSTACLE_PORT=8004

  AMAP_API_KEY=your-key
  SPRING_BOOT_TTS_URL=http://YOUR_PC_IP:8090/api/tts/enqueue
EOF
}

print_line() {
  printf '%s\n' "------------------------------------------------------------"
}

print_header() {
  print_line
  printf '%s\n' "$1"
  print_line
}

port_in_use() {
  local port="$1"
  if command -v lsof >/dev/null 2>&1; then
    lsof -ti ":${port}" >/dev/null 2>&1
    return $?
  fi

  if command -v ss >/dev/null 2>&1; then
    ss -ltn "( sport = :${port} )" | grep -q ":${port}"
    return $?
  fi

  return 1
}

wait_for_http() {
  local url="$1"
  local timeout="$2"
  local elapsed=0

  while (( elapsed < timeout )); do
    if curl -fsS "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep "${POLL_INTERVAL}"
    elapsed=$(( elapsed + POLL_INTERVAL ))
  done

  return 1
}

warn_if_vllm_missing() {
  local name="$1"
  local base_url="$2"
  local check_url="${base_url%/v1}/v1/models"

  if ! wait_for_http "${check_url}" 2; then
    printf 'warning      : %s model backend is not reachable yet (%s)\n' "${name}" "${check_url}"
  fi
}

start_uvicorn_service() {
  local key="$1"
  local workdir="$2"
  local app_ref="$3"
  local port="$4"
  shift 4
  local -a env_kv=("$@")

  local log_file="${LOG_DIR}/fastapi_${key}.log"
  local pid_file="${PID_DIR}/fastapi_${key}.pid"
  local health_url="http://${HEALTH_HOST}:${port}/health"

  print_header "Starting FastAPI service: ${key}"
  printf 'workdir      : %s\n' "${workdir}"
  printf 'app          : %s\n' "${app_ref}"
  printf 'port         : %s\n' "${port}"
  printf 'log file     : %s\n' "${log_file}"

  if port_in_use "${port}"; then
    if wait_for_http "${health_url}" 2; then
      printf 'status       : already running on port %s\n' "${port}"
      return 0
    fi
    printf 'status       : port %s is occupied, skip starting %s\n' "${port}" "${key}"
    return 1
  fi

  (
    cd "${workdir}"
    nohup env "${env_kv[@]}" \
      "${PYTHON_BIN}" -m uvicorn "${app_ref}" --host "${HOST}" --port "${port}" \
      >"${log_file}" 2>&1 &
    echo $! > "${pid_file}"
  )

  local pid
  pid="$(cat "${pid_file}")"
  printf 'pid          : %s\n' "${pid}"

  if wait_for_http "${health_url}" "${STARTUP_TIMEOUT}"; then
    printf 'health       : ok (%s)\n' "${health_url}"
    return 0
  fi

  printf 'health       : timeout waiting for %s\n' "${health_url}"
  printf 'hint         : tail -f %s\n' "${log_file}"
  return 1
}

main() {
  if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
    usage
    exit 0
  fi

  if [[ "${SPRING_BOOT_TTS_URL}" == "http://127.0.0.1:8090/api/tts/enqueue" ]]; then
    printf 'note         : SPRING_BOOT_TTS_URL is still local-only; override it if Spring Boot runs on another machine.\n'
  fi

  local -a targets=()
  if [[ $# -eq 0 ]]; then
    targets=(planner navigation autoglm obstacle)
  else
    targets=("$@")
  fi

  local failures=0

  for target in "${targets[@]}"; do
    case "${target}" in
      planner)
        warn_if_vllm_missing "planner" "${PLANNER_BASE_URL}"
        start_uvicorn_service \
          "planner" \
          "${PROJECT_ROOT}/FastAPI/IntentClassifier" \
          "main:app" \
          "${PLANNER_PORT}" \
          "BASE_URL=${PLANNER_BASE_URL}" \
          "MODEL_NAME=${PLANNER_MODEL_NAME}" \
          "API_KEY=${PLANNER_API_KEY}" \
          "HOST=${HOST}" \
          "PORT=${PLANNER_PORT}" \
          "NAVIGATION_BASE_URL=${NAVIGATION_BASE_URL}" \
          "OBSTACLE_BASE_URL=${OBSTACLE_TOOL_BASE_URL}" \
          "PHONE_CONTROL_BASE_URL=${PHONE_CONTROL_BASE_URL}" || failures=$(( failures + 1 ))
        ;;
      navigation)
        warn_if_vllm_missing "navigation" "${NAVIGATION_MODEL_BASE_URL}"
        start_uvicorn_service \
          "navigation" \
          "${PROJECT_ROOT}/FastAPI/NavigationAgent" \
          "main:app" \
          "${NAVIGATION_PORT}" \
          "MODEL_BASE_URL=${NAVIGATION_MODEL_BASE_URL}" \
          "MODEL_NAME=${NAVIGATION_MODEL_NAME}" \
          "NAVIGATION_PORT=${NAVIGATION_PORT}" \
          "AMAP_API_KEY=${AMAP_API_KEY}" || failures=$(( failures + 1 ))
        ;;
      autoglm)
        warn_if_vllm_missing "autoglm" "${AUTOGLM_MODEL_BASE_URL}"
        start_uvicorn_service \
          "autoglm" \
          "${PROJECT_ROOT}/FastAPI/AutoGLM" \
          "server:app" \
          "${AUTOGLM_API_PORT}" \
          "MODEL_BASE_URL=${AUTOGLM_MODEL_BASE_URL}" \
          "MODEL_NAME=${AUTOGLM_MODEL_NAME}" || failures=$(( failures + 1 ))
        ;;
      obstacle)
        warn_if_vllm_missing "obstacle" "${OBSTACLE_BASE_URL}"
        start_uvicorn_service \
          "obstacle" \
          "${PROJECT_ROOT}/FastAPI/ObstacleDetection" \
          "main:app" \
          "${OBSTACLE_PORT}" \
          "BASE_URL=${OBSTACLE_BASE_URL}" \
          "MODEL_NAME=${OBSTACLE_MODEL_NAME}" \
          "API_KEY=${OBSTACLE_API_KEY}" \
          "PORT=${OBSTACLE_PORT}" \
          "SPRING_BOOT_TTS_URL=${SPRING_BOOT_TTS_URL}" || failures=$(( failures + 1 ))
        ;;
      all)
        warn_if_vllm_missing "planner" "${PLANNER_BASE_URL}"
        start_uvicorn_service \
          "planner" \
          "${PROJECT_ROOT}/FastAPI/IntentClassifier" \
          "main:app" \
          "${PLANNER_PORT}" \
          "BASE_URL=${PLANNER_BASE_URL}" \
          "MODEL_NAME=${PLANNER_MODEL_NAME}" \
          "API_KEY=${PLANNER_API_KEY}" \
          "HOST=${HOST}" \
          "PORT=${PLANNER_PORT}" \
          "NAVIGATION_BASE_URL=${NAVIGATION_BASE_URL}" \
          "OBSTACLE_BASE_URL=${OBSTACLE_TOOL_BASE_URL}" \
          "PHONE_CONTROL_BASE_URL=${PHONE_CONTROL_BASE_URL}" || failures=$(( failures + 1 ))
        warn_if_vllm_missing "navigation" "${NAVIGATION_MODEL_BASE_URL}"
        start_uvicorn_service \
          "navigation" \
          "${PROJECT_ROOT}/FastAPI/NavigationAgent" \
          "main:app" \
          "${NAVIGATION_PORT}" \
          "MODEL_BASE_URL=${NAVIGATION_MODEL_BASE_URL}" \
          "MODEL_NAME=${NAVIGATION_MODEL_NAME}" \
          "NAVIGATION_PORT=${NAVIGATION_PORT}" \
          "AMAP_API_KEY=${AMAP_API_KEY}" || failures=$(( failures + 1 ))
        warn_if_vllm_missing "autoglm" "${AUTOGLM_MODEL_BASE_URL}"
        start_uvicorn_service \
          "autoglm" \
          "${PROJECT_ROOT}/FastAPI/AutoGLM" \
          "server:app" \
          "${AUTOGLM_API_PORT}" \
          "MODEL_BASE_URL=${AUTOGLM_MODEL_BASE_URL}" \
          "MODEL_NAME=${AUTOGLM_MODEL_NAME}" || failures=$(( failures + 1 ))
        warn_if_vllm_missing "obstacle" "${OBSTACLE_BASE_URL}"
        start_uvicorn_service \
          "obstacle" \
          "${PROJECT_ROOT}/FastAPI/ObstacleDetection" \
          "main:app" \
          "${OBSTACLE_PORT}" \
          "BASE_URL=${OBSTACLE_BASE_URL}" \
          "MODEL_NAME=${OBSTACLE_MODEL_NAME}" \
          "API_KEY=${OBSTACLE_API_KEY}" \
          "PORT=${OBSTACLE_PORT}" \
          "SPRING_BOOT_TTS_URL=${SPRING_BOOT_TTS_URL}" || failures=$(( failures + 1 ))
        ;;
      *)
        printf 'Unknown service: %s\n' "${target}"
        usage
        exit 1
        ;;
    esac
  done

  print_header "FastAPI summary"
  printf 'planner    : http://%s:%s\n' "${HEALTH_HOST}" "${PLANNER_PORT}"
  printf 'navigation : http://%s:%s\n' "${HEALTH_HOST}" "${NAVIGATION_PORT}"
  printf 'autoglm    : http://%s:%s\n' "${HEALTH_HOST}" "${AUTOGLM_API_PORT}"
  printf 'obstacle   : http://%s:%s\n' "${HEALTH_HOST}" "${OBSTACLE_PORT}"
  printf 'logs       : %s\n' "${LOG_DIR}"

  if (( failures > 0 )); then
    exit 1
  fi
}

main "$@"
