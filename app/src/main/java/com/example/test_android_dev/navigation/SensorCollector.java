package com.example.test_android_dev.navigation;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.example.test_android_dev.manager.WebSocketConnectionManager;

/**
 * 传感器数据收集器
 * 收集GPS位置、手机朝向等导航所需数据
 */
public class SensorCollector {
    private static final String TAG = "SensorCollector";
    private static final long MIN_TIME_BETWEEN_UPDATES = 1000; // 1秒
    private static final float MIN_DISTANCE_FOR_UPDATE = 1.0f; // 1米

    private final Context context;
    private final SensorManager sensorManager;
    private final LocationManager locationManager;

    // 传感器数据
    private float heading = 0f; // 手机朝向（0-360度）
    private double latitude = 0.0;
    private double longitude = 0.0;
    private double altitude = 0.0;
    private float accuracy = 0f;

    // 监听器
    private final SensorEventListener orientationListener;
    private final LocationListener locationListener;

    // 回调接口
    public interface SensorDataCallback {
        void onSensorDataChanged(SensorData data);
    }

    private SensorDataCallback callback;

    public SensorCollector(Context context) {
        this.context = context.getApplicationContext();
        this.sensorManager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);

        // 朝向传感器监听器
        this.orientationListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
                    // azimuth[0]: 方向角，0=北，90=东，180=南，270=西
                    float newHeading = event.values[0];
                    if (Math.abs(newHeading - heading) > 1.0f) { // 变化超过1度才更新
                        heading = newHeading;
                        notifyCallback();
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // 忽略
            }
        };

        // 位置监听器
        this.locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                latitude = location.getLatitude();
                longitude = location.getLongitude();
                altitude = location.getAltitude();
                accuracy = location.getAccuracy();
                notifyCallback();
            }

            @Override
            public void onStatusChanged(String provider, int status, Bundle extras) {
                Log.d(TAG, "Location status changed: " + provider + ", status=" + status);
            }

            @Override
            public void onProviderEnabled(String provider) {
                Log.d(TAG, "Location provider enabled: " + provider);
            }

            @Override
            public void onProviderDisabled(String provider) {
                Log.w(TAG, "Location provider disabled: " + provider);
            }
        };
    }

    /**
     * 设置数据变化回调
     */
    public void setCallback(SensorDataCallback callback) {
        this.callback = callback;
    }

    /**
     * 启动传感器数据收集
     */
    public void start() {
        Log.d(TAG, "Starting sensor collection");

        // 注册朝向传感器
        Sensor orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION);
        if (orientationSensor != null) {
            sensorManager.registerListener(orientationListener, orientationSensor,
                    SensorManager.SENSOR_DELAY_UI);
            Log.d(TAG, "Orientation sensor registered");
        } else {
            Log.w(TAG, "Orientation sensor not available");
        }

        // 注册位置监听
        try {
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER,
                    MIN_TIME_BETWEEN_UPDATES, MIN_DISTANCE_FOR_UPDATE,
                    ContextCompat.getMainExecutor(context), locationListener);

            // 同时使用网络定位作为备份
            locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                    MIN_TIME_BETWEEN_UPDATES, MIN_DISTANCE_FOR_UPDATE,
                    ContextCompat.getMainExecutor(context), locationListener);

            Log.d(TAG, "Location listeners registered");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
    }

    /**
     * 停止传感器数据收集
     */
    public void stop() {
        Log.d(TAG, "Stopping sensor collection");
        sensorManager.unregisterListener(orientationListener);
        locationManager.removeUpdates(locationListener);
    }

    /**
     * 获取当前传感器数据
     */
    public SensorData getCurrentData() {
        return new SensorData(latitude, longitude, altitude, heading, accuracy);
    }

    /**
     * 获取当前朝向（0-360度）
     */
    public float getHeading() {
        return heading;
    }

    /**
     * 获取朝向的文字描述
     */
    public String getHeadingText() {
        if (heading >= 337.5 || heading < 22.5) return "北";
        if (heading >= 22.5 && heading < 67.5) return "东北";
        if (heading >= 67.5 && heading < 112.5) return "东";
        if (heading >= 112.5 && heading < 157.5) return "东南";
        if (heading >= 157.5 && heading < 202.5) return "南";
        if (heading >= 202.5 && heading < 247.5) return "西南";
        if (heading >= 247.5 && heading < 292.5) return "西";
        return "西北";
    }

    /**
     * 通知回调
     */
    private void notifyCallback() {
        if (callback != null) {
            callback.onSensorDataChanged(getCurrentData());
        }
    }

    /**
     * 传感器数据类
     */
    public static class SensorData {
        public final double latitude;
        public final double longitude;
        public final double altitude;
        public final float heading;    // 0-360度
        public final float accuracy;   // 位置精度（米）
        public final long timestamp;

        public SensorData(double latitude, double longitude, double altitude,
                         float heading, float accuracy) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
            this.heading = heading;
            this.accuracy = accuracy;
            this.timestamp = System.currentTimeMillis();
        }

        /**
         * 判断位置数据是否有效
         */
        public boolean isValid() {
            return latitude != 0.0 && longitude != 0.0;
        }

        @Override
        public String toString() {
            return String.format("位置:(%.6f,%.6f) 朝向:%.1f° 精度:%.0fm",
                    latitude, longitude, heading, accuracy);
        }
    }
}
