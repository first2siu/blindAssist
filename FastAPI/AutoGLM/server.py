"""AutoGLM FastAPI wrapper with websocket and planner-tool interfaces."""

from __future__ import annotations

import os
import time
from typing import Any, Dict, List, Optional

import uvicorn
from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from pydantic import BaseModel, ConfigDict, Field

from phone_agent.actions.handler import parse_action
from phone_agent.config import get_system_prompt
from phone_agent.model import ModelClient, ModelConfig
from phone_agent.model.client import MessageBuilder


app = FastAPI(title="AutoGLM Phone Control Service")

model_config = ModelConfig(
    base_url=os.getenv("MODEL_BASE_URL", "http://localhost:8002/v1"),
    model_name=os.getenv("MODEL_NAME", "/data/lilele/AutoGLM/models/ZhipuAI/AutoGLM-Phone-9B"),
    temperature=0.1,
)
model_client = ModelClient(model_config)
system_prompt = get_system_prompt("cn")


class PhoneToolBaseRequest(BaseModel):
    """Shared request fields for planner-callable phone tools."""

    model_config = ConfigDict(populate_by_name=True)

    session_id: str = "planner-session"
    screenshot: Optional[str] = None
    screen_info: str = Field(default="", alias="screen_info")


class PhoneToolStartRequest(PhoneToolBaseRequest):
    """Start request for the phone-control tool interface."""

    task: str


class PhoneToolResetRequest(BaseModel):
    """Reset request for the phone-control tool interface."""

    session_id: str = "planner-session"


class AgentSession:
    """In-memory phone-control session context."""

    def __init__(self, client_id: str) -> None:
        self.client_id = client_id
        self.context: List[Dict[str, Any]] = []
        self.step_count = 0

    def init_session(self, task: str, screen_info: str, screenshot_base64: str) -> None:
        self.context = [MessageBuilder.create_system_message(system_prompt)]
        self.step_count = 1
        text_content = f"{task}\n\nScreen Info: {screen_info}"
        self.context.append(
            MessageBuilder.create_user_message(
                text=text_content,
                image_base64=screenshot_base64,
            )
        )

    def step_session(self, screen_info: str, screenshot_base64: str) -> None:
        self.step_count += 1
        if self.context:
            self.context[-1] = MessageBuilder.remove_images_from_message(self.context[-1])

        self.context.append(
            MessageBuilder.create_user_message(
                text=f"** Screen Info **\n\n{screen_info}",
                image_base64=screenshot_base64,
            )
        )

    def add_assistant_response(self, thinking: str, action_str: str) -> None:
        self.context.append(
            MessageBuilder.create_assistant_message(
                f"{thinking}\n\n<answer>{action_str}</answer>"
            )
        )


class ConnectionManager:
    """Stores websocket connections and reusable sessions."""

    def __init__(self) -> None:
        self.active_connections: Dict[str, WebSocket] = {}
        self.phone_sessions: Dict[str, AgentSession] = {}

    async def connect(self, websocket: WebSocket, client_id: str) -> AgentSession:
        if client_id in self.active_connections:
            old_websocket = self.active_connections[client_id]
            try:
                await old_websocket.close()
            except Exception:
                pass
            self.phone_sessions.pop(client_id, None)

        await websocket.accept()
        self.active_connections[client_id] = websocket
        return self.get_or_create_session(client_id)

    def disconnect(self, client_id: str) -> None:
        self.active_connections.pop(client_id, None)
        self.phone_sessions.pop(client_id, None)

    def get_or_create_session(self, client_id: str) -> AgentSession:
        session = self.phone_sessions.get(client_id)
        if session is None:
            session = AgentSession(client_id)
            self.phone_sessions[client_id] = session
        return session

    def reset_session(self, client_id: str) -> None:
        self.phone_sessions.pop(client_id, None)


manager = ConnectionManager()


def _wrap_phone_result(result: Dict[str, Any], source_tool: str) -> Dict[str, Any]:
    """Attach a normalized observation envelope while preserving legacy fields."""
    action = result.get("action") if isinstance(result.get("action"), dict) else {}
    action_metadata = str(action.get("_metadata") or "").lower()

    delegation_status = "CONTINUE"
    if result.get("finished") or action_metadata in {"finish", "done"}:
        delegation_status = "FINISHED"
    elif action_metadata in {"ask_user", "need_input", "need_user_input"}:
        delegation_status = "ASK_USER"
    elif result.get("status") == "error":
        delegation_status = "ASK_USER"

    summary = str(result.get("raw_response") or result.get("thinking") or "Phone-control step completed.")
    structured_data = {
        "step": result.get("step"),
        "thinking": result.get("thinking"),
        "action": result.get("action"),
        "raw_response": result.get("raw_response"),
        "finished": result.get("finished"),
    }

    envelope = {
        "observation_type": "phone_control",
        "delegation_status": delegation_status,
        "summary": summary,
        "structured_data": structured_data,
        "source_tool": source_tool,
        "timestamp": int(time.time() * 1000),
        "client_message": result,
        "tts_message": None,
    }
    return {**result, **envelope}


@app.get("/health")
async def health_check() -> Dict[str, str]:
    """Health endpoint."""
    return {"status": "healthy", "service": "autoglm-phone-control"}


@app.get("/tools/metadata")
async def tools_metadata() -> Dict[str, object]:
    """Expose planner-facing tool metadata."""
    return {
        "service": "autoglm",
        "tools": [
            {
                "name": "phone_control.start",
                "transport": "http",
                "endpoint": "/tools/phone/start",
            },
            {
                "name": "phone_control.step",
                "transport": "http",
                "endpoint": "/tools/phone/step",
            },
            {
                "name": "phone_control.reset",
                "transport": "http",
                "endpoint": "/tools/phone/reset",
            },
            {
                "name": "phone_control.websocket",
                "transport": "websocket",
                "endpoint": "/ws/agent/{client_id}",
            },
        ],
    }


@app.post("/tools/phone/start")
async def tool_phone_start(request: PhoneToolStartRequest) -> Dict[str, Any]:
    """Planner-callable HTTP wrapper for starting a phone-control session."""
    if not request.screenshot:
        raise HTTPException(status_code=400, detail="Missing screenshot")

    session = manager.get_or_create_session(request.session_id)
    session.init_session(request.task, request.screen_info, request.screenshot)
    return _wrap_phone_result(_run_model_step(session), "phone_control.start")


@app.post("/tools/phone/step")
async def tool_phone_step(request: PhoneToolBaseRequest) -> Dict[str, Any]:
    """Planner-callable HTTP wrapper for continuing a phone-control session."""
    if not request.screenshot:
        raise HTTPException(status_code=400, detail="Missing screenshot")

    session = manager.get_or_create_session(request.session_id)
    if not session.context:
        raise HTTPException(status_code=404, detail="Session not initialized")

    session.step_session(request.screen_info, request.screenshot)
    return _wrap_phone_result(_run_model_step(session), "phone_control.step")


@app.post("/tools/phone/reset")
async def tool_phone_reset(request: PhoneToolResetRequest) -> Dict[str, str]:
    """Clear planner-callable phone-control session state."""
    manager.reset_session(request.session_id)
    legacy = {"status": "success", "message": "phone_control_session_reset"}
    return {
        **legacy,
        "observation_type": "phone_control",
        "delegation_status": "FINISHED",
        "summary": "Phone-control session reset.",
        "structured_data": {"session_id": request.session_id},
        "source_tool": "phone_control.reset",
        "timestamp": int(time.time() * 1000),
        "client_message": legacy,
        "tts_message": None,
    }


@app.websocket("/ws/agent/{client_id}")
async def agent_websocket_endpoint(websocket: WebSocket, client_id: str) -> None:
    """Existing websocket entrypoint used by the Java server."""
    session = await manager.connect(websocket, client_id)

    try:
        while True:
            data = await websocket.receive_json()
            req_type = data.get("type")
            screenshot = data.get("screenshot")
            screen_info = data.get("screen_info", "Unknown Page")

            if not screenshot:
                await websocket.send_json({"status": "error", "message": "Missing screenshot"})
                continue

            if req_type == "init":
                task = data.get("task")
                if not task:
                    await websocket.send_json({"status": "error", "message": "Missing task"})
                    continue
                session.init_session(task, screen_info, screenshot)
            elif req_type == "step":
                if not session.context:
                    await websocket.send_json({"status": "error", "message": "Session not initialized"})
                    continue
                session.step_session(screen_info, screenshot)
            else:
                await websocket.send_json({"status": "error", "message": f"Unknown message type: {req_type}"})
                continue

            await websocket.send_json(_run_model_step(session))
    except WebSocketDisconnect:
        manager.disconnect(client_id)
    except Exception as exc:
        await websocket.send_json({"status": "error", "message": str(exc)})
        manager.disconnect(client_id)


def _run_model_step(session: AgentSession) -> Dict[str, Any]:
    """Run one model step for either websocket or HTTP tool usage."""
    response = model_client.request(session.context)
    try:
        action_data = parse_action(response.action)
    except ValueError:
        action_data = {"_metadata": "finish", "message": response.action}

    session.add_assistant_response(response.thinking, response.action)
    return {
        "status": "success",
        "step": session.step_count,
        "thinking": response.thinking,
        "action": action_data,
        "raw_response": response.action,
        "finished": action_data.get("_metadata") == "finish",
    }


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8080)
