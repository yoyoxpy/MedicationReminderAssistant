package com.example.medicationreminderassistant.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.util.SettingsManager;
import com.example.medicationreminderassistant.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 服药闹钟接收器：
 * - 消息通知方式：弹状态栏通知，按设置语音播报；
 * - 闹钟方式：启动前台 AlarmService 全屏响铃（震动/语音由服务按设置执行）；
 * - 同一时刻需要服用的多种药物合并为一次提醒，连续播报并同屏展示全部信息；
 * - 用持久化去重键避免同一时刻的多个闹钟重复呈现；
 * - 触发后安排下一天的同名闹钟实现每日循环（贪睡/重提醒不重排）；
 * - 若已到点仍未打卡，按设置间隔安排"漏服重提醒"，直到打卡已服用为止。
 */
public class ReminderReceiver extends BroadcastReceiver {

    private static final String TAG = "ReminderReceiver";
    private static final String PREFS = "reminder_dedup";
    private static final String KEY_LAST_PRESENTED = "last_presented_key";

    /** 同一时刻多个闹钟几乎同时触发，用进程内锁串行化去重判断 */
    private static final Object DEDUP_LOCK = new Object();

    @Override
    public void onReceive(Context context, Intent intent) {
        final long[] groupIds = intent.getLongArrayExtra(
                NotificationService.EXTRA_MED_IDS);
        final long medicationId = intent.getLongExtra(
                NotificationService.EXTRA_MED_ID, -1L);
        final String time = intent.getStringExtra(NotificationService.EXTRA_TIME);
        final boolean isSnooze = intent.getBooleanExtra(
                NotificationService.EXTRA_SNOOZE, false);
        final boolean isReRemind = intent.getBooleanExtra(
                NotificationService.EXTRA_RE_REMIND, false);
        final String alarmAction = intent.getStringExtra(
                NotificationService.EXTRA_ALARM_ACTION);
        Log.d(TAG, "onReceive: medId=" + medicationId
                + " group=" + (groupIds == null ? 0 : groupIds.length)
                + " time=" + time + " snooze=" + isSnooze
                + " reRemind=" + isReRemind + " action=" + alarmAction);
        if ((groupIds == null || groupIds.length == 0) && medicationId < 0) {
            Log.w(TAG, "onReceive: 无药品 id，忽略");
            return;
        }

        // 闹钟通知上的快捷操作（已服用 / 5 分钟贪睡 / 停止响铃）
        if (alarmAction != null && groupIds != null && groupIds.length > 0) {
            final PendingResult actionResult = goAsync();
            new Thread(() -> {
                try {
                    handleAlarmAction(context, groupIds, time, alarmAction);
                } finally {
                    actionResult.finish();
                }
            }).start();
            return;
        }

        final PendingResult asyncResult = goAsync();
        new Thread(() -> {
            try {
                SettingsManager settings = new SettingsManager(context);
                // 一批药品的贪睡：整批再次呈现（不重排下一天）
                if (groupIds != null && groupIds.length > 0) {
                    handleSnoozeGroup(context, groupIds, time, settings);
                    return;
                }

                Medication medication =
                        MedicationDatabase.getInstance(context).getMedication(medicationId);
                if (medication == null || !medication.isEnabled()) {
                    Log.w(TAG, "药品不存在或已关闭: id=" + medicationId);
                    return;
                }
                if (isSnooze || isReRemind) {
                    handleSingle(context, medication, settings, isReRemind);
                } else {
                    handleScheduled(context, medication, time, settings);
                }
            } catch (Throwable t) {
                // 广播线程未捕获异常会直接杀死整个进程，导致提醒彻底无声无息
                Log.e(TAG, "处理提醒异常，尝试通知兜底", t);
                try {
                    Medication fallback = MedicationDatabase.getInstance(context)
                            .getMedication(medicationId);
                    if (fallback != null) {
                        NotificationService.showReminderNotification(context, fallback);
                    }
                } catch (Exception ignored) {
                    // 兜底失败只能记录日志
                }
            } finally {
                // 给 TTS 异步初始化和播报留出时间
                new Handler(Looper.getMainLooper())
                        .postDelayed(asyncResult::finish, 9000L);
            }
        }).start();
    }

    /** 正常定时提醒：合并同一时刻的全部药品，呈现一次并各自重排下一天 */
    private void handleScheduled(Context context, Medication medication,
                                 String time, SettingsManager settings) {
        // 去重：同一时刻可能有多条药品的闹钟几乎同时触发，只呈现一次
        if (!claimPresentation(context, TimeUtils.today() + "_" + time)) {
            return;
        }

        List<Medication> group = NotificationService.medicationsAt(context, time);
        if (group.isEmpty()) {
            group = Collections.singletonList(medication);
        }

        // 每日循环：为该时刻的全部药品安排明天同一时间
        for (Medication m : group) {
            NotificationService.scheduleNextDay(context, m.getId(), time,
                    NotificationService.indexOfTime(m, time));
        }

        present(context, group, settings, NotificationService.groupNotificationId(time), time);
        scheduleReReminders(context, group, settings);
    }

    /** 贪睡 / 漏服重提醒：只针对单条药品 */
    private void handleSingle(Context context, Medication medication,
                              SettingsManager settings, boolean isReRemind) {
        boolean takenToday = isTaken(context, medication.getId());
        if (isReRemind && takenToday) {
            return;
        }
        List<Medication> group = Collections.singletonList(medication);
        present(context, group, settings, (int) medication.getId(), "");
        if (!takenToday && settings.isRemindAgainEnabled()) {
            scheduleReReminder(context, medication.getId(), settings);
        }
    }

    /** 一批药品的贪睡：整批再次呈现，并为仍未打卡者安排后续重提醒 */
    private void handleSnoozeGroup(Context context, long[] ids, String time,
                                   SettingsManager settings) {
        List<Medication> group = new ArrayList<>();
        MedicationDatabase database = MedicationDatabase.getInstance(context);
        for (long id : ids) {
            Medication m = database.getMedication(id);
            if (m != null && m.isEnabled()) {
                group.add(m);
            }
        }
        if (group.isEmpty()) {
            return;
        }
        present(context, group, settings, NotificationService.groupNotificationId(time), time);
        scheduleReReminders(context, group, settings);
    }

    /** 按提醒方式呈现（闹钟全屏响铃 / 消息通知 + 语音播报） */
    private void present(Context context, List<Medication> group,
                         SettingsManager settings, int notificationId, String time) {
        int mode = settings.getReminderMode();
        Log.d(TAG, "present: mode=" + mode + " meds=" + group.size()
                + " notifId=" + notificationId + " time=" + time);
        if (mode == SettingsManager.MODE_ALARM) {
            long[] ids = new long[group.size()];
            for (int i = 0; i < group.size(); i++) {
                ids[i] = group.get(i).getId();
            }
            try {
                AlarmService.start(context, ids, notificationId, time);
            } catch (Throwable t) {
                // 后台启动前台服务可能被系统拦截（如未获自启动/后台运行权限），
                // 此时回退为横幅通知 + 语音，至少让用户感知到提醒
                Log.e(TAG, "启动响铃服务失败，回退为通知提醒", t);
                NotificationService.showReminderNotification(context, group, notificationId);
                if (settings.isTtsEnabled()) {
                    try {
                        TTSService.get(context).speakWithSettings(
                                NotificationService.buildSpeechText(group), settings);
                    } catch (Exception ignored) {
                        // 语音失败不影响通知
                    }
                }
            }
        } else {
            NotificationService.showReminderNotification(context, group, notificationId);
            if (settings.isTtsEnabled()) {
                TTSService.get(context).speakWithSettings(
                        NotificationService.buildSpeechText(group), settings);
            }
        }
    }

    /** 为尚未打卡的药品安排漏服重提醒 */
    private void scheduleReReminders(Context context, List<Medication> group,
                                     SettingsManager settings) {
        if (!settings.isRemindAgainEnabled()) {
            return;
        }
        for (Medication m : group) {
            if (!isTaken(context, m.getId())) {
                scheduleReReminder(context, m.getId(), settings);
            }
        }
    }

    private void scheduleReReminder(Context context, long medicationId,
                                    SettingsManager settings) {
        NotificationService.scheduleReReminder(context, medicationId,
                settings.getRemindAgainMinutes() * 60_000L);
    }

    /** 处理闹钟通知上的快捷操作按钮（不需要打开全屏页即可处理） */
    private void handleAlarmAction(Context context, long[] ids, String time, String action) {
        String toastText;
        switch (action) {
            case NotificationService.ALARM_ACTION_TAKEN:
                MedicationDatabase takenDb = MedicationDatabase.getInstance(context);
                String today = TimeUtils.today();
                for (long id : ids) {
                    takenDb.recordDose(id, today);
                    NotificationService.cancelReReminder(context, id);
                }
                NotificationService.cancelGroupSnooze(context);
                toastText = "已记录服药";
                break;
            case NotificationService.ALARM_ACTION_SNOOZE:
                for (long id : ids) {
                    NotificationService.cancelReReminder(context, id);
                }
                NotificationService.scheduleSnoozeGroup(
                        context, ids, time, NotificationService.SNOOZE_DELAY_MS);
                toastText = "5 分钟后再次提醒";
                break;
            case NotificationService.ALARM_ACTION_DISMISS:
                toastText = "已停止响铃";
                break;
            default:
                return;
        }
        AlarmService.stop(context);
        final String text = toastText;
        new Handler(Looper.getMainLooper())
                .post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show());
    }

    private boolean isTaken(Context context, long medicationId) {
        return MedicationDatabase.getInstance(context)
                .isTaken(medicationId, TimeUtils.today());
    }

    /**
     * 用持久化键去重：仅当与上次呈现的时刻不同才返回 true。
     * 多个闹钟在同一时刻并发触发时，只有第一个通过。
     */
    private boolean claimPresentation(Context context, String key) {
        synchronized (DEDUP_LOCK) {
            SharedPreferences prefs = context.getSharedPreferences(
                    PREFS, Context.MODE_PRIVATE);
            if (key.equals(prefs.getString(KEY_LAST_PRESENTED, null))) {
                return false;
            }
            // 同步提交，保证并发触发时只有一次通过
            prefs.edit().putString(KEY_LAST_PRESENTED, key).commit();
            return true;
        }
    }
}
