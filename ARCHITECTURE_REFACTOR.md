# 后端架构重构方案：导航服务分离与优化

## 一、概述

### 1.1 重构目标

将导航服务从 AutoGLM 中分离，使用轻量级 qwen2-1.5B-Instruct 模型作为导航 agent，通过 prompt 工程实现盲人友好化指令转换。

### 1.2 架构变化

```
【重构前】
Android → Spring Boot → FastAPI/AutoGLM (9B 模型) → AmapClient

【重构后】
Android → Spring Boot → FastAPI/NavigationAgent (qwen2-1.5B) → AmapClient
                      → FastAPI/AutoGLM (9B 模型，仅手机控制)
```

---

## 二、新建导航 Agent 服务

### 2.1 服务结构

```
FastAPI/NavigationAgent/
├── main.py              # 服务入口，WebSocket 端点
├── navigation_agent.py  # 导航 Agent 核心逻辑
├── prompts/
│   ├── __init__.py
│   ├── base.py          # 基础系统 prompt
│   ├── route_planning.py # 路线规划场景 prompt
│   ├── navigation.py     # 实时导航场景 prompt
│   └── arrival.py       # 到达确认场景 prompt
├── instruction_builder.py # 盲人友好化指令构建器
└── requirements.txt
```

### 2.2 WebSocket 端点定义

**端点**: `/ws/navigation/{client_id}`

**消息格式**:

```python
# 客户端 → 服务端
{
    "type": "init",              # 初始化导航
    "user_task": "带我去最近的肯德基",
    "origin": {"lon": 108.65, "lat": 34.24},
    "sensor_data": {
        "heading": 45.0,         # 朝向（度）
        "accuracy": 10.0         # GPS 精度
    }
}

{
    "type": "location_update",   # 位置更新
    "origin": {"lon": 108.66, "lat": 34.25},
    "sensor_data": {
        "heading": 50.0,
        "accuracy": 8.0
    }
}

{
    "type": "cancel"             # 取消导航
}

# 服务端 → 客户端
{
    "status": "success",
    "type": "route_planned",
    "instruction": "请向右前方走约300步后左转",
    "waypoints": [...]           # 可选：路径关键点
}

{
    "status": "success",
    "type": "navigation_update",
    "instruction": "继续向前走约150步",
    "distance_to_next": 100     # 距离下一步的距离（米）
}

{
    "status": "warning",
    "type": "off_route",
    "instruction": "您偏离了路线，正在重新规划"
}
```

### 2.3 模型调用

复用 IntentClassifier 的模型调用方式：

```python
from openai import OpenAI

client = OpenAI(
    base_url="http://localhost:8001/v1",
    api_key="EMPTY"
)

response = client.chat.completions.create(
    model="Qwen/Qwen2-1.5B-Instruct",
    messages=[
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt}
    ],
    temperature=0.1,
    max_tokens=500
)
```

---

## 三、Prompt 工程设计

### 3.1 场景化 Prompt 策略

| 场景 | Prompt 文件 | 功能 |
|------|-------------|------|
| 路线规划 | `route_planning.py` | 解析用户目的地，调用高德 API，生成初始指令 |
| 实时导航 | `navigation.py` | 根据位置和传感器数据，生成下一步指令 |
| 到达确认 | `arrival.py` | 判断是否到达目的地 |
| 异常处理 | `exception.py` | 处理偏离路线、GPS 异常等情况 |

### 3.2 传感器数据嵌入

**Prompt 模板示例**:

```python
def build_navigation_prompt(
    user_task: str,
    current_location: dict,
    sensor_data: dict,
    next_instruction: str,
    remaining_distance: int
) -> str:
    """构建实时导航场景的 prompt"""

    return f"""你是视障人士的导航助手。请根据当前情况生成简洁、友好的语音指令。

## 用户目标
{user_task}

## 当前状态
- 位置: {current_location['lon']}, {current_location['lat']}
- 朝向: {sensor_data['heading']}度 (0=北, 90=东, 180=南, 270=西)
- 距离下一步: {remaining_distance}米

## 原始导航指令
{next_instruction}

## 要求
1. 将绝对方向（东南西北）转换为相对方向（左前右后）
2. 距离以步数为单位（约0.65米/步）
3. 使用简洁明了的语言
4. 重要信息放在前面
5. 如果用户接近目标（<50米），提示"即将到达"

## 输出格式（直接输出指令文本，不要其他说明）：
"""
```

### 3.3 动态 Prompt 选择

```python
class PromptSelector:
    """根据场景选择合适的 prompt"""

    @staticmethod
    def select_prompt(context: NavigationContext) -> str:
        if context.is_initial:
            return prompts.route_planning()
        elif context.is_off_route:
            return prompts.exception("off_route")
        elif context.is_near_destination:
            return prompts.arrival()
        else:
            return prompts.navigation()
```

---

## 四、高德地图集成

### 4.1 复用现有 AmapClient

直接从 AutoGLM 复用 `phone_agent/amap/client.py`：

```python
from phone_agent.amap.client import AmapClient

amap_client = AmapClient(api_key=amap_api_key)

# 搜索附近 POI
results = amap_client.search_nearby(
    keywords="肯德基",
    location={"lon": 108.65, "lat": 34.24},
    radius=1000
)

# 规划步行路线
route = amap_client.plan_walking_route(
    origin={"lon": 108.65, "lat": 34.24},
    destination={"lon": 108.77, "lat": 34.21}
)
```

### 4.2 可选：MCP 协议封装

如果未来需要标准化，可以将 AmapClient 封装为 MCP 协议：

```python
# FastAPI/mcp/amap_server.py
from mcp.server.models import Resource, Tool

@mcp.tool()
async def search_nearby_poi(
    keywords: str,
    location: tuple[float, float],
    radius: int = 1000
) -> list[dict]:
    """搜索附近的兴趣点"""
    ...

@mcp.tool()
async def plan_walking_route(
    origin: tuple[float, float],
    destination: tuple[float, float]
) -> NavigationRoute:
    """规划步行路线"""
    ...
```

---

## 五、AutoGLM 服务清理

### 5.1 需要删除的内容

| 文件/组件 | 删除内容 |
|-----------|----------|
| `server.py` | `NavigationSession` 类 |
| `server.py` | `/ws/navigation/{client_id}` 端点 |
| `server.py` | `NAVIGATION_SYSTEM_PROMPT` 变量 |
| `server.py` | `_convert_to_relative_direction()` 函数 |
| `server.py` | `_execute_tool()` 中的导航逻辑 |
| `server.py` | `ConnectionManager` 中的 `navigation_sessions` |

### 5.2 需要保留的内容

| 文件/组件 | 保留内容 |
|-----------|----------|
| `server.py` | `AgentSession` 类（手机控制） |
| `server.py` | `/ws/agent/{client_id}` 端点 |
| `server.py` | `SYSTEM_PROMPT` 变量 |
| `phone_agent/` | 全部保留（手机操控核心） |
| `phone_agent/amap/` | **保留**（被 NavigationAgent 复用） |

### 5.3 清理后的 server.py 结构

```python
# 保留的核心组件
from phone_agent.model import ModelClient
from phone_agent.actions.handler import parse_action
from phone_agent.config import get_system_prompt

# 保留的端点
@app.websocket("/ws/agent/{client_id}")
async def agent_websocket_endpoint(...):
    """手机操控 WebSocket 端点（保留）"""
    ...

# 删除的端点
# @app.websocket("/ws/navigation/{client_id}")  # 删除
```

---

## 六、Spring Boot 端修改

### 6.1 NavigationAgentService 修改

```java
// 修改连接地址
private static final String NAVIGATION_WS_URL =
    "ws://10.184.17.161:8081/ws/navigation/";  // 新端口

// 消息格式保持不变，但增加传感器数据
NavigationRequest request = NavigationRequest.builder()
    .type("init")
    .userTask(userTask)
    .origin(gpsInfo)
    .sensorData(SensorData.builder()
        .heading(locationInfo.getHeading())
        .accuracy(locationInfo.getAccuracy())
        .build())
    .amapApiKey(amapApiKey)
    .build();
```

### 6.2 传感器数据接口

Android 端需要提供获取传感器数据的接口：

```java
// NavigationManager.java
public SensorData getCurrentSensorData() {
    return SensorData.builder()
        .heading(locationHelper.getHeading())
        .pitch(locationHelper.getPitch())
        .roll(locationHelper.getRoll())
        .accuracy(locationInfo.getAccuracy())
        .timestamp(System.currentTimeMillis())
        .build();
}
```

---

## 七、实施步骤

### 阶段一：新建 NavigationAgent 服务（优先）

1. 创建 `FastAPI/NavigationAgent/` 目录结构
2. 实现 `navigation_agent.py` 核心逻辑
3. 实现 prompts 模块（4 个场景）
4. 实现 `instruction_builder.py`
5. 创建 `main.py`，启动 WebSocket 服务（端口 8081）

### 阶段二：Spring Boot 端适配

1. 修改 `NavigationAgentService` 连接地址
2. 更新消息格式，增加传感器数据
3. 测试端到端通信

### 阶段三：AutoGLM 清理

1. 删除 `server.py` 中的导航相关代码
2. 验证手机控制功能不受影响
3. 移除 `phone_agent/amap/` 中的导航依赖（如果有）

### 阶段四：测试与优化

1. 端到端测试
2. Prompt 优化迭代
3. 性能测试和调优

---

## 八、风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| qwen2-1.5B 模型能力不足 | 导航理解不准确 | 充分的 prompt 工程和测试 |
| 传感器数据延迟 | 指令不准确 | 客户端缓存+服务端超时处理 |
| 高德 API 调用失败 | 无法规划路线 | 降级方案：返回错误提示 |
| WebSocket 连接不稳定 | 导航中断 | 重连机制+本地状态缓存 |

---

## 九、文件变更清单

### 新增文件

| 路径 | 说明 |
|------|------|
| `FastAPI/NavigationAgent/main.py` | 导航服务入口 |
| `FastAPI/NavigationAgent/navigation_agent.py` | 导航 Agent 核心 |
| `FastAPI/NavigationAgent/prompts/__init__.py` | Prompt 模块 |
| `FastAPI/NavigationAgent/prompts/base.py` | 基础 prompt |
| `FastAPI/NavigationAgent/prompts/route_planning.py` | 路线规划 prompt |
| `FastAPI/NavigationAgent/prompts/navigation.py` | 实时导航 prompt |
| `FastAPI/NavigationAgent/prompts/arrival.py` | 到达确认 prompt |
| `FastAPI/NavigationAgent/prompts/exception.py` | 异常处理 prompt |
| `FastAPI/NavigationAgent/instruction_builder.py` | 指令构建器 |
| `FastAPI/NavigationAgent/requirements.txt` | 依赖清单 |

### 修改文件

| 路径 | 变更说明 |
|------|----------|
| `FastAPI/AutoGLM/server.py` | 删除导航相关代码（约 400 行） |
| `server/.../NavigationAgentService.java` | 修改连接地址和消息格式 |

### 保留文件

| 路径 | 说明 |
|------|------|
| `FastAPI/AutoGLM/phone_agent/amap/client.py` | 被 NavigationAgent 复用 |

---

## 十、预期收益

| 指标 | 重构前 | 重构后 | 改善 |
|------|--------|--------|------|
| 模型大小 | 9B | 1.5B | ↓ 83% |
| 推理延迟 | ~4s | ~1s | ↓ 75% |
| 服务耦合 | 高 | 低 | 解耦 |
| 代码可维护性 | 中 | 高 | ↑ |
| 盲人指令准确性 | 固定规则 | 动态生成 | ↑ |
