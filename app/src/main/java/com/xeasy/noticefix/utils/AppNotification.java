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

    // 记录弹窗对象，防止重复弹窗，并支持返回时自动销毁
    public static AlertDialog permissionDialog = null;

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
        // 1. 判断全局通知是否开启，且同一时刻只允许存在一个提示框
        if (!isNotificationEnabled(context)) {
            if (permissionDialog == null || !permissionDialog.isShowing()) {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("提示");
                builder.setMessage("是否开启通知权限？");
                builder.setPositiveButton("确定", (dialogInterface, i) -> {
                    openNotification(context);
                    if (permissionDialog != null) {
                        permissionDialog.dismiss();
                        permissionDialog = null;
                    }
                });
                builder.setNegativeButton("取消", (dialogInterface, i) -> {
                    if (permissionDialog != null) {
                        permissionDialog.dismiss();
                        permissionDialog = null;
                    }
                });
                permissionDialog = builder.create();
                permissionDialog.show();
            }
        }

        // 2. 静默创建通知渠道，彻底删除原版二次弹窗逻辑
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String message = channelId.equals(mediaChannelId) ? mediaChannelName : foodChannelName;
            createNotificationChannel(context, channelId, message, NotificationManager.IMPORTANCE_HIGH);
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

    /**
     * 当应用切回前台时调用：若用户已开启通知，自动关闭残留的弹窗
     */
    public static void checkAndDismissDialog(Context context) {
        if (permissionDialog != null && permissionDialog.isShowing()) {
            if (isNotificationEnabled(context)) {
                permissionDialog.dismiss();
                permissionDialog = null;
            }
        }
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
