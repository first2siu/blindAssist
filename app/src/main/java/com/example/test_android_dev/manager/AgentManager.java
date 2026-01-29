package com.example.test_android_dev.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.test_android_dev.App;
import com.example.test_android_dev.LocationHelper;
import com.example.test_android_dev.model.ConnectionState;
import com.example.test_android_dev.model.LocationInfo;
import com.example.test_android_dev.model.TaskState;
import com.example.test_android_dev.service.AutoGLMService;
import com.example.test_android_dev.service.BackgroundKeepAliveService;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;

public class AgentManager {
    private static final String TAG = "AgentManager";
    private static final String SERVER_URL = "ws://10.181.78.161:8090/ws/agent";

    private static AgentManager instance;
    private final Gson gson = new Gson();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WebSocketConnectionManager connectionManager;
    private WakeLockManager wakeLockManager;
    private TaskStateManager taskStateManager;

    private TaskState currentTask;
    private boolean isTaskRunning = false;
    private int screenWidth;
    private int screenHeight;
    private Context appContext;

    // 服务连接重试计数器
    private int serviceRetryCount = 0;
    private static final int MAX_SERVICE_RETRY = 10;

    private AgentManager() {
        connectionManager = WebSocketConnectionManager.getInstance();
        wakeLockManager = WakeLockManager.getInstance();
        taskStateManager = TaskStateManager.getInstance();
    }

    public static synchronized AgentManager getInstance() {
        if (instance == null) {
            instance = new AgentManager();
        }
        return instance;
    }


    public void init(Context context) {
        this.appContext = context.getApplicationContext();
        wakeLockManager.init(appContext);
        taskStateManager.init(appContext);
    }

    public void startTask(String taskPrompt, int width, int height) {
        Log.d(TAG, "========== startTask ==========");
        Log.d(TAG, "任务: " + taskPrompt);
        Log.d(TAG, "屏幕尺寸: " + width + "x" + height);
        Log.d(TAG, "当前任务状态: " + (isTaskRunning ? "运行中" : "未运行"));

        // 如果已有任务在运行，先停止它（支持任务切换）
        if (isTaskRunning) {
            Log.i(TAG, "检测到已有任务运行中，先停止旧任务再启动新任务");
            stopTask();
            // 等待旧任务清理完成
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Log.w(TAG, "任务切换等待被中断");
            }
        }

        this.screenWidth = width;
        this.screenHeight = height;
        this.isTaskRunning = true;

        currentTask = new TaskState(taskPrompt, width, height);
        currentTask.markRunning();
        taskStateManager.saveState(currentTask);

        wakeLockManager.acquire(heldMs -> Log.w(TAG, "唤醒锁超时: " + heldMs));

        if (appContext != null) {
            BackgroundKeepAliveService.start(appContext, taskPrompt);
        }

        // 获取 GPS 位置（异步，使用缓存位置或请求新位置）
        LocationHelper locationHelper = LocationHelper.getInstance(appContext);
        LocationInfo cachedLocation = locationHelper.getCachedLocation();
        if (cachedLocation != null) {
            Log.d(TAG, "使用缓存 GPS: " + cachedLocation);
        } else {
            Log.d(TAG, "请求 GPS 位置更新...");
            locationHelper.getCurrentLocation(location -> {
                if (location != null) {
                    Log.d(TAG, "获取到新 GPS: " + location);
                } else {
                    Log.w(TAG, "GPS 位置获取失败");
                }
            });
        }

        Log.d(TAG, "准备连接 WebSocket...");
        connectWebSocket(taskPrompt);
    }

    public void stopTask() {
        Log.d(TAG, "========== stopTask 被调用 ==========");
        Log.d(TAG, "调用堆栈:");
        for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
            Log.d(TAG, "    " + element.toString());
        }

        isTaskRunning = false;
        connectionManager.disconnect();
        wakeLockManager.release();

        if (currentTask != null) {
            taskStateManager.markTaskStopped(currentTask);
            currentTask = null;
        }

        if (appContext != null) {
            BackgroundKeepAliveService.stop(appContext);
        }
    }

    public void resumeTask(TaskState savedTask) {
        this.currentTask = savedTask;
        this.screenWidth = savedTask.getScreenWidth();
        this.screenHeight = savedTask.getScreenHeight();
        this.isTaskRunning = true;

        currentTask.markRunning();
        taskStateManager.saveState(currentTask);
        wakeLockManager.acquire(null);

        if (appContext != null) {
            BackgroundKeepAliveService.start(appContext, savedTask.getTaskPrompt());
        }
        connectWebSocket(savedTask.getTaskPrompt());
    }

    public void checkAndReconnectIfNeeded() {
        if (isTaskRunning) {
            connectionManager.checkAndReconnectIfNeeded();
        }
    }

    public boolean hasIncompleteTask() {
        return taskStateManager.hasIncompleteTask();
    }

    public void promptTaskRecovery(Context ctx, TaskStateManager.TaskRecoveryCallback cb) {
        taskStateManager.promptTaskRecovery(ctx, cb);
    }

    public TaskState getCurrentTask() { return currentTask; }
    public boolean isTaskRunning() { return isTaskRunning; }


    private void connectWebSocket(String initTask) {
        // 重置服务重试计数器
        serviceRetryCount = 0;

        Log.d(TAG, "========== 开始连接 WebSocket ==========");
        Log.d(TAG, "服务器地址: " + SERVER_URL);

        // 确保先断开旧连接（防止重复连接）
        ConnectionState currentState = connectionManager.getConnectionState();
        if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) {
            Log.w(TAG, "检测到已有活跃连接，先断开");
            connectionManager.disconnect();
            // 等待断开完成
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        connectionManager.connect(SERVER_URL, new WebSocketConnectionManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                Log.d(TAG, "========== WebSocket 已连接 ==========");
                updateServiceState(ConnectionState.CONNECTED);
                Log.d(TAG, "准备截图并发送 init 消息...");
                captureAndSend(true, initTask);
            }

            @Override
            public void onDisconnected(String reason) {
                Log.d(TAG, "========== WebSocket 已断开 ==========");
                Log.d(TAG, "断开原因: " + reason);
                updateServiceState(ConnectionState.DISCONNECTED);
            }

            @Override
            public void onReconnecting(int attempt, long nextRetryMs) {
                updateServiceState(ConnectionState.RECONNECTING);
                BackgroundKeepAliveService svc = BackgroundKeepAliveService.getInstance();
                if (svc != null) svc.showReconnectingNotification(attempt, nextRetryMs);
            }

            @Override
            public void onReconnectFailed() {
                BackgroundKeepAliveService svc = BackgroundKeepAliveService.getInstance();
                if (svc != null) svc.showErrorNotification("连接失败");
                isTaskRunning = false;
            }

            @Override
            public void onMessage(String message) {
                handleServerMessage(message);
            }

            @Override
            public void onConnectionStateChanged(ConnectionState state) {
                updateServiceState(state);
            }
        });
    }

    private void updateServiceState(ConnectionState state) {
        BackgroundKeepAliveService svc = BackgroundKeepAliveService.getInstance();
        if (svc != null) svc.updateConnectionState(state);
    }

    public void sendInit(String task, String base64Image) {
        Log.d(TAG, "========== sendInit ==========");
        Log.d(TAG, "任务: " + task);
        Log.d(TAG, "截图长度: " + (base64Image != null ? base64Image.length() : 0));

        // 获取 GPS 位置
        LocationHelper locationHelper = LocationHelper.getInstance();
        LocationInfo location = null;
        if (locationHelper != null) {
            location = locationHelper.getCachedLocation();
        }
        if (location != null) {
            Log.d(TAG, "GPS位置: " + location);
        } else {
            Log.w(TAG, "GPS位置不可用，使用 null");
        }

        JsonObject json = new JsonObject();
        json.addProperty("type", "init");
        json.addProperty("task", task);
        json.addProperty("screenshot", base64Image);
        json.addProperty("screen_info", "Android Screen");

        // 添加 GPS 位置信息
        if (location != null) {
            JsonObject locationJson = new JsonObject();
            locationJson.addProperty("latitude", location.getLatitude());
            locationJson.addProperty("longitude", location.getLongitude());
            if (location.getAltitude() != null) {
                locationJson.addProperty("altitude", location.getAltitude());
            }
            if (location.getHeading() != null) {
                locationJson.addProperty("heading", location.getHeading());
            }
            if (location.getAccuracy() != null) {
                locationJson.addProperty("accuracy", location.getAccuracy());
            }
            locationJson.addProperty("timestamp", location.getTimestamp() != null ? location.getTimestamp() : System.currentTimeMillis());
            json.add("location", locationJson);
        }

        String jsonString = gson.toJson(json);
        Log.d(TAG, "消息长度: " + jsonString.length());

        boolean sent = connectionManager.send(jsonString);
        Log.d(TAG, "发送结果: " + (sent ? "成功" : "失败"));
    }

    private void sendStep(String base64Image) {
        if (!isTaskRunning) return;
        JsonObject json = new JsonObject();
        json.addProperty("type", "step");
        json.addProperty("screenshot", base64Image);
        json.addProperty("screen_info", "Step Screen");
        connectionManager.send(gson.toJson(json));
    }


    private void handleServerMessage(String text) {
        try {
            JsonObject response = gson.fromJson(text, JsonObject.class);

            // 处理停止状态（来自服务端的 STOP 意图响应）
            if (response.has("status") && "stopped".equals(response.get("status").getAsString())) {
                String msg = "任务已停止";
                if (response.has("message")) {
                    msg = response.get("message").getAsString();
                }
                Log.i(TAG, "收到服务器停止指令: " + msg);
                showToast(msg);
                stopTask();
                return;
            }

            // 处理错误状态
            if (response.has("status") && "error".equals(response.get("status").getAsString())) {
                String errorMsg = "操作出错";
                if (response.has("message")) {
                    errorMsg = response.get("message").getAsString();
                }
                Log.e(TAG, "服务器返回错误: " + errorMsg);
                showToast(errorMsg);
                // 可选：是否要在错误时停止任务
                // stopTask();
                return;
            }

            if (response.has("finished") && response.get("finished").getAsBoolean()) {
                String msg = "任务完成";
                if (response.has("action") && response.getAsJsonObject("action").has("message")) {
                    msg = response.getAsJsonObject("action").get("message").getAsString();
                }
                showToast(msg);
                BackgroundKeepAliveService svc = BackgroundKeepAliveService.getInstance();
                if (svc != null) svc.showTaskCompleteNotification(msg);
                stopTask();
                return;
            }

            if (response.has("action")) {
                JsonObject actionJson = response.getAsJsonObject("action");
                Map<String, Object> actionMap = parseActionJsonToMap(actionJson);

                AutoGLMService service = AutoGLMService.getInstance();
                if (service == null) {
                    showToast("请开启无障碍服务");
                    stopTask();
                    return;
                }

                service.executeAction(actionMap);
                if (currentTask != null) taskStateManager.updateStep(currentTask);

                long waitTime = 2000;
                String actionType = (String) actionMap.get("action");
                if ("Tap".equals(actionType)) waitTime = 1000;
                if ("Launch".equals(actionType)) waitTime = 5000;
                if ("Type".equals(actionType)) waitTime = 3000;

                Thread.sleep(waitTime);
                captureAndSend(false, null);
            }
        } catch (Exception e) {
            Log.e(TAG, "处理指令异常", e);
        }
    }

    private void captureAndSend(boolean isInit, String taskPrompt) {
        Log.d(TAG, "---------- captureAndSend ----------");
        Log.d(TAG, "isInit: " + isInit + ", taskPrompt: " + taskPrompt);
        Log.d(TAG, "isTaskRunning: " + isTaskRunning);

        if (!isTaskRunning) {
            Log.w(TAG, "任务未运行，退出 captureAndSend");
            return;
        }

        AutoGLMService service = AutoGLMService.getInstance();
        Log.d(TAG, "AutoGLMService: " + (service != null ? "已连接" : "NULL"));

        if (service == null) {
            // 无障碍服务尚未绑定，延迟重试
            if (serviceRetryCount < MAX_SERVICE_RETRY) {
                serviceRetryCount++;
                Log.w(TAG, "无障碍服务未就绪(" + serviceRetryCount + "/" + MAX_SERVICE_RETRY + ")，1秒后重试...");
                mainHandler.postDelayed(() -> captureAndSend(isInit, taskPrompt), 1000);
            } else {
                Log.e(TAG, "无障碍服务重试超时，停止任务");
                showToast("无障碍服务连接超时，请检查设置");
                stopTask();
            }
            return;
        }

        // 服务已连接，重置计数器
        serviceRetryCount = 0;
        Log.d(TAG, "无障碍服务已就绪，开始截图...");

        AccessibilityScreenshotManager.getInstance().capture(service,
            new AccessibilityScreenshotManager.ScreenshotCallback() {
                @Override
                public void onSuccess(String base64) {
                    Log.d(TAG, "截图成功，准备发送消息...");
                    if (isInit) sendInit(taskPrompt, base64);
                    else sendStep(base64);
                }

                @Override
                public void onFailure(String error) {
                    Log.e(TAG, "截图失败: " + error);
                    // 截图失败时短暂重试
                    if (isInit) {
                        mainHandler.postDelayed(() -> captureAndSend(isInit, taskPrompt), 500);
                    }
                }
            });
    }


    private Map<String, Object> parseActionJsonToMap(JsonObject json) {
        Map<String, Object> map = new HashMap<>();
        map.put("action", json.get("action").getAsString());

        if (json.has("element")) {
            int[] p = convertCoordinates(json.get("element"));
            map.put("element", java.util.Arrays.asList(p[0], p[1]));
        }
        if (json.has("start")) {
            int[] p = convertCoordinates(json.get("start"));
            map.put("start", java.util.Arrays.asList(p[0], p[1]));
        }
        if (json.has("end")) {
            int[] p = convertCoordinates(json.get("end"));
            map.put("end", java.util.Arrays.asList(p[0], p[1]));
        }
        if (json.has("text")) map.put("text", json.get("text").getAsString());
        if (json.has("app")) map.put("app", json.get("app").getAsString());
        if (json.has("message")) map.put("message", json.get("message").getAsString());
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

    private int[] convertCoordinates(JsonElement element) {
        try {
            if (element.isJsonArray()) {
                JsonArray arr = element.getAsJsonArray();
                int absX = (int) (arr.get(0).getAsInt() / 1000.0f * screenWidth);
                int absY = (int) (arr.get(1).getAsInt() / 1000.0f * screenHeight);
                return new int[]{absX, absY};
            }
        } catch (Exception e) {
            Log.e(TAG, "坐标解析错误", e);
        }
        return new int[]{0, 0};
    }

    private void showToast(String msg) {
        mainHandler.post(() -> android.widget.Toast.makeText(
            App.getContext(), msg, android.widget.Toast.LENGTH_SHORT).show());
    }
}
