# server.py
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from typing import List, Dict, Any, Optional
import json
import traceback
import os
import math

# 导入 Open-AutoGLM 的核心组件
from phone_agent.model import ModelClient, ModelConfig
from phone_agent.model.client import MessageBuilder
from phone_agent.actions.handler import parse_action
from phone_agent.config import get_system_prompt
from phone_agent.amap.client import AmapClient

app = FastAPI()

# 1. 配置模型
model_config = ModelConfig(
    base_url=os.getenv("MODEL_BASE_URL", "http://localhost:8002/v1"),
    model_name=os.getenv("MODEL_NAME", "/data/lilele/AutoGLM/models/ZhipuAI/AutoGLM-Phone-9B"),
    temperature=0.1,
)
model_client = ModelClient(model_config)

# 高德地图客户端
amap_client = AmapClient()

# 系统提示词
SYSTEM_PROMPT = get_system_prompt("cn")
NAVIGATION_PROMPT = get_system_prompt("cn", mode="navigation")

# 导航专用增强 Prompt
NAVIGATION_SYSTEM_PROMPT = """
今天的日期是: 2025年1月28日

你是一个为视障人士服务的导航助手。你的任务是理解用户的导航需求，然后调用工具获取信息，最后返回简洁的语音指令。

## 重要：你必须严格按照以下格式调用工具

1. 如果需要搜索附近地点（如"最近的肯德基"），输出：
```
do(action="search_nearby", keyword="肯德基")
```

2. 如果需要规划路线，输出：
```
do(action="plan_route", destination="肯德基")
```

3. 如果已经有足够信息给用户导航指令，输出：
```
finish(instruction="向前走大约300步，然后向左转", distance=200)
```

## 工具说明
- search_nearby(keyword): 在当前位置搜索关键词，返回附近地点列表
- plan_route(destination): 规划到目的地的步行路线
- finish(instruction, distance): 结束导航，返回语音指令

## 指令转换规则
- 绝对方向（东南西北）→ 相对方向（左前右后）
- 距离以米为单位 → 约0.65米/步
- 使用简洁明了的语言
- 重要信息放在前面

## 示例
用户说"带我去最近的肯德基"：
1. 先调用: do(action="search_nearby", keyword="肯德基")
2. 收到结果后调用: do(action="plan_route", destination="肯德基")
3. 最后输出: finish(instruction="向前走大约300步到达肯德基", distance=200)

## 注意
- 不要输出其他格式的工具调用
- 必须严格按照 do(action="...", param="...") 或 finish(..., ...) 格式
- 如果不知道如何处理，直接返回 finish() 指令让用户知道当前状态
"""


class NavigationSession:
    """
    导航会话，维护导航状态
    """
    def __init__(self, client_id: str):
        self.client_id = client_id
        self.context: List[Dict[str, Any]] = []
        self.step_count = 0

        # 导航状态
        self.user_task: Optional[str] = None  # 用户完整任务
        self.destination: Optional[str] = None
        self.current_location: Optional[Dict[str, float]] = None  # {lon, lat}
        self.user_heading: float = 0.0
        self.planned_route: Optional[Dict] = None
        self.route_waypoints: List[Dict[str, float]] = []  # 路径关键点
        self.current_step_index: int = 0
        self.last_announced_distance: float = float('inf')

        # 暂停/恢复状态
        self.is_paused: bool = False
        self.paused_location: Optional[Dict[str, float]] = None  # 暂停时的位置

    def init_navigation(self, user_task: str, origin: Dict[str, float], sensor_data: Dict):
        """初始化导航

        Args:
            user_task: 用户完整任务，如"带我去最近的肯德基"
            origin: 起点 GPS 坐标 {"lon": xxx, "lat": xxx}
            sensor_data: 传感器数据 {"heading": xxx, "accuracy": xxx}
        """
        self.context = []
        self.step_count = 1
        self.user_task = user_task
        self.current_location = origin
        self.user_heading = sensor_data.get("heading", 0)

        # 使用导航专用 prompt
        self.context.append(MessageBuilder.create_system_message(NAVIGATION_SYSTEM_PROMPT))

        # 构造任务描述，包含用户任务和位置信息
        task = f"## 用户任务\n{user_task}\n\n"
        if origin:
            task += f"## 当前位置\n经度: {origin.get('lon')}\n纬度: {origin.get('lat')}\n\n"
        heading = sensor_data.get("heading", 0)
        task += f"当前朝向: {heading}度\n\n"

        # 明确告诉模型下一步该做什么
        task += f"## 请执行\n"
        task += f"根据用户任务，请调用合适的工具。如果用户说\"附近的XX\"或\"最近的XX\"，先用 search_nearby 搜索。"
        task += f"如果用户说\"去XX\"，直接用 plan_route。搜索和规划完成后，用 finish 返回导航指令。\n\n"
        task += f"**重要**：必须严格按照 do(action=\"...\", param=\"...\") 或 finish(..., ...) 格式输出。"

        self.context.append(MessageBuilder.create_user_message(text=task))

    def update_location(self, location: Dict[str, float], sensor_data: Dict):
        """更新位置并检测偏离"""
        if not self.current_location:
            self.current_location = location
            return

        self.current_location = location
        self.user_heading = sensor_data.get("heading", 0)

        # 检测偏离
        is_off_route, distance_to_route = self._check_off_route(location)

        update_info = f"当前位置更新: 经度{location.get('lon')}, 纬度{location.get('lat')}"
        update_info += f"\n朝向: {self.user_heading}度"

        if is_off_route:
            update_info += f"\n警告: 用户偏离路线约{distance_to_route:.0f}米，需要重新规划"
        else:
            # 更新到下一步的距离
            remaining_distance = self._get_distance_to_next_step(location)
            update_info += f"\n距离下一步约{remaining_distance:.0f}米"

            # 当接近下一步时（10米内）
            if remaining_distance < 10 and remaining_distance < self.last_announced_distance:
                update_info += f"\n用户已接近当前导航点，准备下达下一步指令"

            self.last_announced_distance = remaining_distance

        self.context.append(MessageBuilder.create_user_message(text=update_info))

    def is_off_route(self) -> bool:
        """检查是否偏离路线"""
        if not self.current_location or not self.route_waypoints:
            return False

        is_off, distance = self._check_off_route(self.current_location)
        return is_off

    def _check_off_route(self, location: Dict[str, float]) -> tuple[bool, float]:
        """检查位置是否偏离路线"""
        if not self.route_waypoints:
            return False, 0.0

        # 找到最近的路径点
        min_distance = float('inf')
        for point in self.route_waypoints:
            dist = self._haversine_distance(
                location.get('lon', 0), location.get('lat', 0),
                point.get('lon', 0), point.get('lat', 0)
            )
            min_distance = min(min_distance, dist)

        # 偏离阈值：50米
        return min_distance > 50, min_distance

    def _get_distance_to_next_step(self, location: Dict[str, float]) -> float:
        """获取到下一步的距离"""
        if not self.route_waypoints or self.current_step_index >= len(self.route_waypoints):
            return 0.0

        next_point = self.route_waypoints[self.current_step_index]
        return self._haversine_distance(
            location.get('lon', 0), location.get('lat', 0),
            next_point.get('lon', 0), next_point.get('lat', 0)
        )

    def _extract_waypoints(self, route) -> List[Dict[str, float]]:
        """从路径中提取关键点"""
        waypoints = []
        for step in route.steps:
            # 这里简化处理，实际应从步骤中提取坐标
            # 高德API返回的steps中每个step包含polyline
            pass
        return waypoints

    def _geocode_destination(self, destination: str) -> Dict[str, float]:
        """将目的地转换为坐标"""
        result = amap_client.geocode(destination)
        if result:
            return {"lon": result.get("lon"), "lat": result.get("lat")}
        # 默认返回北京市中心
        return {"lon": 116.397428, "lat": 39.90923}

    def pause(self):
        """暂停导航"""
        self.is_paused = True
        self.paused_location = self.current_location.copy() if self.current_location else None
        print(f"[{self.client_id}] Navigation paused")

    def resume(self):
        """恢复导航"""
        self.is_paused = False
        print(f"[{self.client_id}] Navigation resumed")

    def is_paused_state(self) -> bool:
        """检查导航是否暂停"""
        return self.is_paused

    def add_assistant_response(self, thinking: str, action: str):
        """添加助手响应到上下文"""
        response_text = thinking
        if action:
            response_text += "\n" + action
        self.context.append(MessageBuilder.create_assistant_message(response_text))

    @staticmethod
    def _haversine_distance(lon1: float, lat1: float, lon2: float, lat2: float) -> float:
        """计算两点间距离（米）"""
        R = 6371000  # 地球半径（米）

        phi1 = math.radians(lat1)
        phi2 = math.radians(lat2)
        delta_phi = math.radians(lat2 - lat1)
        delta_lambda = math.radians(lon2 - lon1)

        a = math.sin(delta_phi/2)**2 + math.cos(phi1) * math.cos(phi2) * math.sin(delta_lambda/2)**2
        c = 2 * math.atan2(math.sqrt(a), math.sqrt(1-a))

        return R * c


class AgentSession:
    """手机操控会话"""
    def __init__(self, client_id: str):
        self.client_id = client_id
        self.context: List[Dict[str, Any]] = []
        self.step_count = 0

    def init_session(self, task: str, screen_info: str, screenshot_base64: str):
        self.context = []
        self.step_count = 1
        self.context.append(MessageBuilder.create_system_message(SYSTEM_PROMPT))

        text_content = f"{task}\n\nScreen Info: {screen_info}"
        self.context.append(
            MessageBuilder.create_user_message(
                text=text_content,
                image_base64=screenshot_base64
            )
        )

    def step_session(self, screen_info: str, screenshot_base64: str):
        self.step_count += 1
        if self.context:
            self.context[-1] = MessageBuilder.remove_images_from_message(self.context[-1])

        text_content = f"** Screen Info **\n\n{screen_info}"
        self.context.append(
            MessageBuilder.create_user_message(
                text=text_content,
                image_base64=screenshot_base64
            )
        )

    def add_assistant_response(self, thinking: str, action_str: str):
        self.context.append(
            MessageBuilder.create_assistant_message(
                f"<think>\n{thinking}\n</think>\n<answer>{action_str}</answer>"
            )
        )


class ConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, WebSocket] = {}
        self.phone_sessions: Dict[str, AgentSession] = {}
        self.navigation_sessions: Dict[str, NavigationSession] = {}

    async def connect(self, websocket: WebSocket, client_id: str, mode: str = "phone"):
        # 检查是否已有相同 client_id 的连接
        if client_id in self.active_connections:
            print(f"[{client_id}] 检测到重复连接，关闭旧连接")
            old_websocket = self.active_connections[client_id]
            try:
                await old_websocket.close()
            except:
                pass
            # 清理旧会话
            if client_id in self.phone_sessions:
                del self.phone_sessions[client_id]
            if client_id in self.navigation_sessions:
                del self.navigation_sessions[client_id]

        await websocket.accept()
        self.active_connections[client_id] = websocket

        if mode == "navigation":
            self.navigation_sessions[client_id] = NavigationSession(client_id)
        else:
            self.phone_sessions[client_id] = AgentSession(client_id)

        print(f"Client connected: {client_id} (mode: {mode})")

    def disconnect(self, client_id: str):
        if client_id in self.active_connections:
            del self.active_connections[client_id]
        if client_id in self.phone_sessions:
            del self.phone_sessions[client_id]
        if client_id in self.navigation_sessions:
            del self.navigation_sessions[client_id]
        print(f"Client disconnected: {client_id}")

    def get_phone_session(self, client_id: str) -> Optional[AgentSession]:
        return self.phone_sessions.get(client_id)

    def get_navigation_session(self, client_id: str) -> Optional[NavigationSession]:
        return self.navigation_sessions.get(client_id)


manager = ConnectionManager()


@app.websocket("/ws/agent/{client_id}")
async def agent_websocket_endpoint(websocket: WebSocket, client_id: str):
    """手机操控 WebSocket 端点"""
    await manager.connect(websocket, client_id, mode="phone")
    session = manager.get_phone_session(client_id)

    try:
        while True:
            data = await websocket.receive_json()
            req_type = data.get("type")
            screenshot = data.get("screenshot")
            screen_info = data.get("screen_info", "Unknown Page")

            if not screenshot:
                await websocket.send_json({"status": "error", "message": "Missing screenshot"})
                continue

            if req_type == "init":
                task = data.get("task")
                if not task:
                    await websocket.send_json({"status": "error", "message": "Missing task"})
                    continue
                session.init_session(task, screen_info, screenshot)
                print(f"[{client_id}] Phone task initialized: {task}")

            elif req_type == "step":
                session.step_session(screen_info, screenshot)

            # 模型推理
            try:
                response = model_client.request(session.context)
            except Exception as e:
                traceback.print_exc()
                await websocket.send_json({"status": "error", "message": f"Model inference failed: {str(e)}"})
                continue

            # 解析动作
            try:
                action_data = parse_action(response.action)
            except ValueError:
                action_data = {"_metadata": "finish", "message": response.action}

            session.add_assistant_response(response.thinking, response.action)

            response_payload = {
                "status": "success",
                "step": session.step_count,
                "thinking": response.thinking,
                "action": action_data,
                "raw_response": response.action,
                "finished": action_data.get("_metadata") == "finish"
            }

            await websocket.send_json(response_payload)

    except WebSocketDisconnect:
        manager.disconnect(client_id)
    except Exception as e:
        print(f"Agent WebSocket Error: {e}")


@app.websocket("/ws/navigation/{client_id}")
async def navigation_websocket_endpoint(websocket: WebSocket, client_id: str):
    """
    导航专用 WebSocket 端点

    支持的消息类型:
    - init: 初始化导航
    - location_update: 更新GPS位置
    - cancel: 取消导航

    消息格式:
    {
        "type": "init",
        "user_task": "带我去最近的肯德基",
        "origin": {"lon": 116.39, "lat": 39.90},
        "sensor_data": {"heading": 45}
    }
    """
    await manager.connect(websocket, client_id, mode="navigation")
    session = manager.get_navigation_session(client_id)

    try:
        while True:
            data = await websocket.receive_json()
            req_type = data.get("type")

            if req_type == "init":
                user_task = data.get("user_task")
                if not user_task:
                    await websocket.send_json({"status": "error", "message": "Missing user_task"})
                    continue

                origin = data.get("origin", {})
                sensor_data = data.get("sensor_data", {})

                # 接收并设置高德 API Key（从 Spring Boot 传递过来）
                amap_api_key = data.get("amap_api_key")
                if amap_api_key:
                    global amap_client
                    amap_client.api_key = amap_api_key
                    amap_client.session.params = {"key": amap_api_key}
                    print(f"[{client_id}] Amap API Key updated from Spring Boot")

                print(f"[{client_id}] Navigation initialized: {user_task}")

                # 初始化导航会话（让模型理解任务）
                session.init_navigation(user_task, origin, sensor_data)

                # 工具执行循环：处理模型的工具调用请求
                max_iterations = 10  # 防止无限循环
                final_instruction = "正在准备导航..."

                try:
                    for iteration in range(max_iterations):
                        print(f"[{client_id}] ===== 第 {iteration+1} 轮开始 =====")
                        print(f"[{client_id}] 当前上下文消息数: {len(session.context)}")

                        try:
                            response = model_client.request(session.context)
                        except Exception as model_err:
                            print(f"[{client_id}] 模型请求异常: {model_err}")
                            traceback.print_exc()
                            final_instruction = f"模型请求失败，请重试"
                            break

                        # 打印模型原始响应用于调试
                        action_preview = response.action[:200] if len(response.action) > 200 else response.action
                        print(f"[{client_id}] 第 {iteration+1} 轮响应: action={action_preview}")

                        # 检查是否是 finish 指令（导航完成）
                        if "finish(message=" in response.action or "finish(instruction=" in response.action:
                            final_instruction = _extract_finish_message(response.action)
                            print(f"[{client_id}] 导航指令: {final_instruction}")
                            break

                        # 检查是否是工具调用
                        if "do(action=" in response.action:
                            action_data = _extract_action_data(response.action)
                            print(f"[{client_id}] 解析到工具调用: {action_data}")

                            if not action_data or "action" not in action_data:
                                # 解析失败，模型输出格式不对
                                print(f"[{client_id}] 警告：无法解析工具调用，格式不符合预期")
                                # 将模型的原始响应用户化后返回
                                final_instruction = _simplify_navigation_response(response.action, user_task)
                                break

                            # 保存 action 名称供后续使用
                            action = action_data.get("action", "")

                            tool_result = await _execute_tool(action_data, session, amap_client)

                            # 将工具结果添加到上下文
                            session.context.append(MessageBuilder.create_assistant_message(response.action))
                            session.context.append(MessageBuilder.create_user_message(f"工具执行结果: {tool_result}"))
                            print(f"[{client_id}] 工具执行结果: {tool_result}")
                            print(f"[{client_id}] ===== 继续下一轮 =====")

                            # 优化：如果是 search_nearby 成功，直接调用 plan_route 而不是再次询问模型
                            if action == "search_nearby" and "找到" in tool_result and "请使用 plan_route" in tool_result:
                                print(f"[{client_id}] 自动规划路线中...")
                                # 提取目的地坐标和名称
                                import re
                                coord_match = re.search(r'在 ([\d.]+),([\d.]+) 找到 (.+?)。', tool_result)
                                if coord_match:
                                    lon, lat, name = coord_match.groups()
                                    dest_coords = {"lon": float(lon), "lat": float(lat)}
                                    # 规划路线
                                    route_result = await _execute_tool(
                                        {"action": "plan_route", "destination": name, "coords": dest_coords},
                                        session, amap_client
                                    )
                                    print(f"[{client_id}] 路线规划结果: {route_result}")
                                    # 添加到上下文并结束
                                    session.context.append(MessageBuilder.create_assistant_message(f"do(action=\"plan_route\", destination=\"{name}\")"))
                                    session.context.append(MessageBuilder.create_user_message(f"工具执行结果: {route_result}"))
                                    final_instruction = route_result
                                    print(f"[{client_id}] 导航完成，最终指令: {final_instruction}")
                                    break
                        else:
                            # 没有工具调用，检查响应内容
                            print(f"[{client_id}] 响应不包含工具调用格式")
                            if response.action and len(response.action.strip()) > 0:
                                # 模型返回了内容但不是工具调用格式
                                print(f"[{client_id}] 模型未按工具格式输出，使用简化响应")
                                final_instruction = _simplify_navigation_response(response.action, user_task)
                            else:
                                final_instruction = "正在为您规划路线..."
                            print(f"[{client_id}] 结束迭代，最终指令: {final_instruction}")
                            break

                    # 检查是否达到最大迭代次数
                    if iteration == max_iterations - 1:
                        print(f"[{client_id}] 警告：达到最大迭代次数 ({max_iterations})，强制结束")
                        if "正在准备导航" == final_instruction:
                            final_instruction = f"正在尝试为您{user_task}，请稍候"
                except Exception as e:
                    print(f"[{client_id}] 导航初始化异常: {e}")
                    traceback.print_exc()  # 打印完整堆栈跟踪
                    final_instruction = f"导航初始化遇到问题，请重试。错误: {str(e)[:50]}"

                await websocket.send_json({
                    "status": "success",
                    "type": "route_planned",
                    "instruction": final_instruction,
                    "user_task": user_task
                })

            elif req_type == "location_update":
                # 如果导航暂停，忽略位置更新
                if session.is_paused_state():
                    await websocket.send_json({
                        "status": "paused",
                        "type": "navigation_paused",
                        "message": "导航已暂停，位置更新被忽略"
                    })
                    continue

                origin = data.get("origin", {})
                sensor_data = data.get("sensor_data", {})

                session.update_location(origin, sensor_data)

                # 检测偏离
                if session.is_off_route():
                    await websocket.send_json({
                        "status": "warning",
                        "type": "off_route",
                        "instruction": "您偏离了路线，正在重新规划"
                    })
                    # 重新规划
                    session.init_navigation(session.destination, origin, sensor_data)
                    response = model_client.request(session.context)
                    await websocket.send_json({
                        "status": "success",
                        "type": "route_recalculated",
                        "instruction": _extract_instruction(response)
                    })
                else:
                    # 正常更新，返回下一步指令
                    response = model_client.request(session.context)
                    await websocket.send_json({
                        "status": "success",
                        "type": "location_update",
                        "instruction": _extract_instruction(response),
                        "remaining_distance": session._get_distance_to_next_step(origin)
                    })

            elif req_type == "cancel":
                manager.disconnect(client_id)
                await websocket.send_json({"status": "success", "message": "Navigation cancelled"})

            elif req_type == "pause":
                session.pause()
                await websocket.send_json({
                    "status": "success",
                    "type": "paused",
                    "message": "导航已暂停"
                })

            elif req_type == "resume":
                session.resume()
                # 恢复时重新获取当前位置并生成指令
                origin = data.get("origin", session.current_location or {})
                sensor_data = data.get("sensor_data", {"heading": session.user_heading})

                session.update_location(origin, sensor_data)
                response = model_client.request(session.context)
                await websocket.send_json({
                    "status": "success",
                    "type": "resumed",
                    "instruction": _extract_instruction(response),
                    "message": "导航已恢复"
                })

    except WebSocketDisconnect:
        manager.disconnect(client_id)
    except Exception as e:
        print(f"Navigation WebSocket Error: {e}")
        traceback.print_exc()
        try:
            await websocket.send_json({"status": "error", "message": str(e)})
        except:
            pass


def _extract_instruction(response) -> str:
    """从模型响应中提取导航指令"""
    if hasattr(response, 'action'):
        action_text = response.action
        # 尝试解析JSON格式
        try:
            data = json.loads(action_text)
            if "instruction" in data:
                return data["instruction"]
        except:
            pass
        # 返回原始文本
        return action_text[:200]  # 限制长度
    return "继续前进"


def _extract_finish_message(action_text: str) -> str:
    """从 finish(message=...) 中提取消息"""
    try:
        # finish(message="向前走100米")
        if "finish(message=" in action_text:
            msg_part = action_text.split("finish(message=", 1)[1]
            # 找到结尾的 )
            if ")" in msg_part:
                msg = msg_part.split(")", 1)[0]
                # 去掉引号
                msg = msg.strip('"').strip("'")
                return msg
        return action_text[:200]
    except:
        return action_text[:200]


def _extract_action_data(action_text: str) -> dict:
    """从 do(action="...") 中提取动作数据"""
    try:
        # do(action="Call_Navigation", destination="肯德基")
        if 'do(action=' in action_text:
            action_part = action_text.split('do(action=', 1)[1]
            # 解析类似 JSON 的格式
            import re
            # 提取 action 名称
            action_match = re.search(r'"([^"]+)"', action_part)
            if action_match:
                action_name = action_match.group(1)
                result = {"action": action_name}

                # 提取其他参数
                param_matches = re.findall(r'(\w+)="([^"]+)"', action_part)
                for key, value in param_matches:
                    result[key] = value

                return result
        return {}
    except:
        return {}


def _convert_to_relative_direction(instruction: str, user_heading: float) -> str:
    """
    将绝对方向转换为相对方向（基于用户当前朝向）

    Args:
        instruction: 原始指令（包含东南西北等方向）
        user_heading: 用户当前朝向（度，0=北，90=东，180=南，270=西）

    Returns:
        转换为相对方向的指令
    """
    # 定义方向映射
    direction_map = {
        "向东": (90, "向右"),
        "向西": (270, "向左"),
        "向南": (180, "向后"),
        "向北": (0, "向前"),
        "东南": (135, "向右后方"),
        "西南": (225, "向左后方"),
        "东北": (45, "向右前方"),
        "西北": (315, "向左前方"),
    }

    result = instruction
    for abs_dir, (angle, rel_dir) in direction_map.items():
        if abs_dir in instruction:
            # 计算相对方向
            angle_diff = (angle - user_heading + 360) % 360

            # 根据角度差确定相对方向
            if angle_diff < 22.5 or angle_diff >= 337.5:
                relative = "向前"
            elif angle_diff < 67.5:
                relative = "向右前方"
            elif angle_diff < 112.5:
                relative = "向右"
            elif angle_diff < 157.5:
                relative = "向右后方"
            elif angle_diff < 202.5:
                relative = "向后"
            elif angle_diff < 247.5:
                relative = "向左后方"
            elif angle_diff < 292.5:
                relative = "向左"
            else:
                relative = "向左前方"

            result = instruction.replace(abs_dir, relative)
            break

    return result


async def _execute_tool(action_data: dict, session: "NavigationSession", amap_client) -> str:
    """执行导航工具调用"""
    action = action_data.get("action", "")
    print(f"[_execute_tool] 执行工具: action={action}, data={action_data}")

    if action == "search_nearby":
        # 搜索附近的地点
        keyword = action_data.get("keyword", "") or action_data.get("text", "")
        print(f"[_execute_tool] search_nearby: keyword='{keyword}'")
        if not keyword and session.user_task:
            # 从用户任务中提取关键词
            keyword = session.user_task.replace("带我去", "").replace("最近的", "").replace("请", "").strip()
            print(f"[_execute_tool] 从用户任务提取关键词: '{keyword}'")

        if session.current_location:
            print(f"[_execute_tool] 搜索位置: {session.current_location}")
            results = amap_client.search_nearby(keyword, session.current_location)
            print(f"[_execute_tool] 搜索结果数量: {len(results) if results else 0}")
            if results:
                # 返回最近的结果
                nearest = results[0]
                name = nearest.get("name", "未知地点")
                distance = nearest.get("distance")
                location = nearest.get("location", {})

                # 构建更清晰的工具结果格式
                if distance:
                    result = f"在 {location.get('lon', '')},{location.get('lat', '')} 找到 {name}，距离 {distance} 米。请使用 plan_route 规划路线。"
                else:
                    result = f"在 {location.get('lon', '')},{location.get('lat', '')} 找到 {name}。请使用 plan_route 规划路线。"
                print(f"[_execute_tool] 返回结果: {result}")
                return result
            else:
                result = f"未找到附近的 {keyword}"
                print(f"[_execute_tool] 返回结果: {result}")
                return result
        return "无法搜索，缺少当前位置"

    elif action == "Call_Navigation" or action == "plan_route":
        # 规划路线
        destination = action_data.get("destination", "")
        dest_coords = action_data.get("coords")  # 预计算的坐标

        print(f"[_execute_tool] plan_route: destination='{destination}', coords={dest_coords}")

        if session.current_location:
            # 如果没有预计算坐标，搜索目的地
            if not dest_coords:
                search_results = amap_client.search_nearby(destination, session.current_location, radius=5000)
                if search_results:
                    dest_coords = search_results[0].get("location", {})

            if dest_coords:
                print(f"[_execute_tool] 目标坐标: {dest_coords}")
                # 规划路线 - 使用 plan_walking_route 方法
                route = amap_client.plan_walking_route(
                    origin=session.current_location,
                    destination=dest_coords
                )
                if route:
                    # 保存路线信息
                    session.planned_route = route
                    session.destination = destination

                    # 提取第一步指令并转换为相对方向
                    if route.steps:
                        first_step = route.steps[0]
                        instruction = first_step.instruction

                        # 将绝对方向转换为相对方向（基于用户朝向）
                        relative_instruction = _convert_to_relative_direction(
                            instruction, session.user_heading
                        )

                        # 计算步数（约0.65米/步）
                        distance = first_step.distance
                        steps_count = int(distance / 0.65) if distance > 0 else 0

                        result = f"请{relative_instruction}，约{steps_count}步后{first_step.action}。"
                        print(f"[_execute_tool] 返回结果: {result}")
                        return result
                    return "路线规划成功"
        return "无法规划路线，缺少位置信息"

    else:
        return f"未知工具: {action}"


def _simplify_navigation_response(model_action: str, user_task: str) -> str:
    """将模型的非标准格式响应简化为用户友好的导航指令"""
    try:
        # 尝试解析是否是 JSON 格式
        if model_action.strip().startswith("[{"):
            import json
            try:
                data = json.loads(model_action)
                if isinstance(data, list) and len(data) > 0:
                    first_item = data[0]
                    if isinstance(first_item, dict):
                        # 尝试提取有用信息
                        if "text" in first_item:
                            return first_item["text"][:100]
                        if "type" in first_item and "instruction" in first_item:
                            return first_item["instruction"][:100]
            except:
                pass

        # 清理响应中的特殊字符和控制字符
        cleaned = model_action
        # 移除常见的控制字符和多余空白
        for char in ["\n", "\r", "\t", "[", "]", "{", "}", "(", ")"]:
            cleaned = cleaned.replace(char, " ")
        # 压缩多个空格
        import re
        cleaned = re.sub(r"\s+", " ", cleaned).strip()

        # 限制长度
        if len(cleaned) > 150:
            cleaned = cleaned[:150] + "..."

        # 如果响应为空或太短，返回基于用户任务的默认指令
        if len(cleaned) < 10:
            return f"正在为您{user_task}，请稍候"

        return cleaned
    except Exception as e:
        print(f"简化导航响应时出错: {e}")
        return f"正在为您{user_task}"


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8080)
