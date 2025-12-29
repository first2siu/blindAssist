package com.example.test_android_dev.manager;

import android.accessibilityservice.AccessibilityService;
import android.view.Display;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 专门负责通过无障碍服务进行静默截屏的管理器
 * 要求：Android 11 (API 30) 及以上
 */
public class AccessibilityScreenshotManager {
    private static final String TAG = "AccessScreenshotMgr";

    // 单线程池，用于处理截图的耗时操作，避免阻塞主线程
    private final Executor executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static AccessibilityScreenshotManager instance;

    public static synchronized AccessibilityScreenshotManager getInstance() {
        if (instance == null) {
            instance = new AccessibilityScreenshotManager();
        }
        return instance;
    }

    public interface ScreenshotCallback {
        void onSuccess(String base64);
        void onFailure(String error);
    }

    /**
     * 核心截图方法
     * @param service 必须传入当前的 AutoGLMService 实例
     * @param callback 结果回调
     */
    public void capture(@NonNull AccessibilityService service, @NonNull ScreenshotCallback callback) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            callback.onFailure("系统版本过低，无障碍截图仅支持 Android 11+");
            return;
        }

        try {
            // 调用系统 API 进行截图
            service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    new AccessibilityService.TakeScreenshotCallback() {
                        @RequiresApi(api = Build.VERSION_CODES.R)
                        @Override
                        public void onSuccess(@NonNull AccessibilityService.ScreenshotResult screenshotResult) {
                            try {
                                HardwareBuffer buffer = screenshotResult.getHardwareBuffer();
                                ColorSpace colorSpace = screenshotResult.getColorSpace();

                                // 1. HardwareBuffer -> Bitmap
                                Bitmap bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace);

                                if (bitmap == null) {
                                    postFailure(callback, "生成 Bitmap 失败");
                                    buffer.close();
                                    return;
                                }

                                // 2. 复制一份 Bitmap 用于处理 (HardwareBuffer 包装的 Bitmap 通常是不可变的且必须尽快释放)
                                // 我们需要把它转为软件 Bitmap 或者是可以直接压缩的格式
                                Bitmap copy = bitmap.copy(Bitmap.Config.ARGB_8888, false);

                                // 立即释放硬件资源
                                buffer.close();
                                bitmap.recycle();

                                // 3. 压缩并转 Base64
                                String base64 = bitmapToBase64(copy);
                                copy.recycle(); // 释放内存

                                // 4. 回调主线程
                                mainHandler.post(() -> callback.onSuccess(base64));

                            } catch (Exception e) {
                                Log.e(TAG, "处理截图数据失败", e);
                                postFailure(callback, "处理截图异常: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            Log.e(TAG, "系统截图失败，错误码: " + errorCode);
                            postFailure(callback, "系统截图失败 Code: " + errorCode);
                        }
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "调用 takeScreenshot 异常", e);
            callback.onFailure("调用异常: " + e.getMessage());
        }
    }

    private void postFailure(ScreenshotCallback callback, String msg) {
        mainHandler.post(() -> callback.onFailure(msg));
    }

    /**
     * 图片压缩转 Base64
     * 策略：
     * 1. 缩放到合适尺寸 (例如宽 720 或 1080)，因为模型不需要超高清，太大会导致网络卡顿
     * 2. 压缩为 JPEG
     */
    private String bitmapToBase64(Bitmap bitmap) {
        // 目标最大边长 (根据你的模型需求调整，AutoGLM 建议 1000 左右)
        final int MAX_SIZE = 1080;

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        float scale = 1.0f;

        if (width > MAX_SIZE || height > MAX_SIZE) {
            scale = Math.min((float) MAX_SIZE / width, (float) MAX_SIZE / height);
        }

        Bitmap finalBitmap = bitmap;
        if (scale < 1.0f) {
            finalBitmap = Bitmap.createScaledBitmap(bitmap,
                    (int) (width * scale),
                    (int) (height * scale),
                    true);
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        // 压缩质量 70%，兼顾清晰度和体积
        finalBitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream);

        byte[] bytes = outputStream.toByteArray();

        // 如果创建了新的缩放图，记得回收
        if (finalBitmap != bitmap) {
            finalBitmap.recycle();
        }

        return Base64.encodeToString(bytes, Base64.NO_WRAP);
    }
}
