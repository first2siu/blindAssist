package com.example.test_android_dev;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.example.test_android_dev.manager.AgentManager;
import com.example.test_android_dev.manager.SoundManager;
import com.example.test_android_dev.manager.TaskStateManager;
import com.example.test_android_dev.model.TaskState;
import com.example.test_android_dev.service.AutoGLMService;
import com.example.test_android_dev.asr.AsrManager;
import com.example.test_android_dev.LocationHelper;

/**
 * 应用入口 Activity
 * - 支持两种模式：用户语音界面 和 开发测试界面
 * - 通过 Config.DEBUG_MODE 控制界面切换
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQ_PERMISSIONS = 1001;

    // 语音界面相关
    private Button voiceButton;
    private TextView statusText;
    private VoiceManager.VoiceCallback currentVoiceCallback;
    
    // 按钮状态管理
    private volatile boolean isButtonPressed = false; // 按钮是否被按下（正在录音）
    private volatile boolean isRecognizing = false;   // 是否正在识别（已停止录音，等待结果）
    
    // 识别超时处理
    private android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable recognitionTimeoutRunnable;
    private static final long RECOGNITION_TIMEOUT = 8000; // 8秒超时

    // 测试界面相关
    private EditText etCommand;
    private LinearLayout debugLayout;
    private RelativeLayout voiceLayout;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        requestBasicPermissions();
        initCoreManagers();
        setupUI();
        
        // 检查是否有未完成的任务
        checkIncompleteTask();
    }
    
    /**
     * 检查并恢复未完成的任务
     */
    private void checkIncompleteTask() {
        if (AgentManager.getInstance().hasIncompleteTask()) {
            AgentManager.getInstance().promptTaskRecovery(this, new TaskStateManager.TaskRecoveryCallback() {
                @Override
                public void onTaskRecovered(TaskState state) {
                    Log.d(TAG, "恢复任务: " + state.getTaskPrompt());
                    statusText.setText("恢复任务中...");
                    AgentManager.getInstance().resumeTask(state);
                }

                @Override
                public void onTaskDiscarded() {
                    Log.d(TAG, "用户放弃恢复任务");
                }
            });
        }
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        // 从后台恢复时检查连接状态
        AgentManager.getInstance().checkAndReconnectIfNeeded();
    }

    private void setupUI() {
        // 绑定两套界面的控件
        voiceLayout = findViewById(R.id.voice_layout);
        debugLayout = findViewById(R.id.debug_layout);
        voiceButton = findViewById(R.id.voice_button);
        statusText = findViewById(R.id.status_text);
        etCommand = findViewById(R.id.et_command);
        Button btnStart = findViewById(R.id.btn_start_test);
        Button btnStop = findViewById(R.id.btn_stop_test);

        // 根据配置显示不同界面
        if (Config.DEBUG_MODE) {
            // 显示开发测试界面
            voiceLayout.setVisibility(View.GONE);
            debugLayout.setVisibility(View.VISIBLE);
            setupDebugUI(btnStart, btnStop);
        } else {
            // 显示用户语音界面
            voiceLayout.setVisibility(View.VISIBLE);
            debugLayout.setVisibility(View.GONE);
            setupVoiceUI();
        }
    }

    /**
     * 设置语音交互界面（用户模式）
     * 按住说话逻辑：
     * - 按下：开始录音，显示"正在听..."
     * - 松开：停止录音，显示"识别中..."，等待结果
     * - 识别中：不响应任何点击
     */
    @SuppressLint("ClickableViewAccessibility")
    private void setupVoiceUI() {
        voiceButton.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    Log.d(TAG, "ACTION_DOWN, isRecognizing=" + isRecognizing + ", isButtonPressed=" + isButtonPressed);
                    
                    // 识别中状态，忽略所有点击
                    if (isRecognizing) {
                        Log.d(TAG, "正在识别中，忽略点击");
                        return true;
                    }
                    
                    // 防止重复按下
                    if (isButtonPressed) {
                        Log.d(TAG, "按钮已按下，忽略");
                        return true;
                    }
                    
                    isButtonPressed = true;
                    
                    // 播放按下动画
                    Animation pressAnim = AnimationUtils.loadAnimation(this, R.anim.button_press);
                    voiceButton.startAnimation(pressAnim);

                    // 检查语音识别是否可用
                    if (!VoiceManager.getInstance().isAsrAvailable()) {
                        isButtonPressed = false;
                        showTextInputDialog();
                        return true;
                    }

                    // 播放开始录音提示音
                    SoundManager.getInstance().playStartTone();
                    
                    // 开始录音
                    startVoiceRecognition();
                    v.performClick();
                    break;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    Log.d(TAG, "ACTION_UP/CANCEL, isButtonPressed=" + isButtonPressed);
                    
                    // 如果不是按下状态，忽略
                    if (!isButtonPressed) {
                        Log.d(TAG, "按钮未按下，忽略松开事件");
                        return true;
                    }
                    
                    isButtonPressed = false;
                    
                    // 播放释放动画
                    Animation releaseAnim = AnimationUtils.loadAnimation(this, R.anim.button_release);
                    voiceButton.startAnimation(releaseAnim);
                    
                    // 播放结束录音提示音
                    SoundManager.getInstance().playStopTone();
                    
                    // 进入识别状态
                    isRecognizing = true;
                    voiceButton.setText("识别中...");
                    statusText.setText("正在识别...");
                    
                    // 停止录音，等待识别结果
                    Log.d(TAG, "用户松开按钮，停止录音，等待识别结果");
                    VoiceManager.getInstance().stopListening();
                    
                    // 设置识别超时
                    startRecognitionTimeout();
                    break;
            }
            return true;
        });

        // 显示当前使用的 ASR 引擎
        String engineName = VoiceManager.getInstance().getCurrentAsrEngineName();
        Log.i(TAG, "当前 ASR 引擎: " + engineName);
    }
    
    /**
     * 开始语音识别
     */
    private void startVoiceRecognition() {
        voiceButton.setText("正在听...");
        voiceButton.setBackground(getDrawable(R.drawable.btn_speak_background));
        statusText.setText("请说话...");

        currentVoiceCallback = new VoiceManager.VoiceCallback() {
            @Override
            public void onResult(String text) {
                Log.d(TAG, "语音识别成功: " + text);
                runOnUiThread(() -> {
                    cancelRecognitionTimeout();
                    resetButtonState();
                    statusText.setText("处理中...");
                });
                handleVoiceResult(text);
            }

            @Override
            public void onError(String error) {
                Log.d(TAG, "语音识别错误: " + error);
                runOnUiThread(() -> {
                    cancelRecognitionTimeout();
                    resetButtonState();
                    // 根据错误类型显示不同提示
                    if (error.contains("网络")) {
                        statusText.setText("网络错误");
                    } else if (error.contains("超时")) {
                        statusText.setText("识别超时");
                    } else {
                        statusText.setText("请重试");
                    }
                });
                
                // 根据错误类型播报不同提示
                if (error.contains("网络") || error.contains("超时")) {
                    VoiceManager.getInstance().speakImmediate("网络连接失败，请检查网络后重试");
                } else if (!"客户端错误".equals(error) && !"识别器繁忙".equals(error) && !"识别器繁忙，请稍后重试".equals(error)) {
                    VoiceManager.getInstance().speakImmediate("没有听到声音，请重试");
                }
            }
        };
        VoiceManager.getInstance().startListening(currentVoiceCallback);
    }
    
    /**
     * 开始识别超时计时
     */
    private void startRecognitionTimeout() {
        cancelRecognitionTimeout();
        recognitionTimeoutRunnable = () -> {
            Log.w(TAG, "识别超时，强制重置");
            VoiceManager.getInstance().cancelListening();
            resetButtonState();
            statusText.setText("识别超时");
            VoiceManager.getInstance().speakImmediate("识别超时，请重试");
        };
        timeoutHandler.postDelayed(recognitionTimeoutRunnable, RECOGNITION_TIMEOUT);
    }
    
    /**
     * 取消识别超时计时
     */
    private void cancelRecognitionTimeout() {
        if (recognitionTimeoutRunnable != null) {
            timeoutHandler.removeCallbacks(recognitionTimeoutRunnable);
            recognitionTimeoutRunnable = null;
        }
    }
    
    /**
     * 重置按钮状态
     */
    private void resetButtonState() {
        voiceButton.setText("按住说话");
        voiceButton.setBackground(getDrawable(R.drawable.btn_speak_material));
        isRecognizing = false;
        isButtonPressed = false;
        currentVoiceCallback = null;
    }

    /**
     * 设置开发测试界面（调试模式）
     */
    private void setupDebugUI(Button btnStart, Button btnStop) {
        btnStart.setOnClickListener(v -> {
            String command = etCommand.getText().toString().trim();
            if (TextUtils.isEmpty(command)) {
                Toast.makeText(this, "请输入指令", Toast.LENGTH_SHORT).show();
                return;
            }
            startTest(command);
        });

        btnStop.setOnClickListener(v -> {
            AgentManager.getInstance().stopTask();
            Toast.makeText(this, "任务已停止", Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 处理语音识别结果
     */
    private void handleVoiceResult(String text) {
        Log.i(TAG, "识别结果: " + text);
        VoiceManager.getInstance().speak("好的，正在处理您的指令: " + text);
        statusText.setText("执行中: " + text);

        // 检查无障碍服务
        AutoGLMService service = AutoGLMService.getInstance();
        Log.d(TAG, "AutoGLMService.getInstance() 返回: " + (service != null ? "非空" : "null"));

        if (service == null) {
            // 检查系统中是否已启用无障碍服务（不依赖静态变量）
            boolean isEnabled = AutoGLMService.isServiceEnabled(this);
            Log.d(TAG, "系统中无障碍服务启用状态: " + isEnabled);

            if (!isEnabled) {
                Log.w(TAG, "无障碍服务未启用，引导用户开启");
                Toast.makeText(this, "请先开启无障碍服务！", Toast.LENGTH_LONG).show();
                VoiceManager.getInstance().speak("请先开启无障碍服务");
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
                return;
            } else {
                // 服务已启用但 instance 为空，等待服务连接
                Log.w(TAG, "无障碍服务已启用，但尚未连接，等待连接...");
                Toast.makeText(this, "正在连接无障碍服务...", Toast.LENGTH_SHORT).show();
                // 短暂延迟后重试（给系统时间连接服务）
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    retryHandleVoiceResult(text);
                }, 1000);
                return;
            }
        }

        Log.d(TAG, "无障碍服务已启动，准备启动任务");

        // 获取屏幕尺寸并启动任务
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        Log.d(TAG, "屏幕尺寸: " + width + "x" + height);
        AgentManager.getInstance().startTask(text, width, height);
    }

    /**
     * 重试处理语音结果（等待服务连接后）
     */
    private void retryHandleVoiceResult(String text) {
        AutoGLMService service = AutoGLMService.getInstance();
        if (service != null) {
            Log.d(TAG, "服务已连接，继续执行任务");
            DisplayMetrics metrics = new DisplayMetrics();
            getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
            int width = metrics.widthPixels;
            int height = metrics.heightPixels;
            AgentManager.getInstance().startTask(text, width, height);
        } else {
            Log.w(TAG, "服务仍未连接，提示用户");
            Toast.makeText(this, "无障碍服务连接超时，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 开发测试模式：手动输入指令测试
     */
    private void startTest(String command) {
        // 检查无障碍服务是否开启
        AutoGLMService service = AutoGLMService.getInstance();
        if (service == null && !AutoGLMService.isServiceEnabled(this)) {
            Toast.makeText(this, "请先开启无障碍服务！", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
            return;
        } else if (service == null) {
            Toast.makeText(this, "等待无障碍服务连接...", Toast.LENGTH_SHORT).show();
            return;
        }

        // 获取屏幕宽高
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        // 启动 Agent 任务
        Toast.makeText(this, "发送指令: " + command, Toast.LENGTH_SHORT).show();
        AgentManager.getInstance().startTask(command, width, height);
    }

    /**
     * 显示文本输入对话框（语音识别不可用时的备选方案）
     */
    private void showTextInputDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("文本输入");
        builder.setMessage("语音识别不可用，请输入您的指令：");

        final EditText input = new EditText(this);
        input.setHint("请输入您的问题或指令...");
        input.setInputType(InputType.TYPE_CLASS_TEXT);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.setMargins(40, 0, 40, 0);
        input.setLayoutParams(lp);

        builder.setView(input);

        builder.setPositiveButton("确定", (dialog, which) -> {
            String text = input.getText().toString().trim();
            if (!text.isEmpty()) {
                handleVoiceResult(text);
            } else {
                VoiceManager.getInstance().speak("请输入有效的指令");
            }
        });

        builder.setNegativeButton("取消", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void initCoreManagers() {
        Log.d(TAG, "========== initCoreManagers() 开始 ==========");
        
        // 先显示加载状态
        if (voiceButton != null) {
            voiceButton.setVisibility(View.INVISIBLE);
        }
        if (statusText != null) {
            statusText.setText("正在初始化语音引擎...");
        }
        
        Log.d(TAG, "准备调用 VoiceManager.init()");
        
        // 初始化 VoiceManager，带回调
        VoiceManager.getInstance().init(getApplicationContext(), new AsrManager.InitCallback() {
            @Override
            public void onInitStart() {
                runOnUiThread(() -> {
                    if (statusText != null) {
                        statusText.setText("正在初始化语音引擎...");
                    }
                });
            }
            
            @Override
            public void onInitProgress(String message) {
                runOnUiThread(() -> {
                    if (statusText != null) {
                        statusText.setText(message);
                    }
                });
            }
            
            @Override
            public void onInitComplete() {
                runOnUiThread(() -> {
                    Log.i(TAG, "语音引擎初始化完成");
                    if (voiceButton != null) {
                        voiceButton.setVisibility(View.VISIBLE);
                    }
                    if (statusText != null) {
                        statusText.setText("按住按钮说话");
                    }
                    // 播报欢迎语
                    VoiceManager.getInstance().speakImmediate("欢迎使用，请按住屏幕中央的按钮对我说话");
                });
            }
            
            @Override
            public void onInitFailed(String error) {
                runOnUiThread(() -> {
                    Log.e(TAG, "语音引擎初始化失败: " + error);
                    if (voiceButton != null) {
                        voiceButton.setVisibility(View.VISIBLE);
                    }
                    if (statusText != null) {
                        statusText.setText("初始化失败，点击重试");
                    }
                    Toast.makeText(MainActivity.this, "语音引擎初始化失败: " + error, Toast.LENGTH_LONG).show();
                });
            }
        });
        
        // 配置讯飞语音识别（如果已配置凭证）
        if (!Config.XUNFEI_APP_ID.isEmpty() && !Config.XUNFEI_API_KEY.isEmpty() && !Config.XUNFEI_API_SECRET.isEmpty()) {
            VoiceManager.getInstance().configureXunfeiAsr(
                    Config.XUNFEI_APP_ID,
                    Config.XUNFEI_API_KEY,
                    Config.XUNFEI_API_SECRET
            );
            Log.i(TAG, "讯飞 ASR 已配置");
        } else {
            Log.w(TAG, "讯飞 ASR 未配置，将使用系统语音识别（需要 Google 服务或国产手机自带服务）");
        }
        
        ImageCaptureManager.getInstance().init(this);
        NetworkClient.getInstance().init(getApplicationContext());

        // 初始化提示音管理器
        SoundManager.getInstance().init(getApplicationContext());

        // 初始化 GPS 定位助手
        LocationHelper.getInstance(getApplicationContext()).init(getApplicationContext());
        Log.i(TAG, "LocationHelper 已初始化");

        // 初始化AgentManager（后台保活功能）
        AgentManager.getInstance().init(getApplicationContext());
    }

    private void requestBasicPermissions() {
        String[] permissions = new String[]{
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
        boolean allGranted = true;
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQ_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                VoiceManager.getInstance().speak("感谢授权");
            } else {
                VoiceManager.getInstance().speak("权限被拒绝，部分功能可能无法使用");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (currentVoiceCallback != null) {
            currentVoiceCallback = null;
        }
        AgentManager.getInstance().stopTask();
        ImageCaptureManager.getInstance().release();
        SoundManager.getInstance().release();
        VoiceManager.getInstance().destroy();
    }
}
