"""
指令构建器 - 将高德返回的导航指令转换为盲人友好化指令
"""

import math
from typing import Optional


class InstructionBuilder:
    """
    导航指令构建器

    将高德地图返回的技术性导航指令转换为适合视障人士的语音指令。
    """

    # 方向角度映射
    DIRECTION_ANGLES = {
        "北": 0,
        "东北": 45,
        "东": 90,
        "东南": 135,
        "南": 180,
        "西南": 225,
        "西": 270,
        "西北": 315,
    }

    # 米转步数系数
    METERS_TO_STEPS = 1 / 0.65  # 约 0.65 米/步

    @staticmethod
    def convert_to_relative_direction(
        instruction: str,
        user_heading: float,
        raw_action: Optional[str] = None
    ) -> str:
        """
        将绝对方向指令转换为相对方向指令

        Args:
            instruction: 原始指令（如"向东走100米后左转"）
            user_heading: 用户当前朝向（度，0=北，90=东，180=南，270=西）
            raw_action: 原始动作类型（turn_left, turn_right, go_straight 等）

        Returns:
            转换后的相对方向指令
        """
        result = instruction

        # 替换绝对方向为相对方向
        for direction, angle in InstructionBuilder.DIRECTION_ANGLES.items():
            if direction in instruction:
                relative_dir = InstructionBuilder._get_relative_direction(angle, user_heading)
                # 替换 "向东" -> "向右前方" 等
                result = result.replace(f"向{direction}", relative_dir)
                result = result.replace(f"沿{direction}", f"往{relative_dir.replace('向', '')}")
                result = result.replace(f"{direction}方向", relative_dir)
                break

        return result

    @staticmethod
    def _get_relative_direction(target_angle: float, user_heading: float) -> str:
        """
        根据目标角度和用户朝向计算相对方向

        Args:
            target_angle: 目标方向角度（0-360）
            user_heading: 用户当前朝向（0-360）

        Returns:
            相对方向描述
        """
        # 计算角度差（-180 到 180）
        angle_diff = (target_angle - user_heading + 540) % 360 - 180

        # 根据角度差确定相对方向
        if angle_diff < -67.5:
            return "向后"
        elif angle_diff < -22.5:
            return "向左后方"
        elif angle_diff < 22.5:
            return "向前"
        elif angle_diff < 67.5:
            return "向右前方"
        else:
            return "向右"

    @staticmethod
    def convert_distance_to_steps(distance_meters: float) -> str:
        """
        将距离转换为步数描述

        Args:
            distance_meters: 距离（米）

        Returns:
            步数描述字符串
        """
        if distance_meters <= 0:
            return ""

        steps = int(distance_meters * InstructionBuilder.METERS_TO_STEPS)

        if steps < 10:
            return f"{steps}步"
        elif steps < 50:
            return f"约{steps}步"
        elif steps < 200:
            # 将大距离分段
            major_part = (steps // 50) * 50
            if steps % 50 >= 20:
                major_part += 50
            return f"约{major_part}步"
        else:
            # 超长距离，用米表示
            return f"约{int(distance_meters)}米"

    @staticmethod
    def build_navigation_instruction(
        raw_instruction: str,
        distance: float,
        user_heading: float,
        action: Optional[str] = None,
        is_near_destination: bool = False
    ) -> str:
        """
        构建完整的导航指令

        Args:
            raw_instruction: 原始指令文本
            distance: 距离（米）
            user_heading: 用户当前朝向
            action: 动作类型（turn_left, turn_right, go_straight 等）
            is_near_destination: 是否接近目的地

        Returns:
            盲人友好化的导航指令
        """
        # 转换方向
        relative_instruction = InstructionBuilder.convert_to_relative_direction(
            raw_instruction, user_heading, action
        )

        # 转换距离
        distance_str = InstructionBuilder.convert_distance_to_steps(distance)

        # 提取动作描述
        action_desc = InstructionBuilder._get_action_description(action)

        # 组装指令
        if is_near_destination:
            if distance < 20:
                return "即将到达目的地"
            elif distance < 50:
                return f"目的地在前方，{distance_str}"
            else:
                return f"继续向前{distance_str}到达目的地"

        if action_desc:
            if distance_str:
                return f"请{relative_instruction}，{distance_str}后{action_desc}"
            else:
                return f"请{action_desc}"
        else:
            if distance_str:
                return f"{relative_instruction}{distance_str}"
            else:
                return relative_instruction

    @staticmethod
    def _get_action_description(action: Optional[str]) -> str:
        """
        获取动作的中文描述

        Args:
            action: 动作类型

        Returns:
            中文描述
        """
        action_map = {
            "turn_left": "左转",
            "turn_right": "右转",
            "go_straight": "继续直行",
            "u_turn": "掉头",
            "enter": "进入",
            "exit": "离开",
        }
        return action_map.get(action, "继续前行")

    @staticmethod
    def build_search_result_summary(
        place_name: str,
        distance: Optional[float],
        address: Optional[str] = None
    ) -> str:
        """
        构建搜索结果摘要

        Args:
            place_name: 地点名称
            distance: 距离（米）
            address: 地址

        Returns:
            搜索结果描述
        """
        if distance:
            distance_str = InstructionBuilder.convert_distance_to_steps(distance)
            result = f"找到{place_name}，距离{distance_str}"
        else:
            result = f"找到{place_name}"

        if address:
            result += f"，{address}"

        return result

    @staticmethod
    def build_off_route_instruction(offset_distance: float) -> str:
        """
        构建偏离路线指令

        Args:
            offset_distance: 偏离距离（米）

        Returns:
            偏离提示指令
        """
        if offset_distance < 20:
            return "您稍微偏离了路线，请向右靠一点"
        elif offset_distance < 50:
            return "您偏离了路线，正在重新规划"
        else:
            return "您已偏离路线，请停下等待重新规划"

    @staticmethod
    def build_arrival_instruction(
        destination_name: str,
        nearby_landmark: Optional[str] = None
    ) -> str:
        """
        构建到达指令

        Args:
            destination_name: 目的地名称
            nearby_landmark: 附近地标

        Returns:
            到达确认指令
        """
        if nearby_landmark:
            return f"已到达{destination_name}，{nearby_landmark}"
        else:
            return f"已到达{destination_name}"
