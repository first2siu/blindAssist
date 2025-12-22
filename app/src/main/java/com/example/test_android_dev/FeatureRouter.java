package com.example.test_android_dev;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 根据功能类型调度对应的业务流程
 */
public class FeatureRouter {
    private static final String TAG = "FeatureRouter";
    private static FeatureRouter instance;
    private VoiceManager voiceManager;
    private NetworkClient networkClient;
    private ImageCaptureManager imageManager;
    private ScheduledExecutorService obstacleExecutor;
    private String currentSessionId = "";

    private FeatureRouter() {}

    public static synchronized FeatureRouter getInstance() {
        if (instance == null) {
            instance = new FeatureRouter();
        }
        return instance;
    }

    public void init(Context context) {
        voiceManager = VoiceManager.getInstance();
        networkClient = NetworkClient.getInstance();
        imageManager = ImageCaptureManager.getInstance();
    }

    public void route(FeatureType feature) {
        Log.d(TAG, "Routing to: " + feature.name());
        switch (feature) {
            case NAVIGATION:
                startNavigationFlow();
                break;
            case OBSTACLE_AVOIDANCE:
                startObstacleAvoidance(false);
                break;
            case QA_VOICE:
                startQAFlow();
                break;
            case OCR:
                startOCRFlow();
                break;
            case SCENE_DESCRIPTION:
                startSceneDescriptionFlow();
                break;
            default:
                voiceManager.speak("抱歉，该功能暂未实现。");
                break;
        }
    }

    private void startNavigationFlow() {
        voiceManager.speak("进入导航模式。要去哪里？");
        voiceManager.startListening(text -> {
            networkClient.sendVoiceCommand(text, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    voiceManager.speak("语音理解失败，请重试。");
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    // 解析指令并请求导航（此处简化为直接请求目的地）
                    networkClient.requestNavigation(0, 0, 0, 0, text, new Callback() {
                        @Override
                        public void onFailure(@NonNull Call call, @NonNull IOException e) {
                            voiceManager.speak("获取导航路径失败。");
                        }

                        @Override
                        public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                            voiceManager.speak("找到路线，开始导航。第一步：向前直行。");
                            startObstacleAvoidance(true); // 导航时自动开启后台避障
                        }
                    });
                }
            });
        });
    }

    private void startObstacleAvoidance(boolean isBackground) {
        if (!isBackground) voiceManager.speak("实时避障已开启。");
        
        networkClient.openObstacleWebSocket(new WebSocketListener() {
            @Override
            public void onMessage(@NonNull WebSocket webSocket, @NonNull String text) {
                // 模拟解析 JSON: {"message": "前方有障碍物，请向左绕行"}
                voiceManager.speakImmediate("注意，前方有障碍物。");
            }
        });

        if (obstacleExecutor != null) obstacleExecutor.shutdown();
        obstacleExecutor = Executors.newSingleThreadScheduledExecutor();
        obstacleExecutor.scheduleAtFixedRate(() -> {
            byte[] frame = imageManager.captureCurrentFrame();
            networkClient.sendFrameViaWS(frame);
        }, 0, 1, TimeUnit.SECONDS);
    }

    private void startQAFlow() {
        voiceManager.speak("我是您的智能助理，请提问。");
        voiceManager.startListening(text -> {
            networkClient.askQuestion(text, currentSessionId, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    voiceManager.speak("网络开小差了，请稍后再问。");
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    String answer = "这是为您找到的答案。"; // 实际应从 response.body() 解析
                    voiceManager.speak(answer);
                }
            });
        });
    }

    private void startOCRFlow() {
        voiceManager.speak("请将手机对准文字，保持稳定，两秒后识别。");
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            byte[] photo = imageManager.captureHighResFrame();
            networkClient.uploadVisionRequest("ocr", photo, new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException e) {
                    voiceManager.speak("识别失败。");
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                    voiceManager.speak("识别到文字：此处为药品说明书。");
                }
            });
        }, 2000);
    }

    private void startSceneDescriptionFlow() {
        voiceManager.speak("正在观察周围环境，请稍候。");
        byte[] photo = imageManager.captureHighResFrame();
        networkClient.uploadVisionRequest("scene", photo, new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                voiceManager.speak("获取场景描述失败。");
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                voiceManager.speak("您面前是一个公园入口，左侧有长椅，右侧有树木。");
            }
        });
    }
