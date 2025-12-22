package com.example.test_android_dev;

import android.app.Activity;

/**
 * 封装摄像头采集，支持抓取单帧和视频流
 */
public class ImageCaptureManager {
    private static ImageCaptureManager instance;

    private ImageCaptureManager() {}

    public static synchronized ImageCaptureManager getInstance() {
        if (instance == null) {
            instance = new ImageCaptureManager();
        }
        return instance;
    }

    public void init(Activity activity) {
        // TODO: 初始化 CameraX 或 Camera2
    }

    public byte[] captureCurrentFrame() {
        // TODO: 从预览流抓取当前帧二进制数据
        return new byte[0];
    }

    public byte[] captureHighResFrame() {
        // TODO: 拍摄高分辨率照片
        return new byte[0];
    }
