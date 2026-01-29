"""
导航 Agent Prompts 模块

提供不同场景的 prompt 模板，用于生成盲人友好化导航指令。
"""

from .base import get_system_prompt
from .route_planning import get_route_planning_prompt
from .navigation import get_navigation_prompt
from .arrival import get_arrival_prompt
from .exception import get_exception_prompt

__all__ = [
    "get_system_prompt",
    "get_route_planning_prompt",
    "get_navigation_prompt",
    "get_arrival_prompt",
    "get_exception_prompt",
]
