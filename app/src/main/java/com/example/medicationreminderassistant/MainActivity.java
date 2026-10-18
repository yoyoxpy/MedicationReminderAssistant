package com.example.medicationreminderassistant;

import android.Manifest;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medicationreminderassistant.adapter.MedicationAdapter;
import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.service.AlarmService;
import com.example.medicationreminderassistant.service.MedicationDatabase;
import com.example.medicationreminderassistant.service.NotificationService;
import com.example.medicationreminderassistant.util.ImageUtils;
import com.example.medicationreminderassistant.util.InsetsHelper;
import com.example.medicationreminderassistant.util.SettingsManager;
import com.example.medicationreminderassistant.util.TimeUtils;
import com.example.medicationreminderassistant.widget.FloatingBallView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_CAMERA = 2001;
    private static final int REQUEST_CODE_NOTIFICATIONS = 2002;
    private static final int REQUEST_CODE_GALLERY = 2003;

    private MedicationDatabase database;
    private MedicationAdapter adapter;
    private TextView tvEmpty;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        // 底部内容自动避开导航栏：开导航栏时贴其上方，关闭后回到底部
        InsetsHelper.applyBottomNavInset(this);

        database = MedicationDatabase.getInstance(this);

        RecyclerView recyclerView = findViewById(R.id.rvMedications);
        tvEmpty = findViewById(R.id.tvEmpty);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MedicationAdapter(medicationId -> {
            Intent intent = new Intent(MainActivity.this, DetailActivity.class);
            intent.putExtra(DetailActivity.EXTRA_MEDICATION_ID, medicationId);
            startActivity(intent);
        });
        recyclerView.setAdapter(adapter);

        Button btnOpenCamera = findViewById(R.id.btnOpenCamera);
        btnOpenCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, CameraActivity.class);
                startActivityForResult(intent, REQUEST_CODE_CAMERA);
            }
        });

        // 从相册选择处方图片识别（系统选择器，免存储权限）
        Button btnPickGallery = findViewById(R.id.btnPickGallery);
        btnPickGallery.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(
                    Intent.createChooser(intent, "选择处方图片"), REQUEST_CODE_GALLERY);
        });

        findViewById(R.id.btnSettings).setOnClickListener(
                v -> startActivity(new Intent(MainActivity.this, SettingsActivity.class)));

        // 启动时重新注册所有开启中的闹钟：防止系统更新/清理后闹钟丢失，
        // 也保证首次安装后已有药品的提醒立即生效（幂等，重复调用安全）
        NotificationService.rescheduleAll(getApplicationContext());

        requestNotificationPermissionIfNeeded();
        maybePromptBatteryOptimization();

        // 主页挂载 AI 助手悬浮球（可拖动，点击进入聊天）
        FloatingBallView.attach(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 闹钟正在响铃时打开 App，直接进入全屏响铃页
        // （亮屏解锁状态下系统默认只把全屏意图降级为横幅通知）
        if (AlarmService.hasActiveAlarm() && !AlarmAlertActivity.isShowing()) {
            try {
                AlarmAlertActivity.show(getApplicationContext(),
                        AlarmService.getActiveIds(), AlarmService.getActiveTime());
                return;
            } catch (Exception ignored) {
                // 极端情况下拉起失败，继续正常显示主页
            }
        }
        loadMedications();
        maybePromptFullScreenPermission();
    }

    /**
     * Android 14+ 闹钟模式下若未授予"全屏通知"权限，到点只会显示横幅通知、
     * 不会自动弹出全屏响铃页。主动引导一次；用户点"暂不"后不再打扰，
     * 仍可随时在设置页的"全屏提醒权限"中开启。
     */
    private void maybePromptFullScreenPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return;
        }
        if (new SettingsManager(this).getReminderMode() != SettingsManager.MODE_ALARM) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.canUseFullScreenIntent()) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences("permission_prompts", MODE_PRIVATE);
        if (prefs.getBoolean("fsi_rationale_dismissed", false)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("开启全屏提醒")
                .setMessage("当前是闹钟提醒模式，但还没有授予“全屏通知”权限。\n\n"
                        + "未授予时到点只会在通知栏提示，不会自动弹出全屏响铃页、"
                        + "锁屏时也不会自动亮起。请在接下来的系统设置中打开本应用的开关。")
                .setPositiveButton("去开启", (d, w) -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        Toast.makeText(this, "未找到该设置页，请进入系统设置手动开启",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("暂不", (d, w) ->
                        prefs.edit().putBoolean("fsi_rationale_dismissed", true).apply())
                .setCancelable(false)
                .show();
    }

    /**
     * 若应用未加入电池优化白名单，系统可能在后台延迟或杀死闹钟进程，
     * 导致到点不提醒。引导用户授权；点"暂不"后不再提示。
     */
    private void maybePromptBatteryOptimization() {
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        SharedPreferences prefs = getSharedPreferences("permission_prompts", MODE_PRIVATE);
        if (prefs.getBoolean("battery_rationale_dismissed", false)) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("允许后台运行")
                .setMessage("为保证到点准时提醒，请将本应用加入电池优化白名单。\n\n"
                        + "否则系统可能在后台延迟或关闭提醒，导致闹钟不响、通知不弹。")
                .setPositiveButton("去开启", (d, w) -> {
                    try {
                        Intent intent = new Intent(
                                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intent.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(intent);
                    } catch (Exception e) {
                        // 个别机型不支持该跳转，回退到电池优化设置页
                        try {
                            Intent intent = new Intent(
                                    Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(intent);
                        } catch (Exception ex) {
                            Toast.makeText(this, "请在系统设置中手动允许后台运行",
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton("暂不", (d, w) ->
                        prefs.edit().putBoolean("battery_rationale_dismissed", true).apply())
                .setCancelable(false)
                .show();
    }

    /** 从数据库加载药品，并查询今日打卡状态 */
    private void loadMedications() {
        List<Medication> medications = database.getAllMedications();
        Set<Long> takenIds = new HashSet<>();
        String today = TimeUtils.today();
        for (Medication m : medications) {
            if (database.isTaken(m.getId(), today)) {
                takenIds.add(m.getId());
            }
        }
        adapter.submit(medications, takenIds);
        tvEmpty.setVisibility(medications.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_CAMERA && resultCode == RESULT_OK && data != null) {
            String photoPath = data.getStringExtra(CameraActivity.EXTRA_PHOTO_PATH);
            if (photoPath != null) {
                openConfirm(photoPath);
            }
        } else if (requestCode == REQUEST_CODE_GALLERY && resultCode == RESULT_OK
                && data != null && data.getData() != null) {
            String photoPath = ImageUtils.copyUriToCache(this, data.getData());
            if (photoPath == null) {
                Toast.makeText(this, "读取图片失败，请重试", Toast.LENGTH_SHORT).show();
                return;
            }
            openConfirm(photoPath);
        }
    }

    /** 携带处方图片路径进入识别确认页 */
    private void openConfirm(String photoPath) {
        Intent intent = new Intent(this, ConfirmActivity.class);
        intent.putExtra(ConfirmActivity.EXTRA_PHOTO_PATH, photoPath);
        startActivity(intent);
    }

    /** Android 13+ 发送通知需要运行时申请 POST_NOTIFICATIONS */
    private void requestNotificationPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_CODE_NOTIFICATIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_NOTIFICATIONS
                && (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED)) {
            Toast.makeText(this, "未授予通知权限，到点提醒可能无法弹出", Toast.LENGTH_LONG).show();
        }
    }
}
