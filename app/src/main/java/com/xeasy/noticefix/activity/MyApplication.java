package com.xeasy.noticefix.activity;

import android.app.Application;

import com.google.android.material.color.DynamicColors;
import com.xeasy.noticefix.dao.GlobalConfigDao;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // 核心：冷启动第一时间完成持久化配置读取，保障全 App 随时可用真实设置
        GlobalConfigDao.initGlobalConfig(this);

        // 全局动态色彩引擎
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
