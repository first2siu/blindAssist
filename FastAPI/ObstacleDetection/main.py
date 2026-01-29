"""Obstacle detection service using FastAPI."""

import asyncio
import base64
import io
import json
import os
from typing import Optional, Dict, Any

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from PIL import Image
from pydantic import BaseModel

sys.path.append(os.path.join(os.path.dirname(__file__), 'models'))
from qwen_vl_client import QwenVLClient
from dedup_manager import get_dedup_manager

# Configuration
MODEL_NAME = os.getenv("MODEL_NAME", "Qwen/Qwen2-VL-7B-Instruct")
BASE_URL = os.getenv("BASE_URL", "http://localhost:8003/v1")
API_KEY = os.getenv("API_KEY", "EMPTY")

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

            current_time = asyncio.get_event_loop().time()

            if msg_type == "register":
                user_id = data.get("user_id")
                # Cleanup old entries for this user
                dedup_manager.cleanup_old_entries(user_id)
                await websocket.send_json({
                    "type": "registered",
                    "message": f"User {user_id} registered for obstacle detection"
                })

            elif msg_type == "frame":
                # Frame rate limiting
                if current_time - last_frame_time < MIN_FRAME_INTERVAL:
                    continue

                last_frame_time = current_time

                # Process frame
                frame_data = data.get("frame_data")
                sensor_data = data.get("sensor_data")

                if frame_data:
                    result = vlm_client.detect_obstacles(frame_data, sensor_data)

                    # Check if should announce based on deduplication
                    obstacles = result.get("obstacles", [])

                    for obstacle in obstacles:
                        obs_type = obstacle.get("type", "unknown")
                        position = obstacle.get("position", "前方")
                        distance = obstacle.get("distance", 0)
                        urgency = obstacle.get("urgency", "medium")
                        in_path = obstacle.get("in_path", True)

                        # Only announce obstacles in the walking path
                        if not in_path:
                            continue

                        # Check deduplication
                        should_announce, reason = dedup_manager.should_announce(
                            user_id or "default",
                            obs_type,
                            position,
                            distance,
                            urgency
                        )

                        if should_announce:
                            await websocket.send_json({
                                "type": "obstacle",
                                "warning": obstacle.get("instruction", result.get("overall_instruction", "")),
                                "urgency": urgency,
                                "obstacle": obstacle,
                                "reason": reason
                            })

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


if __name__ == "__main__":
    port = int(os.getenv("PORT", 8004))
    uvicorn.run(app, host="0.0.0.0", port=port)
