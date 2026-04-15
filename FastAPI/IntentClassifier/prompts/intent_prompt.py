"""Prompt templates and heuristics for the BlindAssist planner."""

from __future__ import annotations

import json
from typing import Any, Dict, List


STOP_KEYWORDS = [
    "停止",
    "取消",
    "结束",
    "退出",
    "算了",
    "关闭",
    "停下",
    "stop",
    "cancel",
    "abort",
    "quit",
]

PAUSE_KEYWORDS = [
    "暂停",
    "等一下",
    "等一等",
    "先别动",
    "休息一下",
    "pause",
    "wait",
    "hold on",
]

RESUME_KEYWORDS = [
    "继续",
    "恢复",
    "接着",
    "继续导航",
    "resume",
    "continue",
    "go on",
]

NAVIGATION_KEYWORDS = [
    "去",
    "到",
    "导航",
    "怎么走",
    "带我去",
    "想去",
    "前往",
    "路线",
    "最近的",
]

OBSTACLE_KEYWORDS = [
    "前面",
    "周围",
    "障碍",
    "路况",
    "环境",
    "场景",
    "看路",
    "有什么",
    "是什么",
]

PHONE_CONTROL_KEYWORDS = [
    "打开",
    "设置",
    "应用",
    "发送",
    "消息",
    "拨号",
    "搜索",
    "点击",
    "输入",
]

STREAMING_OBSTACLE_KEYWORDS = [
    "持续",
    "一直",
    "边走边看",
    "实时",
    "避障",
]


PLANNER_SYSTEM_PROMPT = """
你是 BlindAssist 的任务规划器（planner），负责：
1. 判断用户当前意图。
2. 根据上下文和工具目录决定下一步 planner_action。
3. 在需要时给出 tool_calls。

你不能发明工具，也不能输出工具目录之外的接口。
如果有 latest_observation，优先基于 observation 决定是 FINISH、ASK_USER，还是继续等待。
如果是一次性工具（ONE_SHOT）已经返回 observation，通常应该 FINISH 并总结结果。
如果是会话工具（SESSION）刚刚启动，通常先返回 EXECUTE_TOOL；工具执行后由运行时进入 WAITING_OBSERVATION。

你必须只输出 JSON，不要输出 markdown，不要输出解释性前缀。
JSON 格式必须为：
{
  "intent": "STOP|PAUSE|RESUME|NAVIGATION|OBSTACLE|PHONE_CONTROL|UNKNOWN",
  "reason": "一句简短原因",
  "confidence": 0.0,
  "plan": ["步骤1", "步骤2"],
  "planner_action": "EXECUTE_TOOL|WAIT_FOR_OBSERVATION|ASK_USER|FINISH",
  "response_text": "普通回复文本，可为空字符串",
  "final_response_text": "结束时返回给用户的最终文本，可为空字符串",
  "tool_calls": [
    {
      "name": "tool.name",
      "arguments": {}
    }
  ]
}
"""


def build_planner_user_prompt(
    *,
    user_input: str,
    request_context: Dict[str, Any],
    tool_catalog: List[Dict[str, Any]],
) -> str:
    """Build the user prompt for the planner model."""
    return (
        "用户请求如下，请结合上下文和工具目录返回 JSON 规划结果。\n\n"
        f"用户输入:\n{user_input}\n\n"
        f"上下文:\n{json.dumps(request_context, ensure_ascii=False, indent=2)}\n\n"
        f"工具目录:\n{json.dumps(tool_catalog, ensure_ascii=False, indent=2)}"
    )
