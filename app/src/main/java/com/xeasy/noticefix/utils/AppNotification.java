package com.xeasy.noticefix.utils;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.xeasy.noticefix.R;
import com.xeasy.noticefix.activity.MainActivity;

/**
 * App 通知渠道与推送管理（完整支持横幅悬浮弹出与状态栏小图标）
 */
@SuppressWarnings("unused")
public class AppNotification {
    private static int id = 1000;

    // 更换为新 Channel ID（避开系统旧渠道降级缓存，强迫系统赋予最高横幅弹出权限）
    public final static String mediaChannelId = "notice_channel_v4";
    public final static String mediaChannelName = "通知与测试提醒";
    public final static int mediaChannelImportance = NotificationManager.IMPORTANCE_HIGH;

    public final static String foodChannelId = "food_channel_v2";
    public final static String foodChannelName = "美食服务";
    public final static int foodChannelImportance = NotificationManager.IMPORTANCE_DEFAULT;

    public static void createNotificationChannel(Context applicationContext, String channelId,
                                                 String channelIdName, int channelIdImportance) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) applicationContext.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (notificationManager != null && notificationManager.getNotificationChannel(channelId) == null) {
                NotificationChannel channel = new NotificationChannel(channelId, channelIdName, channelIdImportance);
                // 激活横幅（Heads-up）必需的震动、呼吸灯和锁屏配置
                channel.enableLights(true);
                channel.enableVibration(true);
                channel.setVibrationPattern(new long[]{0, 250, 250, 250});
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    public static void sendNotification(Context context, String channelId, String title,
                                        String text, int smallIcon, int largeIcon, PendingIntent pi) {
        Notification initNotice = initNotice(context, channelId, title, text, smallIcon, largeIcon, pi);
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(id++, initNotice);
        }
    }

    public static void sendFlashNoticeMessage(Context context, String pkgName) {
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        } else {
            pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        }
        String contextString = pkgName == null ? "NoticeFix 规则已重载" : pkgName;

        // 发送带图标的横幅测试通知
        Notification notification = AppNotification.initNotice(
                context,
                AppNotification.mediaChannelId,
                "NoticeFix 测试提醒",
                contextString,
                R.drawable.ic_notification,
                0,
                pi
        );

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(19960324, notification);
        }
    }

    public static Notification initNotice(Context context, String channelId, String title,
                                          String text, int smallIcon, int largeIcon, PendingIntent pi) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(context, channelId, mediaChannelName, NotificationManager.IMPORTANCE_HIGH);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId);
        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setWhen(System.currentTimeMillis());

        // 核心属性：配置最高优先级、震动以及公开可见性，触发原生系统的 Heads-up 横幅弹窗
        builder.setPriority(NotificationCompat.PRIORITY_MAX);
        builder.setDefaults(NotificationCompat.DEFAULT_ALL);
        builder.setVibrate(new long[]{0, 250, 250, 250});
        builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // 核心属性：设置合法状态栏矢量小图标，避免传 0 导致系统丢弃图标
        int validSmallIcon = smallIcon != 0 ? smallIcon : R.drawable.ic_notification;
        builder.setSmallIcon(validSmallIcon);

        if (largeIcon != 0) {
            try {
                builder.setLargeIcon(BitmapFactory.decodeResource(context.getResources(), largeIcon));
            } catch (Exception ignored) {
            }
        }

        if (pi != null) {
            builder.setContentIntent(pi);
        }
        builder.setAutoCancel(true);
        return builder.build();
    }

    public static Boolean isNotificationEnabled(Context context) {
        NotificationManagerCompat notificationManagerCompat = NotificationManagerCompat.from(context);
        return notificationManagerCompat.areNotificationsEnabled();
    }

    public static Boolean isNotificationChannelEnabled(Context context, String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) context.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (notificationManager != null) {
                NotificationChannel channel = notificationManager.getNotificationChannel(channelId);
                return null != channel && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
            }
        }
        return true;
    }

    @SuppressLint("ObsoleteSdkInt")
    public static void openNotification(Context context) {
        String packageName = context.getPackageName();
        int uid = context.getApplicationInfo().uid;
        Intent intent = new Intent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            intent.setAction(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName);
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, uid);
        } else {
            intent.setAction(Settings.ACTION_SETTINGS);
        }
        context.startActivity(intent);
    }
}
