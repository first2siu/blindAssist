"""Qwen-VL client for obstacle detection."""

import base64
import json
from typing import Optional, Dict, Any

from openai import OpenAI


class QwenVLClient:
    """
    Client for Qwen-VL model for obstacle detection.
    """

    OBSTACLE_DETECTION_PROMPT = """
你是一个为视障人士服务的障碍物检测助手。请分析用户面前图像中的**行走路径**（盲道、人行道中间区域），识别可能对用户造成危险的障碍物。

## 重要：检测原则

**只关注用户行走路径上的障碍物**：
- 用户正前方的盲道或人行道中央区域（画面中央约60%范围，即10点到2点钟方向）
- 忽略路两侧的景物（机动车道、远处建筑、路边停放的车辆等）
- 只报告距离用户10米以内的障碍物

## 需要检测的障碍物类型

**高优先级（critical/high）**：
- 台阶/楼梯（在行走路径上）
- 盲道占用物（自行车、共享单车、摊位等）
- 施工围栏/路障（阻挡路径）
- 凸起的井盖或路面坑洼
- 正在倒车的车辆（朝向人行道）

**中优先级（medium）**：
- 低矮悬挂物（可能撞到头部）
- 路灯/电线杆（在正前方路径上）

**低优先级或不报告（low/ignore）**：
- 机动车道上的车辆（不在人行道行走路径上）
- 路边正常停放的车辆
- 路人（除非阻挡整个路径）
- 远处的建筑物、标志牌等

## 位置判断标准

| 位置范围 | 时钟方向 | 判断 |
|---------|---------|------|
| 正前方 | 11-1点 | 主要检测区域 |
| 前方偏左/偏右 | 9-11点，1-3点 | 次要检测区域 |
| 两侧 | 7-9点，3-5点 | 仅高优先级障碍物 |
| 忽略 | 5-7点（后方） | 不报告 |

## 返回格式（JSON）：

```json
{
  "obstacles": [
    {
      "type": "台阶",
      "position": "前方",
      "distance": 3.0,
      "urgency": "high",
      "in_path": true,
      "instruction": "前方三米有台阶，请减速并注意脚下"
    }
  ],
  "safe": false,
  "overall_instruction": "前方三米有台阶，请减速并注意脚下"
}
```

**字段说明**：
- `in_path`: 布尔值，表示障碍物是否在用户行走路径上
- `urgency`: critical（立即停止）/high（警告）/medium（注意）/low（可选播报）
- `distance`: 估算距离（米），只报告10米以内的

**安全返回**：
```json
{
  "obstacles": [],
  "safe": true,
  "overall_instruction": "前方路径畅通"
}
```

**注意**：如果画面中有多个障碍物，只返回在行走路径上且距离最近的1-2个，避免信息过载。
"""

    LAST_TEN_METER_PROMPT = """
你是一个为视障人士服务的精确引导助手。用户已经接近目的地，需要帮助用户找到具体的入口或标志物。

请分析图像并：
1. 识别图像中是否有明显的标志物（如便利店招牌、地铁入口、门牌号等）
2. 判断标志物相对于用户的位置（使用时钟方向，如"2点钟方向"）
3. 估算距离
4. 提供引导语音

返回格式（JSON）：
{
  "landmarks": [
    {
      "name": "XX便利店",
      "direction": "2",
      "direction_text": "右前方",
      "distance": 5.0,
      "confidence": 0.9
    }
  ],
  "instruction": "目标在您2点钟方向约5米处，请注意XX便利店招牌"
}
"""

    def __init__(self, base_url: str, model_name: str, api_key: str = "EMPTY"):
        """
        Initialize Qwen-VL client.

        Args:
            base_url: Base URL for vLLM server
            model_name: Name of the model
            api_key: API key for authentication
        """
        self.client = OpenAI(base_url=base_url, api_key=api_key)
        self.model_name = model_name

    def detect_obstacles(self, image_base64: str, sensor_data: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """
        Detect obstacles in the image.

        Args:
            image_base64: Base64 encoded image (without header)
            sensor_data: Optional sensor data (heading, location, etc.)

        Returns:
            Detection result with obstacles and instructions
        """
        messages = self._build_vision_messages(image_base64, self.OBSTACLE_DETECTION_PROMPT)

        try:
            response = self.client.chat.completions.create(
                model=self.model_name,
                messages=messages,
                temperature=0.3,
                max_tokens=500
            )

            content = response.choices[0].message.content
            return self._parse_json_response(content)

        except Exception as e:
            print(f"Obstacle detection failed: {e}")
            return self._get_safe_response()

    def find_landmark(self, image_base64: str, target_description: str) -> Dict[str, Any]:
        """
        Find specific landmark for "last ten meter" guidance.

        Args:
            image_base64: Base64 encoded image
            target_description: Description of the target to find

        Returns:
            Landmark detection result
        """
        prompt = f"{self.LAST_TEN_METER_PROMPT}\n\n目标：{target_description}"
        messages = self._build_vision_messages(image_base64, prompt)

        try:
            response = self.client.chat.completions.create(
                model=self.model_name,
                messages=messages,
                temperature=0.3,
                max_tokens=500
            )

            content = response.choices[0].message.content
            return self._parse_json_response(content)

        except Exception as e:
            print(f"Landmark detection failed: {e}")
            return {
                "landmarks": [],
                "instruction": "暂时无法识别目标位置，请继续向前探索"
            }

    def _build_vision_messages(self, image_base64: str, prompt: str) -> list:
        """Build messages for vision model."""
        return [
            {
                "role": "system",
                "content": "你是一个专业的视障辅助AI助手。"
            },
            {
                "role": "user",
                "content": [
                    {"type": "text", "text": prompt},
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": f"data:image/jpeg;base64,{image_base64}"
                        }
                    }
                ]
            }
        ]

    def _parse_json_response(self, content: str) -> Dict[str, Any]:
        """Parse JSON response from model."""
        try:
            # Extract JSON from content (in case there's extra text)
            start = content.find('{')
            end = content.rfind('}') + 1

            if start >= 0 and end > start:
                json_str = content[start:end]
                return json.loads(json_str)
            else:
                # No valid JSON found
                raise ValueError("No JSON found in response")

        except (json.JSONDecodeError, ValueError) as e:
            print(f"Failed to parse JSON response: {e}")
            print(f"Raw content: {content}")
            return self._get_safe_response()

    def _get_safe_response(self) -> Dict[str, Any]:
        """Return a safe response when detection fails."""
        return {
            "obstacles": [],
            "safe": True,
            "overall_instruction": "暂时无法分析前方路况，请谨慎前行"
        }
