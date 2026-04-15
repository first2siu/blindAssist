# BlindAssist — AI Agent 工作区指南

视障辅助智能导航系统，三层架构：Android 客户端 → Spring Boot 后端 → FastAPI AI 微服务集群。

## Architecture

```
Android (Java, API 24+)  ──HTTP/WS──►  Spring Boot 8090 (Java 17, Maven)
                                           │
                         ┌─────────────────┼─────────────────┐
                         ▼                 ▼                 ▼
               IntentClassifier:8006  NavigationAgent:8081  ObstacleDetection:8004
               AutoGLM:8080          (各自调用 vLLM GPU 推理引擎)
                         │
                         ▼
                   Redis 6379 (TTS 优先队列)
```

- **Android → Spring Boot**：REST (`/api/*`) + WebSocket (`/ws/agent`, `/ws/obstacle`)
- **Spring Boot → FastAPI**：HTTP + WebSocket (`/ws/navigation/{client_id}`)
- **FastAPI → vLLM**：OpenAI 兼容 API（端口 8001/8002/8003）
- **TTS 队列**：Redis Sorted Set，4 级优先级 (CRITICAL > HIGH > NORMAL > LOW)

## Code Style

- **Android**：Java 11，包名 `com.example.test_android_dev`，标准 Android 命名约定。参考 [app/src/main/java/com/example/test_android_dev/](app/src/main/java/com/example/test_android_dev/)
- **Spring Boot**：Java 17，包名 `com.blindassist.server`，使用 Lombok。参考 [server/src/main/](server/src/main/)
- **FastAPI**：Python 3.10+，Pydantic 模型，使用 OpenAI SDK 对接 vLLM。参考各 `FastAPI/*/main.py`
- **文档与注释**：中文为主

## Build and Test

```powershell
# === Android ===
.\gradlew.bat assembleDebug                    # 构建 Debug APK
.\gradlew.bat test                              # 单元测试
.\gradlew.bat connectedAndroidTest              # 设备测试
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb reverse tcp:8090 tcp:8090                   # USB 端口转发

# === Spring Boot ===
cd server
mvn spring-boot:run                             # 运行（端口 8090）
mvn clean package                               # 打包

# === FastAPI 全部服务 ===
python FastAPI/manage_services.py start          # 启动全部
python FastAPI/manage_services.py stop           # 停止全部
python FastAPI/manage_services.py status         # 查看状态

# === AutoGLM 代码质量 ===
cd FastAPI/AutoGLM && pytest tests/ && black phone_agent/ && ruff check phone_agent/
```

## Project Conventions

- **三层代理路由**：Android 采集语音/图像 → Spring Boot 路由编排 → FastAPI 执行 AI 推理，不要在 Android 端直接调用 FastAPI
- **双路径意图分类**：规则匹配（置信度 > 0.8 快速路径）→ LLM 兜底，见 `IntentClassifier/`
- **多引擎 ASR 降级链**：Vosk 离线 → 系统 ASR → 讯飞云端，见 `app/.../asr/`
- **导航指令友好化**：米→步数（÷0.65），绝对方向→相对方向，见 `NavigationAgent/instruction_builder.py`
- **消息打断/恢复**：避障消息可打断导航播报，之后恢复，通过 `/api/tts/interrupt` 和 `/api/tts/resume`
- **后台保活**：前台服务 + WakeLock + 心跳重连 + 指数退避，见 `app/.../service/`
- **服务统一管理**：`FastAPI/manage_services.py` 管理所有 vLLM 和 FastAPI 进程的启停
- **AutoGLM 子项目**有独立的 `CLAUDE.md`，遵循其中的约定

## Integration Points

| 服务 | 端口 | 模型 |
|------|------|------|
| vLLM Qwen2-1.5B | 8001 | 意图分类 + 导航 |
| vLLM AutoGLM-9B | 8002 | 手机 UI 自动化 |
| vLLM Qwen2-VL-7B | 8003 | 避障视觉检测 |
| IntentClassifier | 8006 | — |
| AutoGLM FastAPI | 8080 | — |
| NavigationAgent | 8081 | + 高德地图 API |
| ObstacleDetection | 8004 | — |
| Spring Boot | 8090 | — |
| Redis | 6379 | TTS 优先队列 |

外部依赖：**高德地图 API**（路线规划/POI/地理编码）、**讯飞语音 API**（可选在线 ASR）

## Security

- API Key 配置在 `server/src/main/resources/application.properties`（高德 `amap.api-key`），勿提交实际密钥
- 讯飞凭证在 `Config.java`（`XUNFEI_APP_ID/API_KEY/API_SECRET`），是占位符需填入
- vLLM 本地部署使用 `API_KEY="EMPTY"`，FastAPI 服务无认证（仅内网）
- `local.properties` 含本机 SDK 路径，已 gitignore
- 当前开发模式允许 cleartext HTTP 和 `CORS *`，生产需关闭
