package com.xeasy.noticefix.hook;

import static com.xeasy.noticefix.hook.HookConstant.gson;

import android.annotation.SuppressLint;
import android.app.AndroidAppHelper;
import android.app.Application;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
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
import java.util.ArrayList;
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

    // 性能优化 1：引入 LruCache 内存缓存，避免重复解码 Base64 和重复像素运算
    private static final LruCache<String, Icon> sIconCache = new LruCache<>(80);
    // 性能优化 2：IPC 请求时间戳节流，防止死循环卡死 Binder
    private static long sLastReadConfigTime = 0;

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
            } catch (Exception e) {
                XposedBridge.log(LOG_PREV + "hook -- NotificationListener 错误");
            }
            try {
                inflateViews(loadPackageParam.classLoader);
            } catch (Exception e) {
                XposedBridge.log(LOG_PREV + "hook -- inflateViews 错误");
            }
            try {
                setIcon(loadPackageParam.classLoader);
            } catch (Exception e) {
                XposedBridge.log(LOG_PREV + "hook -- setIcon 错误");
            }
            try {
                setSystemExpanded(loadPackageParam.classLoader);
            } catch (Exception e) {
                XposedBridge.log(LOG_PREV + "hook -- setSystemExpanded 错误");
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
                            sIconCache.evictAll(); // 热刷新时清空内存缓存
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
                    if (HookConstant.getGlobalConfigDao() != null && HookConstant.getGlobalConfigDao().read && HookConstant.globalConfigDao.showColoredIcons) {
                        for (Object arg : param.args) {
                            if (arg instanceof View) {
                                View iconView = (View) arg;
                                @SuppressLint("DiscouragedApi")
                                int preLTag = AndroidAppHelper.currentApplication().getResources().getIdentifier("icon_is_pre_L", "id", "com.android.systemui");
                                if (preLTag != 0) {
                                    iconView.setTag(preLTag, true);
                                }
                            }
                        }
                    }
                }
            });
        }

        // 性能优化 3：剔除 updateIconColor 中的 createPackageContext 和像素遍历，释放主线程
        XposedHelpers.findAndHookMethod(
                XposedHelpers.findClass("com.android.systemui.statusbar.StatusBarIconView", classLoader),
                "updateIconColor",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (HookConstant.getGlobalConfigDao() != null && HookConstant.getGlobalConfigDao().read && HookConstant.globalConfigDao.showColoredIcons) {
                            ReflexUtil.setField4Obj("mCurrentSetColor", param.thisObject, 0);
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
                // 性能优化 4：节流控制，未读到配置且距离上次读取超过 10 秒才重试，严禁每帧轰炸 IPC
                long now = System.currentTimeMillis();
                if ((HookConstant.globalConfigDao == null || !HookConstant.globalConfigDao.read) && (now - sLastReadConfigTime > 10000)) {
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

    private void readConfigOld(Context context) {
        if (context == null) return;
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
            }
        } catch (Exception e) {
            XposedBridge.log(LOG_PREV + "读取图标资源失败，稍后自动重试");
        }
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

            // 缓存命中检查：同一应用处理过一次后直接使用内存缓存
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
                    // 3. 算法分支
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
