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
        // 保持纯净：绝不在此处执行读写操作，杜绝拉起进程时崩溃
        return true;
    }

    static Gson gson = new Gson();

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        MatrixCursor matrixCursor = new MatrixCursor(new String[]{"globalConfig", "iconFunc", "libIconList", "customIconList"});

        try {
            GlobalConfigDao globalConfigDao = GlobalConfigDao.globalConfigDao;
            if (globalConfigDao != null) {
                globalConfigDao.read = true;
            }

            List<IconFuncDao.IconFuncStatus> iconFunc = IconFuncDao.getIconFunc(getContext());
            Map<String, IconLibBean> iconLib = IconLibDao.getIconLib(getContext(), true);
            Map<String, CustomIconBean> allCustomIcons = CustomIconDao.getAllCustomIcons(getContext());

            matrixCursor.addRow(new Object[]{
                    gson.toJson(globalConfigDao),
                    gson.toJson(iconFunc),
                    gson.toJson(iconLib),
                    gson.toJson(allCustomIcons)
            });
        } catch (Exception ignored) {
        }
        return matrixCursor;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
