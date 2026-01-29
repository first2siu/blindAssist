"""Download script for Obstacle Detection models."""

import os
import sys
import argparse
import subprocess
from pathlib import Path


def download_model(model_name: str, cache_dir: str, mirror: str = None):
    """
    Download model from HuggingFace.

    Args:
        model_name: Name of the model on HuggingFace
        cache_dir: Directory to store the model
        mirror: HuggingFace mirror URL (optional)
    """
    # Set mirror if provided
    if mirror:
        os.environ["HF_ENDPOINT"] = mirror

    cmd = [
        "huggingface-cli", "download",
        model_name,
        "--local-dir", cache_dir,
        "--local-dir-use-symlinks", "False"
    ]

    print(f"Downloading {model_name} to {cache_dir}...")
    print(f"Using mirror: {mirror or 'default (https://huggingface.co)'}")

    try:
        subprocess.run(cmd, check=True)
        print(f"✓ Successfully downloaded {model_name}")
        return True
    except subprocess.CalledProcessError as e:
        print(f"✗ Failed to download {model_name}: {e}")
        return False
    except FileNotFoundError:
        print("✗ huggingface-cli not found. Install with: pip install huggingface-hub")
        return False


def main():
    parser = argparse.ArgumentParser(description="Download obstacle detection model")
    parser.add_argument(
        "--model",
        default="Qwen/Qwen2-VL-7B-Instruct",
        help="Model name on HuggingFace"
    )
    parser.add_argument(
        "--cache-dir",
        default="./models/cache/obstacle_detection",
        help="Directory to store the model"
    )
    parser.add_argument(
        "--mirror",
        default="https://hf-mirror.com",
        help="HuggingFace mirror URL"
    )

    args = parser.parse_args()

    # Create cache directory
    cache_dir = Path(args.cache_dir)
    cache_dir.mkdir(parents=True, exist_ok=True)

    # Download model
    success = download_model(args.model, str(cache_dir), args.mirror)

    if not success:
        sys.exit(1)


if __name__ == "__main__":
    main()
