#!/bin/bash
#
# Stop all services

echo "正在停止所有服务..."

# Kill vLLM processes
pkill -f "vllm.entrypoints.openai" || true

# Kill FastAPI processes
pkill -f "IntentClassifier/main.py" || true
pkill -f "ObstacleDetection/main.py" || true

# Kill specific ports
for port in 8000 8001 8002 8003 8004; do
    pid=$(lsof -ti:$port 2>/dev/null || true)
    if [ -n "$pid" ]; then
        kill $pid 2>/dev/null || true
        echo "已停止端口 $port 的进程 (PID: $pid)"
    fi
done

echo "所有服务已停止"
