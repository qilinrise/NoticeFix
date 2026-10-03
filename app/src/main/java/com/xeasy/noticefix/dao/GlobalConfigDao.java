package com.xeasy.noticefix.dao;

import static com.xeasy.noticefix.constant.MyConstant.GLOBAL_CONFIG_FILE;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.google.gson.Gson;
import com.xeasy.noticefix.utils.AppNotification;

import java.io.File;

import de.robv.android.xposed.XSharedPreferences;

public class GlobalConfigDao {
    /**
     * 是否已被 systemui 读
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
     * 是否跳过灰度（默认保持开启，避免开机读取间隙误伤系统单色图标）
     */
    public boolean skipGrayscale = true;
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

    /**
     * 获取支持 Direct Boot 的安全 Context（DE 空间，开机未解锁即可直接读取）
     */
    private static Context getSafeContext(Context context) {
        if (context == null) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return context.isDeviceProtectedStorage() ? context : context.createDeviceProtectedStorageContext();
        }
        return context;
    }

    public static void initGlobalConfig(Context context) {
        if (context == null) return;
        try {
            Context safeContext = getSafeContext(context);
            SharedPreferences sharedPreferences;

            if (safeContext.getPackageName().equals("com.xeasy.noticefix")) {
                sharedPreferences = safeContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
            } else {
                // SystemUI 注入进程优先从 DE 路径构建 XSharedPreferences
                XSharedPreferences xSharedPreferences = new XSharedPreferences("com.xeasy.noticefix", FILE_NAME);
                xSharedPreferences.makeWorldReadable();
                xSharedPreferences.reload();
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
            Context safeContext = getSafeContext(context);

            // 同时向 DE 空间写入配置，确保冷启动与日常读写双重保障
            SharedPreferences sharedPreferences = safeContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
            sharedPreferences.edit().putString(FILE_NAME, gson.toJson(globalConfigDao)).commit();

            // 针对常规 CE 存储同步一份备份
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && safeContext.isDeviceProtectedStorage()) {
                    context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
                            .edit()
                            .putString(FILE_NAME, gson.toJson(globalConfigDao))
                            .commit();
                }
            } catch (Exception ignored) {
            }

            // 穿透放行 DE 存储与配置文件的 Linux 全局读取权限
            try {
                File dataDir = safeContext.getFilesDir().getParentFile();
                if (dataDir != null) {
                    dataDir.setReadable(true, false);
                    dataDir.setExecutable(true, false);

                    File spDir = new File(dataDir, "shared_prefs");
                    if (spDir.exists()) {
                        spDir.setReadable(true, false);
                        spDir.setExecutable(true, false);

                        File file = new File(spDir, FILE_NAME + ".xml");
                        if (file.exists()) {
                            file.setReadable(true, false);
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            // 发送刷新广播通知
            AppNotification.sendFlashNoticeMessage(context, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
