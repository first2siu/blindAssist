package com.example.test_android_dev;

import android.app.Application;
import android.content.Context;

public class App extends Application {
    private static Context context;

    @Override
    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
    }

    // 这就是你代码里缺少的那个方法
    public static Context getContext() {
        return context;
    }
}