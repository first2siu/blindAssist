package com.blindassist.server.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 传感器数据实体
 */
public class SensorData {

    @JsonProperty("latitude")
    private double latitude;

    @JsonProperty("longitude")
    private double longitude;

    @JsonProperty("altitude")
    private double altitude;

    @JsonProperty("heading")
    private float heading; // 手机朝向，0-360度

    @JsonProperty("accuracy")
    private float accuracy; // 位置精度（米）

    @JsonProperty("timestamp")
    private long timestamp;

    public SensorData() {
    }

    public SensorData(double latitude, double longitude, float heading) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.heading = heading;
        this.timestamp = System.currentTimeMillis();
    }

    // Getters and Setters
    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public double getAltitude() { return altitude; }
    public void setAltitude(double altitude) { this.altitude = altitude; }

    public float getHeading() { return heading; }
    public void setHeading(float heading) { this.heading = heading; }

    public float getAccuracy() { return accuracy; }
    public void setAccuracy(float accuracy) { this.accuracy = accuracy; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

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
}
