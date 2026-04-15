"""Shared request/response models for the BlindAssist planner service."""

from __future__ import annotations

from typing import Any, Dict, List, Optional

from pydantic import BaseModel, ConfigDict, Field


class LocationContext(BaseModel):
    """User location and heading information passed from the client."""

    latitude: Optional[float] = None
    longitude: Optional[float] = None
    heading: Optional[float] = None
    accuracy: Optional[float] = None
    timestamp: Optional[int] = None


class ObservationContext(BaseModel):
    """Normalized observation from the Java runtime."""

    model_config = ConfigDict(populate_by_name=True)

    type: str
    status: str
    delegation_status: Optional[str] = Field(default=None, alias="delegation_status")
    summary: str
    structured_data: Dict[str, Any] = Field(default_factory=dict, alias="structured_data")
    source_tool: Optional[str] = Field(default=None, alias="source_tool")
    timestamp: Optional[int] = None


class ToolHistoryContext(BaseModel):
    """Historical tool execution summary fed back into the planner."""

    model_config = ConfigDict(populate_by_name=True)

    turn_index: int = Field(alias="turn_index")
    tool_name: str = Field(alias="tool_name")
    result_status: str = Field(alias="result_status")
    observation_summary: Optional[str] = Field(default=None, alias="observation_summary")
    arguments_summary: Optional[str] = Field(default=None, alias="arguments_summary")


class PlannerRequest(BaseModel):
    """Planner request payload."""

    model_config = ConfigDict(populate_by_name=True)

    text: str
    goal: Optional[str] = None
    user_id: Optional[str] = None
    session_id: Optional[str] = None
    screenshot: Optional[str] = None
    screen_info: Optional[str] = Field(default=None, alias="screen_info")
    location: Optional[LocationContext] = None
    planner_turn: int = Field(default=0, alias="planner_turn")
    active_tool: Optional[str] = Field(default=None, alias="active_tool")
    active_tool_kind: Optional[str] = Field(default=None, alias="active_tool_kind")
    latest_observation: Optional[ObservationContext] = Field(default=None, alias="latest_observation")
    tool_history: List[ToolHistoryContext] = Field(default_factory=list, alias="tool_history")
    use_rule: bool = True
    execute_tools: bool = False
    available_tools: Optional[List[str]] = Field(default=None, alias="available_tools")
    allowed_tools: Optional[List[str]] = Field(default=None, alias="allowed_tools")


class PlannerToolCall(BaseModel):
    """Structured tool call produced by the planner."""

    model_config = ConfigDict(populate_by_name=True)

    name: str
    service: str
    description: str
    transport: str
    endpoint: str
    execution_mode: str = Field(alias="execution_mode")
    tool_kind: str = Field(alias="tool_kind")
    arguments: Dict[str, Any] = Field(default_factory=dict)
    confidence: float = 0.0


class PlannerToolResult(BaseModel):
    """Execution result for a tool call."""

    model_config = ConfigDict(populate_by_name=True)

    name: str
    status: str
    output: Optional[Dict[str, Any]] = None
    error: Optional[str] = None


class PlannerResponse(BaseModel):
    """Planner response returned to Java server and OpenAI-compatible clients."""

    model_config = ConfigDict(populate_by_name=True)

    intent: str
    feature: str
    reason: str
    detail: str
    confidence: float
    plan: List[str] = Field(default_factory=list)
    planner_action: str = Field(default="EXECUTE_TOOL", alias="planner_action")
    requires_execution: bool = Field(default=False, alias="requires_execution")
    tool_calls: List[PlannerToolCall] = Field(default_factory=list, alias="tool_calls")
    tool_results: List[PlannerToolResult] = Field(default_factory=list, alias="tool_results")
    response_text: Optional[str] = Field(default=None, alias="response_text")
    final_response_text: Optional[str] = Field(default=None, alias="final_response_text")
