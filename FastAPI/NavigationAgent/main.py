"""
导航 Agent 服务

使用 qwen2-1.5B-Instruct 模型作为导航 agent，通过 WebSocket 提供导航服务。
"""

import os
import sys
import traceback
from typing import Dict, Optional

# 将脚本所在目录添加到 sys.path，确保能正确导入同级模块
_script_dir = os.path.dirname(os.path.abspath(__file__))
if _script_dir not in sys.path:
    sys.path.insert(0, _script_dir)

import uvicorn
from fastapi import FastAPI, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware

from navigation_agent import NavigationAgent, SensorData


# 配置
MODEL_BASE_URL = os.getenv("MODEL_BASE_URL", "http://localhost:8001/v1")
MODEL_NAME = os.getenv("MODEL_NAME", "Qwen/Qwen2-1.5B-Instruct")
NAVIGATION_PORT = int(os.getenv("NAVIGATION_PORT", 8081))

# 创建 FastAPI 应用
app = FastAPI(title="Navigation Agent Service")

# 添加 CORS 中间件
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


# ============================================================
# 连接管理
# ============================================================

class ConnectionManager:
    """WebSocket 连接管理器"""

    def __init__(self):
        self.active_connections: Dict[str, WebSocket] = {}
        self.navigation_agents: Dict[str, NavigationAgent] = {}

    async def connect(self, websocket: WebSocket, client_id: str):
        """接受新连接"""
        # 检查是否已有相同 client_id 的连接
        if client_id in self.active_connections:
            print(f"[{client_id}] 检测到重复连接，关闭旧连接")
            old_websocket = self.active_connections[client_id]
            try:
                await old_websocket.close()
            except:
                pass
            if client_id in self.navigation_agents:
                del self.navigation_agents[client_id]

        await websocket.accept()
        self.active_connections[client_id] = websocket

        # 创建导航 Agent
        self.navigation_agents[client_id] = NavigationAgent(
            model_base_url=MODEL_BASE_URL,
            model_name=MODEL_NAME,
        )

        print(f"[{client_id}] 导航服务连接成功")

    def disconnect(self, client_id: str):
        """断开连接"""
        if client_id in self.active_connections:
            del self.active_connections[client_id]
        if client_id in self.navigation_agents:
            del self.navigation_agents[client_id]
        print(f"[{client_id}] 导航服务连接断开")

    def get_agent(self, client_id: str) -> Optional[NavigationAgent]:
        """获取导航 Agent"""
        return self.navigation_agents.get(client_id)


manager = ConnectionManager()


# ============================================================
# HTTP 端点
# ============================================================

@app.get("/health")
async def health_check():
    """健康检查"""
    return {"status": "healthy", "service": "navigation-agent"}


# ============================================================
# WebSocket 端点
# ============================================================

@app.websocket("/ws/navigation/{client_id}")
async def navigation_websocket_endpoint(websocket: WebSocket, client_id: str):
    """
    导航 WebSocket 端点

    支持的消息类型:
    - init: 初始化导航
    - location_update: 更新 GPS 位置
    - cancel: 取消导航

    消息格式:
    {
        "type": "init",
        "user_task": "带我去最近的肯德基",
        "origin": {"lon": 116.39, "lat": 39.90},
        "sensor_data": {"heading": 45, "accuracy": 10}
    }
    """
    await manager.connect(websocket, client_id)
    agent = manager.get_agent(client_id)

    try:
        while True:
            data = await websocket.receive_json()
            req_type = data.get("type")

            print(f"[{client_id}] 收到消息: type={req_type}")

            # 初始化导航
            if req_type == "init":
                user_task = data.get("user_task")
                if not user_task:
                    await websocket.send_json({
                        "status": "error",
                        "message": "Missing user_task"
                    })
                    continue

                origin = data.get("origin", {})
                sensor_data_dict = data.get("sensor_data", {})

                # 设置高德 API Key
                amap_api_key = data.get("amap_api_key")
                if amap_api_key:
                    agent.amap_client.api_key = amap_api_key
                    agent.amap_client.session.params = {"key": amap_api_key}
                    print(f"[{client_id}] 高德 API Key 已更新")

                # 构造传感器数据
                sensor_data = SensorData(
                    heading=sensor_data_dict.get("heading", 0.0),
                    accuracy=sensor_data_dict.get("accuracy", 0.0),
                    pitch=sensor_data_dict.get("pitch", 0.0),
                    roll=sensor_data_dict.get("roll", 0.0),
                )

                print(f"[{client_id}] 导航初始化: {user_task}")
                print(f"[{client_id}] 位置: {origin}")
                print(f"[{client_id}] 传感器: heading={sensor_data.heading}")

                try:
                    result = await agent.initialize_navigation(
                        user_task=user_task,
                        origin=origin,
                        sensor_data=sensor_data,
                    )
                    await websocket.send_json(result)
                    print(f"[{client_id}] 导航初始化成功: {result.get('instruction')}")
                except Exception as e:
                    print(f"[{client_id}] 导航初始化失败: {e}")
                    traceback.print_exc()
                    await websocket.send_json({
                        "status": "error",
                        "message": f"导航初始化失败: {str(e)}"
                    })

            # 位置更新
            elif req_type == "location_update":
                origin = data.get("origin", {})
                sensor_data_dict = data.get("sensor_data", {})

                sensor_data = SensorData(
                    heading=sensor_data_dict.get("heading", 0.0),
                    accuracy=sensor_data_dict.get("accuracy", 0.0),
                )

                try:
                    result = await agent.update_location(
                        location=origin,
                        sensor_data=sensor_data,
                    )
                    await websocket.send_json(result)
                except Exception as e:
                    print(f"[{client_id}] 位置更新失败: {e}")
                    traceback.print_exc()

            # 取消导航
            elif req_type == "cancel":
                result = agent.cancel_navigation()
                await websocket.send_json(result)
                manager.disconnect(client_id)
                break

            else:
                await websocket.send_json({
                    "status": "error",
                    "message": f"Unknown message type: {req_type}"
                })

    except WebSocketDisconnect:
        manager.disconnect(client_id)
    except Exception as e:
        print(f"[{client_id}] WebSocket 错误: {e}")
        traceback.print_exc()
        manager.disconnect(client_id)


# ============================================================
# 主入口
# ============================================================

if __name__ == "__main__":
    print("=" * 60)
    print("  导航 Agent 服务启动")
    print("=" * 60)
    print(f"  模型服务: {MODEL_BASE_URL}")
    print(f"  模型名称: {MODEL_NAME}")
    print(f"  服务端口: {NAVIGATION_PORT}")
    print("=" * 60)

    uvicorn.run(app, host="0.0.0.0", port=NAVIGATION_PORT)
