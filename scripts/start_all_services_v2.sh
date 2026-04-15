#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

LOG_DIR="${LOG_DIR:-${PROJECT_ROOT}/logs}"
PID_DIR="${PID_DIR:-${LOG_DIR}/pids}"

PYTHON_BIN="${PYTHON_BIN:-python}"
HOST="${HOST:-0.0.0.0}"
HEALTH_HOST="${HEALTH_HOST:-127.0.0.1}"
VLLM_STARTUP_TIMEOUT="${VLLM_STARTUP_TIMEOUT:-240}"
FASTAPI_STARTUP_TIMEOUT="${FASTAPI_STARTUP_TIMEOUT:-90}"
POLL_INTERVAL="${POLL_INTERVAL:-3}"

TEXT_PORT="${TEXT_PORT:-8001}"
AUTOGLM_PORT="${AUTOGLM_PORT:-8000}"
OBSTACLE_VLLM_PORT="${OBSTACLE_VLLM_PORT:-8003}"

PLANNER_PORT="${PLANNER_PORT:-8002}"
NAVIGATION_PORT="${NAVIGATION_PORT:-8081}"
AUTOGLM_API_PORT="${AUTOGLM_API_PORT:-8080}"
OBSTACLE_API_PORT="${OBSTACLE_API_PORT:-8004}"

TEXT_GPU="${TEXT_GPU:-0}"
AUTOGLM_GPU="${AUTOGLM_GPU:-1}"
OBSTACLE_GPU="${OBSTACLE_GPU:-2}"

TEXT_MODEL_SOURCE="${TEXT_MODEL_SOURCE:-Qwen/Qwen2-1.5B-Instruct}"
TEXT_MODEL_NAME="${TEXT_MODEL_NAME:-Qwen/Qwen2-1.5B-Instruct}"
AUTOGLM_MODEL_SOURCE="${AUTOGLM_MODEL_SOURCE:-zai-org/AutoGLM-Phone-9B}"
AUTOGLM_MODEL_NAME="${AUTOGLM_MODEL_NAME:-autoglm-phone-9b}"
OBSTACLE_MODEL_SOURCE="${OBSTACLE_MODEL_SOURCE:-Qwen/Qwen2-VL-7B-Instruct}"
OBSTACLE_MODEL_NAME="${OBSTACLE_MODEL_NAME:-Qwen/Qwen2-VL-7B-Instruct}"

TEXT_TP_SIZE="${TEXT_TP_SIZE:-1}"
AUTOGLM_TP_SIZE="${AUTOGLM_TP_SIZE:-1}"
OBSTACLE_TP_SIZE="${OBSTACLE_TP_SIZE:-1}"

TEXT_MAX_MODEL_LEN="${TEXT_MAX_MODEL_LEN:-8192}"
AUTOGLM_MAX_MODEL_LEN="${AUTOGLM_MAX_MODEL_LEN:-8192}"
OBSTACLE_MAX_MODEL_LEN="${OBSTACLE_MAX_MODEL_LEN:-8192}"

TEXT_GPU_MEMORY_UTILIZATION="${TEXT_GPU_MEMORY_UTILIZATION:-0.90}"
AUTOGLM_GPU_MEMORY_UTILIZATION="${AUTOGLM_GPU_MEMORY_UTILIZATION:-0.90}"
OBSTACLE_GPU_MEMORY_UTILIZATION="${OBSTACLE_GPU_MEMORY_UTILIZATION:-0.90}"

TEXT_DTYPE="${TEXT_DTYPE:-auto}"
AUTOGLM_DTYPE="${AUTOGLM_DTYPE:-auto}"
OBSTACLE_DTYPE="${OBSTACLE_DTYPE:-auto}"

TEXT_EXTRA_ARGS="${TEXT_EXTRA_ARGS:-}"
AUTOGLM_EXTRA_ARGS="${AUTOGLM_EXTRA_ARGS:---trust-remote-code}"
OBSTACLE_EXTRA_ARGS="${OBSTACLE_EXTRA_ARGS:-}"

PLANNER_MODEL_NAME="${PLANNER_MODEL_NAME:-Qwen/Qwen2-1.5B-Instruct}"
NAVIGATION_MODEL_NAME="${NAVIGATION_MODEL_NAME:-Qwen/Qwen2-1.5B-Instruct}"
PLANNER_BASE_URL="${PLANNER_BASE_URL:-http://127.0.0.1:${TEXT_PORT}/v1}"
NAVIGATION_MODEL_BASE_URL="${NAVIGATION_MODEL_BASE_URL:-http://127.0.0.1:${TEXT_PORT}/v1}"
AUTOGLM_MODEL_BASE_URL="${AUTOGLM_MODEL_BASE_URL:-http://127.0.0.1:${AUTOGLM_PORT}/v1}"
OBSTACLE_BASE_URL="${OBSTACLE_BASE_URL:-http://127.0.0.1:${OBSTACLE_VLLM_PORT}/v1}"

PLANNER_API_KEY="${PLANNER_API_KEY:-EMPTY}"
OBSTACLE_API_KEY="${OBSTACLE_API_KEY:-EMPTY}"
NAVIGATION_BASE_URL="${NAVIGATION_BASE_URL:-http://127.0.0.1:${NAVIGATION_PORT}}"
PHONE_CONTROL_BASE_URL="${PHONE_CONTROL_BASE_URL:-http://127.0.0.1:${AUTOGLM_API_PORT}}"
OBSTACLE_TOOL_BASE_URL="${OBSTACLE_TOOL_BASE_URL:-http://127.0.0.1:${OBSTACLE_API_PORT}}"
SPRING_BOOT_TTS_URL="${SPRING_BOOT_TTS_URL:-http://127.0.0.1:8090/api/tts/enqueue}"
AMAP_API_KEY="${AMAP_API_KEY:-}"

SKIP_VLLM=false
SKIP_FASTAPI=false

mkdir -p "${LOG_DIR}" "${PID_DIR}"

usage() {
  cat <<'EOF'
Usage:
  bash scripts/start_all_services_v2.sh
  bash scripts/start_all_services_v2.sh --gpu-only
  bash scripts/start_all_services_v2.sh --fastapi-only

Main env vars:
  TEXT_MODEL_SOURCE=/data/models/Qwen2-1.5B-Instruct
  AUTOGLM_MODEL_SOURCE=/data/models/AutoGLM-Phone-9B
  OBSTACLE_MODEL_SOURCE=/data/models/Qwen2-VL-7B-Instruct

  TEXT_GPU=0
  AUTOGLM_GPU=1
  OBSTACLE_GPU=2

  TEXT_PORT=8001
  AUTOGLM_PORT=8000
  OBSTACLE_VLLM_PORT=8003

  PLANNER_PORT=8002
  NAVIGATION_PORT=8081
  AUTOGLM_API_PORT=8080
  OBSTACLE_API_PORT=8004

  AMAP_API_KEY=your-amap-key
  SPRING_BOOT_TTS_URL=http://YOUR_PC_IP:8090/api/tts/enqueue
EOF
}

print_line() {
  printf '%s\n' "============================================================"
}

print_header() {
  printf '\n'
  print_line
  printf '%s\n' "$1"
  print_line
}

print_step() {
  printf '\n>>> %s\n' "$1"
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

check_gpu() {
  print_step "Checking GPU"
  if command -v nvidia-smi >/dev/null 2>&1; then
    nvidia-smi --query-gpu=index,name,memory.total,memory.free --format=csv
  else
    printf 'nvidia-smi not found; continuing without GPU inventory output.\n'
  fi
}

start_vllm_service() {
  local key="$1"
  local model_source="$2"
  local model_name="$3"
  local port="$4"
  local gpu="$5"
  local tp_size="$6"
  local max_model_len="$7"
  local gpu_memory_utilization="$8"
  local dtype="$9"
  local extra_args="${10}"

  local log_file="${LOG_DIR}/vllm_${key}.log"
  local pid_file="${PID_DIR}/vllm_${key}.pid"
  local health_url="http://${HEALTH_HOST}:${port}/v1/models"

  print_step "Starting vLLM: ${key}"
  printf '  model source : %s\n' "${model_source}"
  printf '  served name  : %s\n' "${model_name}"
  printf '  gpu          : %s\n' "${gpu}"
  printf '  port         : %s\n' "${port}"
  printf '  log file     : %s\n' "${log_file}"

  if port_in_use "${port}"; then
    if wait_for_http "${health_url}" 2; then
      printf '  status       : already running\n'
      return 0
    fi
    printf '  status       : port occupied, skip\n'
    return 1
  fi

  local -a cmd=(
    "${PYTHON_BIN}"
    -m
    vllm.entrypoints.openai.api_server
    --host "${HOST}"
    --port "${port}"
    --model "${model_source}"
    --served-model-name "${model_name}"
    --tensor-parallel-size "${tp_size}"
    --max-model-len "${max_model_len}"
    --dtype "${dtype}"
    --gpu-memory-utilization "${gpu_memory_utilization}"
  )

  if [[ -n "${extra_args}" ]]; then
    local -a extra_parts=()
    read -r -a extra_parts <<< "${extra_args}"
    cmd+=("${extra_parts[@]}")
  fi

  (
    cd "${PROJECT_ROOT}"
    nohup env CUDA_VISIBLE_DEVICES="${gpu}" "${cmd[@]}" >"${log_file}" 2>&1 &
    echo $! > "${pid_file}"
  )

  local pid
  pid="$(cat "${pid_file}")"
  printf '  pid          : %s\n' "${pid}"

  if wait_for_http "${health_url}" "${VLLM_STARTUP_TIMEOUT}"; then
    printf '  health       : ok\n'
    return 0
  fi

  printf '  health       : timeout waiting for %s\n' "${health_url}"
  printf '  hint         : tail -f %s\n' "${log_file}"
  return 1
}

warn_if_backend_missing() {
  local name="$1"
  local base_url="$2"
  local check_url="${base_url%/v1}/v1/models"

  if ! wait_for_http "${check_url}" 2; then
    printf '  warning      : %s backend not reachable yet (%s)\n' "${name}" "${check_url}"
  fi
}

start_fastapi_service() {
  local key="$1"
  local workdir="$2"
  local app_ref="$3"
  local port="$4"
  shift 4
  local -a env_kv=("$@")

  local log_file="${LOG_DIR}/fastapi_${key}.log"
  local pid_file="${PID_DIR}/fastapi_${key}.pid"
  local health_url="http://${HEALTH_HOST}:${port}/health"

  print_step "Starting FastAPI: ${key}"
  printf '  workdir      : %s\n' "${workdir}"
  printf '  app          : %s\n' "${app_ref}"
  printf '  port         : %s\n' "${port}"
  printf '  log file     : %s\n' "${log_file}"

  if port_in_use "${port}"; then
    if wait_for_http "${health_url}" 2; then
      printf '  status       : already running\n'
      return 0
    fi
    printf '  status       : port occupied, skip\n'
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
  printf '  pid          : %s\n' "${pid}"

  if wait_for_http "${health_url}" "${FASTAPI_STARTUP_TIMEOUT}"; then
    printf '  health       : ok\n'
    return 0
  fi

  printf '  health       : timeout waiting for %s\n' "${health_url}"
  printf '  hint         : tail -f %s\n' "${log_file}"
  return 1
}

parse_args() {
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --gpu-only)
        SKIP_FASTAPI=true
        ;;
      --fastapi-only)
        SKIP_VLLM=true
        ;;
      --help|-h)
        usage
        exit 0
        ;;
      *)
        printf 'Unknown option: %s\n' "$1"
        usage
        exit 1
        ;;
    esac
    shift
  done
}

main() {
  parse_args "$@"

  print_header "BlindAssist Service Startup v2"

  check_gpu

  local failures=0

  if [[ "${SPRING_BOOT_TTS_URL}" == "http://127.0.0.1:8090/api/tts/enqueue" ]]; then
    printf '\nnote: SPRING_BOOT_TTS_URL is still local-only; override it if Spring Boot runs on another machine.\n'
  fi

  if [[ "${SKIP_VLLM}" == false ]]; then
    print_header "Starting vLLM Services"
    start_vllm_service \
      "text" \
      "${TEXT_MODEL_SOURCE}" \
      "${TEXT_MODEL_NAME}" \
      "${TEXT_PORT}" \
      "${TEXT_GPU}" \
      "${TEXT_TP_SIZE}" \
      "${TEXT_MAX_MODEL_LEN}" \
      "${TEXT_GPU_MEMORY_UTILIZATION}" \
      "${TEXT_DTYPE}" \
      "${TEXT_EXTRA_ARGS}" || failures=$(( failures + 1 ))

    start_vllm_service \
      "autoglm" \
      "${AUTOGLM_MODEL_SOURCE}" \
      "${AUTOGLM_MODEL_NAME}" \
      "${AUTOGLM_PORT}" \
      "${AUTOGLM_GPU}" \
      "${AUTOGLM_TP_SIZE}" \
      "${AUTOGLM_MAX_MODEL_LEN}" \
      "${AUTOGLM_GPU_MEMORY_UTILIZATION}" \
      "${AUTOGLM_DTYPE}" \
      "${AUTOGLM_EXTRA_ARGS}" || failures=$(( failures + 1 ))

    start_vllm_service \
      "obstacle" \
      "${OBSTACLE_MODEL_SOURCE}" \
      "${OBSTACLE_MODEL_NAME}" \
      "${OBSTACLE_VLLM_PORT}" \
      "${OBSTACLE_GPU}" \
      "${OBSTACLE_TP_SIZE}" \
      "${OBSTACLE_MAX_MODEL_LEN}" \
      "${OBSTACLE_GPU_MEMORY_UTILIZATION}" \
      "${OBSTACLE_DTYPE}" \
      "${OBSTACLE_EXTRA_ARGS}" || failures=$(( failures + 1 ))
  fi

  if [[ "${SKIP_FASTAPI}" == false ]]; then
    print_header "Starting FastAPI Services"

    warn_if_backend_missing "planner" "${PLANNER_BASE_URL}"
    start_fastapi_service \
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

    warn_if_backend_missing "navigation" "${NAVIGATION_MODEL_BASE_URL}"
    start_fastapi_service \
      "navigation" \
      "${PROJECT_ROOT}/FastAPI/NavigationAgent" \
      "main:app" \
      "${NAVIGATION_PORT}" \
      "MODEL_BASE_URL=${NAVIGATION_MODEL_BASE_URL}" \
      "MODEL_NAME=${NAVIGATION_MODEL_NAME}" \
      "NAVIGATION_PORT=${NAVIGATION_PORT}" \
      "AMAP_API_KEY=${AMAP_API_KEY}" || failures=$(( failures + 1 ))

    warn_if_backend_missing "autoglm" "${AUTOGLM_MODEL_BASE_URL}"
    start_fastapi_service \
      "autoglm" \
      "${PROJECT_ROOT}/FastAPI/AutoGLM" \
      "server:app" \
      "${AUTOGLM_API_PORT}" \
      "MODEL_BASE_URL=${AUTOGLM_MODEL_BASE_URL}" \
      "MODEL_NAME=${AUTOGLM_MODEL_NAME}" || failures=$(( failures + 1 ))

    warn_if_backend_missing "obstacle" "${OBSTACLE_BASE_URL}"
    start_fastapi_service \
      "obstacle" \
      "${PROJECT_ROOT}/FastAPI/ObstacleDetection" \
      "main:app" \
      "${OBSTACLE_API_PORT}" \
      "BASE_URL=${OBSTACLE_BASE_URL}" \
      "MODEL_NAME=${OBSTACLE_MODEL_NAME}" \
      "API_KEY=${OBSTACLE_API_KEY}" \
      "PORT=${OBSTACLE_API_PORT}" \
      "SPRING_BOOT_TTS_URL=${SPRING_BOOT_TTS_URL}" || failures=$(( failures + 1 ))
  fi

  print_header "Startup Summary"
  printf 'vLLM\n'
  printf '  text     : http://%s:%s/v1\n' "${HEALTH_HOST}" "${TEXT_PORT}"
  printf '  autoglm  : http://%s:%s/v1\n' "${HEALTH_HOST}" "${AUTOGLM_PORT}"
  printf '  obstacle : http://%s:%s/v1\n' "${HEALTH_HOST}" "${OBSTACLE_VLLM_PORT}"
  printf '\n'
  printf 'FastAPI\n'
  printf '  planner    : http://%s:%s\n' "${HEALTH_HOST}" "${PLANNER_PORT}"
  printf '  navigation : http://%s:%s\n' "${HEALTH_HOST}" "${NAVIGATION_PORT}"
  printf '  autoglm    : http://%s:%s\n' "${HEALTH_HOST}" "${AUTOGLM_API_PORT}"
  printf '  obstacle   : http://%s:%s\n' "${HEALTH_HOST}" "${OBSTACLE_API_PORT}"
  printf '\n'
  printf 'logs        : %s\n' "${LOG_DIR}"
  printf 'stop        : bash scripts/stop_all_services.sh\n'

  if (( failures > 0 )); then
    exit 1
  fi
}

main "$@"
