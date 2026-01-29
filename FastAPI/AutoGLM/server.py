# server.py
import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from typing import List, Dict, Any, Optional
import traceback
import os

# 导入 Open-AutoGLM 的核心组件
from phone_agent.model import ModelClient, ModelConfig
from phone_agent.model.client import MessageBuilder
from phone_agent.actions.handler import parse_action
from phone_agent.config import get_system_prompt

app = FastAPI()

# 1. 配置模型
model_config = ModelConfig(
    base_url=os.getenv("MODEL_BASE_URL", "http://localhost:8002/v1"),
    model_name=os.getenv("MODEL_NAME", "/data/lilele/AutoGLM/models/ZhipuAI/AutoGLM-Phone-9B"),
    temperature=0.1,
)
model_client = ModelClient(model_config)

# 系统提示词
SYSTEM_PROMPT = get_system_prompt("cn")


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
                f"{thinking}\n\n<answer>{action_str}</answer>"
            )
        )


class ConnectionManager:
    def __init__(self):
        self.active_connections: Dict[str, WebSocket] = {}
        self.phone_sessions: Dict[str, AgentSession] = {}

    async def connect(self, websocket: WebSocket, client_id: str):
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

        await websocket.accept()
        self.active_connections[client_id] = websocket
        self.phone_sessions[client_id] = AgentSession(client_id)

        print(f"Client connected: {client_id} (mode: phone)")

    def disconnect(self, client_id: str):
        if client_id in self.active_connections:
            del self.active_connections[client_id]
        if client_id in self.phone_sessions:
            del self.phone_sessions[client_id]
        print(f"Client disconnected: {client_id}")

    def get_phone_session(self, client_id: str) -> Optional[AgentSession]:
        return self.phone_sessions.get(client_id)


manager = ConnectionManager()


@app.websocket("/ws/agent/{client_id}")
async def agent_websocket_endpoint(websocket: WebSocket, client_id: str):
    """手机操控 WebSocket 端点"""
    await manager.connect(websocket, client_id)
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


if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=8080)
