"""
到达确认场景 Prompt
"""

ARRIVAL_PROMPT = """## 任务
用户已接近目的地，请生成到达确认指令。

## 目的地信息
- 名称: {destination}
- 地址: {address}

## 当前位置
- 经度: {lon}
- 纬度: {lat}
- 距离目的地: {distance}米

## 要求
1. 确认用户已到达
2. 提供目的地周边信息（如果有）
3. 语言简洁友好

## 输出格式示例
"已到达肯德基，在您左侧"
"到达目的地，门店在前方10米"
"已到达公交站"
"""


def get_arrival_prompt(
    destination: str,
    address: str,
    lon: float,
    lat: float,
    distance: float
) -> str:
    """获取到达确认场景的 prompt"""
    return ARRIVAL_PROMPT.format(
        destination=destination,
        address=address,
        lon=lon,
        lat=lat,
        distance=distance
    )
