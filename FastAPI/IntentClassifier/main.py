"""Intent classification service using FastAPI."""

import asyncio
import json
import os
from typing import Optional

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from openai import OpenAI

# Import prompts
import sys
sys.path.append(os.path.join(os.path.dirname(__file__), 'prompts'))
from intent_prompt import (
    INTENT_CLASSIFICATION_SYSTEM_PROMPT,
    INTENT_CLASSIFICATION_USER_PROMPT,
    NAVIGATION_KEYWORDS,
    OBSTACLE_KEYWORDS
)

# Configuration
# 使用 Qwen2-1.5B-Instruct 进行意图分类（轻量级，响应快速）
MODEL_NAME = os.getenv("MODEL_NAME", "Qwen/Qwen2-1.5B-Instruct")
BASE_URL = os.getenv("BASE_URL", "http://localhost:8001/v1")
API_KEY = os.getenv("API_KEY", "EMPTY")

# Initialize OpenAI client
client = OpenAI(
    base_url=BASE_URL,
    api_key=API_KEY
)

app = FastAPI(
    title="Intent Classification Service",
    description="Classifies user intent into navigation, phone control, or obstacle detection"
)

# CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


class IntentRequest(BaseModel):
    """Request model for intent classification."""
    text: str
    use_rule: Optional[bool] = True


class IntentResponse(BaseModel):
    """Response model for intent classification."""
    intent: str
    reason: str
    confidence: float


def classify_by_rule(text: str) -> Optional[IntentResponse]:
    """
    Classify intent using rule-based approach (fast path).

    Returns None if confidence is low and LLM should be used.
    """
    text_lower = text.lower()

    # Check for navigation intent
    for keyword in NAVIGATION_KEYWORDS:
        if keyword in text_lower:
            # Exclude phone control operations
            if any(w in text_lower for w in ["打开", "设置", "应用"]):
                return IntentResponse(
                    intent="PHONE_CONTROL",
                    reason="检测到应用操作关键词",
                    confidence=0.9
                )
            return IntentResponse(
                intent="NAVIGATION",
                reason=f"检测到导航关键词: {keyword}",
                confidence=0.95
            )

    # Check for obstacle intent
    for keyword in OBSTACLE_KEYWORDS:
        if keyword in text_lower:
            return IntentResponse(
                intent="OBSTACLE",
                reason=f"检测到环境感知关键词: {keyword}",
                confidence=0.9
            )

    # Low confidence, use LLM
    return None


def classify_by_llm(text: str) -> IntentResponse:
    """
    Classify intent using LLM (slow path).
    """
    try:
        response = client.chat.completions.create(
            model=MODEL_NAME,
            messages=[
                {
                    "role": "system",
                    "content": INTENT_CLASSIFICATION_SYSTEM_PROMPT
                },
                {
                    "role": "user",
                    "content": INTENT_CLASSIFICATION_USER_PROMPT.format(user_input=text)
                }
            ],
            temperature=0.1,
            max_tokens=200,
            stream=False
        )

        content = response.choices[0].message.content
        result = json.loads(content)

        return IntentResponse(
            intent=result.get("intent", "PHONE_CONTROL"),
            reason=result.get("reason", ""),
            confidence=0.85
        )

    except Exception as e:
        print(f"LLM classification failed: {e}")
        # Fallback to phone control
        return IntentResponse(
            intent="PHONE_CONTROL",
            reason="LLM分类失败，使用默认分类",
            confidence=0.5
        )


@app.get("/health")
async def health_check():
    """Health check endpoint."""
    return {
        "status": "healthy",
        "model": MODEL_NAME,
        "base_url": BASE_URL
    }


@app.post("/classify", response_model=IntentResponse)
async def classify_intent(request: IntentRequest):
    """
    Classify user intent.

    First uses rule-based classification (fast), then falls back to LLM if needed.
    """
    text = request.text.strip()

    if not text:
        raise HTTPException(status_code=400, detail="Text cannot be empty")

    # Try rule-based classification first
    if request.use_rule:
        result = classify_by_rule(text)
        if result is not None and result.confidence > 0.8:
            return result

    # Use LLM for classification
    result = classify_by_llm(text)
    return result


@app.post("/v1/chat/completions")
async def chat_completions(request: dict):
    """
    OpenAI-compatible endpoint for intent classification.
    """
    messages = request.get("messages", [])
    if not messages:
        raise HTTPException(status_code=400, detail="Messages required")

    # Get the last user message
    user_message = ""
    for msg in reversed(messages):
        if msg.get("role") == "user":
            user_message = msg.get("content", "")
            break

    if not user_message:
        raise HTTPException(status_code=400, detail="User message not found")

    # Classify intent
    result = classify_by_llm(user_message)

    # Format response as OpenAI-compatible
    response_content = json.dumps({
        "intent": result.intent,
        "reason": result.reason
    }, ensure_ascii=False)

    return {
        "id": f"chatcmpl-{asyncio.get_event_loop().time}",
        "object": "chat.completion",
        "created": int(asyncio.get_event_loop().time()),
        "model": MODEL_NAME,
        "choices": [{
            "index": 0,
            "message": {
                "role": "assistant",
                "content": response_content
            },
            "finish_reason": "stop"
        }],
        "usage": {
            "prompt_tokens": len(user_message),
            "completion_tokens": len(response_content),
            "total_tokens": len(user_message) + len(response_content)
        }
    }


if __name__ == "__main__":
    port = int(os.getenv("PORT", 8002))
    uvicorn.run(app, host="0.0.0.0", port=port)
