package com.example.medicationreminderassistant;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.example.medicationreminderassistant.service.NotificationService;
import com.example.medicationreminderassistant.util.SettingsManager;

/**
 * 提醒设置页：选择消息通知/闹钟方式、震动与语音播报开关、申请精确闹钟权限。
 * 设置保存后重建通知渠道，并按新设置重新注册全部药品的提醒。
 */
public class SettingsActivity extends AppCompatActivity {

    private SettingsManager settingsManager;
    private RadioGroup rgMode;
    private SwitchCompat switchVibrate;
    private SwitchCompat switchTts;
    private SwitchCompat switchRemindAgain;
    private RadioGroup rgRemindAgain;
    private TextView tvAlarmPermission;
    private Button btnGrantAlarm;
    private TextView tvFullScreenPermission;
    private Button btnGrantFullScreen;
    private RadioGroup rgRingDuration;
    private RadioGroup rgRinger;
    private TextView tvDndPermission;
    private Button btnGrantDnd;
    /** 勿扰权限的上一次状态，用于检测“刚授权”后重建通知渠道 */
    private Boolean dndPermissionKnown = null;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        settingsManager = new SettingsManager(this);

        rgMode = findViewById(R.id.rgMode);
        switchVibrate = findViewById(R.id.switchVibrate);
        switchTts = findViewById(R.id.switchTts);
        switchRemindAgain = findViewById(R.id.switchRemindAgain);
        rgRemindAgain = findViewById(R.id.rgRemindAgain);
        tvAlarmPermission = findViewById(R.id.tvAlarmPermission);
        btnGrantAlarm = findViewById(R.id.btnGrantAlarm);
        tvFullScreenPermission = findViewById(R.id.tvFullScreenPermission);
        btnGrantFullScreen = findViewById(R.id.btnGrantFullScreen);
        rgRingDuration = findViewById(R.id.rgRingDuration);
        rgRinger = findViewById(R.id.rgRinger);
        tvDndPermission = findViewById(R.id.tvDndPermission);
        btnGrantDnd = findViewById(R.id.btnGrantDnd);

        rgMode.check(settingsManager.getReminderMode() == SettingsManager.MODE_ALARM
                ? R.id.rbAlarm : R.id.rbNotification);
        switchVibrate.setChecked(settingsManager.isVibrateEnabled());
        switchTts.setChecked(settingsManager.isTtsEnabled());
        switchRemindAgain.setChecked(settingsManager.isRemindAgainEnabled());
        checkRemindAgainMinutes(settingsManager.getRemindAgainMinutes());
        updateRemindAgainEnabledState();

        checkRingDuration(settingsManager.getRingDurationMinutes());
        checkRinger(settingsManager.getRinger());

        switchRemindAgain.setOnCheckedChangeListener(
                (button, checked) -> updateRemindAgainEnabledState());
        btnGrantAlarm.setOnClickListener(v -> requestExactAlarmPermission());
        btnGrantFullScreen.setOnClickListener(v -> requestFullScreenPermission());
        btnGrantDnd.setOnClickListener(v -> requestDndPermission());
        findViewById(R.id.btnNotificationSettings).setOnClickListener(v -> openNotificationSettings());
        findViewById(R.id.btnDone).setOnClickListener(v -> saveAndFinish());
    }

    // ---------------- 响铃持续时间 ----------------

    private void checkRingDuration(int minutes) {
        int id;
        switch (minutes) {
            case 1: id = R.id.rbRing1; break;
            case 10: id = R.id.rbRing10; break;
            case 15: id = R.id.rbRing15; break;
            default: id = R.id.rbRing5; break;
        }
        rgRingDuration.check(id);
    }

    private int selectedRingDuration() {
        int checked = rgRingDuration.getCheckedRadioButtonId();
        if (checked == R.id.rbRing1) {
            return 1;
        }
        if (checked == R.id.rbRing10) {
            return 10;
        }
        if (checked == R.id.rbRing15) {
            return 15;
        }
        return 5;
    }

    // ---------------- 闹钟铃声 ----------------

    private void checkRinger(String ringer) {
        int id;
        if (SettingsManager.RINGER_TONE_1.equals(ringer)) {
            id = R.id.rbRingerTone1;
        } else if (SettingsManager.RINGER_TONE_2.equals(ringer)) {
            id = R.id.rbRingerTone2;
        } else if (SettingsManager.RINGER_TONE_3.equals(ringer)) {
            id = R.id.rbRingerTone3;
        } else {
            id = R.id.rbRingerSystem;
        }
        rgRinger.check(id);
    }

    private String selectedRinger() {
        int checked = rgRinger.getCheckedRadioButtonId();
        if (checked == R.id.rbRingerTone1) {
            return SettingsManager.RINGER_TONE_1;
        }
        if (checked == R.id.rbRingerTone2) {
            return SettingsManager.RINGER_TONE_2;
        }
        if (checked == R.id.rbRingerTone3) {
            return SettingsManager.RINGER_TONE_3;
        }
        return SettingsManager.RINGER_SYSTEM;
    }

    private void checkRemindAgainMinutes(int minutes) {
        int id;
        switch (minutes) {
            case 10: id = R.id.rbAgain10; break;
            case 20: id = R.id.rbAgain20; break;
            case 30: id = R.id.rbAgain30; break;
            default: id = R.id.rbAgain15; break;
        }
        rgRemindAgain.check(id);
    }

    private int selectedRemindAgainMinutes() {
        int checked = rgRemindAgain.getCheckedRadioButtonId();
        if (checked == R.id.rbAgain10) {
            return 10;
        }
        if (checked == R.id.rbAgain20) {
            return 20;
        }
        if (checked == R.id.rbAgain30) {
            return 30;
        }
        return 15;
    }

    private void updateRemindAgainEnabledState() {
        boolean enabled = switchRemindAgain.isChecked();
        rgRemindAgain.setEnabled(enabled);
        for (int i = 0; i < rgRemindAgain.getChildCount(); i++) {
            rgRemindAgain.getChildAt(i).setEnabled(enabled);
            rgRemindAgain.getChildAt(i).setAlpha(enabled ? 1f : 0.5f);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAlarmPermissionState();
        refreshFullScreenPermissionState();
        refreshDndPermissionState();
    }

    /** 精确闹钟权限：跳系统授权页（Android 12+；本工程 minSdk 34） */
    private void requestExactAlarmPermission() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null && alarmManager.canScheduleExactAlarms()) {
            Toast.makeText(this, "已拥有精确闹钟权限", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            // 个别机型没有该授权页，回退到应用详情页
            Intent fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            fallback.setData(Uri.parse("package:" + getPackageName()));
            startActivity(fallback);
        }
    }

    private void refreshAlarmPermissionState() {
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        boolean granted = alarmManager != null && alarmManager.canScheduleExactAlarms();
        if (granted) {
            tvAlarmPermission.setText("状态：已授予，提醒可以精确定时触发");
            tvAlarmPermission.setTextColor(getColor(R.color.brand_primary));
            btnGrantAlarm.setText("已授予");
            btnGrantAlarm.setEnabled(false);
            btnGrantAlarm.setAlpha(0.6f);
        } else {
            tvAlarmPermission.setText("状态：未授予。未授予时仍可用兼容模式定时，但建议授予以保证准时");
            tvAlarmPermission.setTextColor(getColor(R.color.danger));
            btnGrantAlarm.setText("授予精确闹钟权限");
            btnGrantAlarm.setEnabled(true);
            btnGrantAlarm.setAlpha(1f);
        }
    }

    /**
     * 全屏提醒权限（Android 14+）：
     * 未授予时，到点的闹钟不会自动弹出全屏响铃页，只能下拉通知栏手动点开。
     */
    private void requestFullScreenPermission() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && manager.canUseFullScreenIntent()) {
            Toast.makeText(this, "已拥有全屏提醒权限", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            openAppDetailsSettings();
        }
    }

    private void refreshFullScreenPermissionState() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean granted = manager != null && manager.canUseFullScreenIntent();
        if (granted) {
            tvFullScreenPermission.setText("状态：已授予。锁屏/灭屏时到点自动弹出全屏响铃页；亮屏使用手机时显示横幅通知，可直接点按钮处理");
            tvFullScreenPermission.setTextColor(getColor(R.color.brand_primary));
            btnGrantFullScreen.setText("已授予");
            btnGrantFullScreen.setEnabled(false);
            btnGrantFullScreen.setAlpha(0.6f);
        } else {
            tvFullScreenPermission.setText("状态：未授予。到点只显示横幅通知，需手动点通知处理，锁屏时不会自动弹出全屏页");
            tvFullScreenPermission.setTextColor(getColor(R.color.danger));
            btnGrantFullScreen.setText("授予全屏提醒权限");
            btnGrantFullScreen.setEnabled(true);
            btnGrantFullScreen.setAlpha(1f);
        }
    }

    /**
     * 勿扰访问权限：授予后通知渠道可绕过系统静音/免打扰，仍能震动提醒。
     * 未授予时只能依赖系统通知设置里的手动开关。
     */
    private void requestDndPermission() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && manager.isNotificationPolicyAccessGranted()) {
            Toast.makeText(this, "已拥有勿扰访问权限", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS));
        } catch (Exception e) {
            openAppDetailsSettings();
        }
    }

    private void refreshDndPermissionState() {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean granted = manager != null && manager.isNotificationPolicyAccessGranted();

        // 权限在渠道创建之后才授予时，旧渠道的“绕过免打扰”不会生效，需重建渠道
        if (Boolean.FALSE.equals(dndPermissionKnown) && granted) {
            NotificationService.refreshChannels(getApplicationContext());
        }
        dndPermissionKnown = granted;

        if (granted) {
            tvDndPermission.setText("状态：已授予，静音/免打扰下仍会震动提醒");
            tvDndPermission.setTextColor(getColor(R.color.brand_primary));
            btnGrantDnd.setText("已授予");
            btnGrantDnd.setEnabled(false);
            btnGrantDnd.setAlpha(0.6f);
        } else {
            tvDndPermission.setText("状态：未授予。静音或免打扰时可能无法震动提醒");
            tvDndPermission.setTextColor(getColor(R.color.danger));
            btnGrantDnd.setText("授予勿扰访问权限");
            btnGrantDnd.setEnabled(true);
            btnGrantDnd.setAlpha(1f);
        }
    }

    /** 打开本应用的系统通知设置，便于在部分机型上手动开启震动/铃声开关 */
    private void openNotificationSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            openAppDetailsSettings();
        }
    }

    private void openAppDetailsSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void saveAndFinish() {
        int mode = rgMode.getCheckedRadioButtonId() == R.id.rbAlarm
                ? SettingsManager.MODE_ALARM : SettingsManager.MODE_NOTIFICATION;
        settingsManager.setReminderMode(mode);
        settingsManager.setVibrateEnabled(switchVibrate.isChecked());
        settingsManager.setTtsEnabled(switchTts.isChecked());
        settingsManager.setRemindAgainEnabled(switchRemindAgain.isChecked());
        settingsManager.setRemindAgainMinutes(selectedRemindAgainMinutes());
        settingsManager.setRingDurationMinutes(selectedRingDuration());
        settingsManager.setRinger(selectedRinger());

        // 震动/铃声配置在建渠道时生效，需要重建；调度方式随提醒方式变化，需要重新注册
        NotificationService.refreshChannels(getApplicationContext());
        NotificationService.rescheduleAll(getApplicationContext());

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
