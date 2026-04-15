"""Planner service that combines intent classification and tool selection."""

from __future__ import annotations

import json
from typing import Any, Dict, List, Optional

try:
    from openai import OpenAI
except ModuleNotFoundError:  # pragma: no cover - dependency fallback
    OpenAI = None

from planner_models import PlannerRequest, PlannerResponse, PlannerToolCall
from planner_tools import PlannerToolRegistry
from prompts.intent_prompt import (
    NAVIGATION_KEYWORDS,
    OBSTACLE_KEYWORDS,
    PAUSE_KEYWORDS,
    PHONE_CONTROL_KEYWORDS,
    PLANNER_SYSTEM_PROMPT,
    RESUME_KEYWORDS,
    STOP_KEYWORDS,
    STREAMING_OBSTACLE_KEYWORDS,
    build_planner_user_prompt,
)


class BlindAssistPlanner:
    """Intent-aware planner for the BlindAssist FastAPI stack."""

    def __init__(
        self,
        *,
        model_name: str,
        base_url: str,
        api_key: str,
        temperature: float,
        max_tokens: int,
        timeout_seconds: float,
        tool_registry: PlannerToolRegistry,
    ) -> None:
        self.model_name = model_name
        self.temperature = temperature
        self.max_tokens = max_tokens
        self.tool_registry = tool_registry
        self.client = None
        if OpenAI is not None:
            self.client = OpenAI(base_url=base_url, api_key=api_key, timeout=timeout_seconds)

    async def plan(self, request: PlannerRequest) -> PlannerResponse:
        """Produce a planner response and optionally execute direct tools."""
        if request.use_rule:
            response = self._plan_by_rule(request)
        else:
            response = self._plan_by_llm(request)

        if request.execute_tools and response.tool_calls:
            response.tool_results = await self.tool_registry.execute_tool_calls(response.tool_calls)
            if not response.response_text:
                response.response_text = self._derive_response_text(response)
            if not response.final_response_text:
                response.final_response_text = response.response_text

        return response

    def _plan_by_rule(self, request: PlannerRequest) -> PlannerResponse:
        latest_observation = request.latest_observation
        if latest_observation is not None:
            return self._plan_from_observation(request)

        text = request.text.strip().lower()
        if not text:
            return PlannerResponse(
                intent="UNKNOWN",
                feature="UNKNOWN",
                reason="empty_text",
                detail="empty_text",
                confidence=1.0,
                plan=["Ask the user to repeat the request."],
                planner_action="ASK_USER",
                requires_execution=False,
                response_text="我没有听清楚，请再说一遍。",
                final_response_text="我没有听清楚，请再说一遍。",
            )

        if self._contains_any(text, STOP_KEYWORDS):
            return self._build_response(
                request=request,
                intent="STOP",
                reason="matched stop keyword",
                confidence=0.99,
                plan=["Stop the current task and clean up session state."],
                tool_names=["control.stop"],
            )

        if self._contains_any(text, PAUSE_KEYWORDS):
            return self._build_response(
                request=request,
                intent="PAUSE",
                reason="matched pause keyword",
                confidence=0.97,
                plan=["Pause the current navigation flow without destroying state."],
                tool_names=["control.pause"],
            )

        if self._contains_any(text, RESUME_KEYWORDS):
            return self._build_response(
                request=request,
                intent="RESUME",
                reason="matched resume keyword",
                confidence=0.97,
                plan=["Resume the paused navigation flow."],
                tool_names=["control.resume"],
            )

        if self._contains_any(text, NAVIGATION_KEYWORDS) and not self._contains_any(text, PHONE_CONTROL_KEYWORDS):
            return self._build_response(
                request=request,
                intent="NAVIGATION",
                reason="matched navigation keyword",
                confidence=0.95,
                plan=[
                    "Resolve the destination from the user task.",
                    "Start navigation and wait for downstream route updates.",
                ],
                tool_names=["navigation.start"],
            )

        if self._contains_any(text, OBSTACLE_KEYWORDS):
            tool_name = "obstacle.start_stream"
            plan = ["Start continuous obstacle perception for the client stream."]
            if request.screenshot and not self._contains_any(text, STREAMING_OBSTACLE_KEYWORDS):
                tool_name = "obstacle.detect_single_frame"
                plan = ["Analyze the current screenshot and then summarize the observation."]

            return self._build_response(
                request=request,
                intent="OBSTACLE",
                reason="matched obstacle keyword",
                confidence=0.92,
                plan=plan,
                tool_names=[tool_name],
            )

        if self._contains_any(text, PHONE_CONTROL_KEYWORDS):
            return self._build_response(
                request=request,
                intent="PHONE_CONTROL",
                reason="matched phone-control keyword",
                confidence=0.9,
                plan=[
                    "Use the phone-control agent to inspect the current screen.",
                    "Wait for the next UI observation.",
                ],
                tool_names=["phone_control.start"],
            )

        return self._build_response(
            request=request,
            intent="PHONE_CONTROL",
            reason="default router fallback",
            confidence=0.65,
            plan=[
                "Treat the request as a general phone-assistant task.",
                "Let the phone-control agent decide the first UI action.",
            ],
            tool_names=["phone_control.start"],
        )

    def _plan_from_observation(self, request: PlannerRequest) -> PlannerResponse:
        observation = request.latest_observation
        summary = observation.summary if observation is not None else "No observation available."
        delegation_status = (observation.delegation_status or "").upper() if observation is not None else ""

        if request.active_tool_kind == "ONE_SHOT" or delegation_status == "NEED_GLOBAL_REPLAN":
            return PlannerResponse(
                intent=request.active_tool or "UNKNOWN",
                feature=request.intent if hasattr(request, "intent") else "UNKNOWN",
                reason="one_shot_observation_complete",
                detail="one_shot_observation_complete",
                confidence=0.9,
                plan=["Summarize the one-shot tool result for the user and finish."],
                planner_action="FINISH",
                requires_execution=False,
                response_text=summary,
                final_response_text=summary,
            )

        if delegation_status == "FINISHED":
            return PlannerResponse(
                intent=request.active_tool or "UNKNOWN",
                feature=request.active_tool or "UNKNOWN",
                reason="delegated_session_finished",
                detail="delegated_session_finished",
                confidence=0.9,
                plan=["Summarize the finished delegated session and finish."],
                planner_action="FINISH",
                requires_execution=False,
                response_text=summary,
                final_response_text=summary,
            )

        if delegation_status == "ASK_USER":
            return PlannerResponse(
                intent="UNKNOWN",
                feature="UNKNOWN",
                reason="delegated_session_needs_user_input",
                detail="delegated_session_needs_user_input",
                confidence=0.8,
                plan=["Ask the user for clarification or confirmation before continuing."],
                planner_action="ASK_USER",
                requires_execution=False,
                response_text=summary,
                final_response_text=summary,
            )

        if observation is not None and observation.status == "error":
            return PlannerResponse(
                intent="UNKNOWN",
                feature="UNKNOWN",
                reason="observation_error",
                detail="observation_error",
                confidence=0.8,
                plan=["Ask the user to retry or provide a new observation."],
                planner_action="ASK_USER",
                requires_execution=False,
                response_text=summary,
                final_response_text=summary,
            )

        return PlannerResponse(
            intent="UNKNOWN",
            feature="UNKNOWN",
            reason="awaiting_more_observation",
            detail="awaiting_more_observation",
            confidence=0.6,
            plan=["Wait for more observation from the active session tool."],
            planner_action="WAIT_FOR_OBSERVATION",
            requires_execution=False,
            response_text=summary,
        )

    def _plan_by_llm(self, request: PlannerRequest) -> PlannerResponse:
        if self.client is None:
            return self._plan_by_rule(request)

        tool_catalog = self.tool_registry.catalog(self._visible_tools(request))
        request_context = self._build_request_context(request)

        response = self.client.chat.completions.create(
            model=self.model_name,
            messages=[
                {"role": "system", "content": PLANNER_SYSTEM_PROMPT},
                {
                    "role": "user",
                    "content": build_planner_user_prompt(
                        user_input=request.text,
                        request_context=request_context,
                        tool_catalog=tool_catalog,
                    ),
                },
            ],
            temperature=self.temperature,
            max_tokens=self.max_tokens,
            stream=False,
        )

        content = response.choices[0].message.content or "{}"
        payload = self._safe_json_loads(content)

        intent = self._normalize_intent(payload.get("intent"))
        plan = payload.get("plan") or []
        if not isinstance(plan, list):
            plan = [str(plan)]

        llm_tool_calls = payload.get("tool_calls") or []
        tool_calls = self._build_tool_calls_from_payload(
            request=request,
            raw_tool_calls=llm_tool_calls,
            fallback_intent=intent,
        )

        planner_action = str(payload.get("planner_action") or "").upper()
        if planner_action not in {"EXECUTE_TOOL", "WAIT_FOR_OBSERVATION", "ASK_USER", "FINISH"}:
            planner_action = self._default_planner_action(tool_calls)

        reason = str(payload.get("reason") or "llm_planner_result")
        confidence = float(payload.get("confidence") or 0.75)
        response_text = payload.get("response_text")
        final_response_text = payload.get("final_response_text")

        return PlannerResponse(
            intent=intent,
            feature=intent,
            reason=reason,
            detail=reason,
            confidence=confidence,
            plan=plan,
            planner_action=planner_action,
            requires_execution=bool(tool_calls) and planner_action == "EXECUTE_TOOL",
            tool_calls=tool_calls,
            response_text=response_text,
            final_response_text=final_response_text,
        )

    def _build_response(
        self,
        *,
        request: PlannerRequest,
        intent: str,
        reason: str,
        confidence: float,
        plan: List[str],
        tool_names: Optional[List[str]] = None,
    ) -> PlannerResponse:
        tool_calls = self._build_tool_calls_for_names(request, tool_names or [])
        planner_action = self._default_planner_action(tool_calls)
        requires_execution = planner_action == "EXECUTE_TOOL" and bool(tool_calls)
        return PlannerResponse(
            intent=intent,
            feature=intent,
            reason=reason,
            detail=reason,
            confidence=confidence,
            plan=plan,
            planner_action=planner_action,
            requires_execution=requires_execution,
            tool_calls=tool_calls,
        )

    def _build_tool_calls_for_names(
        self,
        request: PlannerRequest,
        tool_names: List[str],
    ) -> List[PlannerToolCall]:
        tool_calls: List[PlannerToolCall] = []
        visible_tools = set(self._visible_tools(request) or [])

        for name in tool_names:
            if visible_tools and name not in visible_tools:
                continue
            tool = self.tool_registry.get(name)
            if tool is None:
                continue
            arguments = self._default_arguments_for_tool(name, request)
            tool_calls.append(tool.build_call(arguments=arguments))
        return tool_calls

    def _build_tool_calls_from_payload(
        self,
        *,
        request: PlannerRequest,
        raw_tool_calls: List[Dict[str, Any]],
        fallback_intent: str,
    ) -> List[PlannerToolCall]:
        tool_calls: List[PlannerToolCall] = []
        visible_tools = set(self._visible_tools(request) or [])

        for raw_tool_call in raw_tool_calls:
            name = raw_tool_call.get("name")
            if not name:
                continue
            if visible_tools and str(name) not in visible_tools:
                continue

            tool = self.tool_registry.get(str(name))
            if tool is None:
                continue

            raw_arguments = raw_tool_call.get("arguments")
            if not isinstance(raw_arguments, dict):
                raw_arguments = {}

            merged_arguments = self._default_arguments_for_tool(tool.name, request)
            merged_arguments.update(raw_arguments)
            confidence = float(raw_tool_call.get("confidence") or 0.8)
            tool_calls.append(tool.build_call(arguments=merged_arguments, confidence=confidence))

        if tool_calls:
            return tool_calls
        return self._default_tool_calls_for_intent(request, fallback_intent)

    def _default_tool_calls_for_intent(
        self,
        request: PlannerRequest,
        intent: str,
    ) -> List[PlannerToolCall]:
        mapping = {
            "STOP": ["control.stop"],
            "PAUSE": ["control.pause"],
            "RESUME": ["control.resume"],
            "NAVIGATION": ["navigation.start"],
            "OBSTACLE": [
                "obstacle.detect_single_frame" if request.screenshot else "obstacle.start_stream"
            ],
            "PHONE_CONTROL": ["phone_control.start"],
        }
        return self._build_tool_calls_for_names(request, mapping.get(intent, []))

    def _default_arguments_for_tool(
        self,
        tool_name: str,
        request: PlannerRequest,
    ) -> Dict[str, Any]:
        session_id = request.session_id or request.user_id or "planner-session"
        goal = request.goal or request.text
        arguments: Dict[str, Any] = {}

        location_payload = self._location_payload(request)
        sensor_payload = self._sensor_payload(request)

        if tool_name.startswith("navigation."):
            if tool_name == "navigation.cancel":
                return {"session_id": session_id}
            arguments = {
                "session_id": session_id,
                "user_task": goal,
                "origin": location_payload,
                "sensor_data": sensor_payload,
            }
        elif tool_name == "obstacle.detect_single_frame":
            arguments = {
                "image": request.screenshot or "",
                "sensor_data": {
                    **sensor_payload,
                    "query": request.text,
                },
            }
        elif tool_name == "obstacle.find_landmark":
            arguments = {
                "image": request.screenshot or "",
                "sensor_data": {
                    **sensor_payload,
                    "target": request.text,
                },
            }
        elif tool_name == "obstacle.start_stream":
            arguments = {
                "user_id": request.user_id or session_id,
                "mode": "continuous_perception",
            }
        elif tool_name.startswith("phone_control."):
            if tool_name == "phone_control.reset":
                return {"session_id": session_id}
            arguments = {
                "session_id": session_id,
                "task": goal,
                "screenshot": request.screenshot or "",
                "screen_info": request.screen_info or "",
            }
        return arguments

    def _location_payload(self, request: PlannerRequest) -> Dict[str, float]:
        if not request.location:
            return {"lon": 116.397428, "lat": 39.90923}
        return {
            "lon": request.location.longitude if request.location.longitude is not None else 116.397428,
            "lat": request.location.latitude if request.location.latitude is not None else 39.90923,
        }

    def _sensor_payload(self, request: PlannerRequest) -> Dict[str, float]:
        if not request.location:
            return {"heading": 0.0, "accuracy": 10.0}
        return {
            "heading": request.location.heading if request.location.heading is not None else 0.0,
            "accuracy": request.location.accuracy if request.location.accuracy is not None else 10.0,
        }

    def _build_request_context(self, request: PlannerRequest) -> Dict[str, Any]:
        return {
            "goal": request.goal or request.text,
            "user_id": request.user_id,
            "session_id": request.session_id,
            "planner_turn": request.planner_turn,
            "active_tool": request.active_tool,
            "active_tool_kind": request.active_tool_kind,
            "has_screenshot": bool(request.screenshot),
            "screen_info": request.screen_info or "",
            "location": request.location.model_dump() if request.location else None,
            "latest_observation": request.latest_observation.model_dump(by_alias=True) if request.latest_observation else None,
            "tool_history": [item.model_dump(by_alias=True) for item in request.tool_history],
            "execute_tools": request.execute_tools,
        }

    def _derive_response_text(self, response: PlannerResponse) -> Optional[str]:
        for result in response.tool_results:
            if result.output:
                if "instruction" in result.output:
                    return str(result.output["instruction"])
                if "message" in result.output:
                    return str(result.output["message"])
                if "overall_instruction" in result.output:
                    return str(result.output["overall_instruction"])
        return None

    def _visible_tools(self, request: PlannerRequest) -> Optional[List[str]]:
        if request.allowed_tools:
            return request.allowed_tools
        return request.available_tools

    @staticmethod
    def _default_planner_action(tool_calls: List[PlannerToolCall]) -> str:
        if not tool_calls:
            return "ASK_USER"

        first_tool = tool_calls[0]
        if first_tool.tool_kind == "ONE_SHOT":
            return "EXECUTE_TOOL"
        if first_tool.tool_kind == "SESSION":
            return "EXECUTE_TOOL"
        if first_tool.tool_kind == "CONTROL":
            return "EXECUTE_TOOL"
        return "EXECUTE_TOOL"

    @staticmethod
    def _contains_any(text: str, keywords: List[str]) -> bool:
        return any(keyword in text for keyword in keywords)

    @staticmethod
    def _normalize_intent(intent: Any) -> str:
        normalized = str(intent or "UNKNOWN").upper()
        valid = {"STOP", "PAUSE", "RESUME", "NAVIGATION", "OBSTACLE", "PHONE_CONTROL", "UNKNOWN"}
        return normalized if normalized in valid else "UNKNOWN"

    @staticmethod
    def _safe_json_loads(content: str) -> Dict[str, Any]:
        try:
            return json.loads(content)
        except json.JSONDecodeError:
            start = content.find("{")
            end = content.rfind("}") + 1
            if start >= 0 and end > start:
                try:
                    return json.loads(content[start:end])
                except json.JSONDecodeError:
                    pass
        return {}
