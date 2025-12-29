package com.example.test_android_dev.manager;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.example.test_android_dev.service.AutoGLMService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.*;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class AgentManager {
    private static final String TAG = "AgentManager";

    // 请替换为你 Spring Boot 服务的实际 IP 地址
    private static final String SERVER_URL = "ws://localhost:8090/ws/agent";

    private static AgentManager instance;
    private WebSocket webSocket;
    private final OkHttpClient client;
    private final Gson gson = new Gson();

    // 任务状态标记
    private boolean isTaskRunning = false;

    // 屏幕尺寸，用于坐标转换
    private int screenWidth;
    private int screenHeight;

    private AgentManager() {
        client = new OkHttpClient.Builder()
                .readTimeout(60, TimeUnit.SECONDS) // 设置长一点的超时，防止模型思考时断开
                .build();
    }

    public static synchronized AgentManager getInstance() {
        if (instance == null) {
            instance = new AgentManager();
        }
        return instance;
    }

    /**
     * 1. 启动任务
     * @param taskPrompt 用户指令，如 "帮我点外卖"
     * @param width 屏幕宽度 (px)
     * @param height 屏幕高度 (px)
     */
    public void startTask(String taskPrompt, int width, int height) {
        if (isTaskRunning) {
            Log.w(TAG, "任务已在运行中，请先停止");
            return;
        }
        this.screenWidth = width;
        this.screenHeight = height;
        this.isTaskRunning = true;

        Log.d(TAG, "开始任务: " + taskPrompt);
        connectWebSocket(taskPrompt);
    }

    /**
     * 停止任务并断开连接
     */
    public void stopTask() {
        Log.d(TAG, "停止任务");
        isTaskRunning = false;
        if (webSocket != null) {
            webSocket.close(1000, "User Stopped");
            webSocket = null;
        }
    }

    /**
     * 发送第一帧 (Init)
     * 在 WebSocket 连接成功且 ImageCaptureManager 截取到第一张图后调用
     */
    public void sendInit(String task, String base64Image) {
        if (webSocket == null) return;

        JsonObject json = new JsonObject();
        json.addProperty("type", "init");
        json.addProperty("task", task);
        json.addProperty("screenshot", base64Image);
        json.addProperty("screen_info", "Android Screen"); // 可选

        webSocket.send(gson.toJson(json));
    }

    /**
     * 发送后续帧 (Step)
     * 在执行完动作并重新截图后调用
     */
    private void sendStep(String base64Image) {
        if (webSocket == null || !isTaskRunning) return;

        JsonObject json = new JsonObject();
        json.addProperty("type", "step");
        json.addProperty("screenshot", base64Image);
        json.addProperty("screen_info", "Step Screen");

        webSocket.send(gson.toJson(json));
    }

    private void connectWebSocket(String initTask) {
        Request request = new Request.Builder().url(SERVER_URL).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NonNull WebSocket webSocket, @NonNull Response response) {
                Log.d(TAG, "WebSocket 已连接");
                // 连接成功后，立即触发第一次截图
                // 注意：这里需要回调到 MainActivity 或调用 ImageCaptureManager
                // 假设 ImageCaptureManager 是单例且可访问
                captureAndSend(true, initTask);
            }

            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                Log.d(TAG, "收到服务端指令: " + text);
                handleServerMessage(text);
            }

            @Override
            public void onClosed(@NonNull WebSocket webSocket, int code, @NonNull String reason) {
                Log.d(TAG, "WebSocket 已关闭: " + reason);
                isTaskRunning = false;
            }

            @Override
            public void onFailure(@NonNull WebSocket webSocket, @NonNull Throwable t, Response response) {
                Log.e(TAG, "WebSocket 连接失败", t);
                isTaskRunning = false;
            }
        });
    }

    /**
     * 处理服务端返回的 JSON
     */
    private void handleServerMessage(String text) {
        try {
            JsonObject response = gson.fromJson(text, JsonObject.class);

            // 1. 检查任务是否结束
            if (response.has("finished") && response.get("finished").getAsBoolean()) {
                Log.i(TAG, "AI 判定任务完成");
                String msg = "任务完成";
                if (response.has("action") && response.getAsJsonObject("action").has("message")) {
                    msg = response.getAsJsonObject("action").get("message").getAsString();
                }
                showToast(msg);
                stopTask();
                return;
            }

            // 2. 解析 Action
            if (response.has("action")) {
                JsonObject actionJson = response.getAsJsonObject("action");

                // 将 JsonObject 转换为 Map 以适配 AutoGLMService 的接口
                Map<String, Object> actionMap = parseActionJsonToMap(actionJson);

                // 3. 执行动作
                AutoGLMService service = AutoGLMService.getInstance();
                if (service == null) {
                    Log.e(TAG, "无障碍服务未开启，无法执行动作");
                    showToast("请开启无障碍服务");
                    stopTask();
                    return;
                }

                // 在主线程或辅助线程执行
                boolean success = service.executeAction(actionMap);

                if (success) {
                    // 4. 等待 UI 响应并进入下一轮
                    // 根据动作类型决定等待时间，比如 Launch App 需要等久一点
                    long waitTime = 2000;
                    if ("Launch".equals(actionMap.get("action"))) waitTime = 5000;
                    if ("Type".equals(actionMap.get("action"))) waitTime = 3000;

                    Thread.sleep(waitTime);

                    // 5. 截图并发送下一帧
                    captureAndSend(false, null);
                } else {
                    Log.e(TAG, "动作执行失败，尝试重试或停止");
                    // 这里可以选择重试，或者直接截图让 AI 看到它失败了
                    Thread.sleep(1000);
                    captureAndSend(false, null);
                }
            }

        } catch (Exception e) {
            Log.e(TAG, "处理指令异常", e);
        }
    }

    /**
     * 调用 ImageCaptureManager 截图并发送
     * @param isInit 是否是初始化帧
     * @param taskPrompt 仅在 isInit 为 true 时需要
     */
    private void captureAndSend(boolean isInit, String taskPrompt) {
        if (!isTaskRunning) return;

        // 1. 获取 Service 实例
        AutoGLMService service = AutoGLMService.getInstance();
        if (service == null) {
            Log.e(TAG, "无障碍服务未启动，无法截图");
            // 这里可以尝试重新引导用户开启服务，或停止任务
            stopTask();
            return;
        }

        // 2. 调用新的截图管理器
        AccessibilityScreenshotManager.getInstance().capture(service, new AccessibilityScreenshotManager.ScreenshotCallback() {
            @Override
            public void onSuccess(String base64) {
                Log.d(TAG, "截图成功，准备发送");
                // 此时已在主线程，直接发送
                if (isInit) {
                    sendInit(taskPrompt, base64);
                } else {
                    sendStep(base64);
                }
            }

            @Override
            public void onFailure(String error) {
                Log.e(TAG, "截图失败: " + error);
                // 失败策略：可以重试，或者终止任务
                // stopTask();
            }
        });
    }

    /**
     * 核心逻辑：将服务端返回的相对坐标 (0-1000) 转换为屏幕绝对坐标
     */
    private Map<String, Object> parseActionJsonToMap(JsonObject json) {
        Map<String, Object> map = new HashMap<>();

        // 必需字段
        String actionType = json.get("action").getAsString();
        map.put("action", actionType);

        // 1. 处理坐标 (element: [x, y]) -> [absX, absY]
        if (json.has("element")) {
            int[] point = convertCoordinates(json.get("element"));
            map.put("element", java.util.Arrays.asList(point[0], point[1]));
        }

        // 2. 处理滑动起始点 (start: [x, y], end: [x, y])
        if (json.has("start")) {
            int[] point = convertCoordinates(json.get("start"));
            map.put("start", java.util.Arrays.asList(point[0], point[1]));
        }
        if (json.has("end")) {
            int[] point = convertCoordinates(json.get("end"));
            map.put("end", java.util.Arrays.asList(point[0], point[1]));
        }

        // 3. 其他字段直接拷贝
        if (json.has("text")) map.put("text", json.get("text").getAsString());
        if (json.has("app")) map.put("app", json.get("app").getAsString()); // Launch
        if (json.has("message")) map.put("message", json.get("message").getAsString()); // Take_over

        // 处理时间 duration
        if (json.has("duration")) {
            JsonElement d = json.get("duration");
            if (d.isJsonPrimitive() && d.getAsJsonPrimitive().isNumber()) {
                map.put("duration", d.getAsInt());
            } else {
                map.put("duration", d.getAsString());
            }
        }
        if (json.has("duration_ms")) map.put("duration_ms", json.get("duration_ms").getAsInt());

        return map;
    }

    /**
     * 坐标转换工具：相对坐标 (0-1000) -> 绝对坐标 (px)
     */
    private int[] convertCoordinates(JsonElement element) {
        try {
            if (element.isJsonArray()) {
                JsonArray arr = element.getAsJsonArray();
                int relX = arr.get(0).getAsInt();
                int relY = arr.get(1).getAsInt();

                int absX = (int) (relX / 1000.0f * screenWidth);
                int absY = (int) (relY / 1000.0f * screenHeight);
                return new int[]{absX, absY};
            }
        } catch (Exception e) {
            Log.e(TAG, "坐标解析错误: " + element);
        }
        return new int[]{0, 0};
    }

    private void showToast(String msg) {
        new Handler(Looper.getMainLooper()).post(() ->
                android.widget.Toast.makeText(com.example.test_android_dev.App.getContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
        );
    }
}