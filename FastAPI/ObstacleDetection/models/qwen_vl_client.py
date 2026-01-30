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
你是盲人导航助手。分析图像中心区域（人行道行走路径），只报告**前方5米内阻挡行走**的障碍物。

检测重点：
1. 台阶/楼梯/路缘石高差
2. 盲道上的自行车、电动车、摊位
3. 施工围栏、路障
4. 明显的坑洼或凸起

忽略：
- 路边停放的车辆（不在路径上）
- 机动车道上的车
- 路人、建筑物、树木

返回JSON：
{
  "obstacles": [{"type": "台阶", "position": "前方", "distance": 2, "urgency": "high", "in_path": true, "instruction": "前方2米有台阶"}],
  "safe": false,
  "overall_instruction": "前方2米有台阶，注意脚下"
}

如无障碍物返回：
{
  "obstacles": [],
  "safe": true,
  "overall_instruction": "前方路径畅通"
}
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
            print(f"[VLM] Sending request to model: {self.model_name}")
            print(f"[VLM] Image base64 length: {len(image_base64)} chars")

            response = self.client.chat.completions.create(
                model=self.model_name,
                messages=messages,
                temperature=0.1,  # 降低随机性，提高一致性
                max_tokens=300
            )

            content = response.choices[0].message.content
            print(f"[VLM] Raw model response: {content[:200]}...")  # 打印前200字符

            result = self._parse_json_response(content)
            print(f"[VLM] Parsed result: obstacles={len(result.get('obstacles', []))}, safe={result.get('safe', True)}")
            return result

        except Exception as e:
            print(f"[VLM] Obstacle detection failed: {e}")
            import traceback
            traceback.print_exc()
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
                temperature=0.1,  # 降低随机性，提高一致性
                max_tokens=300
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
