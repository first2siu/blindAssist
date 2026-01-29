"""Configuration for Intent Classifier service."""

import os
from dataclasses import dataclass
from typing import Optional

@dataclass
class IntentClassifierConfig:
    """Configuration for intent classification service."""

    # Model configuration
    model_name: str = "Qwen/Qwen2.5-7B-Instruct"
    base_url: str = "http://localhost:8001/v1"
    api_key: str = "EMPTY"

    # Service configuration
    host: str = "0.0.0.0"
    port: int = 8002

    # LLM parameters
    temperature: float = 0.1
    max_tokens: int = 200
    timeout: int = 10

    # Feature flags
    enable_rule_based: bool = True
    rule_confidence_threshold: float = 0.8

    @classmethod
    def from_env(cls) -> "IntentClassifierConfig":
        """Create configuration from environment variables."""
        return cls(
            model_name=os.getenv("MODEL_NAME", "Qwen/Qwen2.5-7B-Instruct"),
            base_url=os.getenv("BASE_URL", "http://localhost:8001/v1"),
            api_key=os.getenv("API_KEY", "EMPTY"),
            host=os.getenv("HOST", "0.0.0.0"),
            port=int(os.getenv("PORT", 8002)),
            temperature=float(os.getenv("TEMPERATURE", 0.1)),
            max_tokens=int(os.getenv("MAX_TOKENS", 200)),
            timeout=int(os.getenv("TIMEOUT", 10)),
            enable_rule_based=os.getenv("ENABLE_RULE_BASED", "true").lower() == "true",
            rule_confidence_threshold=float(os.getenv("RULE_CONFIDENCE_THRESHOLD", 0.8))
        )
