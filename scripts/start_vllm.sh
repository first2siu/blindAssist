#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

LOG_DIR="${LOG_DIR:-${PROJECT_ROOT}/logs}"
PID_DIR="${PID_DIR:-${LOG_DIR}/pids}"

PYTHON_BIN="${PYTHON_BIN:-python}"
HOST="${HOST:-0.0.0.0}"
HEALTH_HOST="${HEALTH_HOST:-127.0.0.1}"
STARTUP_TIMEOUT="${STARTUP_TIMEOUT:-240}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"

TEXT_PORT="${TEXT_PORT:-8001}"
AUTOGLM_PORT="${AUTOGLM_PORT:-8000}"
OBSTACLE_PORT="${OBSTACLE_PORT:-8003}"

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

mkdir -p "${LOG_DIR}" "${PID_DIR}"

usage() {
  cat <<'EOF'
Usage:
  bash scripts/start_vllm.sh                # start all vLLM services
  bash scripts/start_vllm.sh text autoglm   # start selected services

Services:
  text       Qwen2 text model for planner + navigation
  autoglm    AutoGLM model for phone control
  obstacle   Qwen2-VL model for obstacle detection

Important env vars:
  PYTHON_BIN=python
  LOG_DIR=/data/blindassist/logs

  TEXT_MODEL_SOURCE=/data/models/Qwen2-1.5B-Instruct
  AUTOGLM_MODEL_SOURCE=/data/models/AutoGLM-Phone-9B
  OBSTACLE_MODEL_SOURCE=/data/models/Qwen2-VL-7B-Instruct

  TEXT_GPU=0
  AUTOGLM_GPU=1
  OBSTACLE_GPU=2

  TEXT_PORT=8001
  AUTOGLM_PORT=8000
  OBSTACLE_PORT=8003
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

start_service() {
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

  print_header "Starting vLLM service: ${key}"
  printf 'model source : %s\n' "${model_source}"
  printf 'served name  : %s\n' "${model_name}"
  printf 'gpu          : %s\n' "${gpu}"
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

  local -a targets=()
  if [[ $# -eq 0 ]]; then
    targets=(text autoglm obstacle)
  else
    targets=("$@")
  fi

  local failures=0

  for target in "${targets[@]}"; do
    case "${target}" in
      text)
        start_service \
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
        ;;
      autoglm)
        start_service \
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
        ;;
      obstacle)
        start_service \
          "obstacle" \
          "${OBSTACLE_MODEL_SOURCE}" \
          "${OBSTACLE_MODEL_NAME}" \
          "${OBSTACLE_PORT}" \
          "${OBSTACLE_GPU}" \
          "${OBSTACLE_TP_SIZE}" \
          "${OBSTACLE_MAX_MODEL_LEN}" \
          "${OBSTACLE_GPU_MEMORY_UTILIZATION}" \
          "${OBSTACLE_DTYPE}" \
          "${OBSTACLE_EXTRA_ARGS}" || failures=$(( failures + 1 ))
        ;;
      all)
        start_service \
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
        start_service \
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
        start_service \
          "obstacle" \
          "${OBSTACLE_MODEL_SOURCE}" \
          "${OBSTACLE_MODEL_NAME}" \
          "${OBSTACLE_PORT}" \
          "${OBSTACLE_GPU}" \
          "${OBSTACLE_TP_SIZE}" \
          "${OBSTACLE_MAX_MODEL_LEN}" \
          "${OBSTACLE_GPU_MEMORY_UTILIZATION}" \
          "${OBSTACLE_DTYPE}" \
          "${OBSTACLE_EXTRA_ARGS}" || failures=$(( failures + 1 ))
        ;;
      *)
        printf 'Unknown service: %s\n' "${target}"
        usage
        exit 1
        ;;
    esac
  done

  print_header "vLLM summary"
  printf 'text     : http://%s:%s/v1\n' "${HEALTH_HOST}" "${TEXT_PORT}"
  printf 'autoglm  : http://%s:%s/v1\n' "${HEALTH_HOST}" "${AUTOGLM_PORT}"
  printf 'obstacle : http://%s:%s/v1\n' "${HEALTH_HOST}" "${OBSTACLE_PORT}"
  printf 'logs     : %s\n' "${LOG_DIR}"

  if (( failures > 0 )); then
    exit 1
  fi
}

main "$@"
