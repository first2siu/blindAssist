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
import android.graphics.Matrix;
import android.os.IBinder;
import android.os.Looper;
import android.os.Environment;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleOwner;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import com.example.test_android_dev.R;
import com.example.test_android_dev.navigation.NavigationManager;
import com.example.test_android_dev.navigation.ObstacleDetectionClient;
import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 后台摄像头服务（使用 CameraX）
 * 用于避障功能的静默图像采集
 *
 * 架构：
 * Android → FastAPI ObstacleDetection (8004/ws) → 检测障碍物
 *         → Spring Boot /api/tts/enqueue → Redis TTS 队列
 *         → Android TtsPollingService 轮询 → TTS 播报
 *
 * CameraX 优势：
 * - 更稳定的 API，不会出现 takePicture failed 问题
 * - 自动处理生命周期和设备兼容性
 * - ImageAnalysis 提供高效的帧流处理
 */
public class BackgroundCameraService extends Service {
    private static final String TAG = "BackgroundCameraService";
    private static final String CHANNEL_ID = "obstacle_detection_channel";
    private static final int NOTIFICATION_ID = 2002;

    // 帧发送间隔（毫秒）
    private static final long FRAME_SEND_INTERVAL_MS = 3000;

    // 连接到 FastAPI 避障服务
    private static final String FASTAPI_OBSTACLE_URL = "ws://10.184.17.161:8004/ws";

    private static boolean isRunning = false;

    // CameraX 组件
    private ProcessCameraProvider cameraProvider;
    private androidx.camera.core.Camera camera;
    private ExecutorService cameraExecutor;
    private MutableLifecycle lifecycle;

    // 帧发送控制
    private long lastFrameSendTime = 0;
    private boolean isCapturing = false;

    // 调试：保存第一帧图像
    private boolean firstFrameSaved = false;
    private static final boolean DEBUG_SAVE_IMAGE = true;  // 设置为 false 关闭调试保存

    private ObstacleDetectionClient obstacleClient;
    private NavigationManager navigationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "BackgroundCameraService created (CameraX)");

        // 初始化客户端
        this.obstacleClient = ObstacleDetectionClient.getInstance(this);
        this.navigationManager = NavigationManager.getInstance(this);

        // 创建相机执行器（单线程即可）
        cameraExecutor = Executors.newSingleThreadExecutor();

        // 创建自定义 Lifecycle 用于 Service
        lifecycle = new MutableLifecycle(true);

        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification("避障模式准备中..."));

        // 设置回调
        obstacleClient.setCallback(new ObstacleDetectionClient.ObstacleCallback() {
            @Override
            public void onObstacleDetected(String warning, String urgency) {
                Log.i(TAG, "Obstacle detected: " + warning + ", urgency: " + urgency);
            }

            @Override
            public void onConnected() {
                Log.d(TAG, "Obstacle client connected to FastAPI");
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

        if (cameraExecutor != null) {
            cameraExecutor.shutdown();
        }
    }

    /**
     * 启动避障模式
     */
    private void startObstacleMode() {
        if (isCapturing) {
            Log.w(TAG, "Already capturing");
            return;
        }

        Log.d(TAG, "Starting obstacle mode (CameraX)");
        isRunning = true;
        isCapturing = true;
        lastFrameSendTime = 0;

        // 重置调试标志，每次启动都保存新的调试图像
        firstFrameSaved = false;

        // 连接到避障服务
        obstacleClient.connect(FASTAPI_OBSTACLE_URL);

        // 启动相机
        startCamera();

        updateNotification("避障模式运行中");
    }

    /**
     * 停止避障模式
     */
    private void stopObstacleMode() {
        Log.d(TAG, "Stopping obstacle mode");
        isRunning = false;
        isCapturing = false;
        lastFrameSendTime = 0;

        // 停止相机
        stopCamera();

        // 断开避障服务
        obstacleClient.disconnect();

        updateNotification("避障模式已停止");

        // 延迟停止服务
        new android.os.Handler(Looper.getMainLooper()).postDelayed(() -> {
            stopForeground(true);
            stopSelf();
        }, 1000);
    }

    /**
     * 启动相机（使用 CameraX）
     */
    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            // 获取完成后，切换到主线程绑定用例
            new android.os.Handler(Looper.getMainLooper()).post(() -> {
                try {
                    cameraProvider = cameraProviderFuture.get();
                    bindCameraUseCases();
                } catch (ExecutionException | InterruptedException e) {
                    Log.e(TAG, "Failed to get camera provider", e);
                    updateNotification("相机启动失败");
                }
            });
        }, Executors.newSingleThreadExecutor());
    }

    /**
     * 绑定相机用例
     */
    private void bindCameraUseCases() {
        if (cameraProvider == null) {
            Log.e(TAG, "Camera provider is null");
            return;
        }

        try {
            // 解绑所有用例
            cameraProvider.unbindAll();

            // 1. 预览用例（需要但可以不显示 SurfaceView）
            Preview previewUseCase = new Preview.Builder()
                    .build();

            // 2. 图像分析用例 - 这是获取帧的主要方式
            ImageAnalysis imageAnalysisUseCase = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setImageQueueDepth(1)
                    .build();

            imageAnalysisUseCase.setAnalyzer(cameraExecutor, new ImageAnalysis.Analyzer() {
                @Override
                public void analyze(@NonNull ImageProxy imageProxy) {
                    if (!isCapturing) {
                        imageProxy.close();
                        return;
                    }

                    // 控制发送频率
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastFrameSendTime < FRAME_SEND_INTERVAL_MS) {
                        imageProxy.close();
                        return;
                    }

                    try {
                        // 将 ImageProxy 转换为 Bitmap
                        Bitmap bitmap = imageToBitmap(imageProxy);
                        if (bitmap != null) {
                            lastFrameSendTime = currentTime;
                            Log.d(TAG, "Frame captured via CameraX: " + imageProxy.getWidth() + "x" + imageProxy.getHeight());
                            obstacleClient.sendFrame(bitmap, navigationManager.getCurrentSensorData());
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error processing frame", e);
                    } finally {
                        imageProxy.close();
                    }
                }
            });

            // 选择后置摄像头
            CameraSelector cameraSelector = new CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build();

            // 绑定用例到生命周期
            camera = cameraProvider.bindToLifecycle(
                    lifecycle,
                    cameraSelector,
                    previewUseCase,
                    imageAnalysisUseCase
            );

            Log.d(TAG, "CameraX started successfully");

        } catch (Exception e) {
            Log.e(TAG, "Failed to bind camera use cases", e);
            updateNotification("相机启动失败");
        }
    }

    /**
     * 停止相机
     */
    private void stopCamera() {
        // CameraX 需要在主线程操作
        new android.os.Handler(Looper.getMainLooper()).post(() -> {
            if (cameraProvider != null) {
                try {
                    cameraProvider.unbindAll();
                    Log.d(TAG, "CameraX stopped");
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping camera", e);
                }
            }
            camera = null;
        });
    }

    /**
     * 保存Bitmap到文件（用于调试）
     */
    private void saveDebugImage(Bitmap bitmap, String suffix) {
        if (!DEBUG_SAVE_IMAGE) {
            return;
        }

        try {
            File debugDir = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), "debug_frames");
            if (!debugDir.exists()) {
                debugDir.mkdirs();
            }

            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            File file = new File(debugDir, "frame_" + timestamp + "_" + suffix + ".jpg");

            FileOutputStream fos = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            fos.flush();
            fos.close();

            Log.i(TAG, "Debug image saved: " + file.getAbsolutePath() + ", size: " + bitmap.getWidth() + "x" + bitmap.getHeight());

        } catch (Exception e) {
            Log.e(TAG, "Failed to save debug image", e);
        }
    }

    /**
     * 将 ImageProxy 转换为 Bitmap
     */
    private Bitmap imageToBitmap(ImageProxy imageProxy) {
        try {
            // 获取 ImageProxy 中的 Image
            android.media.Image image = imageProxy.getImage();
            if (image == null) {
                return null;
            }

            // 检查格式
            int format = image.getFormat();
            if (format != ImageFormat.JPEG && format != ImageFormat.YUV_420_888) {
                Log.w(TAG, "Unsupported image format: " + format);
                return null;
            }

            Bitmap bitmap;
            if (format == ImageFormat.JPEG) {
                // JPEG 格式直接解码
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            } else {
                // YUV_420_888 格式需要转换
                bitmap = yuv420ToBitmap(image);
            }

            if (bitmap == null) {
                return null;
            }

            // 修正图像方向：CameraX 后置摄像头捕获的图像需要顺时针旋转90度
            Matrix matrix = new Matrix();
            matrix.postRotate(90f);
            Bitmap rotatedBitmap = Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotatedBitmap != bitmap) {
                bitmap.recycle();
            }
            bitmap = rotatedBitmap;

            Log.d(TAG, "Image rotated 90 degrees clockwise, final size: " + bitmap.getWidth() + "x" + bitmap.getHeight());

            // 保存第一帧用于调试
            if (!firstFrameSaved) {
                saveDebugImage(bitmap, "rotated");
                firstFrameSaved = true;
            }

            return bitmap;

        } catch (Exception e) {
            Log.e(TAG, "Error converting image to bitmap", e);
            return null;
        }
    }

    /**
     * 将 YUV_420_888 格式的 Image 转换为 Bitmap
     */
    private Bitmap yuv420ToBitmap(android.media.Image image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();

            // 获取 Y 平面
            android.media.Image.Plane[] planes = image.getPlanes();
            ByteBuffer yBuffer = planes[0].getBuffer();
            ByteBuffer uBuffer = planes[1].getBuffer();
            ByteBuffer vBuffer = planes[2].getBuffer();

            int ySize = yBuffer.remaining();
            int uSize = uBuffer.remaining();
            int vSize = vBuffer.remaining();

            byte[] data = new byte[ySize + uSize + vSize];

            // Y
            yBuffer.get(data, 0, ySize);

            // U 和 V
            int uvOffset = ySize;
            uBuffer.get(data, uvOffset, uSize);
            vBuffer.get(data, uvOffset + uSize, vSize);

            // 创建 YuvImage 并转换为 JPEG
            android.graphics.YuvImage yuvImage = new android.graphics.YuvImage(
                    data,
                    ImageFormat.NV21,
                    width,
                    height,
                    null
            );

            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            yuvImage.compressToJpeg(new android.graphics.Rect(0, 0, width, height), 90, outputStream);
            byte[] jpegData = outputStream.toByteArray();

            return BitmapFactory.decodeByteArray(jpegData, 0, jpegData.length);

        } catch (Exception e) {
            Log.e(TAG, "Error converting YUV to bitmap", e);
            return null;
        }
    }

    /**
     * 自定义 Lifecycle 用于 Service 环境
     */
    private static class MutableLifecycle implements LifecycleOwner {
        private boolean active;

        MutableLifecycle(boolean active) {
            this.active = active;
        }

        void setCurrentState(boolean active) {
            this.active = active;
        }

        @NonNull
        @Override
        public androidx.lifecycle.Lifecycle getLifecycle() {
            androidx.lifecycle.LifecycleRegistry registry = new androidx.lifecycle.LifecycleRegistry(this);
            if (active) {
                registry.setCurrentState(androidx.lifecycle.Lifecycle.State.STARTED);
            } else {
                registry.setCurrentState(androidx.lifecycle.Lifecycle.State.DESTROYED);
            }
            return registry;
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
     * 启动避障模式（静态方法）
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, BackgroundCameraService.class);
        intent.setAction("START_OBSTACLE_MODE");
        context.startForegroundService(intent);
    }

    /**
     * 停止避障模式（静态方法）
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
