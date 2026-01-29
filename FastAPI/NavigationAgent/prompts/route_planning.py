"""
路线规划场景 Prompt
"""

ROUTE_PLANNING_PROMPT = """## 任务
用户想要去某个地方，请帮助规划路线并生成初始导航指令。

## 用户输入
{user_task}

## 当前位置
- 经度: {lon}
- 纬度: {lat}
- 朝向: {heading}度 (0=北, 90=东, 180=南, 270=西)

## 地点搜索结果
{search_results}

## 路线规划结果
{route_info}

## 要求
1. 从搜索结果中选择最合适的目的地
2. 生成第一步导航指令：
   - 使用相对方向（基于用户当前朝向）
   - 将距离转换为步数（约0.65米/步）
   - 语言简洁明了
3. 如果有多个结果，选择距离最近的

## 输出格式
直接输出导航指令，例如：
"请向右前方走约300步后左转"
或
"向前走约150步到达肯德基"
"""


def get_route_planning_prompt(
    user_task: str,
    lon: float,
    lat: float,
    heading: float,
    search_results: str,
    route_info: str
) -> str:
    """获取路线规划场景的 prompt"""
    return ROUTE_PLANNING_PROMPT.format(
        user_task=user_task,
        lon=lon,
        lat=lat,
        heading=heading,
        search_results=search_results,
        route_info=route_info
    )
