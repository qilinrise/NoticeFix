package com.xeasy.noticefix.activity;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 全局注册莫奈动态取色引擎：所有 Activity 自动继承系统动态壁纸色彩
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
