"""
Navigation-specific prompts for the AI agent.

This module contains prompts for handling navigation requests,
including route planning, instruction refinement, and guidance.
"""

from datetime import datetime

today = datetime.today()
formatted_date = today.strftime("%Y年%m月%d日")

NAVIGATION_SYSTEM_PROMPT = f"""
今天的日期是: {formatted_date}

你是一个为视障人士服务的导航助手。你能够帮助用户：
1. 规划步行路线
2. 提供友好的语音导航指令
3. 搜索附近的地点

你可以执行以下操作：

**导航相关操作:**
- do(action="Call_Navigation", destination="目的地")
    用于规划到目的地的步行路线。
    destination 可以是地点名称（如"肯德基"）或地址（如"学院路1号"）

- do(action="Launch", app="高德地图")
    打开高德地图应用进行导航

- do(action="Type", text="搜索内容")
    在搜索框中输入目的地

- finish(instruction="导航指令", distance=距离米数, urgency="normal/important")
    完成导航规划，返回友好的语音指令

**通用操作:**
- do(action="Tap", element=[x,y]) - 点击屏幕坐标
- do(action="Swipe", start=[x1,y1], end=[x2,y2]) - 滑动屏幕
- do(action="Back") - 返回上一页
- do(action="Home") - 回到桌面

**导航指令转换规则:**
1. 绝对方向（东南西北）需要转换为相对方向（左前右后）
2. 距离以米为单位，可以转换为大约步数（1步≈0.65米）
3. 使用简洁明了的语言，避免复杂的描述
4. 重要信息放在前面，次要信息可以省略

**示例转换:**
- "沿学院路向东走200米，然后左转进入XX路"
  → "向前走大约300步，然后向左转"

- "在红绿灯处右转，继续向南走150米"
  → "走到红绿灯向右转，然后向前走约230步"

**重要提醒:**
- 视障用户无法看屏幕，所有信息必须通过语音传达
- 导航指令要简洁、明确、不含糊
- 注意安全提醒，如台阶、红绿灯等
"""

# 视觉障碍用户友好的导航指令模板
BLIND_FRIENDLY_NAVIGATION_TEMPLATES = {
    "turn_left": "向左转",
    "turn_right": "向右转",
    "go_straight": "继续直行",
    "u_turn": "向后转",
    "enter": "进入",
    "stairs_up": "注意前方有上坡楼梯，请小心",
    "stairs_down": "注意前方有下坡楼梯，请小心",
    "crosswalk": "前方有人行横道，请注意交通",
    "traffic_light": "前方有红绿灯",
}

# 方向转换参考（根据用户朝向）
HEADING_DIRECTIONS = {
    0: "北", 45: "东北", 90: "东", 135: "东南",
    180: "南", 225: "西南", 270: "西", 315: "西北"
}


def get_relative_direction(absolute_direction: str, user_heading: float) -> str:
    """
    将绝对方向转换为相对于用户朝向的方向。

    Args:
        absolute_direction: "东/南/西/北" 或 "east/south/west/north"
        user_heading: 用户朝向角度（0-360，0=北，90=东）

    Returns:
        相对方向: "左前/右前/左后/右后/前方/后方"
    """
    direction_map = {
        "北": 0, "north": 0,
        "东": 90, "east": 90,
        "南": 180, "south": 180,
        "西": 270, "west": 270
    }

    target_deg = direction_map.get(absolute_direction.lower())
    if target_deg is None:
        return absolute_direction

    diff = (target_deg - user_heading + 360) % 360

    if 337.5 <= diff or diff < 22.5:
        return "前方"
    elif 22.5 <= diff < 67.5:
        return "右前方"
    elif 67.5 <= diff < 112.5:
        return "右侧"
    elif 112.5 <= diff < 157.5:
        return "右后方"
    elif 157.5 <= diff < 202.5:
        return "后方"
    elif 202.5 <= diff < 247.5:
        return "左后方"
    elif 247.5 <= diff < 292.5:
        return "左侧"
    else:  # 292.5 <= diff < 337.5
        return "左前方"


def meters_to_steps(meters: int) -> int:
    """将米转换为大约步数。"""
    return max(1, int(meters / 0.65))


def format_navigation_instruction(
    original_instruction: str,
    distance_meters: int,
    user_heading: float = 0
) -> str:
    """
    将原始导航指令转换为视障用户友好的格式。

    Args:
        original_instruction: 原始导航指令（如"沿学院路向东走200米"）
        distance_meters: 距离（米）
        user_heading: 用户当前朝向（度）

    Returns:
        友好的语音指令
    """
    steps = meters_to_steps(distance_meters)

    # 简化指令：移除过于详细的信息
    instruction = original_instruction

    # 替换距离描述
    if f"{distance_meters}米" in instruction:
        instruction = instruction.replace(f"{distance_meters}米", f"约{steps}步")

    # 转换方向（简化处理，实际需要更复杂的NLP）
    for direction, deg in [("东", 90), ("南", 180), ("西", 270), ("北", 0)]:
        if direction in instruction:
            relative = get_relative_direction(direction, user_heading)
            instruction = instruction.replace(direction, relative)
            break

    return instruction


# 导航场景识别
NAVIGATION_SCENARIOS = {
    "to_destination": {
        "keywords": ["去", "到", "导航", "怎么走", "带我去", "前往"],
        "response_template": "好的，为您规划到{destination}的路线"
    },
    "search_nearby": {
        "keywords": ["附近的", "周围的", "找一家", "哪里有"],
        "response_template": "正在搜索附近的{place}"
    },
    "current_location": {
        "keywords": ["我在哪", "这是什么地方", "当前位置"],
        "response_template": "正在获取您的位置信息"
    },
}


def detect_navigation_scenario(text: str) -> str:
    """
    检测用户的导航意图场景。

    Returns:
        'to_destination', 'search_nearby', 'current_location', 或 'unknown'
    """
    text_lower = text.lower()

    for scenario, config in NAVIGATION_SCENARIOS.items():
        for keyword in config["keywords"]:
            if keyword in text_lower:
                return scenario

    return "unknown"
