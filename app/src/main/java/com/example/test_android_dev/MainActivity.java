//package com.example.test_android_dev;
//
//import android.Manifest;
//import android.content.pm.PackageManager;
//import android.os.Bundle;
//import android.widget.FrameLayout;
//
//import androidx.activity.EdgeToEdge;
//import androidx.annotation.NonNull;
//import androidx.appcompat.app.AppCompatActivity;
//import androidx.core.app.ActivityCompat;
//import androidx.core.content.ContextCompat;
//import androidx.core.graphics.Insets;
//import androidx.core.view.ViewCompat;
//import androidx.core.view.WindowInsetsCompat;
//
///**
// * 应用入口 Activity：
// * - 初始化语音、图像与网络模块
// * - 展示并集成功能轮盘
// */
//public class MainActivity extends AppCompatActivity {
//
//    private static final int REQ_PERMISSIONS = 1001;
//    private FeatureWheelView featureWheelView;
//
//    @Override
//    protected void onCreate(Bundle savedInstanceState) {
//        super.onCreate(savedInstanceState); EdgeToEdge.enable(this);
//        setContentView(R.layout.activity_main);
//        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
//            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
//            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
//            return insets;
//        });
//
//        requestBasicPermissions();
//        initCoreManagers();
//        setupFeatureWheel();
//
//        // 精心编排启动语音：先播报欢迎语，完成后再播报当前功能
//        VoiceManager.getInstance().speakImmediate("欢迎使用随行助手。请上下滑动屏幕选择功能，双击进入。", () -> {
//            if (featureWheelView != null) {
//                // 使用常规 speak，加入到欢迎语之后的队列
//                VoiceManager.getInstance().speak(featureWheelView.getCurrentFeature().getDescription());
//            }
//        });
//    }
//
//    private void requestBasicPermissions() {
//        String[] permissions = new String[]{
//                Manifest.permission.RECORD_AUDIO,
//                Manifest.permission.CAMERA,
//                Manifest.permission.INTERNET
//        };
//        boolean needRequest = false;
//        for (String p : permissions) {
//            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
//                needRequest = true;
//                break;
//            }
//        }
//        if (needRequest) {
//            ActivityCompat.requestPermissions(this, permissions, REQ_PERMISSIONS);
//        }
//    }
//
//    private void initCoreManagers() {
//        VoiceManager.getInstance().init(getApplicationContext());
//        ImageCaptureManager.getInstance().init(this);
//        NetworkClient.getInstance().init(getApplicationContext());
//        FeatureRouter.getInstance().init(getApplicationContext());
//    }
//
//    private void setupFeatureWheel() {
//        FrameLayout container = findViewById(R.id.feature_wheel_container);
//        featureWheelView = new FeatureWheelView(this);
//        container.addView(featureWheelView);
//
//        featureWheelView.setOnFeatureSelectedListener(new FeatureWheelView.OnFeatureSelectedListener() {
//            @Override
//            public void onItemSelected(FeatureType feature) {
//                // 滑动到某项，播报功能名
//                VoiceManager.getInstance().speak(feature.getDescription());
//            }
//
//            @Override
//            public void onItemConfirmed(FeatureType feature) {
//                // 双击确认，跳转到对应的功能流程
//                FeatureRouter.getInstance().route(feature);
//            }
//        });
//    }
//
//    @Override
//    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
//                                           @NonNull int[] grantResults) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
//        if (requestCode == REQ_PERMISSIONS) {
//            for (int grantResult : grantResults) {
//                if (grantResult != PackageManager.PERMISSION_GRANTED) {
//                    VoiceManager.getInstance().speak("权限被拒绝，部分功能可能无法使用。请在设置中开启权限。");
//                }
//            }
//        }
//    }
//}


package com.example.test_android_dev;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.test_android_dev.manager.AgentManager;
import com.example.test_android_dev.service.AutoGLMService;

public class MainActivity extends AppCompatActivity {

    private EditText etCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 1. 初始化 AgentManager (如果你使用了 Context 注入方式)
        // 如果你用了 App.java 全局 Context 方式，这一行可以省略
//        AgentManager.getInstance().init(getApplicationContext());

        // 2. 绑定控件
        etCommand = findViewById(R.id.et_command);
        Button btnStart = findViewById(R.id.btn_start_test);
        Button btnStop = findViewById(R.id.btn_stop_test);

        // 3. 设置点击事件
        btnStart.setOnClickListener(v -> {
            String command = etCommand.getText().toString().trim();
            if (TextUtils.isEmpty(command)) {
                Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show();
                return;
            }
            // 执行测试
            startTest(command);
        });

        btnStop.setOnClickListener(v -> {
            AgentManager.getInstance().stopTask();
            Toast.makeText(this, "任务已停止", Toast.LENGTH_SHORT).show();
        });
    }

    private void startTest(String command) {
        // A. 检查无障碍服务是否开启
        // AutoGLMService.getInstance() 在服务未连接时为 null
        if (AutoGLMService.getInstance() == null) {
            Toast.makeText(this, "请先开启无障碍服务！", Toast.LENGTH_LONG).show();
            // 跳转到系统设置
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            return;
        }

        // B. 获取屏幕宽高 (这对坐标转换至关重要)
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        // C. 直接启动 Agent 任务
        // 这会触发: 连接 WebSocket -> 截图 -> 发送给 SpringBoot -> 转发给 Python
        Toast.makeText(this, "发送指令: " + command, Toast.LENGTH_SHORT).show();
        AgentManager.getInstance().startTask(command, width, height);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 退出 App 时断开连接，防止后台持续截图
        AgentManager.getInstance().stopTask();
    }
}