package com.xeasy.noticefix.utils;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.xeasy.noticefix.R;
import com.xeasy.noticefix.activity.MainActivity;

/**
 * App的通知渠道配置与发送
 */
@SuppressWarnings("unused")
public class AppNotification {
    private static int id = 1;

    public final static String mediaChannelId = "chat";
    public final static String mediaChannelName = "聊天";
    public final static int mediaChannelImportance = NotificationManager.IMPORTANCE_HIGH;

    public final static String foodChannelId = "0x2";
    public final static String foodChannelName = "美食";
    public final static int foodChannelImportance = NotificationManager.IMPORTANCE_DEFAULT;

    public static void createNotificationChannel(Context applicationContext, String channelId,
                                                 String channelIdName, int channelIdImportance) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager notificationManager = (NotificationManager) applicationContext.getSystemService(
                    Context.NOTIFICATION_SERVICE);
            if (notificationManager != null && notificationManager.getNotificationChannel(channelId) == null) {
                NotificationChannel notificationChannel = new NotificationChannel(channelId, channelIdName,
                        channelIdImportance);
                notificationManager.createNotificationChannel(notificationChannel);
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

    public static void sendFlashNoticeMessage(Context context, String pkgName){
        Intent intent = new Intent(context, MainActivity.class);
        PendingIntent pi;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE);
        } else {
            pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        }
        String contextString = pkgName == null ? "null" : pkgName;

        // 使用系统原生矢量通知图标，大图标置为 0 保持纯粹通知栏风格
        Notification notification = AppNotification.initNotice(context, AppNotification.mediaChannelId,
                "easy-reset", contextString, R.drawable.ic_notification, 0, pi);

        NotificationManager notificationManager = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.notify(19960324, notification);
        }
    }

    public static Notification initNotice(Context context, String channelId, String title,
                                           String text, int smallIcon, int largeIcon, PendingIntent pi) {
        if (!isNotificationEnabled(context)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("提示");
            builder.setMessage("是否开启通知？");
            builder.setPositiveButton("确定", (dialogInterface, i) -> openNotification(context));
            builder.setNegativeButton("取消", null);
            builder.show();
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!isNotificationChannelEnabled(context, channelId)) {
                String message = "";
                if (channelId.equals(mediaChannelId)) {
                    message = mediaChannelName;
                }
                if (channelId.equals(foodChannelId)) {
                    message = foodChannelName;
                }
                createNotificationChannel(context, channelId, message, NotificationManager.IMPORTANCE_HIGH);
            }
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channelId);
        builder.setContentTitle(title);
        builder.setContentText(text);
        builder.setWhen(System.currentTimeMillis());
        builder.setSmallIcon(smallIcon != 0 ? smallIcon : R.drawable.ic_notification);

        if (largeIcon != 0) {
            try {
                builder.setLargeIcon(BitmapFactory.decodeResource(context.getResources(), largeIcon));
            } catch (Exception ignored) {
            }
        }

        builder.setContentIntent(pi);
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

    public static void openNotificationChannel(Context context, String channelId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent intent = new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName());
            intent.putExtra(Settings.EXTRA_CHANNEL_ID, channelId);
            context.startActivity(intent);
        }
    }
}
