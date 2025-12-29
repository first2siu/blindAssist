package com.example.test_android_dev.service;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.Map;
import java.util.Objects;

import com.example.test_android_dev.utils.AppRegistry;

public class AutoGLMService extends AccessibilityService{
    private static final String TAG = "AutoGLMService";
    private static AutoGLMService instance;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.d(TAG, "AutoGLM 无障碍服务已连接");
        Toast.makeText(this, "AutoGLM 服务已启动", Toast.LENGTH_SHORT).show();
    }

    public static AutoGLMService getInstance() {
        return instance;
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
        // 监听事件（可选），此处暂不需要主动处理
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "服务被中断");
        instance = null;
    }

    /**
     * 核心执行入口：根据 action 字典分发指令
     */
    public boolean executeAction(Map<String, Object> action) {
        if (action == null || !action.containsKey("action")) return false;

        String type = (String) action.get("action");
        Log.d(TAG, "执行指令: " + type);

        try {
            switch (Objects.requireNonNull(type)) {
                case "Launch":
                    return doLaunch((String) action.get("app"));
                case "Tap":
                    return doTap(parsePoint(action.get("element")));
                case "Type":
                    return doType((String) action.get("text"));
                case "Swipe":
                    return doSwipe(parsePoint(action.get("start")), parsePoint(action.get("end")), parseDuration(action.get("duration")));
                case "Back":
                    return performGlobalAction(GLOBAL_ACTION_BACK);
                case "Home":
                    return performGlobalAction(GLOBAL_ACTION_HOME);
                case "Double Tap":
                    return doDoubleTap(parsePoint(action.get("element")));
                case "Long Press":
                    return doLongPress(parsePoint(action.get("element")), parseDuration(action.get("duration_ms")));
                case "Wait":
                    return doWait(parseDuration(action.get("duration")));
                case "Take_over":
                    return doTakeOver((String) action.get("message"));
                default:
                    Log.w(TAG, "未知的指令类型: " + type);
                    return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "执行失败: " + e.getMessage());
            return false;
        }
    }

    // === 具体动作实现 ===

    // 1. Launch App
    private boolean doLaunch(String appName) {
        if (appName == null) return false;

        Log.d(TAG, "请求启动: " + appName);
        String packageName = null;

        // 策略 1: 查静态注册表 (最快、最准)
        // 专门处理 GLM 模型熟悉的常用 App
        packageName = AppRegistry.getPackageName(appName);

        if (packageName != null) {
            Log.d(TAG, "Hit Registry: " + appName + " -> " + packageName);
        } else {
            // 策略 2: 查不到，尝试去搜手机里已安装的应用 (模糊匹配)
            // 适用于用户手机里装了冷门 App
            packageName = findPackageNameByLabel(appName);
        }

        // 策略 3: 还是找不到，可能 AI 传过来的本来就是包名，死马当活马医
        if (packageName == null) {
            packageName = appName;
        }

        // 执行启动
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launchIntent);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "启动异常: " + e.getMessage());
        }

        Log.e(TAG, "启动失败: " + appName + " (Target Package: " + packageName + ")");
        return false;
    }

    // 2. Tap (点击)
    private boolean doTap(int[] coords) {
        if (coords == null) return false;
        return dispatchGesture(createClickGesture(coords[0], coords[1]), null, null);
    }

    // 3. Type (输入文本)
    private boolean doType(String text) {
        if (text == null) return false;
        // 策略：找到当前获得焦点的可编辑节点，设置文本
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo focusNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focusNode != null && focusNode.isEditable()) {
            Bundle arguments = new Bundle();
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean result = focusNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
            focusNode.recycle();
            return result;
        } else {
            // 如果没找到焦点，尝试粘贴 (需要 Android 5.0+)
            // Clipboard logic can be added here
            Log.w(TAG, "未找到输入框焦点");
            return false;
        }
    }

    // 4. Swipe (滑动)
    private boolean doSwipe(int[] start, int[] end, int duration) {
        if (start == null || end == null) return false;
        if (duration <= 0) duration = 500; // 默认 500ms

        Path path = new Path();
        path.moveTo(start[0], start[1]);
        path.lineTo(end[0], end[1]);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGesture(builder.build(), null, null);
    }

    // 5. Double Tap (双击)
    private boolean doDoubleTap(int[] coords) {
        if (coords == null) return false;
        Path path = new Path();
        path.moveTo(coords[0], coords[1]);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        // 第一下点击
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 50));
        // 第二下点击 (间隔 100ms)
        builder.addStroke(new GestureDescription.StrokeDescription(path, 150, 50));

        return dispatchGesture(builder.build(), null, null);
    }

    // 6. Long Press (长按)
    private boolean doLongPress(int[] coords, int duration) {
        if (coords == null) return false;
        if (duration <= 0) duration = 1000; // 默认长按 1秒

        Path path = new Path();
        path.moveTo(coords[0], coords[1]);

        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));
        return dispatchGesture(builder.build(), null, null);
    }

    // 7. Wait (等待)
    private boolean doWait(int duration) {
        if (duration <= 0) duration = 1000;
        try {
            Thread.sleep(duration); // 注意：这会阻塞调用线程 (AgentManager的后台线程)
            return true;
        } catch (InterruptedException e) {
            e.printStackTrace();
            return false;
        }
    }

    // 8. Take Over (接管/提示)
    private boolean doTakeOver(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(this, "AI 请求接管: " + message, Toast.LENGTH_LONG).show()
        );
        // 这里可以扩展为发通知或震动
        return true;
    }

    // === 辅助方法 ===

    private GestureDescription createClickGesture(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        return builder.build();
    }

    // 解析坐标 [x, y]
    private int[] parsePoint(Object element) {
        try {
            // 假设传入的是 JSON 数组或 List，且已由 SpringBoot/Gson 转为 List 或 int[]
            // 这里根据实际数据结构适配
            if (element instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) element;
                int x = ((Number) list.get(0)).intValue();
                int y = ((Number) list.get(1)).intValue();
                return new int[]{x, y};
            }
        } catch (Exception e) {
            Log.e(TAG, "坐标解析失败: " + element);
        }
        return null;
    }

    // 解析时间 duration
    private int parseDuration(Object durationObj) {
        try {
            if (durationObj instanceof Number) {
                return ((Number) durationObj).intValue();
            } else if (durationObj instanceof String) {
                String s = (String) durationObj;
                if (s.contains("seconds")) {
                    return (int) (Double.parseDouble(s.replace("seconds", "").trim()) * 1000);
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private String findPackageNameByLabel(String targetLabel) {
        android.content.pm.PackageManager pm = getPackageManager();
        try {
            // 这里依然获取所有包，没问题
            java.util.List<android.content.pm.ApplicationInfo> packages = pm.getInstalledApplications(android.content.pm.PackageManager.GET_META_DATA);

            for (android.content.pm.ApplicationInfo packageInfo : packages) {
                try {
                    CharSequence labelSeq = pm.getApplicationLabel(packageInfo);
                    String label = labelSeq != null ? labelSeq.toString() : "";

                    // 匹配逻辑
                    if (label.equalsIgnoreCase(targetLabel) ||
                            label.contains(targetLabel) ||
                            targetLabel.contains(label)) {

                        // 🔥🔥🔥 核心修复：多加这一层判断 🔥🔥🔥
                        // 问系统：这个包能通过 launchIntent 启动吗？
                        // com.android.providers.settings 会返回 null
                        // com.android.settings 会返回 Intent
                        if (pm.getLaunchIntentForPackage(packageInfo.packageName) != null) {
                            Log.d(TAG, "找到可启动应用: " + label + " -> " + packageInfo.packageName);
                            return packageInfo.packageName;
                        } else {
                            Log.d(TAG, "跳过不可启动应用: " + label + " -> " + packageInfo.packageName);
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Search failed: " + t.getMessage());
        }
        return null;
    }
}
