package com.example.test_android_dev.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.hardware.Camera;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.example.test_android_dev.R;
import com.example.test_android_dev.navigation.NavigationManager;
import com.example.test_android_dev.navigation.ObstacleDetectionClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * 后台摄像头服务
 * 用于避障功能的静默图像采集
 *
 * 架构更新：避障检测结果通过 Spring Boot 转发到 Redis TTS 队列
 * 不再直接调用 TTS 播报，避免打断导航指令丢失
 */
public class BackgroundCameraService extends Service {
    private static final String TAG = "BackgroundCameraService";
    private static final String CHANNEL_ID = "obstacle_detection_channel";
    private static final int NOTIFICATION_ID = 2002;
    private static final long FRAME_INTERVAL_MS = 500; // 2fps

    // 连接到 Spring Boot 的 ObstacleWebSocketHandler
    private static final String SPRING_BOOT_WS_URL = "ws://10.181.78.161:8090/ws/obstacle";

    private static boolean isRunning = false;

    private Camera camera;
    private Handler frameHandler;
    private boolean isCapturing = false;

    private final ObstacleDetectionClient obstacleClient;
    private final NavigationManager navigationManager;
    private final ScheduledExecutorService executorService;

    public BackgroundCameraService() {
        this.obstacleClient = ObstacleDetectionClient.getInstance(this);
        this.navigationManager = NavigationManager.getInstance(this);
        this.executorService = Executors.newSingleThreadScheduledExecutor();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BackgroundCameraService created");

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("避障模式准备中..."));

        // 设置回调 - 仅用于连接状态通知，不再直接播报
        obstacleClient.setCallback(new ObstacleDetectionClient.ObstacleCallback() {
            @Override
            public void onObstacleDetected(String warning, String urgency) {
                Log.i(TAG, "Obstacle detected: " + warning + ", urgency: " + urgency);
                // 不再直接播报，避障消息已通过 Spring Boot 加入 Redis 队列
                // Android 客户端会通过轮询 TTS 队列获取并播报
            }

            @Override
            public void onConnected() {
                Log.d(TAG, "Obstacle client connected to Spring Boot");
                // 注册用户
                obstacleClient.register();
                updateNotification("避障模式运行中");
            }

            @Override
            public void onDisconnected() {
                Log.d(TAG, "Obstacle client disconnected");
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, "Obstacle client error: " + error);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand");

        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();

            if ("START_OBSTACLE_MODE".equals(action)) {
                startObstacleMode();
            } else if ("STOP_OBSTACLE_MODE".equals(action)) {
                stopObstacleMode();
            }
        }

        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "BackgroundCameraService destroyed");
        stopObstacleMode();
        executorService.shutdown();
    }

    /**
     * 启动避障模式
     */
    private void startObstacleMode() {
        if (isCapturing) {
            Log.w(TAG, "Already capturing");
            return;
        }

        Log.d(TAG, "Starting obstacle mode");
        isRunning = true;
        isCapturing = true;

        // 连接到 Spring Boot 避障 WebSocket 服务
        obstacleClient.connect(SPRING_BOOT_WS_URL);

        // 启动相机
        startCamera();

        // 开始帧采集循环
        startFrameCaptureLoop();

        updateNotification("避障模式运行中");
    }

    /**
     * 停止避障模式
     */
    private void stopObstacleMode() {
        Log.d(TAG, "Stopping obstacle mode");
        isRunning = false;
        isCapturing = false;

        // 停止帧采集
        if (frameHandler != null) {
            frameHandler.removeCallbacksAndMessages(null);
        }

        // 释放相机
        if (camera != null) {
            camera.setPreviewCallback(null);
            camera.stopPreview();
            camera.release();
            camera = null;
        }

        // 断开避障服务连接
        obstacleClient.disconnect();

        updateNotification("避障模式已停止");

        // 延迟停止服务
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            stopForeground(true);
            stopSelf();
        }, 1000);
    }

    /**
     * 启动相机
     */
    private void startCamera() {
        try {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);

            Camera.Parameters parameters = camera.getParameters();
            parameters.setPictureFormat(ImageFormat.JPEG);
            parameters.setPictureSize(1280, 720);
            parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(parameters);

            camera.setPreviewCallback(new Camera.PreviewCallback() {
                @Override
                public void onPreviewFrame(byte[] data, Camera camera) {
                    if (isCapturing && data != null) {
                        processFrame(data, camera);
                    }
                }
            });

            camera.startPreview();
            Log.d(TAG, "Camera started");

        } catch (Exception e) {
            Log.e(TAG, "Failed to start camera", e);
        }
    }

    /**
     * 开始帧采集循环
     */
    private void startFrameCaptureLoop() {
        if (frameHandler == null) {
            frameHandler = new Handler(Looper.getMainLooper());
        }

        frameHandler.post(new Runnable() {
            @Override
            public void run() {
                if (isCapturing) {
                    captureFrame();
                    frameHandler.postDelayed(this, FRAME_INTERVAL_MS);
                }
            }
        });
    }

    /**
     * 捕获帧并发送
     */
    private void captureFrame() {
        if (camera == null || !isCapturing) {
            return;
        }

        camera.takePicture(null, null, new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] data, Camera camera) {
                if (data != null && isCapturing) {
                    Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);

                    if (bitmap != null) {
                        // 发送帧到避障服务（通过 Spring Boot 转发）
                        obstacleClient.sendFrame(bitmap, navigationManager.getCurrentSensorData());
                    }
                }

                // 恢复预览以便继续捕获
                camera.startPreview();
            }
        });
    }

    /**
     * 处理预览帧
     */
    private void processFrame(byte[] data, Camera camera) {
        Camera.Parameters parameters = camera.getParameters();
        int format = parameters.getPictureFormat();

        if (format == ImageFormat.NV21) {
            Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length);
            if (bitmap != null) {
                obstacleClient.sendFrame(bitmap, navigationManager.getCurrentSensorData());
            }
        }
    }

    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "避障检测",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("实时障碍物检测服务");

            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    /**
     * 创建通知
     */
    private Notification createNotification(String text) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("导航助手")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);

        return builder.build();
    }

    /**
     * 更新通知
     */
    private void updateNotification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.notify(NOTIFICATION_ID, createNotification(text));
    }

    /**
     * 启动避障模式
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, BackgroundCameraService.class);
        intent.setAction("START_OBSTACLE_MODE");
        context.startForegroundService(intent);
    }

    /**
     * 停止避障模式
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, BackgroundCameraService.class);
        intent.setAction("STOP_OBSTACLE_MODE");
        context.startService(intent);
    }

    /**
     * 检查是否运行中
     */
    public static boolean isRunning() {
        return isRunning;
    }
}
