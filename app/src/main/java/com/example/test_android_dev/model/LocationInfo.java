package com.example.test_android_dev.model;

import com.google.gson.annotations.SerializedName;

/**
 * GPS 位置信息
 */
public class LocationInfo {
    @SerializedName("latitude")
    private double latitude;

    @SerializedName("longitude")
    private double longitude;

    @SerializedName("altitude")
    private Double altitude;

    @SerializedName("heading")
    private Float heading;

    @SerializedName("accuracy")
    private Float accuracy;

    @SerializedName("timestamp")
    private Long timestamp;

    public LocationInfo() {
    }

    public LocationInfo(double latitude, double longitude, float heading) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.heading = heading;
        this.timestamp = System.currentTimeMillis();
    }

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }

    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public Double getAltitude() { return altitude; }
    public void setAltitude(Double altitude) { this.altitude = altitude; }

    public Float getHeading() { return heading; }
    public void setHeading(Float heading) { this.heading = heading; }

    public Float getAccuracy() { return accuracy; }
    public void setAccuracy(Float accuracy) { this.accuracy = accuracy; }

    public Long getTimestamp() { return timestamp; }
    public void setTimestamp(Long timestamp) { this.timestamp = timestamp; }

    @Override
    public String toString() {
        return "LocationInfo{" +
                "latitude=" + latitude +
                ", longitude=" + longitude +
                ", heading=" + heading +
                ", accuracy=" + accuracy +
                '}';
    }
}
