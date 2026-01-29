"""
异常处理场景 Prompt
"""

OFF_ROUTE_PROMPT = """## 任务
用户偏离了导航路线，请生成重新规划的指令。

## 当前状态
- 原定目的地: {destination}
- 当前位置: {lon}, {lat}
- 偏离距离: {offset_distance}米

## 要求
1. 告知用户已偏离路线
2. 提供简明的重新规划提示
3. 保持安抚的语气

## 输出格式示例
"您偏离了路线，正在重新规划"
"已偏离路线，请停下等待重新规划"
"""

GPS_ERROR_PROMPT = """## 任务
GPS 信号异常，请生成相应的提示指令。

## 当前状态
- GPS 精度: {accuracy}米
- 信号状态: {signal_status}

## 要求
1. 提示用户 GPS 信号问题
2. 给出解决建议
3. 保持简洁

## 输出格式示例
"GPS 信号弱，请走到开阔区域"
"定位不准确，请检查手机定位设置"
"""

NO_ROUTE_PROMPT = """## 任务
无法规划到目的地的路线。

## 目的地
- 名称: {destination}

## 原因
- {reason}

## 要求
1. 告知用户无法规划路线
2. 提供备选建议
3. 保持友好

## 输出格式示例
"无法规划到该地点的路线，请尝试其他交通方式"
"目的地距离过远，建议使用公交导航"
"""


def get_exception_prompt(
    exception_type: str,
    **kwargs
) -> str:
    """
    获取异常处理场景的 prompt

    Args:
        exception_type: 异常类型 (off_route, gps_error, no_route)
        **kwargs: 场景相关参数
    """
    if exception_type == "off_route":
        return OFF_ROUTE_PROMPT.format(**kwargs)
    elif exception_type == "gps_error":
        return GPS_ERROR_PROMPT.format(**kwargs)
    elif exception_type == "no_route":
        return NO_ROUTE_PROMPT.format(**kwargs)
    else:
        return f"遇到异常情况: {exception_type}"
