"""
导航 Agent - 核心逻辑

处理导航请求，调用模型和高德地图 API，生成盲人友好化指令。
"""

import asyncio
import os
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Tuple

from openai import OpenAI

from amap_client import AmapClient
from instruction_builder import InstructionBuilder
from prompts import (
    get_system_prompt,
    get_route_planning_prompt,
    get_navigation_prompt,
    get_arrival_prompt,
    get_exception_prompt,
)


@dataclass
class SensorData:
    """传感器数据"""
    heading: float = 0.0  # 朝向（度，0=北，90=东，180=南，270=西）
    accuracy: float = 0.0  # GPS 精度（米）
    pitch: float = 0.0  # 俯仰角
    roll: float = 0.0  # 横滚角


@dataclass
class NavigationState:
    """导航状态"""
    user_task: str = ""
    destination: str = ""
    current_location: Dict[str, float] = field(default_factory=dict)
    sensor_data: SensorData = field(default_factory=SensorData)
    planned_route: Optional[Any] = None
    current_step_index: int = 0
    total_distance: float = 0.0
    is_active: bool = False


class NavigationAgent:
    """
    导航 Agent

    使用 qwen2-1.5B-Instruct 模型进行导航理解和指令生成。
    """

    def __init__(
        self,
        model_base_url: str = "http://localhost:8001/v1",
        model_name: str = "Qwen/Qwen2-1.5B-Instruct",
        amap_api_key: Optional[str] = None,
    ):
        """
        初始化导航 Agent

        Args:
            model_base_url: 模型服务地址
            model_name: 模型名称
            amap_api_key: 高德 API Key
        """
        self.model_client = OpenAI(
            base_url=model_base_url,
            api_key="EMPTY"
        )
        self.model_name = model_name
        self.amap_client = AmapClient(api_key=amap_api_key)
        self.instruction_builder = InstructionBuilder()
        self.state = NavigationState()

    def _call_model(self, messages: List[Dict[str, str]], max_tokens: int = 500) -> str:
        """
        调用模型

        Args:
            messages: 消息列表
            max_tokens: 最大 token 数

        Returns:
            模型响应文本
        """
        try:
            response = self.model_client.chat.completions.create(
                model=self.model_name,
                messages=messages,
                temperature=0.1,
                max_tokens=max_tokens,
                timeout=30
            )
            return response.choices[0].message.content.strip()
        except Exception as e:
            print(f"[NavigationAgent] 模型调用失败: {e}")
            return ""

    def _clean_instruction(self, raw_instruction: str) -> str:
        """
        清理大模型返回的指令，去除多余格式

        Args:
            raw_instruction: 原始指令

        Returns:
            清理后的指令
        """
        if not raw_instruction:
            return "请开始导航"

        # 去除 markdown 格式的引号和符号
        instruction = raw_instruction.strip()

        # 去除开头的 "- " 或 "• "
        if instruction.startswith("- ") or instruction.startswith("• "):
            instruction = instruction[2:]

        # 去除开头和结尾的引号
        if instruction.startswith('"') and instruction.endswith('"'):
            instruction = instruction[1:-1]
        elif instruction.startswith('"'):
            instruction = instruction[1:]
        elif instruction.endswith('"'):
            instruction = instruction[:-1]

        # 去除可能的 "示例格式：" 等前缀
        for prefix in ["示例格式：", "示例：", "例如："]:
            if instruction.startswith(prefix):
                instruction = instruction[len(prefix):]

        return instruction.strip()

    def _extract_destination_from_task(self, user_task: str) -> str:
        """
        从用户任务中提取目的地

        Args:
            user_task: 用户任务描述

        Returns:
            目的地关键词
        """
        # 简单关键词提取
        task = user_task.lower()

        # 移除常见的前缀
        prefixes = ["带我去", "请带我去", "我要去", "去", "导航到", "到",
                    "最近的", "附近", "找", "搜索", "我要找"]
        for prefix in prefixes:
            if task.startswith(prefix):
                task = task[len(prefix):]
                break

        # 移除常见的后缀
        suffixes = ["怎么走", "路线", "如何到达"]
        for suffix in suffixes:
            if suffix in task:
                task = task.split(suffix)[0]

        return task.strip()

    async def initialize_navigation(
        self,
        user_task: str,
        origin: Dict[str, float],
        sensor_data: SensorData,
    ) -> Dict[str, Any]:
        """
        初始化导航

        Args:
            user_task: 用户任务（如"带我去最近的肯德基"）
            origin: 起点 GPS 坐标 {"lon": xxx, "lat": xxx}
            sensor_data: 传感器数据

        Returns:
            导航初始化结果
        """
        print(f"[NavigationAgent] 初始化导航: {user_task}")

        # 更新状态
        self.state.user_task = user_task
        self.state.current_location = origin
        self.state.sensor_data = sensor_data
        self.state.is_active = True

        # 提取目的地
        destination = self._extract_destination_from_task(user_task)
        self.state.destination = destination

        # 搜索目的地
        search_results = await self._search_destination(destination, origin)

        if not search_results:
            return {
                "status": "error",
                "message": f"未找到 {destination}，请尝试其他地点",
                "instruction": f"未找到 {destination}，请重试"
            }

        # 选择最近的地点
        nearest = search_results[0]
        place_name = nearest.get("name", destination)
        dest_coords = nearest.get("location", {})

        # 规划路线
        route = self.amap_client.plan_walking_route(
            origin=origin,
            destination=dest_coords
        )

        if not route:
            return {
                "status": "error",
                "message": "无法规划路线",
                "instruction": "无法规划到该地点的路线"
            }

        # 保存路线
        self.state.planned_route = route
        self.state.total_distance = route.total_distance
        self.state.destination = place_name

        # 生成第一步指令
        instruction = await self._generate_first_instruction(
            place_name, route, sensor_data
        )

        return {
            "status": "success",
            "type": "route_planned",
            "instruction": instruction,
            "destination": place_name,
            "total_distance": route.total_distance,
            "destination_coords": dest_coords,
        }

    async def _search_destination(
        self,
        keyword: str,
        location: Dict[str, float]
    ) -> List[Dict[str, Any]]:
        """搜索目的地"""
        try:
            results = self.amap_client.search_nearby(
                keywords=keyword,
                location=location,
                radius=2000
            )
            return results if results else []
        except Exception as e:
            print(f"[NavigationAgent] 搜索失败: {e}")
            return []

    async def _generate_first_instruction(
        self,
        place_name: str,
        route,
        sensor_data: SensorData
    ) -> str:
        """生成第一步导航指令（优先使用规则方法）"""
        if not route.steps:
            return f"开始导航到{place_name}，请开始行走"

        first_step = route.steps[0]

        # 优先使用规则方法（更可靠）
        print(f"[NavigationAgent] 使用规则方法生成指令")
        print(f"[NavigationAgent] 用户朝向: {sensor_data.heading}度, 原始指令: {first_step.instruction}")

        instruction = self.instruction_builder.build_navigation_instruction(
            raw_instruction=first_step.instruction,
            distance=first_step.distance,
            user_heading=sensor_data.heading,
            action=first_step.action,
            is_near_destination=False
        )

        print(f"[NavigationAgent] 规则方法生成指令: {instruction}")
        return instruction

    async def update_location(
        self,
        location: Dict[str, float],
        sensor_data: SensorData,
    ) -> Dict[str, Any]:
        """
        更新位置并生成下一步指令（使用大模型）

        Args:
            location: 新位置
            sensor_data: 传感器数据

        Returns:
            导航更新结果
        """
        if not self.state.is_active or not self.state.planned_route:
            return {
                "status": "error",
                "message": "导航未激活"
            }

        # 更新状态
        self.state.current_location = location
        self.state.sensor_data = sensor_data

        # 检查是否偏离路线
        is_off_route, offset_distance = self._check_off_route(location)

        if is_off_route:
            # 使用大模型生成偏离提示
            instruction = await self._generate_off_route_instruction_with_model(
                offset_distance, sensor_data
            )
            return {
                "status": "warning",
                "type": "off_route",
                "instruction": instruction,
                "offset_distance": offset_distance
            }

        # 获取当前步骤
        route = self.state.planned_route
        steps = route.steps

        if self.state.current_step_index >= len(steps):
            # 已完成所有步骤
            instruction = await self._generate_arrival_instruction_with_model(
                self.state.destination, sensor_data
            )
            return {
                "status": "success",
                "type": "arrived",
                "instruction": instruction
            }

        # 获取当前步骤信息
        current_step = steps[self.state.current_step_index]

        # 使用大模型生成实时导航指令
        instruction = await self._generate_navigation_instruction_with_model(
            current_step, sensor_data
        )

        return {
            "status": "success",
            "type": "navigation_update",
            "instruction": instruction,
            "distance_to_next": current_step.distance
        }

    async def _generate_navigation_instruction_with_model(
        self,
        step,
        sensor_data: SensorData
    ) -> str:
        """使用规则方法生成实时导航指令"""
        # 直接使用规则方法，不再调用 LLM
        return self.instruction_builder.build_navigation_instruction(
            raw_instruction=step.instruction,
            distance=step.distance,
            user_heading=sensor_data.heading,
            action=step.action,
            is_near_destination=False
        )

    async def _generate_arrival_instruction_with_model(
        self,
        destination: str,
        sensor_data: SensorData
    ) -> str:
        """使用规则方法生成到达指令"""
        return self.instruction_builder.build_arrival_instruction(destination)

    async def _generate_off_route_instruction_with_model(
        self,
        offset_distance: float,
        sensor_data: SensorData
    ) -> str:
        """使用规则方法生成偏离提示"""
        return self.instruction_builder.build_off_route_instruction(offset_distance)

    def _check_off_route(self, location: Dict[str, float]) -> Tuple[bool, float]:
        """检查是否偏离路线"""
        # 简化处理：检查与起点和终点的距离
        # 实际应检查与路线各点的距离
        return False, 0.0

    def _distance_to_point(self, location: Dict[str, float], step) -> float:
        """计算到步骤终点的距离"""
        # 简化处理
        return 100.0

    def cancel_navigation(self) -> Dict[str, Any]:
        """取消导航"""
        self.state = NavigationState()
        return {
            "status": "success",
            "message": "导航已取消"
        }
