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

当前 Android 客户端位于 `app` 模块，核心类包括（括号中为当前完成度）：
- `MainActivity`：负责权限申请与初始化（**已实现基础版本**）。
- `VoiceManager`：语音输出 TTS 已基于 `TextToSpeech` 实现，ASR 仍为占位（**TTS 已完成，ASR 待接入**）。
- `ImageCaptureManager`：摄像头采集接口（**方法已定义，内部仍为 TODO**）。
- `NetworkClient`：基于 OkHttp 的 HTTP / WebSocket 通信（**主体已实现，但 BASE_URL 为占位且未解析真实响应内容**）。
- `FeatureRouter`：根据功能类型调度各业务流程（**各模式主流程已实现，为示例/假数据版**）。
- `FeatureWheelView`：自定义功能轮盘 View，支持滑动选择与双击确认（**已实现**）。

#### 2.1 语音模块 `VoiceManager`

**现状**：
- 已在 `VoiceManager` 中使用 `TextToSpeech` 完成中文 TTS（`speak` / `speakImmediate` 可用）。
- `startListening` 仍为 TODO，当前只预留了回调接口 `VoiceCallback`。

**后续需要补充：**

- **ASR（语音转文本）接入**
  - 方案 A：使用 Android 原生 `SpeechRecognizer`，本地在线识别。
  - 方案 B：使用第三方 SDK（如讯飞/百度/阿里等）。
  - 方案 C：录音后上传到后端，由后端调用大模型或云 ASR。
  - 实现建议（以 A / B 为例）：
    - 在 `startListening(VoiceCallback callback)` 中：
      - 初始化并启动 ASR 会话；
      - 在识别结束或部分结果可用时，将文本通过 `callback.onResult(text)` 返回；
      - 对错误情况进行区分（网络问题/说话时间过短等），以便上层用 TTS 做不同提示。
    - 在类中增加必要的释放逻辑（如 `release()`），在应用退出时销毁 ASR/TTS 资源。

**开发顺序建议：**
1. 保持现有 TTS 实现不变，优先在 `startListening` 中接入一个可用的 ASR（可以先用最简单实现）。
2. 等整体流程稳定后，再根据实际 ASR 行为微调 `FeatureRouter` 中各模式的语音提示与超时处理。

#### 2.2 图像采集模块 `ImageCaptureManager`

**现状**：`captureCurrentFrame` / `captureHighResFrame` 等方法已定义但返回空数组，还未真正集成 CameraX/Camera2。

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

**现状**：已使用 OkHttp 实现了大部分 HTTP/WebSocket 封装逻辑：
- `sendVoiceCommand` → POST `/api/voice/command`
- `requestNavigation` → POST `/api/navigation/route`
- `askQuestion` → POST `/api/qa/ask`
- `uploadVisionRequest` → POST `/api/vision/ocr|scene`
- `openObstacleWebSocket` / `sendFrameViaWS` → WebSocket `/ws/obstacle`

**仍需补充和完善：**

- **配置化 BASE_URL / WS_URL**
  - 当前常量 `BASE_URL = "http://your-backend-api.com"` 和 `WS_URL` 为占位。
  - 建议：
    - 抽取到单独的配置类或 `BuildConfig` 字段，方便按环境（本机/测试/生产）切换；
    - 在文档中说明如何配置为局域网 IP。

- **响应解析与错误处理**
  - 现在的实现直接把 OkHttp `Callback` 暴露给上层，`FeatureRouter` 中仍用固定文案（例如“这是为您找到的答案。”）。
  - 建议：
    - 引入 `Gson` 或 `Moshi`，在 `NetworkClient` 内解析 JSON，提供更高层次的结果对象（如 `VoiceCommandResult`、`QaResult` 等）；
    - 统一处理 HTTP 错误（非 200）和网络异常，并转换为易懂的错误类型，供上层用 TTS 提示（如“服务器繁忙，请稍后再试”）。

- **WebSocket 连接状态管理**
  - 增加 `closeObstacleWebSocket()`，并在应用退出或模式切换时调用，避免泄漏连接；
  - 为避障 WebSocket 增加重连/心跳逻辑（可视需要实现）。

#### 2.4 功能路由 `FeatureRouter`

**现状**：
- 已经实现了一个 `route(FeatureType feature)` 方法，根据枚举调用：
  - `startNavigationFlow` / `startObstacleAvoidance` / `startQAFlow` / `startOCRFlow` / `startSceneDescriptionFlow`。
- 各方法中已经调用 `VoiceManager` 和 `NetworkClient`，形成了完整的业务链路，但目前多为“示例/假数据”：
  - 导航固定请求 0 坐标，直接播报“第一步：向前直行”；
  - 避障对 WebSocket 返回只做固定提示；
  - QA 固定回答“这是为您找到的答案。”；
  - OCR/场景描述直接读出示例文本，而非解析真实响应。

**后续需要补充与增强：**

- **从真实响应中解析内容**
  - 在 `onResponse` 中通过 `response.body().string()` 获取 JSON，并用 `Gson`/`Moshi` 解析：
    - 导航：解析 `voiceSteps` 数组，按顺序逐条播报，而不是写死一句话；
    - 问答：解析 `answer` 字段，而非固定字符串；
    - OCR：解析 `text` 字段；
    - 场景描述：解析 `description` 字段；
    - 避障 WebSocket：解析 JSON 中的 `message` 或结构化字段，决定播报内容。

- **与 ASR 的结合与容错**
  - 当前 `startNavigationFlow` / `startQAFlow` 等中调用 `startListening` 后，假设一定拿到文本；
  - 在接入真实 ASR 后，需要针对“识别失败/空文本/用户长时间不说话”等场景增加重试和提示逻辑。

- **模式切换与资源回收**
  - 进入新模式时，考虑是否需要关闭上一个模式的定时任务/避障流等（例如退出导航时关闭 `obstacleExecutor` 和 WebSocket）。

#### 2.5 功能轮盘 UI

**现状**：
- 已添加 `FeatureWheelView` 自定义 View：
  - 单指上下滑动切换功能（维护 `FeatureType` 数组与当前索引）；
  - 双击确认当前功能，通过监听器回调。
- 可通过设置 `OnFeatureSelectedListener` 在每次滑动时调用 `VoiceManager.speak` 播报功能名，并在确认时调用 `FeatureRouter.route(feature)`。

**后续可以优化的点：**

- 在 `MainActivity` 中完成：
  - 将 `FeatureWheelView` 添加到 `feature_wheel_container`；
  - 设置 `OnFeatureSelectedListener`，在 `onItemSelected` 时播报当前功能名称，在 `onItemConfirmed` 时调用 `FeatureRouter.route`。
- 加强无障碍支持：
  - 为 View 设置 `contentDescription` 和合适的焦点策略，使 TalkBack 用户也能通过手势访问；
  - 根据用户偏好调整滑动灵敏度（`distanceY` 阈值）和双击识别时间窗口。

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

4. 可选：在服务器前面加 Nginx 等反向代理，启用 HTTPS，便于在公网 and 生产环境中使用。

---

### 六、推荐的近期开发步骤（Checklist）

1. Android 端：
   - [x] 在 `VoiceManager` 中实现 TTS（`TextToSpeech`）。
   - [ ] 在 `VoiceManager` 中接入实际 ASR，让各模式“听得懂”用户语音。
   - [x] 在 `NetworkClient` 中实现基础 HTTP/WebSocket 调用逻辑。
   - [x] 在 `FeatureRouter` 中实现导航/避障/问答/OCR/场景描述的主流程骨架。
   - [ ] 在 `FeatureRouter` 中解析真实后端响应内容，替换掉目前的示例/固定文案。
   - [ ] 在 `MainActivity` 中完成 `FeatureWheelView` 与 `FeatureRouter`/`VoiceManager` 的联动（滑动播报 + 双击确认）。
2. Android 视觉 & 网络：
   - [ ] 在 `ImageCaptureManager` 中使用 CameraX 实现 `captureSingleFrame`。
   - [ ] 在 `NetworkClient` 中实现 `/api/vision/ocr` 和 `/api/vision/scene`。
   - [ ] 在 `FeatureRouter` 中对 OCR 和场景描述使用真实响应文本进行播报。
3. 导航与避障：
   - [x] 在 `NetworkClient` 中实现 `/api/navigation/route` 调用和避障 WebSocket 客户端骨架。
   - [ ] 在 `FeatureRouter` 中解析导航结果（`voiceSteps`）并逐步播报，而非固定文案。
   - [ ] 在 `ImageCaptureManager` 中实现 `startVideoStream` / `stopVideoStream` 并与 WebSocket 发送逻辑打通。
4. 服务端：
   - [ ] 为意图分类、问答、导航、视觉接入实际大模型/MCP 或第三方服务。
   - [ ] 完善避障 WebSocket 中的图像推理逻辑。
5. 打包与部署：
   - [ ] 按本文件说明构建调试 APK 并在真机上测试。
   - [ ] 打包后端 JAR 并在目标服务器上启动。


---

## 更新日志

### 12-19修改

本次更新主要围绕 **提升用户体验和完善核心交互** 进行。

**主要变更内容：**

1.  **功能轮盘-视觉与交互升级:**
    *   **界面可视化**: 为原先不可见的功能轮盘增加了视觉反馈，现在屏幕会居中显示当前功能的 **图标和名称**。
    *   **新增图标资源**: 添加了5个简约风格的矢量图标，分别对应各项功能。
    *   **优化滑动体验**: 降低滑动切换的灵敏度，并优化了播报逻辑，现在只在用户 **手指离开屏幕时** 播报最终选定的功能，避免了滑动过程中连续、嘈杂的提示音。

2.  **语音系统-健壮性与时序问题修复:**
    *   **修复“说听冲突”**: 解决了在进入问答等功能时，提示音（“请提问”）和语音识别启动过近导致的冲突，确保提示音能完整地播出。
    *   **修复启动时序问题**: 完美解决了App刚启动时，“欢迎语”和“初始功能名称”播报顺序错乱或丢失的问题，确保了稳定、有序的启动体验。
    *   **增强语音管理器**: 对 `VoiceManager` 进行了底层升级，使其能够可靠地处理TTS引擎初始化期间的各种播报请求，确保任何语音指令（包括其回调任务）都不会丢失。

3.  **核心功能打通:**
    *   **实现语音识别**: 对接了 Android 原生的语音识别服务，应用现在可以“听懂”用户的语音指令。
    *   **打通问答流程**: 实现了完整的“语音提问 -> 网络请求 -> 解析答案 -> 语音播报”的闭环流程。

### 12-24修改

**机型兼容性优化:**

1.  **TTS 多引擎适配**: 针对无谷歌服务框架（Google Play Services）的国产手机（华为、小米等）进行了专项适配。`VoiceManager` 现在会自动检测并优先调用系统内置的 TTS 引擎（如华为移动服务、小米内置语音等），确保在所有 Android 手机上均能发出声音。
2.  **语言支持增强**: 增加了语言可用性检查逻辑，若当前引擎不支持中文，将自动回退至英文播报或在日志中详细记录错误，避免初始化失败导致的完全无声。
3.  **构建验证**: 已验证可通过 Gradle 成功构建 APK，并支持全机型安装。

### 12-29修改

feat: 集成 AutoGLM 智能体与无障碍服务以实现 UI 自动化

本次提交引入了 AI 智能体 (AutoGLM) 控制设备 UI 的核心框架。它添加了一个用于执行动作的无障碍服务、一个用于连接后端的 WebSocket 通信层，以及一个用于启动任务的测试 UI。

#### 核心架构：

- **Android 客户端**:
    - **`AutoGLMService`**: 一个 `AccessibilityService`（无障碍服务），用于根据智能体的指令执行底层 UI 操作，如点击 (`Tap`)、滑动 (`Swipe`)、输入 (`Type`)、启动应用 (`Launch App`) 等。它包含了手势创建和应用发现的逻辑。
    - **`AgentManager`**: 管理与 Spring Boot 服务器的 WebSocket 连接，编排控制循环（截屏 -> 发送到服务器 -> 接收动作 -> 执行动作），并处理坐标转换。
    - **`AccessibilityScreenshotManager`**: 一个新的工具类，利用无障碍服务静默截屏（适用于 Android 11+）。
    - **`AppRegistry`**: 一个静态映射表，用于快速将常用应用名称解析为包名，提高“启动”动作的可靠性。
- **Spring Boot 服务端 (`server/`)**:
    - 充当 Android 应用与 Python AI 模型之间的**桥梁**。
    - **`AgentWebSocketHandler`**: 通过 WebSocket 接收来自 Android 应用的截图和任务数据。
    - **`AgentService`**: 通过为每个应用会话建立单独的 WebSocket 连接，将数据转发给 Python 模型服务。
    - **`PythonAgentClient`**: 用于与 Python 后端通信的 WebSocket 客户端。

#### 主要变更：

- **Android 应用 (`app/`)**:
    - **无障碍**: 添加了 `AutoGLMService` 及其配置 (`accessibility_service_config.xml`) 以启用 UI 控制。
    - **权限**: 添加了 `QUERY_ALL_PACKAGES` 权限，以允许搜索已安装的应用。
    - **UI**: 在 `MainActivity` 中用新的测试界面替换了之前的 `FeatureWheelView`，用于输入文本指令和启动/停止智能体。
    - **通信**: 实现了 `AgentManager` 以处理 WebSocket 生命周期以及与服务器的消息协议。
    - **依赖**: 添加了 `gson` 用于 JSON 处理。
- **Java 服务端 (`server/`)**:
    - **WebSocket**: 配置了 WebSocket 端点 (`/ws/agent`) 以与 Android 客户端通信。增加了消息缓冲区大小以处理大型截图数据。
    - **智能体逻辑**: 实现了 `AgentService` 以管理智能体任务的生命周期，充当 Python 后端的代理。
    - **依赖**: 添加了 `Java-WebSocket` 用于连接 Python 客户端，以及 `lombok` 用于减少样板代码。
    - **配置**: 在 `application.properties` 中将服务器端口设置为 `8090`。
#### ⭐网络与端口配置指南 (Network Configuration)

本项目包含三层架构：Android 客户端、Spring Boot 中转服务、Python AutoGLM 模型服务。由于开发环境存在网络隔离（如校园网 AP 隔离），请严格按照以下步骤配置 IP 和端口。

#### 1. 服务端配置 (Spring Boot → Python Model)
Spring Boot 服务需要连接远程或局域网内的 AutoGLM 模型服务。

* **配置文件路径**: `server/src/main/java/com/blindassist/server/service/AgentService.java`
* **修改参数**: `PYTHON_SERVER_BASE_URI`
* **配置操作**:
  将 IP 地址修改为 AutoGLM 模型实际运行的服务器 IP（保持端口 `8080` 不变）。
    ```java
    private static final String PYTHON_SERVER_BASE_URI = "ws://your_model_ip:8080/ws/agent/";
    ```


#### 2. 客户端配置 (Android App → Spring Boot)
Android 应用需要连接本地运行的 Spring Boot 服务。根据网络环境不同，分为两种配置方案。

* **配置文件路径**: `app/src/main/java/com/example/test_android_dev/manager/AgentManager.java`
* **修改参数**: `SERVER_URL`

**方案 A：有线 USB 调试模式（当前环境 / AP 隔离）**
*适用场景：校园网或公司内网开启了 AP 隔离，手机与电脑虽在同一 Wi-Fi 但无法互相 Ping 通。*

1.  **修改代码**：
    将地址设置为 `localhost`。在 USB 连接模式下，手机将通过反向代理访问电脑的端口。
    ```java
    private static final String SERVER_URL = "ws://localhost:8090/ws/agent";
    ```
2.  **建立端口映射**（⚠️ **每次连接 USB 必须执行**）：
    在电脑终端运行以下 ADB 命令，将手机的 8090 端口请求转发到电脑的 8090 端口：
    ```bash
    adb reverse tcp:8090 tcp:8090
    ```

**方案 B：标准局域网模式**
*适用场景：手机与电脑连接同一 Wi-Fi，且网络互通。*

1.  **修改代码**：
    将 `localhost` 替换为运行 Spring Boot 的电脑的局域网 IPv4 地址。
    ```java
    // 示例：假设电脑 IP 为 192.168.1.100
    private static final String SERVER_URL = "ws://192.168.1.100:8090/ws/agent";
    ```
2.  **注意事项**：确保电脑防火墙已允许 8090 端口的入站连接。


### 2026-01-04 修改（早期）

feat: 合并前端语音交互界面与后端AutoGLM控制逻辑

本次更新将前端开发的语音交互UI与后端开发的AutoGLM控制逻辑进行了整合，实现了完整的"按住说话→语音识别→发送指令→AI控制手机"的闭环流程。

#### 主要变更：

1. **双界面模式支持**：
   - **用户模式**（默认）：简洁的"按住说话"语音交互界面，适合最终用户使用
   - **调试模式**：手动输入指令的测试界面，通过 `Config.DEBUG_MODE` 开关控制
   - 两套界面共用同一套后端逻辑（AgentManager、AutoGLMService）

2. **语音识别(ASR)完善**：
   - 完整实现了 `VoiceManager.startListening()` 方法
   - 支持中文语音识别（使用 Android 原生 SpeechRecognizer）
   - 增加了语音识别不可用时的文本输入备选方案

3. **配置开关统一管理**：
   - 新增 `Config.java` 配置类
   - `MOCK_MODE`：控制是否使用模拟网络响应
   - `DEBUG_MODE`：控制显示用户界面还是开发测试界面

4. **UI资源整合**：
   - 合并了前端的按钮样式资源（`btn_speak_material.xml` 等）
   - 合并了按钮动画资源（`button_press.xml`、`button_release.xml`）
   - 布局文件支持两套界面的切换显示

5. **工具类新增**：
   - `AudioRecorderManager.java`：音频录制管理器（PCM格式，16kHz采样率）
   - `JsonUtils.java`：JSON转Map工具类
   - `ScreenshotManager.java`：截图管理器

#### 使用说明：

- **切换到调试模式**：将 `Config.DEBUG_MODE` 设置为 `true`，重新编译即可显示手动输入界面
- **切换到用户模式**：将 `Config.DEBUG_MODE` 设置为 `false`（默认），显示语音交互界面

#### 合并说明：

本次合并保留了后端开发的所有核心文件：
- `service/AutoGLMService.java`：无障碍服务，真正执行手机操作
- `manager/AgentManager.java`：WebSocket通信与任务调度
- `manager/AccessibilityScreenshotManager.java`：无障碍截图
- `utils/AppRegistry.java`：App名称到包名的映射表
- `App.java`：全局Application类

前端PR中的stub版本 `AutoGLMService.java` 未被采用，因为后端版本包含完整的手势执行逻辑。


### 2026-01-05 修改

feat: 重构ASR多引擎架构并修复按住说话交互逻辑

本次更新全面重构了语音识别模块，实现了多引擎自动切换架构，并彻底修复了"按住说话"按钮的交互问题，确保在各种网络环境和手机型号上都能正常工作。

#### 核心变更：

**1. 多引擎ASR架构重构**
- 新增 `asr/` 包，实现统一的ASR引擎接口和管理器：
  - `AsrEngine.java`：ASR引擎抽象接口，定义标准化的识别流程
  - `AsrManager.java`：多引擎管理器，自动选择和切换可用引擎
  - `VoskAsrEngine.java`：Vosk离线语音识别引擎（完全离线，无需网络）
  - `SystemAsrEngine.java`：系统原生语音识别引擎（需要网络连接）
  - `XunfeiAsrEngine.java`：讯飞WebSocket流式语音识别引擎（需配置API凭证）

**2. 引擎优先级与自动降级**
- 优先级顺序：Vosk离线识别 → 系统ASR → 讯飞ASR
- 网络错误时自动尝试备用引擎
- 支持运行时动态切换引擎

**3. Vosk离线语音识别集成**
- 集成 `vosk-android:0.3.47` 依赖
- 支持完全离线的中文语音识别（无需网络，保护隐私）
- 智能检测多种模型目录结构（支持直接解压或嵌套目录）
- 模型异步加载机制，首次使用时自动等待（最多5秒）
- 模型文件存在性检查，确保引擎可用性

**4. 按住说话交互完全重构**
- **修复核心问题**：
  - 松开按钮后仍持续监听 → 现在松开时正确调用 `stopListening()`
  - 快速点击导致"识别器繁忙" → 添加500ms防抖机制
  - 识别中仍可点击导致状态混乱 → 添加 `isRecognizing` 状态锁
  - TTS语音累积播放 → 使用 `speakImmediate()` 清空队列
  
- **新增状态管理**：
  - `isButtonPressed`：按钮是否被按下（正在录音）
  - `isRecognizing`：是否正在识别（已停止录音，等待结果）
  - 识别超时机制（10秒），防止无限等待

- **交互流程优化**：
  ```
  按下 → 开始录音 → 显示"正在听..."
  松开 → 停止录音 → 显示"识别中..." → 等待结果
  结果 → 重置状态 → 显示"按住说话"
  ```

**5. 系统ASR增强**
- 添加详细的音频接收日志：
  - `onBufferReceived`：确认收到音频数据
  - `onRmsChanged`：实时音量监控（用于诊断麦克风）
  - `onBeginningOfSpeech`：检测到用户开始说话
  - `onEndOfSpeech`：检测到用户停止说话
- 添加5秒识别超时机制
- 改进网络错误提示和处理
- 防止"识别器繁忙"错误（最小重启间隔800ms）

**6. 讯飞ASR WebSocket实现**
- 完整的WebSocket流式识别实现
- 支持实时部分结果（`onPartialResult`）
- 动态修正模式（`wpgs`）支持
- 完善的鉴权机制（HmacSHA256签名）
- 自动音频录制和帧发送（40ms/帧）

**7. VoiceManager重构**
- 从直接使用 `SpeechRecognizer` 改为使用 `AsrManager`
- 新增方法：
  - `configureXunfeiAsr()`：配置讯飞API凭证
  - `getCurrentAsrEngineName()`：获取当前使用的引擎名称
  - `isAsrAvailable()`：检查ASR是否可用
  - `cancelListening()`：取消语音监听
- 改进错误处理和状态管理

**8. 配置项新增**
- `Config.java` 中添加讯飞ASR配置：
  ```java
  public static final String XUNFEI_APP_ID = "";
  public static final String XUNFEI_API_KEY = "";
  public static final String XUNFEI_API_SECRET = "";
  ```

#### 使用指南：

**Vosk离线模型配置（推荐）：**
1. 下载中文模型：https://alphacephei.com/vosk/models
   - 推荐：`vosk-model-small-cn-0.22`（约50MB）
2. 将模型放置到以下任一位置：
   - `app/src/main/assets/model-cn/vosk-model-small-cn-0.22/`
   - `app/src/main/assets/model-cn/`（解压后内容直接放入）
   - `app/src/main/assets/vosk-model-small-cn-0.22/`

**讯飞ASR配置（可选，作为兜底）：**
1. 注册讯飞开放平台：https://www.xfyun.cn/
2. 创建应用并获取凭证
3. 在 `Config.java` 中填入凭证

**系统ASR（自动可用）：**
- 国产手机（小米、华为、OPPO等）自带语音服务
- 需要网络连接
- 无需额外配置

#### 技术亮点：

- **多引擎架构**：统一接口，易于扩展新引擎
- **自动降级**：网络故障时自动切换到离线引擎
- **状态机管理**：严格的状态转换，避免竞态条件
- **防抖机制**：防止快速点击导致的错误
- **异步加载**：模型加载不阻塞UI线程
- **详细日志**：完整的调试信息，便于问题诊断

#### 已知问题修复：

- ✅ 松开按钮后仍持续监听
- ✅ 快速点击导致"识别器繁忙"
- ✅ 识别中仍可点击导致状态混乱
- ✅ TTS语音累积播放
- ✅ 系统ASR需要网络但无提示
- ✅ 麦克风权限正常但无法识别
- ✅ 模型加载时机不当导致引擎不可用

     - `VoskAsrEngine.java`：Vosk离线语音识别（无需网络）
     - `SystemAsrEngine.java`：系统原生语音识别（需要网络）
     - `XunfeiAsrEngine.java`：讯飞WebSocket流式语音识别（需配置API凭证）
   - 引擎优先级：Vosk离线 → 系统ASR → 讯飞ASR

2. **Vosk离线语音识别支持**：
   - 集成 `vosk-android:0.3.47` 依赖
   - 支持完全离线的中文语音识别
   - 自动检测多种模型目录结构
   - 模型异步加载，首次使用时自动等待

3. **按住说话交互修复**：
   - 修复松开按钮后仍持续监听的问题
   - 添加 `isButtonPressed` 和 `isProcessingVoice` 状态管理
   - 在 `ACTION_UP` 时正确调用 `stopListening()`
   - 添加500ms防抖机制，防止快速点击导致的"识别器繁忙"错误
   - 使用 `speakImmediate()` 防止TTS语音累积播放

4. **系统ASR增强**：
   - 添加详细的音频接收日志（`onBufferReceived`、`onRmsChanged`）
   - 添加5秒识别超时机制
   - 改进网络错误提示

5. **配置项新增**：
   - `Config.java` 中添加讯飞ASR配置项（`XUNFEI_APP_ID`、`XUNFEI_API_KEY`、`XUNFEI_API_SECRET`）

#### Vosk离线模型配置：

1. 下载中文模型：https://alphacephei.com/vosk/models （推荐 `vosk-model-small-cn-0.22`，约50MB）
2. 将模型放置到以下任一位置：
   - `app/src/main/assets/model-cn/vosk-model-small-cn-0.22/`
   - `app/src/main/assets/model-cn/`（解压后内容直接放入）

#### 讯飞ASR配置（可选）：

在 `Config.java` 中填入讯飞开放平台凭证：
```java
public static final String XUNFEI_APP_ID = "你的APPID";
public static final String XUNFEI_API_KEY = "你的APIKey";
public static final String XUNFEI_API_SECRET = "你的APISecret";
```

---


### 2026-01-08 修改

feat: 实现后台保活功能，解决跨应用操作时通信中断问题

本次更新解决了AutoGLM在执行跨应用操作（如调起微信、美团等）时，主应用进入后台导致WebSocket连接断开、任务执行中断的问题。

#### 问题背景：

当AutoGLM执行"Launch"指令调起其他应用时，主应用会被切换到后台。在Android系统的电池优化策略下，后台应用可能会：
- WebSocket连接被系统断开
- 任务执行循环中断
- 无法继续与AutoGLM服务器通信
- 后续复杂操作无法执行

#### 解决方案：

实现了完整的后台保活机制，包含以下核心组件：

**1. 前台服务保活 (`BackgroundKeepAliveService`)**
- 使用Android前台服务机制，显示持久通知
- 返回 `START_STICKY` 确保服务被杀死后自动重启
- 通知栏显示当前任务状态和连接状态
- 支持点击通知返回应用、停止任务等操作

**2. WebSocket连接管理 (`WebSocketConnectionManager`)**
- 心跳机制：每30秒发送心跳包检测连接状态
- 心跳超时检测：10秒内无响应标记连接不健康
- 指数退避重连：1s → 2s → 4s → 8s → 30s（上限）
- 最大重连次数：5次，超过后通知用户
- 从后台恢复时自动检查连接健康状态

**3. 唤醒锁管理 (`WakeLockManager`)**
- 任务开始时获取 `PARTIAL_WAKE_LOCK`
- 防止设备在任务执行期间进入休眠
- 30分钟超时警告机制
- 任务结束时自动释放，防止电池消耗

**4. 任务状态持久化 (`TaskStateManager`)**
- 使用SharedPreferences保存任务状态
- 支持应用意外终止后的任务恢复
- 启动时检测未完成任务并提示用户恢复或放弃

**5. 数据模型**
- `TaskState`：任务状态数据类，包含任务ID、指令、步骤、屏幕尺寸等
- `ConnectionState`：连接状态枚举（DISCONNECTED/CONNECTING/CONNECTED/RECONNECTING）
- `ConnectionStatus`：详细连接状态，包含心跳时间、重连次数、错误信息等

#### 新增权限：

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
<uses-permission android:name="android.permission.VIBRATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

#### 重构的组件：

- `AgentManager`：集成所有保活组件，统一管理任务生命周期
- `MainActivity`：添加任务恢复检查和onResume连接检查

#### 技术规格：

| 参数 | 值 |
|------|-----|
| 心跳间隔 | 30秒 |
| 心跳超时 | 10秒 |
| 最大重连次数 | 5次 |
| 重连延迟序列 | 1s, 2s, 4s, 8s, 30s |
| 唤醒锁超时警告 | 30分钟 |

#### 用户体验改进：

- 任务执行时通知栏显示状态，用户可随时了解进度
- 连接断开时自动重连，无需用户干预
- 重连失败时振动提醒并显示错误通知
- 应用意外关闭后可恢复未完成的任务

#### 文件变更：

**新增文件：**
- `model/TaskState.java` - 任务状态数据类
- `model/ConnectionState.java` - 连接状态枚举
- `model/ConnectionStatus.java` - 连接状态详情类
- `manager/WakeLockManager.java` - 唤醒锁管理器
- `manager/TaskStateManager.java` - 任务状态管理器
- `manager/WebSocketConnectionManager.java` - WebSocket连接管理器
- `service/BackgroundKeepAliveService.java` - 后台保活服务

**修改文件：**
- `manager/AgentManager.java` - 重构，集成保活组件
- `MainActivity.java` - 添加任务恢复和连接检查
- `AndroidManifest.xml` - 添加权限和服务声明

#### 设计文档：

完整的需求、设计和实现计划文档位于：
- `.kiro/specs/background-keep-alive/requirements.md`
- `.kiro/specs/background-keep-alive/design.md`
- `.kiro/specs/background-keep-alive/tasks.md`


### 2026-01-08 修改（追加）

feat: 按住说话提示音功能

为提升用户交互体验，在按住说话功能中增加了音效反馈：

#### 功能说明：

- **按下按钮时**：播放清脆的"嘟"声提示音，表示开始录音
- **松开按钮时**：播放确认音，表示录音结束，进入识别状态

#### 技术实现：

- 新增 `SoundManager` 管理器，使用 Android 系统 `ToneGenerator` 播放提示音
- 使用系统内置音效，无需额外音频资源文件
- 音量设置为媒体音量的 80%，不会过于刺耳

#### 文件变更：

**新增文件：**
- `manager/SoundManager.java` - 提示音管理器

**修改文件：**
- `MainActivity.java` - 在按住说话交互中集成提示音播放


### 2026-01-25 修改

feat: 实现导航与避障模块

本次更新实现了完整的导航与避障功能，为视障用户提供智能化出行辅助。

#### 核心变更：

**1. 模型架构优化**
- 意图分类和指令友好化共用 Qwen2-1.5B-Instruct 模型（GPU 0，节省资源）
- AutoGLM-9B 扩展导航能力（GPU 2）
- Qwen2-VL-7B-Instruct 用于障碍物检测（GPU 3）

**2. 导航功能实现**
- 新增高德地图 API 客户端（`AmapClient`）
- AutoGLM agent 新增 `Call_Navigation` 动作
- 新增 `/ws/navigation` WebSocket 端点
- 实现导航指令"盲人友好化"转换（绝对方向→相对方向，米→步数）

**3. 避障检测服务**
- FastAPI ObstacleDetection 服务（端口 8004）
- 实时视频流处理（2fps）
- TTS 优先级队列（避障 > 导航）

**4. 服务编排与通信**
- Spring Boot 作为统一协调层
- 新增 `NavigationWebSocketHandler` 处理导航请求
- 新增 `TtsMessageQueue` 基于 Redis 的优先级队列
- 新增 `UnifiedWebSocketConfig` 统一 WebSocket 配置

#### 端口分配：

| 服务 | 端口 |
|------|------|
| 意图分类 vLLM | 8001 |
| AutoGLM vLLM | 8000 |
| 避障检测 vLLM | 8003 |
| 意图分类 FastAPI | 8002 |
| 避障检测 FastAPI | 8004 |
| AutoGLM FastAPI | 8080 |
| Spring Boot 服务 | 8090 |

#### 文件变更：

**新增文件（FastAPI/AutoGLM）：**
- `phone_agent/amap/client.py` - 高德地图客户端
- `phone_agent/config/prompts_navigation.py` - 导航专用 Prompt

**新增文件（FastAPI/ObstacleDetection）：**
- `main.py` - 避障检测服务入口

**新增文件（server）：**
- `ws/NavigationWebSocketHandler.java` - 导航 WebSocket 处理器
- `ws/NavigationWebSocketConfig.java` - 导航 WebSocket 配置
- `ws/UnifiedWebSocketConfig.java` - 统一 WebSocket 配置
- `tts/TtsMessageQueue.java` - TTS 消息队列管理
- `model/TtsMessage.java` - TTS 消息数据模型
- `model/TtsPriority.java` - TTS 优先级枚举
- `api/TtsController.java` - TTS 控制器

**修改文件：**
- `server/src/main/java/com/blindassist/server/service/NavigationService.java` - 导航服务增强
- `server/src/main/resources/application.properties` - 配置更新

**脚本更新：**
- `scripts/start_all_services.sh` - 统一服务启动脚本
- `scripts/download_all_models.py` - 模型下载配置

#### 需求文档：

完整的需求与架构文档见：`REQUIREMENTS_VISION_NAV.md`


### 2026-01-28 修改

feat: 统一TTS消息队列管理，解决导航指令被避障警告打断丢失问题

本次更新重构了TTS消息播报架构，将所有语音播报统一纳入Redis优先级队列管理，解决了避障警告打断导航时导致导航指令丢失的问题。

#### 问题背景：

原架构中，Android客户端直接连接避障FastAPI服务，当检测到障碍物时会立即播报警告，打断当前的导航指令播报。被打断的导航指令无法恢复，用户可能错过关键的转向提示。

#### 解决方案：

实现了服务端统一的TTS消息队列管理系统，包含以下核心组件：

**1. Redis优先级队列 (`TtsMessageQueue`)**
- 使用Redis Sorted Set实现优先级队列
- 四级优先级：CRITICAL（紧急避障）> HIGH（恢复消息）> NORMAL（导航）> LOW
- 支持消息持久化和批量出队

**2. 消息打断与恢复机制**
- `saveInterrupted(String userId, TtsMessage)`：保存被打断的消息
- `getInterrupted(String userId)`：获取被打断的消息
- `restoreInterrupted(String userId)`：将被打断的消息重新入队（优先级提升）
- 确保高优先级消息（避障警告）打断后，被中断的指令能恢复播放

**3. 避障消息转发 (`ObstacleWebSocketHandler`)**
- 接收FastAPI避障服务的检测结果
- 根据紧急程度（urgency）映射到TTS优先级
- 转发到Redis队列而非直接播报
- 支持的紧急程度：critical → CRITICAL，high → HIGH，medium → NORMAL，low → LOW

**4. TTS控制API (`TtsController`)**
- `POST /api/tts/interrupt`：保存当前正在播报的消息
- `POST /api/tts/resume`：恢复被打断的消息
- `GET /api/tts/queue`：查看当前队列状态
- `DELETE /api/tts/queue`：清空队列

#### 数据流变更：

**原架构：**
```
Android → FastAPI避障服务 → 直接TTS播报（打断导航）
```

**新架构：**
```
Android → Spring Boot ObstacleWebSocketHandler → Redis TTS队列 → Android轮询获取
                                      ↓
                            保存被打断的导航指令 → 恢复时优先播放
```

#### Android客户端变更：

- `BackgroundCameraService.java`：
  - 移除直接 `speakImmediately()` 调用
  - 改为连接到Spring Boot (`ws://10.181.78.161:8090/ws/obstacle`)

- `ObstacleDetectionClient.java`：
  - 新增 `connect(String url)` 方法支持动态URL配置
  - 新增 `register()` 方法用于用户注册
  - 回调中移除直接TTS播放逻辑

#### Spring Boot服务端变更：

**新增文件：**
- `model/TtsMessage.java` - TTS消息实体（含优先级、时间戳、来源）
- `model/TtsPriority.java` - TTS优先级枚举（CRITICAL/HIGH/NORMAL/LOW）

**修改文件：**
- `ws/ObstacleWebSocketHandler.java` - 完全重写，转发到Redis队列
- `tts/TtsMessageQueue.java` - 添加打断/恢复方法
- `api/TtsController.java` - 添加打断控制端点
- `service/AgentService.java` - 修复硬编码WebSocket URL，使用配置文件

#### 配置文件更新：

`application.properties`：
```properties
# WebSocket配置统一使用横线命名
fastapi.websocket-base-url=ws://10.184.17.161:8080/ws/agent/
fastapi.websocket-navigation-base-url=ws://10.184.17.161:8080/ws/navigation/
```

#### 技术规格：

| 参数 | 值 |
|------|-----|
| 队列数据结构 | Redis Sorted Set |
| Score计算 | 优先级值 × 1,000,000,000 + 时间戳 |
| 默认批次大小 | 10条消息 |
| 恢复消息优先级 | 原优先级提升至HIGH（CRITICAL除外） |

#### 用户体验改进：

- 避障警告立即播报（CRITICAL优先级）
- 被打断的导航指令自动保存
- 避障警告结束后，导航指令恢复播放（提升至HIGH优先级）
- 所有TTS消息统一管理，不会丢失

#### 文件变更清单：

**Android客户端：**
- `app/src/main/java/com/example/test_android_dev/service/BackgroundCameraService.java`
- `app/src/main/java/com/example/test_android_dev/navigation/ObstacleDetectionClient.java`

**Spring Boot服务端（需同步到远程服务器）：**
- `server/src/main/java/com/blindassist/server/ws/ObstacleWebSocketHandler.java`
- `server/src/main/java/com/blindassist/server/tts/TtsMessageQueue.java`
- `server/src/main/java/com/blindassist/server/api/TtsController.java`
- `server/src/main/java/com/blindassist/server/service/AgentService.java`
- `server/src/main/java/com/blindassist/server/config/FastApiProperties.java`
- `server/src/main/resources/application.properties`

**FastAPI服务端（需同步到远程服务器）：**
- `FastAPI/AutoGLM/server.py` - 添加NavigationSession类，实现GPS跟踪和偏航检测


### 2026-01-28 修改（追加）

fix: 修复导航超时、WebSocket连接重复和任务切换问题

本次更新修复了导航功能卡住、WebSocket连接管理混乱、任务切换时状态不一致等关键问题。

#### 修复的问题：

**1. 导航初始化超时/卡住**
- **问题**：导航请求发送后模型输出思考过程但卡住，最终连接关闭，没有返回结果给用户
- **原因**：导航工具执行循环缺少异常处理，当模型响应格式不符合预期时无法优雅降级
- **解决**：在工具执行循环外层添加 try-except 异常捕获，失败时返回友好的错误提示

**2. WebSocket 重复连接导致状态混乱**
- **问题**：日志显示同一客户端有多个连接同时存在，模型输出整个历史对话被重复执行
- **原因**：新连接建立时没有清理旧连接，导致多个会话共存
- **解决**：在 `ConnectionManager.connect()` 中检测重复连接，主动关闭旧连接并清理会话状态

**3. Android 端任务切换时的竞态条件**
- **问题**：快速切换任务时，旧 WebSocket 未完全断开就建立新连接
- **原因**：`stopTask()` 调用 `disconnect()` 是异步的，新任务启动时旧连接可能仍在关闭过程中
- **解决**：在 `connectWebSocket()` 中检查当前连接状态，如已连接则先断开并等待200ms

#### 技术规格：

| 参数 | 值 |
|------|-----|
| 导航工具循环最大迭代次数 | 10次 |
| 任务切换等待时间 | 500ms + 200ms |
| 重复连接检测 | 基于 client_id |

#### 文件变更清单：

**FastAPI服务端：**
- `FastAPI/AutoGLM/server.py`
  - `ConnectionManager.connect()` - 添加重复连接检测和清理
  - 导航工具执行循环 - 添加 try-except 异常处理

**Android客户端：**
- `app/src/main/java/com/example/test_android_dev/manager/AgentManager.java`
  - `connectWebSocket()` - 添加连接状态检查，防止重复连接

**Spring Boot服务端：**
- `server/src/main/java/com/blindassist/server/service/NavigationAgentService.java`
  - 添加高德 API Key 传递给 FastAPI

#### 其他改进：

- **高德 API Key 统一配置**：从 Spring Boot 配置文件读取并通过 WebSocket 传递给 FastAPI，无需在服务端设置环境变量
- **UTF-8 编码修复**：添加 logback-spring.xml 配置文件，解决控制台中文乱码问题
- **GPS 定位异常处理**：LocationHelper 添加完整的异常捕获，防止初始化时应用崩溃
