#!/bin/bash
#
# Unified service startup script
#
# Starts all vLLM and FastAPI services for navigation and obstacle detection
#
# Usage:
#   bash scripts/start_all_services.sh [--gpu-only] [--fastapi-only]

set -e

# Script directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

# Configuration
LOG_DIR="$PROJECT_ROOT/logs"
MODELS_DIR="$PROJECT_ROOT/FastAPI/models"

# GPU allocation
GPU_INTENT=0
GPU_AUTOGLM=2
GPU_OBSTACLE=3

# vLLM ports
PORT_INTENT=8001
PORT_AUTOGLM=8000
PORT_OBSTACLE=8003

# FastAPI ports
PORT_INTENT_API=8002
PORT_OBSTACLE_API=8004

# Create log directory
mkdir -p "$LOG_DIR"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

print_header() {
    echo -e "\n${GREEN}================================${NC}"
    echo -e "${GREEN}  $1${NC}"
    echo -e "${GREEN}================================${NC}\n"
}

print_step() {
    echo -e "\n${YELLOW}>>> $1${NC}\n"
}

check_gpu() {
    print_step "检查GPU"

    if command -v nvidia-smi &> /dev/null; then
        nvidia-smi --query-gpu=index,name,memory.total,memory.free --format=csv
    else
        echo "⚠ 未检测到nvidia-smi，可能没有GPU"
    fi
}

start_vllm() {
    local model_name=$1
    local port=$2
    local gpu=$3
    local log_file=$4

    print_step "启动vLLM: $model_name (GPU $gpu, Port $port)"

    CUDA_VISIBLE_DEVICES=$gpu nohup python -m vllm.entrypoints.openai.api_server \
        --model "$model_name" \
        --port $port \
        --tensor-parallel-size 1 \
        --max-model-len 8192 \
        --dtype auto \
        > "$log_file" 2>&1 &

    local pid=$!
    echo "  PID: $pid"
    echo "  日志: $log_file"

    # Wait for service to be ready
    echo "  等待服务启动..."
    sleep 30

    if curl -s http://localhost:$port/health > /dev/null 2>&1; then
        echo "  ✓ 服务就绪"
    else
        echo "  ⚠ 服务可能未就绪，请检查日志"
    fi
}

start_fastapi() {
    local service_name=$1
    local script_path=$2
    local port=$3
    local log_file=$4

    print_step "启动FastAPI: $service_name (Port $port)"

    nohup python "$script_path" \
        > "$log_file" 2>&1 &

    local pid=$!
    echo "  PID: $pid"
    echo "  日志: $log_file"

    sleep 5

    if curl -s http://localhost:$port/health > /dev/null 2>&1; then
        echo "  ✓ 服务就绪"
    else
        echo "  ⚠ 服务可能未就绪，请检查日志"
    fi
}

# Main execution
print_header "导航避障模块 - 服务启动脚本"

# Parse arguments
SKIP_VLLM=false
SKIP_FASTAPI=false

for arg in "$@"; do
    case $arg in
        --gpu-only)
            SKIP_FASTAPI=true
            ;;
        --fastapi-only)
            SKIP_VLLM=true
            ;;
        --help)
            echo "用法: bash scripts/start_all_models.sh [选项]"
            echo ""
            echo "选项:"
            echo "  --gpu-only       只启动vLLM服务"
            echo "  --fastapi-only   只启动FastAPI服务"
            echo "  --help          显示帮助"
            exit 0
            ;;
    esac
done

# Check GPU
check_gpu

# Start vLLM services
if [ "$SKIP_VLLM" = false ]; then
    # Intent Classifier - 使用轻量级 Qwen2-1.5B-Instruct
    start_vllm \
        "Qwen/Qwen2-1.5B-Instruct" \
        $PORT_INTENT \
        $GPU_INTENT \
        "$LOG_DIR/vllm_intent_classifier.log"

    # AutoGLM
    start_vllm \
        "autoglm-phone-9b" \
        $PORT_AUTOGLM \
        $GPU_AUTOGLM \
        "$LOG_DIR/vllm_autoglm.log"

    # Obstacle Detection
    start_vllm \
        "Qwen/Qwen2-VL-7B-Instruct" \
        $PORT_OBSTACLE \
        $GPU_OBSTACLE \
        "$LOG_DIR/vllm_obstacle_detection.log"
fi

# Start FastAPI services
if [ "$SKIP_FASTAPI" = false ]; then
    # Intent Classifier API
    start_fastapi \
        "IntentClassifier" \
        "$PROJECT_ROOT/FastAPI/IntentClassifier/main.py" \
        $PORT_INTENT_API \
        "$LOG_DIR/intent_classifier_api.log"

    # Obstacle Detection API
    start_fastapi \
        "ObstacleDetection" \
        "$PROJECT_ROOT/FastAPI/ObstacleDetection/main.py" \
        $PORT_OBSTACLE_API \
        "$LOG_DIR/obstacle_detection_api.log"
fi

# Summary
print_header "服务启动完成"

echo "vLLM服务:"
echo "  - 意图分类/导航:     http://localhost:$PORT_INTENT"
echo "  - AutoGLM:          http://localhost:$PORT_AUTOGLM"
echo "  - 避障检测:         http://localhost:$PORT_OBSTACLE"
echo ""
echo "FastAPI服务:"
echo "  - 意图分类API:      http://localhost:$PORT_INTENT_API"
echo "  - 避障检测API:      http://localhost:$PORT_OBSTACLE_API"
echo ""
echo "日志目录: $LOG_DIR"
echo ""
echo "查看日志:"
echo "  tail -f $LOG_DIR/vllm_*.log"
echo "  tail -f $LOG_DIR/*_api.log"
echo ""
echo "停止服务:"
echo "  bash scripts/stop_all_services.sh"
