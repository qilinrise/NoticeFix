package com.xeasy.noticefix.activity;

import static android.view.animation.Animation.INFINITE;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.xeasy.noticefix.R;
import com.xeasy.noticefix.dao.GlobalConfigDao;
import com.xeasy.noticefix.dao.IconLibDao;
import com.xeasy.noticefix.databinding.SettingsActivityBinding;
import com.xeasy.noticefix.utils.GetFilePathFromUri;
import com.xeasy.noticefix.utils.PermissionsUtil;
import com.xeasy.noticefix.utils.TaskUtils;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Objects;
import java.util.concurrent.Callable;

public class SettingsActivity extends AppCompatActivity {

    @SuppressWarnings("FieldCanBeLocal")
    private SettingsActivityBinding binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 核心修复：进入页面第一时间从本地磁盘载入真实保存的配置数据
        GlobalConfigDao.initGlobalConfig(this);

        binding = SettingsActivityBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 菜单栏
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        // 返回箭头
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        // 1. 调试模式开关
        MaterialSwitch debugModeSwitch = findViewById(R.id.debug_mode);
        debugModeSwitch.setChecked(GlobalConfigDao.globalConfigDao.debugMode);
        debugModeSwitch.setOnClickListener((v) -> {
            boolean isChecked = debugModeSwitch.isChecked();
            if (isChecked) {
                debugModeSwitch.setChecked(false);
                reqPass(debugModeSwitch);
            }
        });
        debugModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            GlobalConfigDao.globalConfigDao.debugMode = isChecked;
            GlobalConfigDao.saveConfig(SettingsActivity.this, GlobalConfigDao.globalConfigDao);
        });

        // 2. 始终处理推送开关
        MaterialSwitch alwaysHandleProxyNoticeSwitch = findViewById(R.id.always_handle_proxy_notice);
        alwaysHandleProxyNoticeSwitch.setChecked(GlobalConfigDao.globalConfigDao.alwaysHandleProxyNotice);
        alwaysHandleProxyNoticeSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            GlobalConfigDao.globalConfigDao.alwaysHandleProxyNotice = isChecked;
            GlobalConfigDao.saveConfig(this, GlobalConfigDao.globalConfigDao);
        });

        // 3. 跳过灰度开关
        MaterialSwitch skipGrayscaleIconSwitch = findViewById(R.id.skip_grayscale_icon);
        skipGrayscaleIconSwitch.setChecked(GlobalConfigDao.globalConfigDao.skipGrayscale);
        skipGrayscaleIconSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            GlobalConfigDao.globalConfigDao.skipGrayscale = isChecked;
            GlobalConfigDao.saveConfig(this, GlobalConfigDao.globalConfigDao);
        });

        // 4. 解除彩色开关
        MaterialSwitch showColoredIconsSwitch = findViewById(R.id.show_colored_icons);
        showColoredIconsSwitch.setChecked(GlobalConfigDao.globalConfigDao.showColoredIcons);
        showColoredIconsSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            GlobalConfigDao.globalConfigDao.showColoredIcons = isChecked;
            GlobalConfigDao.saveConfig(this, GlobalConfigDao.globalConfigDao);
        });

        // 5. 展开通知开关
        MaterialSwitch expandAllNoticeSwitch = findViewById(R.id.expand_all_notice);
        expandAllNoticeSwitch.setChecked(GlobalConfigDao.globalConfigDao.expandAllNotice);
        expandAllNoticeSwitch.setOnCheckedChangeListener((btn, isChecked) -> {
            GlobalConfigDao.globalConfigDao.expandAllNotice = isChecked;
            GlobalConfigDao.saveConfig(this, GlobalConfigDao.globalConfigDao);
        });

        // 上传图标包
        ImageView updateIconLibrary = findViewById(R.id.update_icon_library);

        ActivityResultLauncher<Intent> intentActivityResultLauncher = SettingsActivity.this.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    Intent data = result.getData();
                    if (data != null && data.getData() != null && result.getResultCode() == Activity.RESULT_OK) {
                        Uri selectedImage = data.getData();
                        String fileAbsolutePath = GetFilePathFromUri.getFileAbsolutePath(SettingsActivity.this, selectedImage);
                        try {
                            IconLibDao.readAndInitIconLib(SettingsActivity.this, new FileInputStream(fileAbsolutePath));
                        } catch (FileNotFoundException e) {
                            e.printStackTrace();
                        }
                    }
                });

        updateIconLibrary.setOnClickListener(v -> {
            Callable<Objects> callable = () -> {
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.setType("application/json");
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intentActivityResultLauncher.launch(intent);
                return null;
            };
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) {
                PermissionsUtil.reqPermission(this, Manifest.permission.READ_MEDIA_IMAGES, callable);
            } else {
                PermissionsUtil.reqPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE, callable);
            }
        });

        ImageView refreshIconLibrary = findViewById(R.id.refresh_icon_library);
        final ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(refreshIconLibrary, "rotation", 1080f);
        objectAnimator.setDuration(1000);
        objectAnimator.setRepeatCount(INFINITE);
        refreshIconLibrary.setOnClickListener(v -> {
            if (GlobalConfigDao.globalConfigDao.debugMode) {
                objectAnimator.start();
                TaskUtils.createTask(() -> IconLibDao.refreshIconLibOnLine(SettingsActivity.this, objectAnimator));
            } else {
                Toast.makeText(SettingsActivity.this, getString(R.string.function_not_open), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void reqPass(MaterialSwitch switchView) {
        final EditText inputServer = new EditText(this);
        inputServer.setFilters(new InputFilter[]{new InputFilter.LengthFilter(50)});

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        builder.setTitle("暗号?").setIcon(android.R.drawable.ic_dialog_info).setView(inputServer)
                .setNegativeButton(getString(R.string.cancel), (dialog, which) -> switchView.setChecked(false));
        builder.setPositiveButton(getString(R.string.submit), (dialog, which) -> {
            String sign = inputServer.getText().toString();
            if (sign.equals("easy") || sign.equals("星夜不荟")) {
                switchView.setChecked(true);
            } else {
                Toast.makeText(SettingsActivity.this, "暗号错误", Toast.LENGTH_SHORT).show();
                switchView.setChecked(false);
            }
        });
        builder.show();
    }
}
