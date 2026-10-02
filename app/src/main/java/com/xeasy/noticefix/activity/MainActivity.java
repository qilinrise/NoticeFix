package com.xeasy.noticefix.activity;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.DynamicColors;
import com.xeasy.noticefix.R;
import com.xeasy.noticefix.adapter.IconOrderAdapter;
import com.xeasy.noticefix.dao.IconFuncDao;
import com.xeasy.noticefix.databinding.ActivityMainBinding;
import com.xeasy.noticefix.utils.AppNotification;
import com.xeasy.noticefix.utils.CommandUtil;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // 核心：在初始化前注入系统莫奈动态取色（完美对齐系统设置色彩）
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);

        try {
            binding = ActivityMainBinding.inflate(getLayoutInflater());
            setContentView(binding.getRoot());
            if (binding.toolbar != null) {
                setSupportActionBar(binding.toolbar);
            }
        } catch (Exception ignored) {
            setContentView(R.layout.activity_main);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }

        try {
            List<IconFuncDao.IconFuncStatus> iconFunc = IconFuncDao.getIconFunc(this);
            RecyclerView recyclerView = findViewById(R.id.main_recyclerView);
            if (recyclerView != null) {
                recyclerView.setLayoutManager(new LinearLayoutManager(this));
                recyclerView.addItemDecoration(new DividerItemDecoration(this, LinearLayoutManager.VERTICAL));
                IconOrderAdapter adapter = new IconOrderAdapter(iconFunc, recyclerView, this);
                recyclerView.setAdapter(adapter);
            }
        } catch (Exception ignored) {
        }

        View viewById = findViewById(R.id.custom_icon_config);
        if (viewById != null) {
            viewById.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, AppListActivity.class);
                startActivity(intent);
            });
        }

        View viewIconLib = findViewById(R.id.view_icon_lib);
        if (viewIconLib != null) {
            viewIconLib.setOnClickListener(v -> {
                Intent intent = new Intent(MainActivity.this, IconLibActivity.class);
                startActivity(intent);
            });
        }

        activeXposed(false);
    }

    private void activeXposed(boolean active) {
        TextView status = findViewById(R.id.xposed_status);
        if (status != null) {
            if (active) {
                status.setText(getString(R.string.xposed_status, getString(R.string.yes)));
                status.setTextColor(getColor(android.R.color.holo_green_light));
            } else {
                status.setText(getString(R.string.xposed_status, getString(R.string.no)));
                status.setTextColor(getColor(android.R.color.holo_red_light));
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    private static final String LOG_PREV = "NoticeFix---";

    @SuppressLint("UnspecifiedImmutableFlag")
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
            startActivity(intent);
            return true;
        }
        if (id == R.id.reset_icon) {
            String s = CommandUtil.execShellBackAll("chmod 664 /data/data/com.xeasy.noticefix/shared_prefs/global_config_file.xml", false);
            Log.d(LOG_PREV, "设置权限664  ==》 " + s);
            String s2 = CommandUtil.execShellBackAll("chmod -R 755 /data/data/com.xeasy.noticefix/shared_prefs", false);
            Log.d(LOG_PREV, "设置权限 755  ==》 " + s2);

            AppNotification.sendFlashNoticeMessage(this, null);
        }
        if (id == R.id.restart_systemui) {
            new AlertDialog.Builder(this)
                    .setTitle("确认")
                    .setMessage("确定要重启 SystemUI 吗？")
                    .setPositiveButton(getString(R.string.yes), (dialog, which) -> {
                        CommandUtil.restartSystemUI(this);
                    })
                    .setNegativeButton(getString(R.string.no), (dialog, which) -> {
                    })
                    .show();

            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
