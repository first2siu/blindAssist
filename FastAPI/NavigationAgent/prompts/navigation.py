"""
实时导航场景 Prompt
"""

NAVIGATION_PROMPT = """## 任务
用户正在导航中，请根据当前位置和下一步指令，生成盲人友好的导航提示。

## 导航状态
- 目的地: {destination}
- 距离目的地: {total_remaining}米

## 当前位置
- 经度: {lon}
- 纬度: {lat}
- 朝向: {heading}度

## 下一步原始指令
{next_instruction}
- 距离: {step_distance}米
- 动作: {action}

## 要求
1. 将绝对方向转换为相对方向（基于用户朝向）
2. 将米转换为步数（约0.65米/步）
3. 根据距离调整详细程度：
   - <30步：直接说"继续向前X步"
   - 30-100步：提示"继续向前约X步"
   - >100步：分段提示

## 特殊情况处理
- 距离下一步 <10米：提示"准备{动作}"
- 距离目的地 <50米：提示"即将到达"
- 用户偏离路线：提示偏离并建议操作

## 输出格式
直接输出指令，例如：
"继续向前约50步"
"准备左转"
"即将到达目的地"
"""


def get_navigation_prompt(
    destination: str,
    total_remaining: float,
    lon: float,
    lat: float,
    heading: float,
    next_instruction: str,
    step_distance: float,
    action: str
) -> str:
    """获取实时导航场景的 prompt"""
    return NAVIGATION_PROMPT.format(
        destination=destination,
        total_remaining=total_remaining,
        lon=lon,
        lat=lat,
        heading=heading,
        next_instruction=next_instruction,
        step_distance=step_distance,
        action=action
    )
