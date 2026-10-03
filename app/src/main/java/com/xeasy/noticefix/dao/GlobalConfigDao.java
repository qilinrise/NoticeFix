package com.xeasy.noticefix.dao;

import static com.xeasy.noticefix.constant.MyConstant.GLOBAL_CONFIG_FILE;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.xeasy.noticefix.utils.AppNotification;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

public class GlobalConfigDao {
    /**
     * 是否已被systemui读
     */
    public boolean read = false;
    /**
     * 测试模式 开启使用图标库
     */
    public boolean debugMode = false;
    /**
     * 始终处理推送通知
     */
    public boolean alwaysHandleProxyNotice = true;
    /**
     * 是否跳过灰度
     */
    public boolean skipGrayscale = false;
    /**
     * 解除原生安卓色彩
     */
    public boolean showColoredIcons = true;
    /**
     * 展开通知
     */
    public boolean expandAllNotice = false;
    /**
     * 自定义图标辅助
     */
    public boolean customIconHelper = true;

    public static GlobalConfigDao globalConfigDao = new GlobalConfigDao();

    public static final String FILE_NAME = GLOBAL_CONFIG_FILE;

    public static final Gson gson = new Gson();

    public static void initGlobalConfig(Context context) {
        if (context == null) return;
        try {
            SharedPreferences sharedPreferences;
            if (context.getPackageName().equals("com.xeasy.noticefix")) {
                sharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
            } else {
                XSharedPreferences xSharedPreferences = new XSharedPreferences("com.xeasy.noticefix", FILE_NAME);
                xSharedPreferences.makeWorldReadable();
                sharedPreferences = xSharedPreferences;
            }
            String string = sharedPreferences.getString(FILE_NAME, null);
            if (string != null && !string.trim().isEmpty()) {
                GlobalConfigDao loaded = gson.fromJson(string, GlobalConfigDao.class);
                if (loaded != null) {
                    globalConfigDao = loaded;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static void saveConfig(Context context, GlobalConfigDao config) {
        if (context == null) return;
        try {
            if (config != null) {
                globalConfigDao = config;
            }
            SharedPreferences sharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
            // 核心修复：改用 commit() 强制同步写入磁盘，避免划掉后台时丢失数据
            sharedPreferences.edit().putString(FILE_NAME, gson.toJson(globalConfigDao)).commit();

            // 保持配置文件的可读权限，确保 SystemUI 与 Xposed 顺利读取
            try {
                File file = new File(context.getFilesDir().getParent(), "shared_prefs/" + FILE_NAME + ".xml");
                if (file.exists()) {
                    file.setReadable(true, false);
                }
            } catch (Exception ignored) {
            }

            // 发送刷新通知
            AppNotification.sendFlashNoticeMessage(context, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
