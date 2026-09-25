package com.xeasy.noticefix.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import com.google.gson.Gson;
import com.xeasy.noticefix.bean.CustomIconBean;
import com.xeasy.noticefix.bean.IconLibBean;
import com.xeasy.noticefix.dao.CustomIconDao;
import com.xeasy.noticefix.dao.GlobalConfigDao;
import com.xeasy.noticefix.dao.IconFuncDao;
import com.xeasy.noticefix.dao.IconLibDao;

import java.util.List;
import java.util.Map;

public class IconDataContentProvider extends ContentProvider {
    public IconDataContentProvider() {
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public String getType(Uri uri) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public boolean onCreate() {
        // 核心修复 1：必须返回 true，通知系统该 Provider 初始化成功并对外开放
        return true;
    }

    static Gson gson = new Gson();

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor matrixCursor = new MatrixCursor(new String[]{"globalConfig", "iconFunc", "libIconList", "customIconList"});

        // 核心修复 2：防御冷启动空指针，确保配置初始化完成
        if (GlobalConfigDao.globalConfigDao == null) {
            GlobalConfigDao.initGlobalConfig(getContext());
        }
        GlobalConfigDao globalConfigDao = GlobalConfigDao.globalConfigDao;
        if (globalConfigDao != null) {
            globalConfigDao.read = true;
        }

        List<IconFuncDao.IconFuncStatus> iconFunc = IconFuncDao.getIconFunc(getContext());
        
        // 核心修复 3：传入 true，强制从本地持久化中加载你导入的图标包素材
        Map<String, IconLibBean> iconLib = IconLibDao.getIconLib(getContext(), true);
        Map<String, CustomIconBean> allCustomIcons = CustomIconDao.getAllCustomIcons(getContext());

        matrixCursor.addRow(new Object[]{
                gson.toJson(globalConfigDao),
                gson.toJson(iconFunc),
                gson.toJson(iconLib),
                gson.toJson(allCustomIcons)
        });
        return matrixCursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
