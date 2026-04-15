"""BlindAssist planner service built on FastAPI."""

from __future__ import annotations

import json
import time
import uuid
from typing import Any, Dict, List, Optional, Tuple

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware

from config import PlannerConfig
from planner_models import PlannerRequest, PlannerResponse
from planner_service import BlindAssistPlanner
from planner_tools import PlannerToolRegistry


config = PlannerConfig.from_env()
tool_registry = PlannerToolRegistry(
    navigation_base_url=config.navigation_base_url,
    obstacle_base_url=config.obstacle_base_url,
    phone_control_base_url=config.phone_control_base_url,
    timeout_seconds=config.timeout_seconds,
)
planner = BlindAssistPlanner(
    model_name=config.model_name,
    base_url=config.base_url,
    api_key=config.api_key,
    temperature=config.temperature,
    max_tokens=config.max_tokens,
    timeout_seconds=config.timeout_seconds,
    tool_registry=tool_registry,
)

app = FastAPI(
    title="BlindAssist Planner Service",
    description="Intent classification, planning, and tool-calling for BlindAssist",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.get("/health")
async def health_check() -> Dict[str, Any]:
    """Health endpoint."""
    return {
        "status": "healthy",
        "service": "blindassist-planner",
        "model": config.model_name,
        "base_url": config.base_url,
    }


@app.get("/tools")
async def list_tools() -> Dict[str, Any]:
    """List planner-visible tool definitions."""
    return {"tools": tool_registry.catalog()}


@app.post("/plan", response_model=PlannerResponse)
async def plan_task(request: PlannerRequest) -> PlannerResponse:
    """Return intent, plan, and tool calls for the given request."""
    text = request.text.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Text cannot be empty")
    return await planner.plan(request)


@app.post("/classify", response_model=PlannerResponse)
async def classify_and_plan(request: PlannerRequest) -> PlannerResponse:
    """Backward-compatible alias for the old intent-classification endpoint."""
    return await plan_task(request)


@app.post("/v1/chat/completions")
async def chat_completions(request: Dict[str, Any]) -> Dict[str, Any]:
    """OpenAI-compatible endpoint that exposes planner tool calls."""
    user_text, screenshot = _extract_last_user_content(request.get("messages", []))
    if not user_text:
        raise HTTPException(status_code=400, detail="User message not found")

    planner_request = PlannerRequest(
        text=user_text,
        screenshot=screenshot,
        execute_tools=False,
    )
    plan_result = await planner.plan(planner_request)

    wants_tool_calls = bool(request.get("tools")) or request.get("tool_choice") not in (None, "none")
    assistant_message: Dict[str, Any] = {
        "role": "assistant",
        "content": plan_result.response_text or json.dumps(plan_result.model_dump(by_alias=True), ensure_ascii=False),
    }

    if wants_tool_calls and plan_result.tool_calls:
        assistant_message["content"] = plan_result.response_text or ""
        assistant_message["tool_calls"] = [
            {
                "id": f"call_{uuid.uuid4().hex[:12]}",
                "type": "function",
                "function": {
                    "name": tool_call.name,
                    "arguments": json.dumps(tool_call.arguments, ensure_ascii=False),
                },
            }
            for tool_call in plan_result.tool_calls
        ]

    created = int(time.time())
    return {
        "id": f"chatcmpl-{uuid.uuid4().hex}",
        "object": "chat.completion",
        "created": created,
        "model": config.model_name,
        "choices": [
            {
                "index": 0,
                "message": assistant_message,
                "finish_reason": "tool_calls" if "tool_calls" in assistant_message else "stop",
            }
        ],
        "usage": {
            "prompt_tokens": len(user_text),
            "completion_tokens": len(json.dumps(assistant_message, ensure_ascii=False)),
            "total_tokens": len(user_text) + len(json.dumps(assistant_message, ensure_ascii=False)),
        },
    }


def _extract_last_user_content(messages: List[Dict[str, Any]]) -> Tuple[str, Optional[str]]:
    """Extract the last user text message and optional inline image."""
    for message in reversed(messages):
        if message.get("role") != "user":
            continue

        content = message.get("content")
        if isinstance(content, str):
            return content, None

        if isinstance(content, list):
            text_parts: List[str] = []
            screenshot: Optional[str] = None
            for item in content:
                if item.get("type") == "text":
                    text_parts.append(item.get("text", ""))
                elif item.get("type") == "image_url":
                    url = ((item.get("image_url") or {}).get("url")) or ""
                    if "base64," in url:
                        screenshot = url.split("base64,", 1)[1]
            return "\n".join(part for part in text_parts if part).strip(), screenshot

    return "", None


if __name__ == "__main__":
    uvicorn.run(app, host=config.host, port=config.port)
