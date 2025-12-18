package com.example.test_android_dev;

import android.content.Context;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * 自定义功能轮盘 View：
 * - 支持单指上下滑动切换功能
 * - 双击确认选择
 */
public class FeatureWheelView extends View {

    public interface OnFeatureSelectedListener {
        void onItemSelected(FeatureType feature);
        void onItemConfirmed(FeatureType feature);
    }

    private final FeatureType[] features = {
            FeatureType.NAVIGATION,
            FeatureType.OBSTACLE_AVOIDANCE,
            FeatureType.QA_VOICE,
            FeatureType.OCR,
            FeatureType.SCENE_DESCRIPTION
    };

    private int currentIndex = 0;
    private GestureDetector gestureDetector;
    private OnFeatureSelectedListener listener;

    public FeatureWheelView(Context context) {
        super(context);
        init(context);
    }

    public FeatureWheelView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (Math.abs(distanceY) > 30) {
                    if (distanceY > 0) {
                        currentIndex = (currentIndex + 1) % features.length;
                    } else {
                        currentIndex = (currentIndex - 1 + features.length) % features.length;
                    }
                    if (listener != null) {
                        listener.onItemSelected(features[currentIndex]);
                    }
                    return true;
                }
                return false;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (listener != null) {
                    listener.onItemConfirmed(features[currentIndex]);
                }
                return true;
            }
        });
    }

    public void setOnFeatureSelectedListener(OnFeatureSelectedListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event);
    }
}