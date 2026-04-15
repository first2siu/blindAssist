"""Tool registry for the BlindAssist planner."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Dict, List, Optional

from planner_models import PlannerToolCall, PlannerToolResult


ToolExecutor = Callable[[Dict[str, Any]], Awaitable[Dict[str, Any]]]


@dataclass(frozen=True)
class PlannerToolDefinition:
    """A planner-visible tool backed by another FastAPI service."""

    name: str
    description: str
    service: str
    transport: str
    endpoint: str
    execution_mode: str
    tool_kind: str
    parameters: Dict[str, Any]
    executor: Optional[ToolExecutor] = None

    def to_catalog_item(self) -> Dict[str, Any]:
        """Return a prompt-friendly tool description."""
        return {
            "name": self.name,
            "description": self.description,
            "service": self.service,
            "transport": self.transport,
            "endpoint": self.endpoint,
            "execution_mode": self.execution_mode,
            "tool_kind": self.tool_kind,
            "parameters": self.parameters,
        }

    def build_call(
        self,
        arguments: Optional[Dict[str, Any]] = None,
        confidence: float = 0.85,
    ) -> PlannerToolCall:
        """Create a structured tool call instance."""
        return PlannerToolCall(
            name=self.name,
            service=self.service,
            description=self.description,
            transport=self.transport,
            endpoint=self.endpoint,
            execution_mode=self.execution_mode,
            tool_kind=self.tool_kind,
            arguments=arguments or {},
            confidence=confidence,
        )


class PlannerToolRegistry:
    """Registry and optional executor for planner tools."""

    def __init__(
        self,
        navigation_base_url: str,
        obstacle_base_url: str,
        phone_control_base_url: str,
        timeout_seconds: float = 30.0,
    ) -> None:
        self._timeout = timeout_seconds
        self._tools = self._build_tools(
            navigation_base_url=navigation_base_url.rstrip("/"),
            obstacle_base_url=obstacle_base_url.rstrip("/"),
            phone_control_base_url=phone_control_base_url.rstrip("/"),
        )

    def get(self, name: str) -> Optional[PlannerToolDefinition]:
        """Look up a tool definition by name."""
        return self._tools.get(name)

    def list_tools(self, available_tools: Optional[List[str]] = None) -> List[PlannerToolDefinition]:
        """Return tools visible to the current request."""
        if not available_tools:
            return list(self._tools.values())

        visible = []
        for name in available_tools:
            tool = self._tools.get(name)
            if tool is not None:
                visible.append(tool)
        return visible

    def catalog(self, available_tools: Optional[List[str]] = None) -> List[Dict[str, Any]]:
        """Return planner-visible tool descriptions."""
        return [tool.to_catalog_item() for tool in self.list_tools(available_tools)]

    async def execute_tool_calls(
        self,
        tool_calls: List[PlannerToolCall],
    ) -> List[PlannerToolResult]:
        """Execute planner-direct tool calls when an executor is available."""
        results: List[PlannerToolResult] = []

        for tool_call in tool_calls:
            tool = self.get(tool_call.name)
            if tool is None:
                results.append(
                    PlannerToolResult(
                        name=tool_call.name,
                        status="error",
                        error="tool_not_found",
                    )
                )
                continue

            if tool.executor is None:
                results.append(
                    PlannerToolResult(
                        name=tool_call.name,
                        status="delegated",
                        output={
                            "execution_mode": tool.execution_mode,
                            "endpoint": tool.endpoint,
                        },
                    )
                )
                continue

            try:
                output = await tool.executor(tool_call.arguments)
                results.append(
                    PlannerToolResult(
                        name=tool_call.name,
                        status="success",
                        output=output,
                    )
                )
            except Exception as exc:  # pragma: no cover - network/runtime fallback
                results.append(
                    PlannerToolResult(
                        name=tool_call.name,
                        status="error",
                        error=str(exc),
                    )
                )

        return results

    async def _post_json(self, url: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        """POST JSON to a tool endpoint and return the decoded response."""
        import httpx

        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(url, json=payload)
            response.raise_for_status()
            return response.json()

    def _build_tools(
        self,
        navigation_base_url: str,
        obstacle_base_url: str,
        phone_control_base_url: str,
    ) -> Dict[str, PlannerToolDefinition]:
        obstacle_ws_url = (
            obstacle_base_url
            .replace("http://", "ws://")
            .replace("https://", "wss://")
            + "/ws"
        )
        navigation_start_url = f"{navigation_base_url}/tools/navigation/start"
        navigation_update_url = f"{navigation_base_url}/tools/navigation/update"
        navigation_cancel_url = f"{navigation_base_url}/tools/navigation/cancel"
        obstacle_detect_url = f"{obstacle_base_url}/tools/obstacle/detect"
        obstacle_landmark_url = f"{obstacle_base_url}/tools/obstacle/find_landmark"
        phone_start_url = f"{phone_control_base_url}/tools/phone/start"
        phone_step_url = f"{phone_control_base_url}/tools/phone/step"
        phone_reset_url = f"{phone_control_base_url}/tools/phone/reset"

        return {
            "control.stop": PlannerToolDefinition(
                name="control.stop",
                description="Stop the current long-running task such as navigation or phone control.",
                service="planner-control",
                transport="internal",
                endpoint="server://control/stop",
                execution_mode="server_delegate",
                tool_kind="CONTROL",
                parameters={"type": "object", "properties": {}},
            ),
            "control.pause": PlannerToolDefinition(
                name="control.pause",
                description="Pause the current navigation task without discarding session state.",
                service="planner-control",
                transport="internal",
                endpoint="server://control/pause",
                execution_mode="server_delegate",
                tool_kind="CONTROL",
                parameters={"type": "object", "properties": {}},
            ),
            "control.resume": PlannerToolDefinition(
                name="control.resume",
                description="Resume a previously paused navigation task.",
                service="planner-control",
                transport="internal",
                endpoint="server://control/resume",
                execution_mode="server_delegate",
                tool_kind="CONTROL",
                parameters={"type": "object", "properties": {}},
            ),
            "navigation.start": PlannerToolDefinition(
                name="navigation.start",
                description="Start a navigation session and get the first guidance instruction.",
                service="navigation-agent",
                transport="http",
                endpoint=navigation_start_url,
                execution_mode="planner_direct",
                tool_kind="SESSION",
                parameters={
                    "type": "object",
                    "required": ["session_id", "user_task", "origin"],
                    "properties": {
                        "session_id": {"type": "string"},
                        "user_task": {"type": "string"},
                        "origin": {"type": "object"},
                        "sensor_data": {"type": "object"},
                        "amap_api_key": {"type": "string"},
                    },
                },
                executor=lambda payload: self._post_json(navigation_start_url, payload),
            ),
            "navigation.update": PlannerToolDefinition(
                name="navigation.update",
                description="Update an active navigation session with the latest location and heading.",
                service="navigation-agent",
                transport="http",
                endpoint=navigation_update_url,
                execution_mode="planner_direct",
                tool_kind="SESSION",
                parameters={
                    "type": "object",
                    "required": ["session_id", "origin"],
                    "properties": {
                        "session_id": {"type": "string"},
                        "origin": {"type": "object"},
                        "sensor_data": {"type": "object"},
                    },
                },
                executor=lambda payload: self._post_json(navigation_update_url, payload),
            ),
            "navigation.cancel": PlannerToolDefinition(
                name="navigation.cancel",
                description="Cancel an active navigation session and clean up server-side state.",
                service="navigation-agent",
                transport="http",
                endpoint=navigation_cancel_url,
                execution_mode="planner_direct",
                tool_kind="CONTROL",
                parameters={
                    "type": "object",
                    "required": ["session_id"],
                    "properties": {"session_id": {"type": "string"}},
                },
                executor=lambda payload: self._post_json(navigation_cancel_url, payload),
            ),
            "obstacle.detect_single_frame": PlannerToolDefinition(
                name="obstacle.detect_single_frame",
                description="Analyze the current frame for obstacles and return a spoken guidance hint.",
                service="obstacle-detection",
                transport="http",
                endpoint=obstacle_detect_url,
                execution_mode="planner_direct",
                tool_kind="ONE_SHOT",
                parameters={
                    "type": "object",
                    "required": ["image"],
                    "properties": {
                        "image": {"type": "string"},
                        "sensor_data": {"type": "object"},
                    },
                },
                executor=lambda payload: self._post_json(obstacle_detect_url, payload),
            ),
            "obstacle.find_landmark": PlannerToolDefinition(
                name="obstacle.find_landmark",
                description="Find a nearby landmark or entrance in the current frame.",
                service="obstacle-detection",
                transport="http",
                endpoint=obstacle_landmark_url,
                execution_mode="planner_direct",
                tool_kind="ONE_SHOT",
                parameters={
                    "type": "object",
                    "required": ["image", "sensor_data"],
                    "properties": {
                        "image": {"type": "string"},
                        "sensor_data": {"type": "object"},
                    },
                },
                executor=lambda payload: self._post_json(obstacle_landmark_url, payload),
            ),
            "obstacle.start_stream": PlannerToolDefinition(
                name="obstacle.start_stream",
                description="Start the real-time obstacle streaming websocket for continuous perception.",
                service="obstacle-detection",
                transport="websocket",
                endpoint=obstacle_ws_url,
                execution_mode="client_stream",
                tool_kind="SESSION",
                parameters={
                    "type": "object",
                    "properties": {
                        "user_id": {"type": "string"},
                        "mode": {"type": "string"},
                    },
                },
            ),
            "phone_control.start": PlannerToolDefinition(
                name="phone_control.start",
                description="Start a phone-control session and return the first model action.",
                service="autoglm",
                transport="http",
                endpoint=phone_start_url,
                execution_mode="planner_direct",
                tool_kind="SESSION",
                parameters={
                    "type": "object",
                    "required": ["session_id", "task", "screenshot"],
                    "properties": {
                        "session_id": {"type": "string"},
                        "task": {"type": "string"},
                        "screenshot": {"type": "string"},
                        "screen_info": {"type": "string"},
                    },
                },
                executor=lambda payload: self._post_json(phone_start_url, payload),
            ),
            "phone_control.step": PlannerToolDefinition(
                name="phone_control.step",
                description="Continue a phone-control session with a new screenshot and screen info.",
                service="autoglm",
                transport="http",
                endpoint=phone_step_url,
                execution_mode="planner_direct",
                tool_kind="SESSION",
                parameters={
                    "type": "object",
                    "required": ["session_id", "screenshot"],
                    "properties": {
                        "session_id": {"type": "string"},
                        "screenshot": {"type": "string"},
                        "screen_info": {"type": "string"},
                    },
                },
                executor=lambda payload: self._post_json(phone_step_url, payload),
            ),
            "phone_control.reset": PlannerToolDefinition(
                name="phone_control.reset",
                description="Clear the server-side phone-control session state.",
                service="autoglm",
                transport="http",
                endpoint=phone_reset_url,
                execution_mode="planner_direct",
                tool_kind="CONTROL",
                parameters={
                    "type": "object",
                    "required": ["session_id"],
                    "properties": {"session_id": {"type": "string"}},
                },
                executor=lambda payload: self._post_json(phone_reset_url, payload),
            ),
        }
