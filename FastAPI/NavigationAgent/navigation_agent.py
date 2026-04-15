"""Core navigation agent logic for BlindAssist."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from amap_client import AmapClient
from instruction_builder import InstructionBuilder


DEFAULT_ORIGIN = {"lon": 116.397428, "lat": 39.90923}


@dataclass
class SensorData:
    """Lightweight sensor state required for navigation guidance."""

    heading: float = 0.0
    accuracy: float = 0.0
    pitch: float = 0.0
    roll: float = 0.0


@dataclass
class NavigationState:
    """In-memory navigation session state."""

    user_task: str = ""
    destination: str = ""
    current_location: Dict[str, float] = field(default_factory=dict)
    sensor_data: SensorData = field(default_factory=SensorData)
    planned_route: Optional[Any] = None
    current_step_index: int = 0
    total_distance: float = 0.0
    is_active: bool = False
    is_paused: bool = False


class NavigationAgent:
    """Route-aware navigation agent backed by Amap and rule-based instructions."""

    def __init__(
        self,
        model_base_url: str = "http://localhost:8001/v1",
        model_name: str = "Qwen/Qwen2-1.5B-Instruct",
        amap_api_key: Optional[str] = None,
    ) -> None:
        self.model_base_url = model_base_url
        self.model_name = model_name
        self.amap_client = AmapClient(api_key=amap_api_key)
        self.instruction_builder = InstructionBuilder()
        self.state = NavigationState()

    async def initialize_navigation(
        self,
        user_task: str,
        origin: Dict[str, float],
        sensor_data: SensorData,
    ) -> Dict[str, Any]:
        """Start a navigation session and return the first instruction."""
        normalized_origin = self._normalize_location(origin)
        destination_keyword = self._extract_destination_from_task(user_task)

        self.state = NavigationState(
            user_task=user_task,
            destination=destination_keyword,
            current_location=normalized_origin,
            sensor_data=sensor_data,
            is_active=True,
            is_paused=False,
        )

        search_results = await self._search_destination(destination_keyword, normalized_origin)
        if not search_results:
            return {
                "status": "error",
                "message": f"无法找到目的地: {destination_keyword}",
                "instruction": f"没有找到 {destination_keyword}，请换一个说法再试一次。",
            }

        nearest = search_results[0]
        place_name = nearest.get("name") or destination_keyword
        destination_location = self._normalize_location(nearest.get("location") or {})

        route = self.amap_client.plan_walking_route(
            origin=normalized_origin,
            destination=destination_location,
        )
        if not route or not route.steps:
            return {
                "status": "error",
                "message": "route_planning_failed",
                "instruction": f"暂时无法为 {place_name} 规划步行路线。",
            }

        self.state.destination = place_name
        self.state.planned_route = route
        self.state.total_distance = route.total_distance
        self.state.current_step_index = 0

        instruction = self._build_instruction_for_step(route.steps[0], sensor_data, is_near_destination=False)
        return {
            "status": "success",
            "type": "route_planned",
            "instruction": instruction,
            "destination": place_name,
            "total_distance": route.total_distance,
            "destination_coords": destination_location,
        }

    async def update_location(
        self,
        location: Dict[str, float],
        sensor_data: SensorData,
    ) -> Dict[str, Any]:
        """Update location for an active session and return the next guidance hint."""
        if not self.state.is_active or not self.state.planned_route:
            return {
                "status": "error",
                "message": "Navigation is not active.",
            }

        if self.state.is_paused:
            return {
                "status": "paused",
                "type": "navigation_paused",
                "instruction": "导航已暂停，请说继续导航来恢复。",
            }

        normalized_location = self._normalize_location(location)
        self.state.current_location = normalized_location
        self.state.sensor_data = sensor_data

        is_off_route, offset_distance = self._check_off_route(normalized_location)
        if is_off_route:
            return {
                "status": "warning",
                "type": "off_route",
                "instruction": self.instruction_builder.build_off_route_instruction(offset_distance),
                "offset_distance": offset_distance,
            }

        route = self.state.planned_route
        step_index = self._resolve_step_index(route.steps, normalized_location)
        self.state.current_step_index = step_index

        if step_index >= len(route.steps):
            self.state.is_active = False
            return {
                "status": "success",
                "type": "arrived",
                "instruction": self.instruction_builder.build_arrival_instruction(self.state.destination),
            }

        current_step = route.steps[step_index]
        return {
            "status": "success",
            "type": "navigation_update",
            "instruction": self._build_instruction_for_step(current_step, sensor_data, is_near_destination=False),
            "distance_to_next": current_step.distance,
            "step_index": step_index,
        }

    def pause_navigation(self) -> Dict[str, Any]:
        """Pause the current navigation session."""
        if not self.state.is_active or not self.state.planned_route:
            return {
                "status": "error",
                "message": "没有可暂停的导航任务",
            }

        self.state.is_paused = True
        return {
            "status": "success",
            "type": "paused",
            "instruction": "已暂停导航。需要继续时请说继续导航。",
        }

    async def resume_navigation(
        self,
        location: Optional[Dict[str, float]] = None,
        sensor_data: Optional[SensorData] = None,
    ) -> Dict[str, Any]:
        """Resume a paused navigation session."""
        if not self.state.planned_route:
            return {
                "status": "error",
                "message": "没有可恢复的导航任务",
            }

        if location is not None:
            self.state.current_location = self._normalize_location(location)
        if sensor_data is not None:
            self.state.sensor_data = sensor_data

        self.state.is_active = True
        self.state.is_paused = False

        if self.state.current_step_index >= len(self.state.planned_route.steps):
            return {
                "status": "success",
                "type": "arrived",
                "instruction": self.instruction_builder.build_arrival_instruction(self.state.destination),
            }

        current_step = self.state.planned_route.steps[self.state.current_step_index]
        return {
            "status": "success",
            "type": "resumed",
            "instruction": self._build_instruction_for_step(current_step, self.state.sensor_data, False),
        }

    def cancel_navigation(self) -> Dict[str, Any]:
        """Cancel the current navigation session."""
        self.state = NavigationState()
        return {
            "status": "success",
            "message": "导航已取消",
        }

    async def _search_destination(
        self,
        keyword: str,
        location: Dict[str, float],
    ) -> List[Dict[str, Any]]:
        try:
            return self.amap_client.search_nearby(keywords=keyword, location=location, radius=2000) or []
        except Exception:
            return []

    def _extract_destination_from_task(self, user_task: str) -> str:
        task = user_task.strip()
        if not task:
            return "目的地"

        prefixes = [
            "带我去",
            "请带我去",
            "我要去",
            "去",
            "导航到",
            "到",
            "附近的",
            "最近的",
            "帮我找",
            "找",
        ]
        for prefix in prefixes:
            if task.startswith(prefix):
                task = task[len(prefix):]
                break

        suffixes = ["怎么走", "路线", "如何到达", "怎么去"]
        for suffix in suffixes:
            if suffix in task:
                task = task.split(suffix, 1)[0]

        return task.strip() or user_task.strip()

    def _normalize_location(self, location: Optional[Dict[str, Any]]) -> Dict[str, float]:
        if not location:
            return dict(DEFAULT_ORIGIN)

        lon = location.get("lon", location.get("longitude", DEFAULT_ORIGIN["lon"]))
        lat = location.get("lat", location.get("latitude", DEFAULT_ORIGIN["lat"]))
        return {"lon": float(lon), "lat": float(lat)}

    def _build_instruction_for_step(
        self,
        step: Any,
        sensor_data: SensorData,
        is_near_destination: bool,
    ) -> str:
        return self.instruction_builder.build_navigation_instruction(
            raw_instruction=step.instruction,
            distance=step.distance,
            user_heading=sensor_data.heading,
            action=step.action,
            is_near_destination=is_near_destination,
        )

    def _resolve_step_index(self, steps: List[Any], location: Dict[str, float]) -> int:
        if not steps:
            return 0

        current_step = steps[min(self.state.current_step_index, len(steps) - 1)]
        distance_to_step = self._distance_to_point(location, current_step)
        if distance_to_step < 15 and self.state.current_step_index < len(steps):
            return min(self.state.current_step_index + 1, len(steps))
        return self.state.current_step_index

    def _check_off_route(self, location: Dict[str, float]) -> Tuple[bool, float]:
        if not self.state.current_location:
            return False, 0.0

        moved_distance = abs(location["lon"] - self.state.current_location.get("lon", location["lon"])) + abs(
            location["lat"] - self.state.current_location.get("lat", location["lat"])
        )
        approx_meters = moved_distance * 111000
        return approx_meters > 80, approx_meters

    def _distance_to_point(self, location: Dict[str, float], step: Any) -> float:
        # Amap step data does not expose a structured end-point in the current client.
        # Use the remaining step distance as a stable approximation.
        return float(getattr(step, "distance", 0.0) or 0.0)
