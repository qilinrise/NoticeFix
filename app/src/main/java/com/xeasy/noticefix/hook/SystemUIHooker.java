package com.xeasy.noticefix.hook;

import static com.xeasy.noticefix.hook.HookConstant.gson;

import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.LruCache;
import android.view.View;
import android.widget.RemoteViews;

import com.google.gson.reflect.TypeToken;
import com.xeasy.noticefix.bean.CustomIconBean;
import com.xeasy.noticefix.bean.IconFunc;
import com.xeasy.noticefix.bean.IconLibBean;
import com.xeasy.noticefix.dao.GlobalConfigDao;
import com.xeasy.noticefix.dao.IconFuncDao;
import com.xeasy.noticefix.utils.ImageTools;
import com.xeasy.noticefix.utils.ImageUtils;
import com.xeasy.noticefix.utils.ReflexUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SystemUIHooker implements IXposedHookLoadPackage {

    private static final String LOG_PREV = "NoticeFix---";

    // 内存缓存：避免重复 Base64 转换和重复像素单色化计算
    private static final LruCache<String, Icon> sIconCache = new LruCache<>(80);
    // IPC 跨进程防抖时间戳
    private static long sLastReadConfigTime = 0;
    // 监听器注册标记
    private static boolean sReceiverRegistered = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        // 激活状态自检 Hook
        if (loadPackageParam.packageName.equals("com.xeasy.noticefix")) {
            Class<?> aClass = XposedHelpers.findClass("com.xeasy.noticefix.activity.MainActivity", loadPackageParam.classLoader);
            XposedHelpers.findAndHookMethod(aClass, "activeXposed", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.args[0] = true;
                }
            });
        }

        // 注入 SystemUI 核心逻辑
        if (loadPackageParam.packageName.equals("com.android.systemui")) {
            try {
                NotificationListener(loadPackageParam.classLoader);
            } catch (Exception ignored) {
            }
            try {
                inflateViews(loadPackageParam.classLoader);
            } catch (Exception ignored) {
            }
            try {
                setIcon(loadPackageParam.classLoader);
            } catch (Exception ignored) {
            }
            try {
                setSystemExpanded(loadPackageParam.classLoader);
            } catch (Exception ignored) {
            }
        }
    }

    private void NotificationListener(ClassLoader classLoader) {
        final Class<?> clazz = XposedHelpers.findClass("com.android.systemui.statusbar.NotificationListener", classLoader);
        Constructor<?>[] constructors = clazz.getDeclaredConstructors();
        Class<?>[] genericParameterTypes = constructors[constructors.length - 1].getParameterTypes();
        Object[] parameterTypesAndCallback = new Object[genericParameterTypes.length + 1];
        System.arraycopy(genericParameterTypes, 0, parameterTypesAndCallback, 0, genericParameterTypes.length);
        parameterTypesAndCallback[parameterTypesAndCallback.length - 1] = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                HookConstant.objectMap.put("NotificationListener", param.thisObject);
                // SystemUI 初始化完成后，立即挂载开机与解锁监听
                registerUnlockReceiver(AndroidAppHelper.currentApplication());
            }
        };
        XposedHelpers.findAndHookConstructor(clazz, parameterTypesAndCallback);

        XposedHelpers.findAndHookMethod(clazz, "onNotificationPosted",
                StatusBarNotification.class, NotificationListenerService.RankingMap.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        StatusBarNotification statusBarNotification = (StatusBarNotification) param.args[0];
                        String packageName = statusBarNotification.getPackageName();
                        String title = statusBarNotification.getNotification().extras.getString(Notification.EXTRA_TITLE, "");
                        if ("com.xeasy.noticefix".equals(packageName) && "easy-reset".equals(title)) {
                            param.setResult(null);
                            NotificationManager mNotificationManager = (NotificationManager) ReflexUtil.getField4Obj(param.thisObject, "mNotificationManager");
                            if (mNotificationManager != null) {
                                mNotificationManager.cancel(19960324);
                            }
                            // 收到重置广播时，清空缓存并热更新所有通知
                            sIconCache.evictAll();
                            readConfigOld(AndroidAppHelper.currentApplication());
                            refreshAllActiveNotifications();
                        }
                    }
                });
    }

    /**
     * 解决重启后不打开模块不生效：开机解锁时自动静默加载配置并重绘通知
     */
    private static void registerUnlockReceiver(Context context) {
        if (sReceiverRegistered || context == null) return;
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_USER_PRESENT);
            filter.addAction(Intent.ACTION_BOOT_COMPLETED);
            context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    sLastReadConfigTime = 0;
                    if (readConfigOld(ctx)) {
                        refreshAllActiveNotifications();
                    }
                }
            }, filter);
            sReceiverRegistered = true;
        } catch (Exception ignored) {
        }
    }

    /**
     * 重新派发当前已挂载在通知栏上的旧通知，强制应用最新颜色与图标
     */
    private static void refreshAllActiveNotifications() {
        try {
            Object listener = HookConstant.objectMap.get("NotificationListener");
            if (listener != null) {
                StatusBarNotification[] activeNotifications = (StatusBarNotification[]) ReflexUtil.runMethod(listener, "getActiveNotifications", new Object[]{});
                Object currentRanking = ReflexUtil.runMethod(listener, "getCurrentRanking", new Object[]{});
                if (activeNotifications != null) {
                    for (StatusBarNotification sbn : activeNotifications) {
                        if (!"com.xeasy.noticefix".equals(sbn.getPackageName())) {
                            ReflexUtil.runMethod(listener, "onNotificationPosted", new Object[]{sbn, currentRanking},
                                    StatusBarNotification.class, NotificationListenerService.RankingMap.class);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void setIcon(ClassLoader classLoader) {
        final Class<?> clazz = XposedHelpers.findClass("com.android.systemui.statusbar.notification.icon.IconManager", classLoader);
        final Class<?> args0 = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.NotificationEntry", classLoader);
        final Class<?> args1 = XposedHelpers.findClass("com.android.internal.statusbar.StatusBarIcon", classLoader);
        final Class<?> args2 = XposedHelpers.findClass("com.android.systemui.statusbar.StatusBarIconView", classLoader);
        Method methodsByExactParameters = ReflexUtil.findMethodIfParamExist(clazz, "setIcon", args0, args1, args2);

        if (methodsByExactParameters != null) {
            XposedBridge.hookMethod(methodsByExactParameters, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 仅当用户开启了“彩色图标”且素材确实不是单色图时，才打 pre_L 标签
                    if (HookConstant.getGlobalConfigDao() != null && HookConstant.getGlobalConfigDao().read && HookConstant.globalConfigDao.showColoredIcons) {
                        for (Object arg : param.args) {
                            if (arg instanceof View) {
                                View iconView = (View) arg;
                                StatusBarNotification sbn = null;
                                try {
                                    sbn = (StatusBarNotification) ReflexUtil.getField4Obj(iconView, "mNotification");
                                } catch (Exception ignored) {
                                }

                                if (sbn != null && sbn.getNotification() != null) {
                                    Bitmap bmp = getBitmap4Icon(sbn.getNotification().getSmallIcon(), AndroidAppHelper.currentApplication());
                                    // 单色矢量图坚决不打 pre_L，放行系统原生深浅色着色管线
                                    if (bmp != null && !new ImageUtils().isGrayscale(bmp)) {
                                        @SuppressLint("DiscouragedApi")
                                        int preLTag = AndroidAppHelper.currentApplication().getResources().getIdentifier("icon_is_pre_L", "id", "com.android.systemui");
                                        if (preLTag != 0) {
                                            iconView.setTag(preLTag, true);
                                        }
                                        return;
                                    }
                                }
                            }
                        }
                    }
                }
            });
        }

        // 状态栏图标视图变色 Hook：放行单色图标的 DarkIconDispatcher 变色通道
        XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass("com.android.systemui.statusbar.StatusBarIconView", classLoader),
                "updateIconColor",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (HookConstant.getGlobalConfigDao() != null && HookConstant.getGlobalConfigDao().read && HookConstant.globalConfigDao.showColoredIcons) {
                            try {
                                StatusBarNotification sbn = (StatusBarNotification) ReflexUtil.getField4Obj(param.thisObject, "mNotification");
                                if (sbn != null && sbn.getNotification() != null) {
                                    Bitmap bmp = getBitmap4Icon(sbn.getNotification().getSmallIcon(), AndroidAppHelper.currentApplication());
                                    // 仅对真正的彩色位图抹除着色；单色矢量图交由系统自动变黑变白
                                    if (bmp != null && !new ImageUtils().isGrayscale(bmp)) {
                                        ReflexUtil.setField4Obj("mCurrentSetColor", param.thisObject, 0);
                                    }
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    }
                });

        final Class<?> standardTemplateParamsClass = XposedHelpers.findClass("android.app.Notification.StandardTemplateParams", classLoader);
        XposedHelpers.findAndHookMethod(Notification.Builder.class, "processSmallIconColor",
                Icon.class, RemoteViews.class, standardTemplateParamsClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (HookConstant.globalConfigDao != null && HookConstant.globalConfigDao.read && HookConstant.globalConfigDao.showColoredIcons) {
                            RemoteViews contentView = (RemoteViews) param.args[1];
                            if (contentView != null) {
                                contentView.setInt(android.R.id.icon, "setOriginalIconColor", android.R.color.transparent);
                                param.setResult(true);
                            }
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (HookConstant.globalConfigDao != null && HookConstant.globalConfigDao.read && HookConstant.globalConfigDao.showColoredIcons) {
                            RemoteViews contentView = (RemoteViews) param.args[1];
                            if (contentView != null) {
                                contentView.setInt(android.R.id.icon, "setBackgroundResource", android.R.color.transparent);
                                contentView.setViewPadding(android.R.id.icon, 0, 0, 0, 0);
                                @SuppressLint("DiscouragedApi")
                                int left_icon = AndroidAppHelper.currentApplication().getResources().getIdentifier("left_icon", "id", "com.android.systemui");
                                if (left_icon != 0) {
                                    contentView.setViewPadding(left_icon, 0, 0, 0, 0);
                                }
                            }
                        }
                    }
                });
    }

    private void setSystemExpanded(ClassLoader classLoader) {
        Class<?> clazz = XposedHelpers.findClass("com.android.systemui.statusbar.notification.row.ExpandableNotificationRow", classLoader);
        Method setExpandedMethod = ReflexUtil.findMethodIfParamExist(clazz, "setSystemExpanded", boolean.class);
        if (setExpandedMethod != null) {
            XposedBridge.hookMethod(setExpandedMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (HookConstant.getGlobalConfigDao() != null && HookConstant.getGlobalConfigDao().read) {
                        Object mEntry = ReflexUtil.getField4Obj(param.thisObject, "mEntry");
                        Object mSbn = ReflexUtil.getField4Obj(mEntry, "mSbn");
                        String pkg = (String) ReflexUtil.getField4Obj(mSbn, "pkg");
                        CustomIconBean customIconBean = HookConstant.customIconBeanMap != null ? HookConstant.customIconBeanMap.get(pkg) : null;
                        if (HookConstant.globalConfigDao.expandAllNotice || (customIconBean != null && customIconBean.expandStatusBar)) {
                            param.args[0] = true;
                        }
                    }
                }
            });
        }
    }

    private void inflateViews(ClassLoader classLoader) {
        Class<?> clazz = XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.collection.inflation.NotificationRowBinderImpl", classLoader);
        if (clazz == null) {
            clazz = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.NotificationRowBinderImpl", classLoader);
        }
        final Class<?> args0 = XposedHelpers.findClass("com.android.systemui.statusbar.notification.collection.NotificationEntry", classLoader);

        XC_MethodHook xc_methodHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                registerUnlockReceiver(AndroidAppHelper.currentApplication());
                long now = System.currentTimeMillis();
                // 节流机制：严禁频繁跨进程查询引起 Binder 阻塞
                if ((HookConstant.globalConfigDao == null || !HookConstant.globalConfigDao.read) && (now - sLastReadConfigTime > 3000)) {
                    sLastReadConfigTime = now;
                    readConfigOld(AndroidAppHelper.currentApplication());
                }
                try {
                    for (Object arg : param.args) {
                        if (arg != null && arg.getClass() == args0) {
                            StatusBarNotification statusBarNotification = (StatusBarNotification) ReflexUtil.getField4ObjByClass(arg.getClass(), arg, StatusBarNotification.class);
                            Context mContext = (Context) ReflexUtil.getField4Obj(param.thisObject, "mContext");
                            if (statusBarNotification != null && mContext != null) {
                                fixNotificationIcon(statusBarNotification, mContext);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        };

        Method[] declaredMethods = clazz.getDeclaredMethods();
        List<Method> inflateViewsMethods = Arrays.stream(declaredMethods).filter(method -> method.getName().equals("inflateViews")).collect(Collectors.toList());
        for (Method inflateViewsMethod : inflateViewsMethods) {
            Class<?>[] parameterTypes = inflateViewsMethod.getParameterTypes();
            if (Arrays.asList(parameterTypes).contains(args0)) {
                Method m = XposedHelpers.findMethodExact(clazz, inflateViewsMethod.getName(), parameterTypes);
                XposedBridge.hookMethod(m, xc_methodHook);
                return;
            }
        }
    }

    private static boolean readConfigOld(Context context) {
        if (context == null) return false;
        try {
            ContentResolver contentResolver = context.getContentResolver();
            Uri uri = Uri.parse("content://com.xeasy.noticefix.provider.IconDataContentProvider");
            Cursor query = contentResolver.query(uri, null, null, null);
            if (query != null && query.moveToNext()) {
                String globalConfig = query.getString(0);
                String iconFunc = query.getString(1);
                String libIconList = query.getString(2);
                String customIconList = query.getString(3);
                query.close();

                HookConstant.globalConfigDao = gson.fromJson(globalConfig, GlobalConfigDao.class);
                Type type = new TypeToken<List<IconFuncDao.IconFuncStatus>>() {}.getType();
                HookConstant.iconFuncStatuses = gson.fromJson(iconFunc, type);
                if (HookConstant.iconFuncStatuses != null) {
                    Collections.sort(HookConstant.iconFuncStatuses);
                }
                Type type2 = new TypeToken<Map<String, IconLibBean>>() {}.getType();
                HookConstant.iconLibBeanMap = gson.fromJson(libIconList, type2);
                Type type3 = new TypeToken<Map<String, CustomIconBean>>() {}.getType();
                HookConstant.customIconBeanMap = gson.fromJson(customIconList, type3);
                return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    public static void fixNotificationIcon(StatusBarNotification statusBarNotification, Context context) {
        try {
            Notification notification = statusBarNotification.getNotification();
            String packageName = statusBarNotification.getPackageName();

            CustomIconBean customIconBean = HookConstant.customIconBeanMap != null ? HookConstant.customIconBeanMap.get(packageName) : null;
            if (customIconBean != null && customIconBean.noHandle) {
                String opPkg = statusBarNotification.getOpPkg();
                boolean isProxy = !opPkg.equals(packageName);
                if (!isProxy || (HookConstant.globalConfigDao != null && !HookConstant.globalConfigDao.alwaysHandleProxyNotice)) {
                    return;
                }
            }

            // 命中内存缓存直接设置，零延迟
            Icon cached = sIconCache.get(packageName);
            if (cached != null) {
                ImageTools.setSmallIcon(cached, notification);
                return;
            }

            Icon smallIcon = notification.getSmallIcon();
            if (HookConstant.globalConfigDao != null && HookConstant.globalConfigDao.skipGrayscale) {
                try {
                    Bitmap bitmap = getBitmap4Icon(smallIcon, context);
                    if (new ImageUtils().isGrayscale(bitmap)) {
                        return;
                    }
                } catch (Exception ignored) {
                }
            }

            if (HookConstant.iconFuncStatuses == null) return;

            for (IconFuncDao.IconFuncStatus iconFuncStatus : HookConstant.iconFuncStatuses) {
                if (iconFuncStatus.active) {
                    // 1. 图标库分支
                    if (iconFuncStatus.iconFuncId == IconFunc.LIB_FIX.funcId) {
                        IconLibBean iconLibBean = HookConstant.iconLibBeanMap != null ? HookConstant.iconLibBeanMap.get(packageName) : null;
                        if (iconLibBean != null) {
                            Bitmap raw = ImageTools.base64ToBitmap(iconLibBean.iconBitmap);
                            if (raw != null) {
                                // 核心：未开彩色模式下，强制洗成单色透明图，供原生状态栏自由变黑变白
                                Bitmap finalBmp = (HookConstant.globalConfigDao != null && HookConstant.globalConfigDao.showColoredIcons)
                                        ? raw : ImageTools.getSinglePic(raw);
                                Icon newIcon = Icon.createWithBitmap(finalBmp);
                                if (iconLibBean.iconColor != null && !iconLibBean.iconColor.isEmpty()) {
                                    notification.color = Color.parseColor(iconLibBean.iconColor);
                                }
                                sIconCache.put(packageName, newIcon);
                                ImageTools.setSmallIcon(newIcon, notification);
                                return;
                            }
                        }
                    }
                    // 2. 自定义图标分支
                    if (iconFuncStatus.iconFuncId == IconFunc.CUSTOM_FIX.funcId) {
                        if (customIconBean != null && customIconBean.iconBase64 != null && !customIconBean.iconBase64.isEmpty()) {
                            Bitmap raw = ImageTools.base64ToBitmap(customIconBean.iconBase64);
                            if (raw != null) {
                                Bitmap finalBmp = (HookConstant.globalConfigDao != null && HookConstant.globalConfigDao.showColoredIcons)
                                        ? raw : ImageTools.getSinglePic(raw);
                                Icon newIcon = Icon.createWithBitmap(finalBmp);
                                sIconCache.put(packageName, newIcon);
                                ImageTools.setSmallIcon(newIcon, notification);
                                return;
                            }
                        }
                    }
                    // 3. 内置算法分支
                    if (iconFuncStatus.iconFuncId == IconFunc.AUTO_FIX.funcId) {
                        Bitmap bitmap = getBitmap4Icon(smallIcon, context);
                        if (!new ImageUtils().isGrayscale(bitmap)) {
                            Bitmap bitmap1 = ImageTools.getSinglePic(bitmap);
                            Icon newIcon = Icon.createWithBitmap(bitmap1);
                            sIconCache.put(packageName, newIcon);
                            ImageTools.setSmallIcon(newIcon, notification);
                            return;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Bitmap getBitmap4Icon(Icon smallIcon, Context context) {
        if (smallIcon == null) return null;
        if (smallIcon.getType() == Icon.TYPE_RESOURCE) {
            return ImageTools.getBitmap(context, smallIcon.getResId());
        }
        if (smallIcon.getType() == Icon.TYPE_BITMAP || smallIcon.getType() == Icon.TYPE_ADAPTIVE_BITMAP) {
            return (Bitmap) ReflexUtil.getField4Obj(smallIcon, "mObj1");
        }
        if (smallIcon.getType() == Icon.TYPE_URI || smallIcon.getType() == Icon.TYPE_URI_ADAPTIVE_BITMAP) {
            String url = (String) ReflexUtil.getField4Obj(smallIcon, "mString1");
            return BitmapFactory.decodeFile(url);
        }
        if (smallIcon.getType() == Icon.TYPE_DATA) {
            byte[] mObj1s = (byte[]) ReflexUtil.getField4Obj(smallIcon, "mObj1");
            int mInt1 = (int) Objects.requireNonNull(ReflexUtil.getField4Obj(smallIcon, "mInt1"));
            int mInt2 = (int) Objects.requireNonNull(ReflexUtil.getField4Obj(smallIcon, "mInt2"));
            return BitmapFactory.decodeByteArray(mObj1s, mInt2, mInt1);
        }
        Drawable drawable = smallIcon.loadDrawable(context);
        return ImageTools.toBitmap(drawable);
    }
            }
