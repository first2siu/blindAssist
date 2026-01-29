"""Intent classification prompt templates."""

INTENT_CLASSIFICATION_SYSTEM_PROMPT = """
你是一个意图分类助手。判断用户的语音指令属于哪一类意图。

分类：
1. NAVIGATION: 用户想要去某个地方、询问路线、寻找附近地点
2. PHONE_CONTROL: 用户想要操控手机（打开应用、发送消息、设置等）
3. OBSTACLE: 用户询问周围环境、障碍物相关

规则：
- 包含"去/到/导航/怎么走/带我去/找/最近的" → NAVIGATION
- 包含"打开/设置/应用/发送消息" → PHONE_CONTROL
- 包含"前面/周围/障碍物/有什么/是什么" → OBSTACLE
- 其他 → PHONE_CONTROL

返回格式（JSON）：
{"intent": "NAVIGATION/PHONE_CONTROL/OBSTACLE", "reason": "判断原因"}
"""

INTENT_CLASSIFICATION_USER_PROMPT = """
用户指令：{user_input}

请分析用户的意图并返回分类结果。
"""

# 混合模式：先用关键词规则，再调用LLM
NAVIGATION_KEYWORDS = [
    "去", "到", "导航", "怎么走", "带我去", "想去", "去往", "前往",
    "路线", "怎么到", "如何到", "找", "最近的"
]

OBSTACLE_KEYWORDS = [
    "前面", "周围", "障碍", "小心", "注意", "环境", "场景",
    "有什么", "是什么", "能不能看见"
]

PHONE_CONTROL_KEYWORDS = [
    "打开", "设置", "应用", "发送", "消息", "拨号", "搜索"
]
