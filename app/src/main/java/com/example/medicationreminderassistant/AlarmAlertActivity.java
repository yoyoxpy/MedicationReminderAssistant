package com.example.medicationreminderassistant;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.service.AlarmService;
import com.example.medicationreminderassistant.service.MedicationDatabase;
import com.example.medicationreminderassistant.service.NotificationService;
import com.example.medicationreminderassistant.util.InsetsHelper;
import com.example.medicationreminderassistant.util.TimeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟形式的全屏响铃页（锁屏上也能直接显示）：
 * 同一时刻需要服用的多种药物会全部列出；
 * 我已服用（打卡并停铃）、5 分钟后再提醒（贪睡）、停止响铃。
 */
public class AlarmAlertActivity extends AppCompatActivity {

    public static final String EXTRA_MED_ID = "alarm_med_id";
    public static final String EXTRA_MED_IDS = "alarm_med_ids";

    private final List<Medication> medications = new ArrayList<>();
    private String reminderTime = "";

    /** 全屏响铃页是否正在前台显示，供其他界面避免重复拉起 */
    private static volatile boolean showing;

    public static boolean isShowing() {
        return showing;
    }

    /** 从任意上下文（服务/广播/界面）拉起全屏响铃页 */
    public static void show(Context context, long[] ids, String time) {
        Intent intent = new Intent(context, AlarmAlertActivity.class);
        intent.putExtra(EXTRA_MED_IDS, ids);
        intent.putExtra(AlarmService.EXTRA_TIME, time == null ? "" : time);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        showing = true;
    }

    @Override
    protected void onPause() {
        super.onPause();
        showing = false;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 锁屏上显示并点亮屏幕
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            KeyguardManager keyguardManager =
                    (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            if (keyguardManager != null) {
                keyguardManager.requestDismissKeyguard(this, null);
            }
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }

        setContentView(R.layout.activity_alarm_alert);
        // 底部三个操作按钮自动避开导航栏
        InsetsHelper.applyBottomNavInset(this);

        reminderTime = getIntent().getStringExtra(AlarmService.EXTRA_TIME);
        if (reminderTime == null) {
            reminderTime = "";
        }

        loadMedications();
        if (medications.isEmpty()) {
            AlarmService.stop(this);
            finish();
            return;
        }

        ((TextView) findViewById(R.id.tvAlarmTitle)).setText(
                medications.size() == 1 ? "用药闹钟" : "用药闹钟（多种药物）");
        ((TextView) findViewById(R.id.tvAlarmName)).setText(medications.size() == 1
                ? medications.get(0).getName()
                : "该服用 " + medications.size() + " 种药物");
        ((TextView) findViewById(R.id.tvAlarmDetail)).setText(
                NotificationService.buildReminderText(medications));

        Button btnTaken = findViewById(R.id.btnTaken);
        Button btnSnooze = findViewById(R.id.btnSnooze);
        Button btnDismiss = findViewById(R.id.btnDismiss);

        btnTaken.setOnClickListener(v -> {
            MedicationDatabase database = MedicationDatabase.getInstance(this);
            for (Medication m : medications) {
                database.recordDose(m.getId(), TimeUtils.today());
                // 已服用：取消尚未触发的漏服重提醒
                NotificationService.cancelReReminder(getApplicationContext(), m.getId());
            }
            NotificationService.cancelGroupSnooze(getApplicationContext());
            stopRinging();
            Toast.makeText(this, "已记录今日服药", Toast.LENGTH_SHORT).show();
        });

        btnSnooze.setOnClickListener(v -> {
            // 取消已排定的漏服重提醒，改为 5 分钟后整批再次提醒
            long[] ids = new long[medications.size()];
            for (int i = 0; i < medications.size(); i++) {
                ids[i] = medications.get(i).getId();
                NotificationService.cancelReReminder(getApplicationContext(), ids[i]);
            }
            NotificationService.scheduleSnoozeGroup(
                    getApplicationContext(), ids, reminderTime,
                    NotificationService.SNOOZE_DELAY_MS);
            stopRinging();
            Toast.makeText(this, "5 分钟后再次提醒", Toast.LENGTH_SHORT).show();
        });

        // 仅停止响铃：保留漏服重提醒，稍后会按设置的间隔自动再次提醒
        btnDismiss.setOnClickListener(v -> stopRinging());
    }

    private void loadMedications() {
        long[] ids = getIntent().getLongArrayExtra(EXTRA_MED_IDS);
        MedicationDatabase database = MedicationDatabase.getInstance(this);
        if (ids != null && ids.length > 0) {
            for (long id : ids) {
                Medication medication = database.getMedication(id);
                if (medication != null) {
                    medications.add(medication);
                }
            }
            return;
        }
        long medicationId = getIntent().getLongExtra(EXTRA_MED_ID, -1L);
        Medication medication = database.getMedication(medicationId);
        if (medication != null) {
            medications.add(medication);
        }
    }

    private void stopRinging() {
        AlarmService.stop(this);
        finish();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        long[] newIds = intent.getLongArrayExtra(EXTRA_MED_IDS);
        long[] oldIds = new long[medications.size()];
        for (int i = 0; i < medications.size(); i++) {
            oldIds[i] = medications.get(i).getId();
        }
        boolean same = newIds != null && newIds.length == oldIds.length;
        if (same) {
            for (int i = 0; i < newIds.length; i++) {
                if (newIds[i] != oldIds[i]) {
                    same = false;
                    break;
                }
            }
        }
        if (!same) {
            // 又有新的一批药品触发响铃：更新数据并重建页面
            setIntent(intent);
            recreate();
        }
    }

    @Override
    public void onBackPressed() {
        // 闹钟必须显式处理，不允许返回键静默关掉
    }
}
