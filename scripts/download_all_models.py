#!/usr/bin/env python3
"""
Unified model download script for all services.

Downloads:
1. Qwen2-1.5B-Instruct - Intent classification (lightweight, fast response)
2. AutoGLM-9B - Phone control and navigation
3. Qwen2-VL-7B-Instruct - Obstacle detection

Usage:
    python scripts/download_all_models.py [--mirror MIRROR]
"""

import argparse
import os
import subprocess
import sys
from pathlib import Path
from typing import Dict, List

# Model configurations
MODELS = {
    "intent_classifier": {
        "name": "Qwen/Qwen2-1.5B-Instruct",
        "cache_dir": "./models/cache/intent_classifier",
        "size_gb": 3,
        "description": "意图分类（轻量级，响应快速）"
    },
    "autoglm": {
        "name": "autoglm-phone-9b",
        "cache_dir": "./models/cache/autoglm",
        "size_gb": 20,
        "description": "手机操控和导航"
    },
    "obstacle_detection": {
        "name": "Qwen/Qwen2-VL-7B-Instruct",
        "cache_dir": "./models/cache/obstacle_detection",
        "size_gb": 16,
        "description": "视觉障碍物检测"
    }
}

DEFAULT_MIRROR = "https://hf-mirror.com"


def print_header(text: str):
    """Print a formatted header."""
    print("\n" + "=" * 60)
    print(f"  {text}")
    print("=" * 60)


def check_disk_space(required_gb: float) -> bool:
    """Check if there's enough disk space."""
    try:
        import shutil
        stat = shutil.disk_usage("/")
        free_gb = stat.free / (1024 ** 3)
        return free_gb >= required_gb * 1.5  # 50% buffer
    except:
        return True  # Assume OK if check fails


def check_huggingface_cli() -> bool:
    """Check if huggingface-cli is installed."""
    try:
        result = subprocess.run(
            ["huggingface-cli", "--version"],
            capture_output=True,
            timeout=5
        )
        return result.returncode == 0
    except:
        return False


def download_model(model_name: str, cache_dir: str, mirror: str) -> bool:
    """Download a single model."""
    cache_path = Path(cache_dir)
    cache_path.mkdir(parents=True, exist_ok=True)

    # Set mirror
    if mirror:
        os.environ["HF_ENDPOINT"] = mirror

    cmd = [
        "huggingface-cli", "download",
        model_name,
        "--local-dir", str(cache_path),
        "--local-dir-use-symlinks", "False"
    ]

    print(f"  模型: {model_name}")
    print(f"  缓存: {cache_dir}")
    print(f"  镜像: {mirror or '默认'}")

    try:
        subprocess.run(cmd, check=True)
        print(f"  ✓ 下载成功")
        return True
    except subprocess.CalledProcessError as e:
        print(f"  ✗ 下载失败: {e}")
        return False
    except FileNotFoundError:
        print(f"  ✗ huggingface-cli 未安装")
        print(f"  安装命令: pip install huggingface-hub")
        return False


def main():
    parser = argparse.ArgumentParser(
        description="下载所有导航避障模块所需的模型",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  # 下载所有模型（使用镜像）
  python scripts/download_all_models.py

  # 下载指定模型
  python scripts/download_all_models.py --model intent_classifier

  # 使用官方源下载
  python scripts/download_all_models.py --mirror ""
        """
    )

    parser.add_argument(
        "--model",
        choices=["all", "intent_classifier", "autoglm", "obstacle_detection"],
        default="all",
        help="要下载的模型（默认: all）"
    )
    parser.add_argument(
        "--mirror",
        default=DEFAULT_MIRROR,
        help="HuggingFace镜像地址（默认: %s）" % DEFAULT_MIRROR
    )
    parser.add_argument(
        "--skip-existing",
        action="store_true",
        help="跳过已存在的模型"
    )

    args = parser.parse_args()

    print_header("导航避障模块 - 模型下载工具")

    # Check huggingface-cli
    if not check_huggingface_cli():
        print("\n✗ huggingface-cli 未安装！")
        print("\n请先安装:")
        print("  pip install huggingface-hub")
        sys.exit(1)

    print("\n✓ huggingface-cli 已安装")

    # Determine which models to download
    models_to_download = []
    if args.model == "all":
        models_to_download = list(MODELS.keys())
    else:
        models_to_download = [args.model]

    # Check disk space
    total_size = sum(MODELS[m]["size_gb"] for m in models_to_download)
    print(f"\n需要磁盘空间: ~{total_size * 1.5:.1f} GB (包含余量)")

    if not check_disk_space(total_size * 1.5):
        print("\n✗ 磁盘空间不足！")
        sys.exit(1)

    print("✓ 磁盘空间充足")

    # Download models
    results = {}

    for model_key in models_to_download:
        config = MODELS[model_key]

        print_header(f"下载: {config['description']}")

        # Check if already exists
        cache_path = Path(config["cache_dir"])
        if args.skip_existing and cache_path.exists():
            print(f"  ✓ 跳过已存在的模型: {model_key}")
            results[model_key] = True
            continue

        success = download_model(
            config["name"],
            config["cache_dir"],
            args.mirror
        )
        results[model_key] = success

    # Summary
    print_header("下载结果摘要")

    all_success = all(results.values())
    for model_key, success in results.items():
        status = "✓ 成功" if success else "✗ 失败"
        print(f"  {MODELS[model_key]['description']}: {status}")

    print()

    if all_success:
        print("所有模型下载完成！")
        print("\n下一步:")
        print("  1. 启动vLLM服务: bash scripts/start_all_services.sh")
        print("  2. 启动FastAPI服务")
        print("  3. 启动Spring Boot服务")
    else:
        print("部分模型下载失败，请重试或手动下载")
        sys.exit(1)


if __name__ == "__main__":
    main()
