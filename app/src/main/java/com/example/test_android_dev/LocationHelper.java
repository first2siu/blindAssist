package com.example.test_android_dev;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import com.example.test_android_dev.model.LocationInfo;

/**
 * GPS 定位助手
 * 获取用户当前位置信息
 */
public class LocationHelper {
    private static final String TAG = "LocationHelper";
    private static final long MIN_TIME_MS = 0; // 立即获取
    private static final float MIN_DISTANCE_M = 0; // 不要求最小距离

    private static LocationHelper instance;
    private final Context appContext;
    private LocationManager locationManager;
    private LocationInfo lastKnownLocation;

    private LocationHelper(Context context) {
        this.appContext = context.getApplicationContext();
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void init(Context context) {
        // 注意：appContext 和 locationManager 已在构造函数中初始化
        // 这里只用于预热缓存位置
        Log.d(TAG, "LocationHelper 已初始化");

        // 预热：尝试获取缓存位置（带异常保护，避免初始化时崩溃）
        try {
            LocationInfo cached = getCachedLocation();
            if (cached != null) {
                Log.i(TAG, "已找到缓存位置: " + cached.getLatitude() + ", " + cached.getLongitude());
            } else {
                Log.w(TAG, "暂无缓存位置，将在首次使用时获取");
            }
        } catch (Exception e) {
            Log.w(TAG, "初始化时获取缓存位置失败，将在首次使用时获取: " + e.getMessage());
            // 不抛出异常，允许应用继续运行
        }
    }

    public static synchronized LocationHelper getInstance(Context context) {
        if (instance == null) {
            instance = new LocationHelper(context);
        }
        return instance;
    }

    public static synchronized LocationHelper getInstance() {
        return instance;
    }

    /**
     * 检查 LocationHelper 是否已初始化
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * 获取当前位置（异步）
     * 使用回调返回位置信息
     */
    public void getCurrentLocation(LocationCallback callback) {
        if (!hasLocationPermission()) {
            Log.w(TAG, "没有定位权限");
            callback.onLocationResult(null);
            return;
        }

        // 先尝试获取最后已知位置
        LocationInfo cached = getCachedLocation();
        if (cached != null && isLocationFresh(cached)) {
            Log.d(TAG, "使用缓存的位置: " + cached);
            callback.onLocationResult(cached);
            return;
        }

        // 请求新的位置更新
        requestSingleLocationUpdate(callback);
    }

    /**
     * 获取缓存的位置
     */
    public LocationInfo getCachedLocation() {
        if (lastKnownLocation == null) {
            try {
                Location lastLoc = null;
                // 尝试从 GPS 获取
                if (locationManager != null && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    lastLoc = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                }
                // 如果 GPS 没有位置，尝试网络定位
                if (lastLoc == null && locationManager != null && locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lastLoc = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                }
                if (lastLoc != null) {
                    lastKnownLocation = locationToInfo(lastLoc);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "获取缓存位置失败(权限): " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "获取缓存位置失败: " + e.getMessage());
            }
        }
        return lastKnownLocation;
    }

    /**
     * 设置缓存位置（供其他服务调用，如后台服务）
     */
    public void setCachedLocation(LocationInfo location) {
        this.lastKnownLocation = location;
        Log.d(TAG, "更新缓存位置: " + location);
    }

    /**
     * 请求单次位置更新
     */
    private void requestSingleLocationUpdate(LocationCallback callback) {
        try {
            // 优先使用 GPS
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.GPS_PROVIDER,
                    new LocationListener() {
                        @Override
                        public void onLocationChanged(Location location) {
                            LocationInfo info = locationToInfo(location);
                            lastKnownLocation = info;
                            callback.onLocationResult(info);
                        }

                        @Override
                        public void onStatusChanged(String provider, int status, Bundle extras) {}

                        @Override
                        public void onProviderEnabled(String provider) {}

                        @Override
                        public void onProviderDisabled(String provider) {
                            // GPS 被禁用，尝试网络定位
                            tryNetworkLocation(callback);
                        }
                    },
                    Looper.getMainLooper()
                );
                return;
            }
        } catch (SecurityException e) {
            Log.e(TAG, "GPS 定位失败: " + e.getMessage());
        }

        // GPS 不可用，尝试网络定位
        tryNetworkLocation(callback);
    }

    /**
     * 尝试网络定位
     */
    private void tryNetworkLocation(LocationCallback callback) {
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER,
                    new LocationListener() {
                        @Override
                        public void onLocationChanged(Location location) {
                            LocationInfo info = locationToInfo(location);
                            lastKnownLocation = info;
                            callback.onLocationResult(info);
                        }

                        @Override
                        public void onStatusChanged(String provider, int status, Bundle extras) {}

                        @Override
                        public void onProviderEnabled(String provider) {}

                        @Override
                        public void onProviderDisabled(String provider) {
                            Log.w(TAG, "网络定位被禁用");
                            callback.onLocationResult(null);
                        }
                    },
                    Looper.getMainLooper()
                );
            } else {
                Log.w(TAG, "没有可用的定位提供者");
                callback.onLocationResult(null);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "网络定位失败: " + e.getMessage());
            callback.onLocationResult(null);
        }
    }

    /**
     * 将 Location 转换为 LocationInfo
     */
    private LocationInfo locationToInfo(Location location) {
        LocationInfo info = new LocationInfo();
        info.setLatitude(location.getLatitude());
        info.setLongitude(location.getLongitude());
        info.setAltitude(location.getAltitude());
        info.setAccuracy(location.getAccuracy());
        info.setHeading(location.getBearing());
        info.setTimestamp(System.currentTimeMillis());
        return info;
    }

    /**
     * 检查位置是否新鲜（5秒内）
     */
    private boolean isLocationFresh(LocationInfo location) {
        if (location == null || location.getTimestamp() == null) {
            return false;
        }
        long age = System.currentTimeMillis() - location.getTimestamp();
        return age < 5000; // 5秒内认为是新鲜的
    }

    /**
     * 检查是否有定位权限
     */
    private boolean hasLocationPermission() {
        return ActivityCompat.checkSelfPermission(appContext,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
               ActivityCompat.checkSelfPermission(appContext,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 位置回调接口
     */
    public interface LocationCallback {
        void onLocationResult(LocationInfo location);
    }
}
