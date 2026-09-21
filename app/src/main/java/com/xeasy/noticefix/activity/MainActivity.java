package com.xeasy.noticefix.activity;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.xeasy.noticefix.R;
import com.xeasy.noticefix.adapter.IconOrderAdapter;
import com.xeasy.noticefix.dao.IconFuncDao;
import com.xeasy.noticefix.databinding.ActivityMainBinding;
import com.xeasy.noticefix.utils.AppNotification;
import com.xeasy.noticefix.utils.CommandUtil;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    @SuppressWarnings("FieldCanBeLocal")
    private ActivityMainBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        // 从工具类获取配置文件信息
        List<IconFuncDao.IconFuncStatus> iconFunc = IconFuncDao.getIconFunc(this);

        RecyclerView recyclerView = findViewById(R.id.main_recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.addItemDecoration(new DividerItemDecoration(this, LinearLayoutManager.VERTICAL));
        IconOrderAdapter adapter = new IconOrderAdapter(iconFunc, recyclerView, this);
        recyclerView.setAdapter(adapter);

        // 自定义图标页面跳转
        View viewById = findViewById(R.id.custom_icon_config);
        viewById.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AppListActivity.class);
            startActivity(intent);
        });
        
        // 图标库页面跳转
        View viewIconLib = findViewById(R.id.view_icon_lib);
        viewIconLib.setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, IconLibActivity.class);
            startActivity(intent);
        });

        activeXposed(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置开启权限切回前台时，自动检测并关闭提示弹窗
        AppNotification.checkAndDismissDialog(this);
    }

    @SuppressWarnings("SameParameterValue")
    private void activeXposed(boolean active) {
        TextView status = findViewById(R.id.xposed_status);
        if ( active ) {
            status.setText(getString(R.string.xposed_status, getString(R.string.yes)));
            status.setTextColor(getColor(android.R.color.holo_green_dark));
        } else {
            status.setText(getString(R.string.xposed_status, getString(R.string.no)));
            status.setTextColor(getColor(android.R.color.holo_red_dark));
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
            new AlertDialog.Builder(this).setTitle("confirm")
                    .setMessage("Restart SystemUI ? ")
                    .setPositiveButton(this.getString(R.string.yes), (dialog, which) -> {
                        CommandUtil.restartSystemUI(this);
                    }).setNegativeButton(this.getString(R.string.no), (dialog, which) -> {
                    }).show();

            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
