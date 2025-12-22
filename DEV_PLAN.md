## BlindAssist 项目后续开发规划与构建说明

本文档说明本项目后续的开发内容、推荐开发顺序、需要补充的逻辑代码以及如何构建可安装的 Android App 与启动后端服务。

---

### 一、整体开发阶段与优先级

推荐按以下阶段迭代，每一阶段都能产出一个可运行的版本：

1. **基础可用版本（MVP）**
   - 语音问答打通（语音输入 → 文本 → `/api/qa/ask` → 文本回答 → 语音播报）。
   - 功能轮盘基本交互（可以切换到“问答模式”并听到语音反馈）。

2. **视觉功能版本**
   - 图像文字识别（OCR）：对准药品/说明书 → 拍照 → `/api/vision/ocr` → 读出文字。
   - 场景描述：对准前方 → 拍照 → `/api/vision/scene` → 读出环境描述。

3. **导航与避障联动版本**
   - 导航：语音说明目的地 → `/api/voice/command` 分类为导航 → `/api/navigation/route` → 播报路线步骤。
   - 避障：开启摄像头视频流 + WebSocket `/ws/obstacle` → 实时语音避障提示。

4. **无障碍与体验优化版本**
   - 网络异常/权限拒绝/无法理解指令等场景的语音反馈。
   - 多轮问答会话（带 sessionId）、导航进度播报、模式切换手势优化。

5. **部署与上线版本**
   - Android APK 签名打包。
   - 后端打包为独立 JAR，在本机或云服务器上长期运行。

---

### 二、Android 客户端需要补充的逻辑

当前 Android 客户端位于 `app` 模块，核心类包括：
- `MainActivity`：负责权限申请与初始化。
- `VoiceManager`：语音输入/输出接口（待实现）。
- `ImageCaptureManager`：摄像头采集接口（待实现）。
- `NetworkClient`：与后端 HTTP / WebSocket 通信（待实现）。
- `FeatureRouter`：根据功能类型调度各业务流程。

#### 2.1 语音模块 `VoiceManager`

**现状**：只定义了 `init` / `startListening` / `stopListening` / `speakText` 方法，未实现具体逻辑。

**后续需要补充：**

- **ASR（语音转文本）**
  - 方案 A：使用第三方 SDK（讯飞、Google Speech 等）。
  - 方案 B：本地录音后通过 `NetworkClient` 上传到服务端，由服务端调用大模型或云 ASR。
  - 实现建议：
    - 在 `startListening(VoiceResultCallback callback)` 中：
      - 开启录音（`AudioRecord` 或 SDK 提供的接口）。
      - 本地识别：监听识别回调，将结果通过 `callback.onResult(text)` 返回。
      - 云端识别：将音频缓冲编码（如 PCM/OPUS），调用后端识别接口，拿到文本后调用 `callback.onResult(text)`。
    - 在 `stopListening()` 中停止录音并释放资源。

- **TTS（文本转语音）**
  - 可直接使用 Android 自带 `TextToSpeech`。
  - 在 `init(Context)` 中初始化 TTS 引擎并设置中文语言。
  - 在 `speakText(String text)` 中：
    - 调用 `textToSpeech.speak(text, ...)`。
    - 对多条文本进行简单队列管理，避免抢读。

**开发顺序建议：**
1. 先实现 TTS（本地 `TextToSpeech`），以便在所有模式中播放语音提示。
2. 再实现最简 ASR（哪怕是本地录音后模拟返回固定文本），让主流程可以跑通。
3. 最后替换为真实 ASR 实现。

#### 2.2 图像采集模块 `ImageCaptureManager`

**现状**：`captureSingleFrame` / `startVideoStream` / `stopVideoStream` 是空实现。

**后续需要补充：**

- **CameraX 初始化**
  - 在 `init(Activity activity)` 中：
    - 初始化 CameraX，用隐藏的预览或小预览 Surface/Texture 进行绑定。

- **单帧采集 `captureSingleFrame(ImageFrameCallback callback)`**
  - 使用 CameraX `ImageCapture`：
    - 打开相机后调用 `takePicture`。
    - 在回调里将 `ImageProxy` 转换为 JPEG `byte[]`。
    - 调用 `callback.onFrame(imageBytes)` 返回到上层。
  - 上层根据场景调用：
    - OCR：`NetworkClient.uploadImageFrame(bytes, "ocr", ...)`。
    - 场景描述：`NetworkClient.uploadImageFrame(bytes, "scene_description", ...)`。

- **视频流采集 `startVideoStream(VideoStreamListener listener)`**
  - 使用 CameraX `ImageAnalysis`：
    - 每隔固定时间（如 200ms）从 `ImageProxy` 里抓取一帧。
    - 将图像缩放/压缩成小分辨率 JPEG `byte[]`。
    - 通过 `NetworkClient` 的 WebSocket 客户端发送帧数据到 `/ws/obstacle`。
  - 在 `stopVideoStream()` 中停止分析器和关闭 WebSocket 连接。

**开发顺序建议：**
1. 优先实现 `captureSingleFrame`，打通 OCR 与场景描述接口。
2. 再逐步实现 `startVideoStream`，从低帧率开始，关注性能与带宽。

#### 2.3 网络模块 `NetworkClient`

**现状**：已定义接口方法，内部尚未实现。

**后续需要补充：**

- **HTTP 请求实现**
  - 推荐引入 `Retrofit` + `OkHttp` + `Gson`，在 `init(Context)` 中初始化。
  - 实现以下方法：
    - `sendVoiceText(String text, NetworkCallback<String> callback)`：
      - POST `/api/voice/command`，Body: `{ "text": text }`。
      - 将响应 JSON 中的 `feature` / `detail` 或原始 JSON 返回给上层。
    - `requestNavigation(NavigationRequest request, NetworkCallback<NavigationResult> callback)`：
      - POST `/api/navigation/route`，Body 对应 `NavigationRequest`。
      - 将 `voiceSteps` 解析为 `NavigationResult`。
    - `askQuestion(String question, NetworkCallback<String> callback)`：
      - POST `/api/qa/ask`，Body: `{ "question": question, "sessionId": 当前会话ID }`。
    - `uploadImageFrame(byte[] imageData, String sceneType, NetworkCallback<String> callback)`：
      - 若 `sceneType == "obstacle_avoidance"`：可选择直接走 WebSocket，不通过此接口。
      - 若 `sceneType == "ocr"`：POST `/api/vision/ocr`，Content-Type: `application/octet-stream`。
      - 若 `sceneType == "scene_description"`：POST `/api/vision/scene`。
  - 对错误和超时调用 `callback.onError(e)`，由上层统一用 TTS 进行友好提示。

- **WebSocket 避障接口**
  - 在 `NetworkClient` 中增加：
    - `connectObstacleWebSocket(Listener listener)`：建立到 `/ws/obstacle` 的连接。
    - `sendObstacleFrame(byte[] frame)`：发送二进制帧。
    - `closeObstacleWebSocket()`：关闭连接。
  - `Listener` 提供：
    - `onConnected()`、`onInstruction(String json)`、`onError(Throwable)` 等回调。
  - 使用 OkHttp 的 `WebSocket` 实现。

#### 2.4 功能路由 `FeatureRouter`

**现状**：有枚举和基本分类逻辑，`triggerFeature` 中各 case 尚未填充。

**后续需要补充：**

- **语音入口与意图识别主流程**
  - 新增方法如 `startVoiceEntry()`：
    - 用 TTS 提示“请说出您需要的帮助，比如导航、避障、读文字或问问题。”
    - 调用 `VoiceManager.startListening` 获取文本。
    - 优先调用 `NetworkClient.sendVoiceText` 让后端分类；
    - 如网络不可用或失败，退回到 `classifyFeatureLocally`。
    - 获取 feature 后调用 `triggerFeature(feature)`。

- **各功能模式的业务流程实现**
  - `NAVIGATION`：
    - 若后端返回的 detail 中已经包含目的地信息，则直接请求路线；
    - 否则先问一次“请告诉我要去哪里”，再次识别后再调用导航接口；
    - 拿到 `voiceSteps` 后，用 TTS 逐条播报，并在播报过程中启动避障。
  - `OBSTACLE_AVOIDANCE`：
    - 调用 `ImageCaptureManager.startVideoStream` 和 `NetworkClient.connectObstacleWebSocket`；
    - 将服务端返回的 JSON 指令通过 `VoiceManager.speakText` 即时播报。
  - `QA_VOICE`：
    - 进入循环会话：录音 → `/api/qa/ask` → 播报回答；
    - 将 `sessionId` 在客户端保存下来，多轮带上。
  - `OCR`：
    - 提示用户对准需要读取的文字；
    - 调用 `captureSingleFrame` → `/api/vision/ocr` → 播报文字。
  - `SCENE_DESCRIPTION`：
    - 提示用户对准前方场景；
    - 调用 `captureSingleFrame` → `/api/vision/scene` → 播报描述。

#### 2.5 功能轮盘 UI

**现状**：`activity_main.xml` 中仅有 `FrameLayout` 容器。

**后续需要补充：**

- 创建一个自定义 View 或 Fragment（例如 `FeatureWheelView`）：
  - 放置在 `feature_wheel_container` 中。
  - 内部维护一个功能列表：导航 / 避障 / 问答 / OCR / 场景描述。
  - 通过单指上下/左右滑动改变当前选中索引。
  - 每次选中变化时通过 `VoiceManager.speakText("导航")` 等播报。
  - 通过双击或长按当前区域确认选择，调用 `FeatureRouter.triggerFeature`。

- 无障碍支持：
  - 为 View 设置合适的 `contentDescription`。
  - 保持操作逻辑简单，不依赖复杂多指手势。

---

### 三、后端需要补充的逻辑

后端工程位于 `server` 目录，为 Spring Boot 项目。

当前已实现的接口包括：
- `/api/voice/command`：语音意图分类（规则实现）。
- `/api/navigation/route`：示例导航步骤。
- `/api/qa/ask`：示例问答。
- `/api/vision/ocr`：示例 OCR。
- `/api/vision/scene`：示例场景描述。
- WebSocket `/ws/obstacle`：示例避障指令。

#### 3.1 大模型与外部服务接入

- 在 `IntentClassificationService` 中：
  - 将规则改为调用大模型：
    - 向大模型发送用户文本和可用功能列表。
    - 让大模型返回结构化 JSON（feature + detail）。
    - 解析 JSON 并构造 `VoiceCommandResponse`。

- 在 `QaService` 中：
  - 使用大模型 API 处理问答：
    - 利用 `sessionId` 管理对话上下文（可以使用内存 Map 或 Redis）。
    - 对大模型的回复做一次“易懂化”处理（简化长句）。

- 在 `NavigationService` 中：
  - 对接高德地图 MCP 或 AutoGLM：
    - 使用 MCP 调用高德路线规划接口；
    - 或让 AutoGLM 控制手机上的地图 App（如果运行在同一设备环境）。
    - 将路线结果转换为简洁的语音步骤列表返回。

- 在 `VisionService` 中：
  - OCR：
    - 接入 OCR 引擎，例如 PaddleOCR、本地部署服务或云接口。
  - 场景描述：
    - 使用多模态大模型，输入图像获得文本描述。

#### 3.2 避障 WebSocket 实现

在 `ObstacleWebSocketHandler` 中：
- 将示例的 `sendFakeInstruction` 替换为真实视觉推理：
  - 收到二进制图像帧后放入线程池处理，避免阻塞 WebSocket I/O 线程。
  - 模型识别障碍物位置、距离、方向以及红绿灯状态等。
  - 组装简洁的 JSON 指令通过 `session.sendMessage` 返回。
- 考虑多客户端并发时对 `frameCounter` 和状态的隔离，可通过 session 属性或专用对象管理。

---

### 四、如何构建 Android APK

#### 4.1 调试版 APK

1. 在项目根目录（包含 `app` 的那一级）打开终端：

   ```bash
   # Windows PowerShell
   .\gradlew.bat assembleDebug

   # 或类 Unix 系统
   ./gradlew assembleDebug
   ```

2. 构建成功后，调试 APK 位于：
   - `app/build/outputs/apk/debug/app-debug.apk`

3. 使用 ADB 安装到真机：

   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. 在手机上找到应用（名称如 `BlindAssist`）并运行。

#### 4.2 发布版 APK

1. 在 Android Studio 中使用 `Build > Generate Signed Bundle / APK` 创建签名密钥。
2. 配置 `release` 签名并生成 `app-release.apk` 或 `.aab`。
3. 使用该包进行内测或发布。

> 提示：客户端需要在 `NetworkClient` 中配置服务端的基础地址（IP/端口或域名），建议集中管理，后期方便切换环境。

---

### 五、如何启动后端服务

后端工程路径：`server`

#### 5.1 本地开发运行

前提：已安装 JDK 17+ 和 Maven。

1. 进入后端目录：

   ```bash
   cd server
   ```

2. 使用 Maven 运行 Spring Boot：

   ```bash
   mvn spring-boot:run
   ```

3. 启动成功后：
   - HTTP: `http://localhost:8080`
   - WebSocket: `ws://localhost:8080/ws/obstacle`

4. 在 Android 客户端中，将服务端地址设置为：
   - 若真机与电脑在同一局域网：`http://<电脑局域网IP>:8080`
   - 不要使用 `localhost` 或 `127.0.0.1`（对真机来说是手机自身）。

#### 5.2 打包为可部署 JAR

1. 在 `server` 目录打包：

   ```bash
   mvn clean package
   ```

2. 打包完成后，JAR 一般位于：
   - `server/target/blindassist-server-0.0.1-SNAPSHOT.jar`（具体名称视版本而定）。

3. 在目标服务器运行：

   ```bash
   java -jar blindassist-server-0.0.1-SNAPSHOT.jar
   ```

4. 可选：在服务器前面加 Nginx 等反向代理，启用 HTTPS，便于在公网和生产环境中使用。

---

### 六、推荐的近期开发步骤（Checklist）

1. Android 端：
   - [ ] 在 `VoiceManager` 中实现 TTS（`TextToSpeech`）。
   - [ ] 使用占位或简单方案实现 ASR，让问答闭环可以跑通。
   - [ ] 在 `NetworkClient` 中实现 `/api/qa/ask` 的调用逻辑。
   - [ ] 在 `FeatureRouter` 中实现 `QA_VOICE` 流程。
2. Android 视觉 & 网络：
   - [ ] 在 `ImageCaptureManager` 中使用 CameraX 实现 `captureSingleFrame`。
   - [ ] 在 `NetworkClient` 中实现 `/api/vision/ocr` 和 `/api/vision/scene`。
   - [ ] 在 `FeatureRouter` 中实现 OCR 和场景描述逻辑。
3. 导航与避障：
   - [ ] 在 `NetworkClient` 中实现 `/api/navigation/route` 调用。
   - [ ] 在 `NetworkClient` 中实现 WebSocket 客户端，连接 `/ws/obstacle`。
   - [ ] 在 `ImageCaptureManager` 中实现 `startVideoStream` / `stopVideoStream` 并接到 WebSocket。
4. 服务端：
   - [ ] 为意图分类、问答、导航、视觉接入实际大模型/MCP 或第三方服务。
   - [ ] 完善避障 WebSocket 中的图像推理逻辑。
5. 打包与部署：
   - [ ] 按本文件说明构建调试 APK 并在真机上测试。
   - [ ] 打包后端 JAR 并在目标服务器上启动。


