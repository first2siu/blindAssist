"""Obstacle detection service using FastAPI."""

import asyncio
import base64
import io
import json
import os
import sys
from typing import Optional, Dict, Any
from datetime import datetime

import httpx
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image
from pydantic import BaseModel

# 调试开关：保存接收到的图像
DEBUG_SAVE_IMAGES = True
DEBUG_IMAGE_DIR = "/data/lilele/debug_obstacle_frames"

sys.path.append(os.path.join(os.path.dirname(__file__), 'models'))
from qwen_vl_client import QwenVLClient
from dedup_manager import get_dedup_manager

# Configuration
MODEL_NAME = os.getenv("MODEL_NAME", "Qwen/Qwen2-VL-7B-Instruct")
BASE_URL = os.getenv("BASE_URL", "http://10.184.17.161:8003/v1")
API_KEY = os.getenv("API_KEY", "EMPTY")

# Spring Boot REST API URL for TTS enqueue
SPRING_BOOT_TTS_URL = os.getenv(
    "SPRING_BOOT_TTS_URL",
    "http://10.181.78.161:8090/api/tts/enqueue"
)

# Initialize VLM client
vlm_client = QwenVLClient(
    base_url=BASE_URL,
    model_name=MODEL_NAME,
    api_key=API_KEY
)

# Frame rate limiting
MAX_FRAME_RATE = 3  # fps
MIN_FRAME_INTERVAL = 1.0 / MAX_FRAME_RATE

app = FastAPI(
    title="Obstacle Detection Service",
    description="Real-time obstacle detection using Qwen-VL"
)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class DetectionRequest(BaseModel):
    """Request model for obstacle detection."""
    image: str  # Base64 encoded image
    sensor_data: Optional[Dict[str, Any]] = None


class DetectionResponse(BaseModel):
    """Response model for obstacle detection."""
    safe: bool
    instruction: str
    obstacles: list = []


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "model": MODEL_NAME,
        "base_url": BASE_URL
    }


def save_debug_image(image_data: bytes, suffix: str = ""):
    """保存图像用于调试"""
    if not DEBUG_SAVE_IMAGES:
        return

    try:
        os.makedirs(DEBUG_IMAGE_DIR, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        filename = f"frame_{timestamp}_{suffix}.jpg"
        filepath = os.path.join(DEBUG_IMAGE_DIR, filename)

        with open(filepath, "wb") as f:
            f.write(image_data)

        print(f"[Debug] Image saved: {filepath}, size: {len(image_data)} bytes")
    except Exception as e:
        print(f"[Debug] Failed to save image: {e}")


@app.post("/detect", response_model=DetectionResponse)
async def detect_obstacles(request: DetectionRequest):
    """
    Detect obstacles in the image.

    Args:
        request: Detection request with base64 encoded image

    Returns:
        Detection response with obstacles and instructions
    """
    try:
        result = vlm_client.detect_obstacles(
            request.image,
            request.sensor_data
        )

        return DetectionResponse(
            safe=result.get("safe", True),
            instruction=result.get("overall_instruction", ""),
            obstacles=result.get("obstacles", [])
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Detection failed: {str(e)}")


@app.post("/find_landmark")
async def find_landmark(request: DetectionRequest):
    """
    Find specific landmark for "last ten meter" guidance.

    Args:
        request: Detection request

    Returns:
        Landmark detection result
    """
    target = request.sensor_data.get("target", "") if request.sensor_data else ""

    try:
        result = vlm_client.find_landmark(request.image, target)
        return result

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Landmark detection failed: {str(e)}")


@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    """
    WebSocket endpoint for real-time obstacle detection.

    Expected message format:
    {
        "type": "register" | "frame" | "keep_alive",
        "user_id": "string",
        "frame_data": "base64_encoded_image"  # for frame type
        "sensor_data": {...}  # optional
    }
    """
    await websocket.accept()

    user_id = None
    last_frame_time = 0
    dedup_manager = get_dedup_manager()

    try:
        while True:
            data = await websocket.receive_json()
            msg_type = data.get("type")

            print(f"[WebSocket] Received message type: {msg_type}")

            current_time = asyncio.get_event_loop().time()

            if msg_type == "register":
                user_id = data.get("user_id")
                print(f"[WebSocket] User registered: {user_id}")
                # Cleanup old entries for this user
                dedup_manager.cleanup_old_entries(user_id)
                await websocket.send_json({
                    "type": "registered",
                    "message": f"User {user_id} registered for obstacle detection"
                })

            elif msg_type == "frame":
                # Frame rate limiting
                if current_time - last_frame_time < MIN_FRAME_INTERVAL:
                    print(f"[WebSocket] Frame skipped due to rate limiting")
                    continue

                last_frame_time = current_time

                # Process frame
                frame_data = data.get("frame_data")
                sensor_data = data.get("sensor_data")

                print(f"[WebSocket] Frame received, frame_data length: {len(frame_data) if frame_data else 0}")

                if frame_data:
                    print(f"[WebSocket] Processing frame...")

                    # 解码并保存原始图像用于调试
                    try:
                        image_bytes = base64.b64decode(frame_data)
                        save_debug_image(image_bytes, "received")
                        print(f"[WebSocket] Decoded image size: {len(image_bytes)} bytes")
                    except Exception as e:
                        print(f"[WebSocket] Failed to decode image: {e}")

                    result = vlm_client.detect_obstacles(frame_data, sensor_data)
                    print(f"[WebSocket] Detection result: {result}")

                    # Check if should announce based on deduplication
                    obstacles = result.get("obstacles", [])
                    print(f"[WebSocket] Found {len(obstacles)} obstacles")

                    for obstacle in obstacles:
                        obs_type = obstacle.get("type", "unknown")
                        position = obstacle.get("position", "前方")
                        distance = obstacle.get("distance", 0)
                        urgency = obstacle.get("urgency", "medium")
                        in_path = obstacle.get("in_path", True)

                        print(f"[WebSocket] Obstacle: type={obs_type}, position={position}, in_path={in_path}")

                        # Only announce obstacles in the walking path
                        if not in_path:
                            print(f"[WebSocket] Skipping obstacle (not in path): {obs_type}")
                            continue

                        # Check deduplication
                        should_announce, reason = dedup_manager.should_announce(
                            user_id or "android_user_default",
                            obs_type,
                            position,
                            distance,
                            urgency
                        )

                        print(f"[WebSocket] Should announce: {should_announce}, reason: {reason}")

                        if should_announce:
                            instruction = obstacle.get("instruction", result.get("overall_instruction", ""))
                            print(f"[WebSocket] Sending to TTS queue: {instruction}")
                            # Send to Spring Boot TTS queue instead of back to Android via WebSocket
                            await send_to_spring_boot_tts(
                                user_id=user_id or "android_user_default",
                                instruction=instruction,
                                urgency=urgency,
                                source="obstacle"
                            )
                            # Still send a minimal acknowledgment via WebSocket
                            await websocket.send_json({
                                "type": "obstacle_queued",
                                "urgency": urgency,
                                "reason": reason
                            })
                        else:
                            print(f"[WebSocket] Skipping announcement (duplicate): {reason}")

            elif msg_type == "keep_alive":
                await websocket.send_json({"type": "alive"})

    except WebSocketDisconnect:
        print(f"WebSocket disconnected for user {user_id}")
    except Exception as e:
        print(f"WebSocket error: {e}")
        try:
            await websocket.send_json({
                "type": "error",
                "message": str(e)
            })
        except:
            pass


def _get_urgency_from_obstacles(obstacles: list) -> str:
    """Determine urgency level from obstacles."""
    if not obstacles:
        return "normal"

    for obstacle in obstacles:
        urgency = obstacle.get("urgency", "low")
        if urgency in ("critical", "high"):
            return urgency

    return "normal"


async def send_to_spring_boot_tts(
    user_id: str,
    instruction: str,
    urgency: str,
    source: str = "obstacle"
) -> bool:
    """
    Send obstacle detection result to Spring Boot TTS queue.

    Args:
        user_id: User identifier
        instruction: TTS instruction text
        urgency: Urgency level (critical, high, medium, low)
        source: Source identifier

    Returns:
        True if successful, False otherwise
    """
    # Map urgency to TTS priority
    priority_map = {
        "critical": "CRITICAL",
        "high": "HIGH",
        "medium": "NORMAL",
        "low": "LOW"
    }
    priority = priority_map.get(urgency, "NORMAL")

    payload = {
        "user_id": user_id,
        "content": instruction,
        "priority": priority,
        "source": source
    }

    print(f"[TTS] Sending to Spring Boot: url={SPRING_BOOT_TTS_URL}, payload={payload}")

    try:
        async with httpx.AsyncClient(timeout=10.0) as client:  # 增加超时到10秒
            response = await client.post(
                SPRING_BOOT_TTS_URL,
                json=payload,
                headers={"Content-Type": "application/json"}
            )
            print(f"[TTS] Response: status={response.status_code}, body={response.text[:200]}")
            if response.status_code == 200:
                print(f"[ObstacleDetection] Sent to TTS queue: {instruction}")
                return True
            else:
                print(f"[ObstacleDetection] Failed to send to TTS: {response.status_code}")
                return False
    except Exception as e:
        print(f"[ObstacleDetection] Error sending to Spring Boot: {e}")
        import traceback
        traceback.print_exc()
        return False


if __name__ == "__main__":
    port = int(os.getenv("PORT", 8004))
    uvicorn.run(app, host="0.0.0.0", port=port)
