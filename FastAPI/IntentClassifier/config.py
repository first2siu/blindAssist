"""Configuration helpers for the BlindAssist planner service."""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass
class PlannerConfig:
    """Configuration for the planner service."""

    model_name: str = "Qwen/Qwen2-1.5B-Instruct"
    base_url: str = "http://localhost:8001/v1"
    api_key: str = "EMPTY"

    host: str = "0.0.0.0"
    port: int = 8002

    temperature: float = 0.1
    max_tokens: int = 400
    timeout_seconds: float = 20.0

    enable_rule_based: bool = True
    rule_confidence_threshold: float = 0.8

    navigation_base_url: str = "http://localhost:8081"
    obstacle_base_url: str = "http://localhost:8004"
    phone_control_base_url: str = "http://localhost:8080"

    @classmethod
    def from_env(cls) -> "PlannerConfig":
        """Create configuration from environment variables."""
        return cls(
            model_name=os.getenv("MODEL_NAME", "Qwen/Qwen2-1.5B-Instruct"),
            base_url=os.getenv("BASE_URL", "http://localhost:8001/v1"),
            api_key=os.getenv("API_KEY", "EMPTY"),
            host=os.getenv("HOST", "0.0.0.0"),
            port=int(os.getenv("PORT", 8002)),
            temperature=float(os.getenv("TEMPERATURE", 0.1)),
            max_tokens=int(os.getenv("MAX_TOKENS", 400)),
            timeout_seconds=float(os.getenv("TIMEOUT", 20.0)),
            enable_rule_based=os.getenv("ENABLE_RULE_BASED", "true").lower() == "true",
            rule_confidence_threshold=float(os.getenv("RULE_CONFIDENCE_THRESHOLD", 0.8)),
            navigation_base_url=os.getenv("NAVIGATION_BASE_URL", "http://localhost:8081"),
            obstacle_base_url=os.getenv("OBSTACLE_BASE_URL", "http://localhost:8004"),
            phone_control_base_url=os.getenv("PHONE_CONTROL_BASE_URL", "http://localhost:8080"),
        )
