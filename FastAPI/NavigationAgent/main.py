"""Navigation FastAPI service with websocket and planner-tool interfaces."""

from __future__ import annotations

import os
import time
from typing import Dict, Optional

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, ConfigDict, Field

from navigation_agent import NavigationAgent, SensorData


MODEL_BASE_URL = os.getenv("MODEL_BASE_URL", "http://localhost:8001/v1")
MODEL_NAME = os.getenv("MODEL_NAME", "Qwen/Qwen2-1.5B-Instruct")
NAVIGATION_PORT = int(os.getenv("NAVIGATION_PORT", 8081))


class NavigationToolRequest(BaseModel):
    """Shared fields for tool-style navigation calls."""

    model_config = ConfigDict(populate_by_name=True)

    session_id: str = "planner-session"
    origin: Optional[Dict[str, float]] = None
    sensor_data: Optional[Dict[str, float]] = Field(default=None, alias="sensor_data")


class NavigationStartRequest(NavigationToolRequest):
    """Start-navigation tool request."""

    model_config = ConfigDict(populate_by_name=True)

    user_task: str = Field(alias="user_task")
    amap_api_key: Optional[str] = Field(default=None, alias="amap_api_key")


class NavigationCancelRequest(BaseModel):
    """Cancel-navigation tool request."""

    session_id: str = "planner-session"


class NavigationControlRequest(NavigationToolRequest):
    """Pause/resume request for an existing navigation session."""


class ConnectionManager:
    """Tracks websocket connections and in-memory navigation agents."""

    def __init__(self) -> None:
        self.active_connections: Dict[str, WebSocket] = {}
        self.navigation_agents: Dict[str, NavigationAgent] = {}

    async def connect(self, websocket: WebSocket, client_id: str) -> NavigationAgent:
        if client_id in self.active_connections:
            old_websocket = self.active_connections[client_id]
            try:
                await old_websocket.close()
            except Exception:
                pass
            self.navigation_agents.pop(client_id, None)

        await websocket.accept()
        self.active_connections[client_id] = websocket
        return self.get_or_create_agent(client_id)

    def disconnect(self, client_id: str) -> None:
        self.active_connections.pop(client_id, None)
        self.navigation_agents.pop(client_id, None)

    def get_or_create_agent(self, client_id: str) -> NavigationAgent:
        agent = self.navigation_agents.get(client_id)
        if agent is None:
            agent = NavigationAgent(
                model_base_url=MODEL_BASE_URL,
                model_name=MODEL_NAME,
            )
            self.navigation_agents[client_id] = agent
        return agent

    def remove_agent(self, client_id: str) -> None:
        self.navigation_agents.pop(client_id, None)


manager = ConnectionManager()

app = FastAPI(title="Navigation Agent Service")
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _wrap_navigation_result(result: Dict[str, object], source_tool: str) -> Dict[str, object]:
    """Attach a normalized observation envelope while preserving legacy fields."""
    status = str(result.get("status", "success"))
    result_type = str(result.get("type", "navigation"))
    instruction = str(result.get("instruction") or result.get("message") or "")

    if result_type == "arrived":
        delegation_status = "FINISHED"
    elif status == "error":
        delegation_status = "ASK_USER"
    else:
        delegation_status = "CONTINUE"

    client_message: Optional[Dict[str, object]] = None
    if result_type == "route_planned":
        client_message = {"status": "success", "type": "route_planned", "message": "Route planned."}
    elif result_type == "arrived":
        client_message = {"status": "success", "type": "arrived", "message": "Arrived at destination."}
    elif result_type == "paused":
        client_message = {"status": "success", "type": "paused", "message": "Navigation paused."}
    elif result_type == "resumed":
        client_message = {"status": "success", "type": "resumed", "message": "Navigation resumed."}
    elif status == "error":
        client_message = {"status": "error", "message": instruction or "Navigation failed."}

    envelope = {
        "observation_type": "navigation",
        "delegation_status": delegation_status,
        "summary": instruction or result_type,
        "structured_data": result,
        "source_tool": source_tool,
        "timestamp": int(time.time() * 1000),
        "client_message": client_message,
        "tts_message": instruction or None,
    }
    return {**result, **envelope}


@app.get("/health")
async def health_check() -> Dict[str, str]:
    """Health endpoint."""
    return {"status": "healthy", "service": "navigation-agent"}


@app.get("/tools/metadata")
async def tools_metadata() -> Dict[str, object]:
    """Expose planner-facing tool metadata for this service."""
    return {
        "service": "navigation-agent",
        "tools": [
            {
                "name": "navigation.start",
                "transport": "http",
                "endpoint": "/tools/navigation/start",
            },
            {
                "name": "navigation.update",
                "transport": "http",
                "endpoint": "/tools/navigation/update",
            },
            {
                "name": "navigation.cancel",
                "transport": "http",
                "endpoint": "/tools/navigation/cancel",
            },
            {
                "name": "navigation.websocket",
                "transport": "websocket",
                "endpoint": "/ws/navigation/{client_id}",
            },
        ],
    }


@app.post("/tools/navigation/start")
async def tool_start_navigation(request: NavigationStartRequest) -> Dict[str, object]:
    """Planner-callable HTTP wrapper for starting navigation."""
    agent = manager.get_or_create_agent(request.session_id)
    if request.amap_api_key:
        agent.amap_client.api_key = request.amap_api_key
        agent.amap_client.session.params = {"key": request.amap_api_key}

    result = await agent.initialize_navigation(
        user_task=request.user_task,
        origin=request.origin or {"lon": 116.397428, "lat": 39.90923},
        sensor_data=_build_sensor_data(request.sensor_data),
    )
    return _wrap_navigation_result(result, "navigation.start")


@app.post("/tools/navigation/update")
async def tool_update_navigation(request: NavigationToolRequest) -> Dict[str, object]:
    """Planner-callable HTTP wrapper for updating navigation."""
    agent = manager.get_or_create_agent(request.session_id)
    result = await agent.update_location(
        location=request.origin or {"lon": 116.397428, "lat": 39.90923},
        sensor_data=_build_sensor_data(request.sensor_data),
    )
    return _wrap_navigation_result(result, "navigation.update")


@app.post("/tools/navigation/pause")
async def tool_pause_navigation(request: NavigationControlRequest) -> Dict[str, object]:
    """Internal HTTP wrapper for pausing navigation."""
    agent = manager.get_or_create_agent(request.session_id)
    return _wrap_navigation_result(agent.pause_navigation(), "control.pause")


@app.post("/tools/navigation/resume")
async def tool_resume_navigation(request: NavigationControlRequest) -> Dict[str, object]:
    """Internal HTTP wrapper for resuming navigation."""
    agent = manager.get_or_create_agent(request.session_id)
    result = await agent.resume_navigation(
        location=request.origin,
        sensor_data=_build_sensor_data(request.sensor_data),
    )
    return _wrap_navigation_result(result, "control.resume")


@app.post("/tools/navigation/cancel")
async def tool_cancel_navigation(request: NavigationCancelRequest) -> Dict[str, object]:
    """Planner-callable HTTP wrapper for cancelling navigation."""
    agent = manager.get_or_create_agent(request.session_id)
    result = agent.cancel_navigation()
    manager.remove_agent(request.session_id)
    return _wrap_navigation_result(result, "navigation.cancel")


@app.websocket("/ws/navigation/{client_id}")
async def navigation_websocket_endpoint(websocket: WebSocket, client_id: str) -> None:
    """Websocket entrypoint used by the Java server today."""
    agent = await manager.connect(websocket, client_id)

    try:
        while True:
            data = await websocket.receive_json()
            req_type = data.get("type")

            if req_type == "init":
                if data.get("amap_api_key"):
                    agent.amap_client.api_key = data["amap_api_key"]
                    agent.amap_client.session.params = {"key": data["amap_api_key"]}
                result = await agent.initialize_navigation(
                    user_task=data.get("user_task", ""),
                    origin=data.get("origin") or {"lon": 116.397428, "lat": 39.90923},
                    sensor_data=_build_sensor_data(data.get("sensor_data")),
                )
                await websocket.send_json(result)
            elif req_type == "location_update":
                result = await agent.update_location(
                    location=data.get("origin") or {"lon": 116.397428, "lat": 39.90923},
                    sensor_data=_build_sensor_data(data.get("sensor_data")),
                )
                await websocket.send_json(result)
            elif req_type == "pause":
                await websocket.send_json(agent.pause_navigation())
            elif req_type == "resume":
                result = await agent.resume_navigation(
                    location=data.get("origin"),
                    sensor_data=_build_sensor_data(data.get("sensor_data")),
                )
                await websocket.send_json(result)
            elif req_type == "cancel":
                await websocket.send_json(agent.cancel_navigation())
                manager.disconnect(client_id)
                break
            else:
                await websocket.send_json(
                    {"status": "error", "message": f"Unknown message type: {req_type}"}
                )
    except WebSocketDisconnect:
        manager.disconnect(client_id)
    except Exception as exc:
        await websocket.send_json({"status": "error", "message": str(exc)})
        manager.disconnect(client_id)


def _build_sensor_data(raw_sensor_data: Optional[Dict[str, float]]) -> SensorData:
    """Convert request payloads into SensorData."""
    raw_sensor_data = raw_sensor_data or {}
    return SensorData(
        heading=float(raw_sensor_data.get("heading", 0.0) or 0.0),
        accuracy=float(raw_sensor_data.get("accuracy", 0.0) or 0.0),
        pitch=float(raw_sensor_data.get("pitch", 0.0) or 0.0),
        roll=float(raw_sensor_data.get("roll", 0.0) or 0.0),
    )


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=NAVIGATION_PORT)
