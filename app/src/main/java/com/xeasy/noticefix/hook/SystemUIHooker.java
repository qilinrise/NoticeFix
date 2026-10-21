package com.xeasy.noticefix.hook;

import static com.xeasy.noticefix.hook.HookConstant.gson;

import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
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
import java.util.stream.Collectors;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class SystemUIHooker implements IXposedHookLoadPackage {

    private static final String LOG_PREV = "NoticeFix---";

    private static final LruCache<String, Icon> sIconCache = new LruCache<>(80);
    private static long sLastReadConfigTime = 0;

    public static boolean isSystemPackage(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        return "android".equals(packageName)
                || "com.android.systemui".equals(packageName)
                || "com.google.android.systemui".equals(packageName)
                || packageName.startsWith("com.android.internal");
    }

    private static String getRealPackageName(Object builder, RemoteViews rv) {
        if (builder != null) {
            try {
                Notification n = (Notification) XposedHelpers.getObjectField(builder, "mNotification");
                if (n != null && n.extras != null) {
                    ApplicationInfo ai = n.extras.getParcelable("android.appInfo");
                    if (ai != null && ai.packageName != null && !ai.packageName.isEmpty()) {
                        return ai.packageName;
                    }
                    String extraPkg = n.extras.getString("android.substName");
                    if (extraPkg != null && !extraPkg.isEmpty()) {
                        return extraPkg;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        if (rv != null) {
            try {
                String rvPkg = rv.getPackage();
                if (rvPkg != null && !rvPkg.isEmpty() && !"com.android.systemui".equals(rvPkg)) {
                    return rvPkg;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Context getContextFromBuilder(Object builder) {
        if (builder == null) return null;
        try {
            return (Context) XposedHelpers.getObjectField(builder, "mContext");
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam.packageName.equals("com.xeasy.noticefix")) {
            Class<?> aClass = XposedHelpers.findClass("com.xeasy.noticefix.activity.MainActivity", loadPackageParam.classLoader);
            XposedHelpers.findAndHookMethod(aClass, "activeXposed", boolean.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.args[0] = true;
                }
            });
        }

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
                            sIconCache.evictAll();
                            readConfigOld(AndroidAppHelper.currentApplication());
                        }
                    }
                });
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
                    try {
                        Object entry = param.args[0];
                        View iconView = (View) param.args[2];
                        if (entry != null && iconView != null) {
                            StatusBarNotification sbn = (StatusBarNotification) ReflexUtil.getField4ObjByClass(entry.getClass(), entry, StatusBarNotification.class);
                            if (sbn != null) {
                                String pkg = sbn.getPackageName();
                                if (!isSystemPackage(pkg)) {
                                    @SuppressLint("DiscouragedApi")
                                    int icon_is_pre_L = AndroidAppHelper.currentApplication().getResources().getIdentifier("icon_is_pre_L", "id", "com.android.systemui");
                                    if (icon_is_pre_L != 0) {
                                        boolean shouldShowColor = HookConstant.globalConfigDao != null 
                                                && HookConstant.globalConfigDao.read 
                                                && HookConstant.globalConfigDao.showColoredIcons;
                                        iconView.setTag(icon_is_pre_L, shouldShowColor);
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        }

        final Class<?> standardTemplateParamsClass = XposedHelpers.findClass("android.app.Notification.StandardTemplateParams", classLoader);
        XposedHelpers.findAndHookMethod(Notification.Builder.class, "processSmallIconColor",
                Icon.class, RemoteViews.class, standardTemplateParamsClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        String realPkg = getRealPackageName(param.thisObject, (RemoteViews) param.args[1]);

                        if (isSystemPackage(realPkg)) {
                            return;
                        }

                        if (realPkg != null && HookConstant.customIconBeanMap != null) {
                            CustomIconBean customIconBean = HookConstant.customIconBeanMap.get(realPkg);
                            if (customIconBean != null && customIconBean.noHandle) {
                                return;
                            }
                        }

                        if (HookConstant.globalConfigDao != null && HookConstant.globalConfigDao.skipGrayscale) {
                            Icon icon = (Icon) param.args[0];
                            Context context = getContextFromBuilder(param.thisObject);
                            if (icon != null && context != null) {
                                try {
                                    Bitmap bitmap = getBitmap4Icon(icon, context);
                                    if (bitmap != null && new ImageUtils().isGrayscale(bitmap)) {
                                        return;
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                        }

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
                        if (!param.hasThrowable() && param.getResult() == null) {
                            return;
                        }

                        String realPkg = getRealPackageName(param.thisObject, (RemoteViews) param.args[1]);
                        if (isSystemPackage(realPkg)) {
                            return;
                        }

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
                        if (isSystemPackage(pkg)) {
                            return;
                        }
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
                long now = System.currentTimeMillis();
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
                                if (!isSystemPackage(statusBarNotification.getPackageName())) {
                                    fixNotificationIcon(statusBarNotification, mContext);
                                }
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

    private static void readConfigOld(Context context) {
        boolean loaded = false;
        if (context != null) {
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
                    loaded = true;
                }
            } catch (Exception ignored) {
            }
        }

        if (!loaded && (HookConstant.globalConfigDao == null || !HookConstant.globalConfigDao.read)) {
            try {
                XSharedPreferences xsp = new XSharedPreferences("com.xeasy.noticefix", "global_config_file");
                xsp.reload();
                String globalConfig = xsp.getString("global_config_file", null);
                if (globalConfig != null && !globalConfig.trim().isEmpty()) {
                    HookConstant.globalConfigDao = gson.fromJson(globalConfig, GlobalConfigDao.class);
                    if (HookConstant.globalConfigDao != null) {
                        HookConstant.globalConfigDao.read = true;
                    }
            } catch (Throwable ignored) {
            }
        }

        if (HookConstant.globalConfigDao == null) {
            HookConstant.globalConfigDao = new GlobalConfigDao();
            HookConstant.globalConfigDao.skipGrayscale = true;
            HookConstant.globalConfigDao.showColoredIcons = true;
            HookConstant.globalConfigDao.alwaysHandleProxyNotice = true;
        }
    }

    public static void fixNotificationIcon(StatusBarNotification statusBarNotification, Context context) {
        try {
            String packageName = statusBarNotification.getPackageName();
            if (isSystemPackage(packageName)) {
                return;
            }

            String opPkg = statusBarNotification.getOpPkg();
            // 核心修复 1：严格重构代理推送判定
            boolean isProxy = (opPkg != null && !opPkg.equals(packageName))
                    || "com.huawei.hwid".equals(opPkg)
                    || "com.huawei.hwid".equals(packageName);

            boolean alwaysHandle = HookConstant.globalConfigDao == null || HookConstant.globalConfigDao.alwaysHandleProxyNotice;

            CustomIconBean customIconBean = HookConstant.customIconBeanMap != null ? HookConstant.customIconBeanMap.get(packageName) : null;
            if (customIconBean != null && customIconBean.noHandle) {
                // 如果用户对该应用开启了“不处理”，但这是一条代理推送且开启了“始终处理推送消息”，则强制放行处理；否则拦截
                if (!isProxy || !alwaysHandle) {
                    return;
                }
            }

            Notification notification = statusBarNotification.getNotification();

            Icon cached = sIconCache.get(packageName);
            if (cached != null) {
                ImageTools.setSmallIcon(cached, notification);
                return;
            }

            Icon smallIcon = notification.getSmallIcon();

            // 核心修复 2：针对代理推送（如 HMS），不跳过灰度检测，强制让算法提取单色剪影
            if (!isProxy && HookConstant.globalConfigDao != null && HookConstant.globalConfigDao.skipGrayscale) {
                try {
                    Bitmap bitmap = getBitmap4Icon(smallIcon, context);
                    if (bitmap != null && new ImageUtils().isGrayscale(bitmap)) {
                        return;
                    }
                } catch (Exception ignored) {
                }
            }

            if (HookConstant.iconFuncStatuses == null) return;

            for (IconFuncDao.IconFuncStatus iconFuncStatus : HookConstant.iconFuncStatuses) {
                if (iconFuncStatus.active) {
                    // 图标库分支
                    if (iconFuncStatus.iconFuncId == IconFunc.LIB_FIX.funcId) {
                        IconLibBean iconLibBean = HookConstant.iconLibBeanMap != null ? HookConstant.iconLibBeanMap.get(packageName) : null;
                        if (iconLibBean != null) {
                            Bitmap raw = ImageTools.base64ToBitmap(iconLibBean.iconBitmap);
                            if (raw != null) {
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
                    // 自定义图标分支
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
                    // 算法提取分支（核心修复 3：针对 HMS 传过来的全彩桌面位图，提取为规范单色剪影）
                    if (iconFuncStatus.iconFuncId == IconFunc.AUTO_FIX.funcId) {
                        Bitmap bitmap = getBitmap4Icon(smallIcon, context);
                        if (bitmap != null) {
                            Bitmap singlePic = ImageTools.getSinglePic(bitmap);
                            if (singlePic != null) {
                                Icon newIcon = Icon.createWithBitmap(singlePic);
                                sIconCache.put(packageName, newIcon);
                                ImageTools.setSmallIcon(newIcon, notification);
                                return;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    // 核心修复 4：通过系统标准 Drawable API 解析各类位图与矢量图，彻底兼容 HMS 传入的自适应位图
    private static Bitmap getBitmap4Icon(Icon smallIcon, Context context) {
        if (smallIcon == null || context == null) return null;
        try {
            Drawable drawable = smallIcon.loadDrawable(context);
            if (drawable != null) {
                return ImageTools.toBitmap(drawable);
            }
        } catch (Throwable ignored) {
        }

        try {
            if (smallIcon.getType() == Icon.TYPE_RESOURCE) {
                return ImageTools.getBitmap(context, smallIcon.getResId());
            }
            if (smallIcon.getType() == Icon.TYPE_BITMAP || smallIcon.getType() == Icon.TYPE_ADAPTIVE_BITMAP) {
                return (Bitmap) ReflexUtil.getField4Obj(smallIcon, "mObj1");
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
