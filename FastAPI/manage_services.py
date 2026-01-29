#!/usr/bin/env python3
"""
服务管理脚本 - 启动/停止所有模型服务和 FastAPI 服务

功能:
1. 启动所有 vLLM 模型服务
2. 启动所有 FastAPI 服务
3. 停止所有服务
4. 查看服务状态

Usage:
    # 启动所有服务
    python manage_services.py start

    # 启动 vLLM 服务
    python manage_services.py start --vllm-only

    # 启动 FastAPI 服务
    python manage_services.py start --fastapi-only

    # 停止所有服务
    python manage_services.py stop

    # 查看状态
    python manage_services.py status
"""

import argparse
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path
from typing import Dict, List, Optional


# ============================================================
# 配置区域 - 可以在这里修改端口和其他配置
# ============================================================

CONFIG = {
    # 环境配置
    "conda_env": "autoglm_p310",
    "project_root": "/data/lilele",
    "log_dir": "/data/lilele/logs",
    "python_bin": "python",  # 或指定完整路径如 "/home/lilele/miniconda3/envs/autoglm_p310/bin/python"

    # vLLM 模型服务配置
    "vllm_services": {
        "intent_classifier": {
            "name": "Qwen/Qwen2-1.5B-Instruct",
            "port": 8001,
            "gpu": 0,
            "gpu_memory_utilization": 0.1,  # GPU显存利用率 (0.1-1.0)
            "model_path": "/data/lilele/IntentClassifier/models_pt/intent_classifier",
            "max_model_len": 8192,
        },
        "autoglm": {
            "name": "autoglm-phone-9b",
            "port": 8002,
            "gpu": 0,
            "gpu_memory_utilization": 0.4,
            "model_path": "/data/lilele/AutoGLM/models/ZhipuAI/AutoGLM-Phone-9B",
            "max_model_len": 8192,
        },
        "obstacle_detection": {
            "name": "Qwen/Qwen2-VL-7B-Instruct",
            "port": 8003,
            "gpu": 0,
            "gpu_memory_utilization": 0.4,
            "model_path": "/data/lilele/ObstacleDetection/models_pt/obstacle_detection",
            "max_model_len": 8192,
        },
    },

    # FastAPI 服务配置
    "fastapi_services": {
        "intent_classifier": {
            "script": "/data/lilele/IntentClassifier/main.py",
            "port": 8006,
            "vllm_url": "http://localhost:8001/v1",
        },
        "obstacle_detection": {
            "script": "/data/lilele/ObstacleDetection/main.py",
            "port": 8004,
            "vllm_url": "http://localhost:8003/v1",
        },
        "autoglm": {
            "script": "/data/lilele/AutoGLM/server.py",
            "port": 8080,
            "vllm_url": "http://localhost:8002/v1",
        },
    },

    # 启动等待时间（秒）
    "vllm_startup_wait": 30,
    "fastapi_startup_wait": 5,
}

# PID 文件存储位置
PID_DIR = "/data/lilele/logs/pids"


# ============================================================
# 服务管理类
# ============================================================

class ServiceManager:
    def __init__(self, config: dict):
        self.config = config
        self.project_root = Path(config["project_root"])
        self.log_dir = Path(config["log_dir"])
        self.pid_dir = Path(PID_DIR)
        self.pid_dir.mkdir(parents=True, exist_ok=True)
        self.log_dir.mkdir(parents=True, exist_ok=True)

    def _get_pid_file(self, service_name: str) -> Path:
        return self.pid_dir / f"{service_name}.pid"

    def _get_log_file(self, service_name: str) -> Path:
        return self.log_dir / f"{service_name}.log"

    def _read_pid(self, service_name: str) -> Optional[int]:
        pid_file = self._get_pid_file(service_name)
        if pid_file.exists():
            with open(pid_file) as f:
                try:
                    return int(f.read().strip())
                except ValueError:
                    return None
        return None

    def _write_pid(self, service_name: str, pid: int):
        pid_file = self._get_pid_file(service_name)
        with open(pid_file, 'w') as f:
            f.write(str(pid))

    def _remove_pid(self, service_name: str):
        pid_file = self._get_pid_file(service_name)
        if pid_file.exists():
            pid_file.unlink()

    def _is_process_running(self, pid: int) -> bool:
        try:
            os.kill(pid, 0)
            return True
        except OSError:
            return False

    def _get_conda_command(self) -> str:
        """获取 conda 环境下的 Python 命令"""
        if self.config.get("conda_env"):
            return f"conda run -n {self.config['conda_env']} --no-capture-output python"
        return self.config.get("python_bin", "python")

    def start_vllm_service(self, service_name: str, service_config: dict) -> bool:
        """启动单个 vLLM 服务"""
        print(f"\n{'='*60}")
        print(f"启动 vLLM 服务: {service_name}")
        print(f"{'='*60}")

        # 检查是否已运行
        existing_pid = self._read_pid(f"vllm_{service_name}")
        if existing_pid and self._is_process_running(existing_pid):
            print(f"  ✓ 服务已在运行 (PID: {existing_pid})")
            return True

        # 检查模型路径
        model_path = service_config.get("model_path")
        if model_path and not Path(model_path).exists():
            print(f"  ✗ 模型路径不存在: {model_path}")
            return False

        # 构建启动命令 - 优先使用 model_path，否则使用 name
        model_to_use = model_path if model_path else service_config["name"]
        cmd_parts = [
            self._get_conda_command(),
            "-m", "vllm.entrypoints.openai.api_server",
            "--model", model_to_use,
            "--port", str(service_config["port"]),
            "--tensor-parallel-size", "1",
            "--max-model-len", str(service_config.get("max_model_len", 8192)),
            "--dtype", "auto",
            "--gpu-memory-utilization", str(service_config.get("gpu_memory_utilization", 0.9)),
        ]

        cmd = " ".join(cmd_parts)

        # 设置 GPU
        env = os.environ.copy()
        env["CUDA_VISIBLE_DEVICES"] = str(service_config.get("gpu", 0))

        print(f"  命令: {cmd}")
        print(f"  GPU: {service_config.get('gpu', 0)}")
        print(f"  端口: {service_config['port']}")

        # 启动服务
        log_file = self._get_log_file(f"vllm_{service_name}")
        try:
            process = subprocess.Popen(
                cmd,
                shell=True,
                env=env,
                stdout=open(log_file, 'w'),
                stderr=subprocess.STDOUT,
                start_new_session=True
            )
            self._write_pid(f"vllm_{service_name}", process.pid)
            print(f"  PID: {process.pid}")
            print(f"  日志: {log_file}")

            # 等待服务启动
            print(f"  等待服务启动... (最多 {self.config['vllm_startup_wait']} 秒)")
            for i in range(self.config['vllm_startup_wait']):
                time.sleep(1)
                if not self._is_process_running(process.pid):
                    print(f"  ✗ 服务启动失败，请检查日志")
                    self._remove_pid(f"vllm_{service_name}")
                    return False
                try:
                    import requests
                    response = requests.get(f"http://localhost:{service_config['port']}/health", timeout=2)
                    if response.status_code == 200:
                        print(f"  ✓ 服务就绪")
                        return True
                except:
                    if i % 5 == 0:
                        print(f"    等待中... {i}/{self.config['vllm_startup_wait']}")

            print(f"  ⚠ 服务可能未就绪，请检查日志: {log_file}")
            return True  # 进程在运行，只是健康检查可能还没通过

        except Exception as e:
            print(f"  ✗ 启动失败: {e}")
            return False

    def start_fastapi_service(self, service_name: str, service_config: dict) -> bool:
        """启动单个 FastAPI 服务"""
        print(f"\n{'='*60}")
        print(f"启动 FastAPI 服务: {service_name}")
        print(f"{'='*60}")

        # 检查是否已运行
        existing_pid = self._read_pid(f"fastapi_{service_name}")
        if existing_pid and self._is_process_running(existing_pid):
            print(f"  ✓ 服务已在运行 (PID: {existing_pid})")
            return True

        script_path = service_config["script"]
        if not Path(script_path).exists():
            print(f"  ✗ 脚本不存在: {script_path}")
            return False

        cmd = f"{self._get_conda_command()} {script_path}"

        print(f"  命令: {cmd}")
        print(f"  端口: {service_config['port']}")
        print(f"  vLLM: {service_config['vllm_url']}")

        # 启动服务
        log_file = self._get_log_file(f"fastapi_{service_name}")
        try:
            process = subprocess.Popen(
                cmd,
                shell=True,
                stdout=open(log_file, 'w'),
                stderr=subprocess.STDOUT,
                start_new_session=True
            )
            self._write_pid(f"fastapi_{service_name}", process.pid)
            print(f"  PID: {process.pid}")
            print(f"  日志: {log_file}")

            # 等待服务启动
            time.sleep(self.config['fastapi_startup_wait'])

            if not self._is_process_running(process.pid):
                print(f"  ✗ 服务启动失败，请检查日志")
                self._remove_pid(f"fastapi_{service_name}")
                return False

            print(f"  ✓ 服务已启动")
            return True

        except Exception as e:
            print(f"  ✗ 启动失败: {e}")
            return False

    def start_all(self, vllm_only: bool = False, fastapi_only: bool = False):
        """启动所有服务"""
        print("\n" + "="*60)
        print("  服务启动管理")
        print("="*60)

        results = {"vllm": {}, "fastapi": {}}

        # 启动 vLLM 服务
        if not fastapi_only:
            print("\n>>> 启动 vLLM 模型服务")
            for name, config in self.config["vllm_services"].items():
                results["vllm"][name] = self.start_vllm_service(name, config)

        # 启动 FastAPI 服务
        if not vllm_only:
            # 如果没有跳过 vLLM，等待一下
            if not fastapi_only and not vllm_only:
                print("\n等待 vLLM 服务稳定...")
                time.sleep(5)

            print("\n>>> 启动 FastAPI 服务")
            for name, config in self.config["fastapi_services"].items():
                results["fastapi"][name] = self.start_fastapi_service(name, config)

        # 打印摘要
        self._print_startup_summary(results)

    def stop_service(self, pid: int, service_name: str) -> bool:
        """停止单个服务"""
        try:
            os.kill(pid, signal.SIGTERM)
            # 等待进程结束
            for _ in range(10):
                time.sleep(0.5)
                if not self._is_process_running(pid):
                    print(f"  ✓ 已停止: {service_name} (PID: {pid})")
                    return True
            # 强制杀死
            os.kill(pid, signal.SIGKILL)
            print(f"  ✓ 已强制停止: {service_name} (PID: {pid})")
            return True
        except OSError:
            print(f"  ✓ 进程已不存在: {service_name}")
            return True

    def stop_all(self):
        """停止所有服务"""
        print("\n" + "="*60)
        print("  停止所有服务")
        print("="*60)

        stopped = 0

        # 1. 停止所有记录的服务 (PID 文件)
        for pid_file in self.pid_dir.glob("*.pid"):
            service_name = pid_file.stem
            try:
                with open(pid_file) as f:
                    pid = int(f.read().strip())
                if self._is_process_running(pid):
                    self.stop_service(pid, service_name)
                    stopped += 1
                else:
                    print(f"  - 进程已不存在: {service_name} (PID: {pid})")
                pid_file.unlink()
            except (ValueError, IOError):
                pass

        # 2. 通过配置主动查找并停止服务
        print("\n>>> 查找并停止服务进程...")

        # 停止 vLLM 服务
        for name, config in self.config["vllm_services"].items():
            killed = self._kill_vllm_service(name, config)
            if killed:
                stopped += killed

        # 停止 FastAPI 服务
        for name, config in self.config["fastapi_services"].items():
            killed = self._kill_fastapi_service(name, config)
            if killed:
                stopped += killed

        print(f"\n总计停止 {stopped} 个服务")

    def _kill_vllm_service(self, name: str, config: dict) -> int:
        """停止 vLLM 服务"""
        port = config["port"]
        model_name = config["name"]

        # 方法1: 通过端口查找进程
        try:
            result = subprocess.run(
                ["lsof", "-ti", f":{port}"],
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode == 0:
                pids = result.stdout.strip().split('\n')
                for pid_str in pids:
                    if pid_str:
                        try:
                            pid = int(pid_str)
                            os.kill(pid, signal.SIGTERM)
                            print(f"  ✓ 停止 vLLM {name}: PID {pid} (端口 {port})")
                            return 1
                        except (ValueError, OSError):
                            pass
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass

        # 方法2: 通过进程名查找（更严格的匹配）
        patterns = [
            f"vllm.*--port {port}",  # 端口匹配最安全
            f".*python.*vllm.*{port}",  # 包含端口的完整命令
        ]
        for pattern in patterns:
            killed = self._kill_by_name(pattern, f"vLLM {name}")
            if killed:
                return killed

        return 0

    def _kill_fastapi_service(self, name: str, config: dict) -> int:
        """停止 FastAPI 服务"""
        port = config["port"]
        script = config["script"]

        # 方法1: 通过端口查找进程
        try:
            result = subprocess.run(
                ["lsof", "-ti", f":{port}"],
                capture_output=True,
                text=True,
                timeout=5
            )
            if result.returncode == 0:
                pids = result.stdout.strip().split('\n')
                for pid_str in pids:
                    if pid_str:
                        try:
                            pid = int(pid_str)
                            os.kill(pid, signal.SIGTERM)
                            print(f"  ✓ 停止 FastAPI {name}: PID {pid} (端口 {port})")
                            return 1
                        except (ValueError, OSError):
                            pass
        except (FileNotFoundError, subprocess.TimeoutExpired):
            pass

        # 方法2: 通过脚本路径查找
        killed = self._kill_by_name(script, f"FastAPI {name}")
        if killed:
            return killed

        return 0

    def _kill_by_name(self, pattern: str, service_label: str = None) -> int:
        """通过进程名杀死进程，返回 killed 数量"""
        try:
            result = subprocess.run(
                ["pgrep", "-f", pattern],
                capture_output=True,
                text=True
            )
            if result.returncode == 0:
                pids = result.stdout.strip().split('\n')
                killed = 0
                for pid_str in pids:
                    if pid_str:
                        try:
                            pid = int(pid_str)
                            os.kill(pid, signal.SIGTERM)
                            label = service_label or pattern
                            print(f"  ✓ 停止 {label}: PID {pid}")
                            killed += 1
                        except (ValueError, OSError):
                            pass
                return killed
        except FileNotFoundError:
            pass
        return 0

    def show_status(self):
        """显示服务状态"""
        print("\n" + "="*60)
        print("  服务状态")
        print("="*60)

        # vLLM 服务状态
        print("\n>>> vLLM 模型服务")
        for name, config in self.config["vllm_services"].items():
            pid = self._read_pid(f"vllm_{name}")
            is_running = False
            status = "✗ 未运行"

            # 首先检查 PID 文件记录的进程
            if pid and self._is_process_running(pid):
                is_running = True

            # 如果 PID 无效，尝试通过端口检查
            if not is_running:
                try:
                    import requests
                    response = requests.get(f"http://localhost:{config['port']}/health", timeout=2)
                    if response.status_code == 200:
                        is_running = True
                        pid = 0  # 未知 PID
                except:
                    pass

            if is_running:
                # 再次检查健康状态
                try:
                    import requests
                    response = requests.get(f"http://localhost:{config['port']}/health", timeout=2)
                    if response.status_code == 200:
                        status = "✓ 健康"
                    else:
                        status = "⚠ 响应异常"
                except:
                    status = "⚠ 无响应"
                pid_str = f"{pid:6d}" if pid else "(外部)"
                print(f"  {name:20s} PID:{pid_str}  端口:{config['port']:5d}  {status}")
            else:
                print(f"  {name:20s} {'-':6s}  端口:{config['port']:5d}  ✗ 未运行")

        # FastAPI 服务状态
        print("\n>>> FastAPI 服务")
        for name, config in self.config["fastapi_services"].items():
            pid = self._read_pid(f"fastapi_{name}")
            is_running = False
            status = "✗ 未运行"

            # 首先检查 PID 文件记录的进程
            if pid and self._is_process_running(pid):
                is_running = True

            # 如果 PID 无效，尝试通过端口检查
            if not is_running:
                try:
                    import requests
                    response = requests.get(f"http://localhost:{config['port']}/docs", timeout=2)
                    if response.status_code == 200:
                        is_running = True
                        pid = 0
                except:
                    pass

            if is_running:
                try:
                    import requests
                    response = requests.get(f"http://localhost:{config['port']}/docs", timeout=2)
                    status = "✓ 健康" if response.status_code == 200 else "⚠ 响应异常"
                except:
                    status = "⚠ 无响应"
                pid_str = f"{pid:6d}" if pid else "(外部)"
                print(f"  {name:20s} PID:{pid_str}  端口:{config['port']:5d}  {status}")
            else:
                print(f"  {name:20s} {'-':6s}  端口:{config['port']:5d}  ✗ 未运行")

        print("\n" + "="*60)

    def _print_startup_summary(self, results: dict):
        """打印启动摘要"""
        print("\n" + "="*60)
        print("  启动摘要")
        print("="*60)

        if results.get("vllm"):
            print("\nvLLM 服务:")
            for name, success in results["vllm"].items():
                status = "✓ 成功" if success else "✗ 失败"
                port = self.config["vllm_services"][name]["port"]
                print(f"  {name:20s}  端口:{port:5d}  {status}")

        if results.get("fastapi"):
            print("\nFastAPI 服务:")
            for name, success in results["fastapi"].items():
                status = "✓ 成功" if success else "✗ 失败"
                port = self.config["fastapi_services"][name]["port"]
                print(f"  {name:20s}  端口:{port:5d}  {status}")
                print(f"  {'':20s}  API:  http://localhost:{port}/docs")

        print("\n日志目录: " + str(self.log_dir))
        print("\n查看日志:")
        print("  tail -f " + str(self.log_dir) + "/*.log")
        print("\n停止服务:")
        print("  python manage_services.py stop")


# ============================================================
# 命令行接口
# ============================================================

def main():
    parser = argparse.ArgumentParser(
        description="服务管理脚本 - 启动/停止所有模型服务和 FastAPI 服务",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 启动所有服务
  python manage_services.py start

  # 只启动 vLLM 服务
  python manage_services.py start --vllm-only

  # 只启动 FastAPI 服务
  python manage_services.py start --fastapi-only

  # 停止所有服务
  python manage_services.py stop

  # 查看服务状态
  python manage_services.py status

  # 导出当前配置
  python manage_services.py export-config
        """
    )

    parser.add_argument(
        "action",
        choices=["start", "stop", "status", "export-config"],
        help="操作: start(启动), stop(停止), status(状态), export-config(导出配置)"
    )

    parser.add_argument(
        "--vllm-only",
        action="store_true",
        help="只启动 vLLM 服务"
    )

    parser.add_argument(
        "--fastapi-only",
        action="store_true",
        help="只启动 FastAPI 服务"
    )

    parser.add_argument(
        "--config",
        type=str,
        help="使用自定义配置文件 (JSON)"
    )

    args = parser.parse_args()

    # 加载配置
    config = CONFIG.copy()
    if args.config:
        with open(args.config) as f:
            user_config = json.load(f)
            config.update(user_config)

    # 创建管理器
    manager = ServiceManager(config)

    # 执行操作
    if args.action == "start":
        manager.start_all(vllm_only=args.vllm_only, fastapi_only=args.fastapi_only)
    elif args.action == "stop":
        manager.stop_all()
    elif args.action == "status":
        manager.show_status()
    elif args.action == "export-config":
        print(json.dumps(config, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
