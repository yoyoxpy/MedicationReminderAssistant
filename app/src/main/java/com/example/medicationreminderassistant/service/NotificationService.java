package com.example.medicationreminderassistant.service;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.medicationreminderassistant.AlarmAlertActivity;
import com.example.medicationreminderassistant.MainActivity;
import com.example.medicationreminderassistant.R;
import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.util.SettingsManager;
import com.example.medicationreminderassistant.util.TimeUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 提醒服务：
 * - 两个通知渠道：消息通知 / 闹钟（USAGE_ALARM 铃声、可全屏）；
 * - 调度：闹钟方式用 setAlarmClock；消息方式在有精确闹钟权限时用
 *   setExactAndAllowWhileIdle，无权限时回退 setAlarmClock 保证准时；
 * - 通知与语音内容统一包含药品名称、每次剂量、服用方法（备注）。
 */
public class NotificationService {

    private static final String TAG = "NotificationService";

    public static final String EXTRA_MED_ID = "med_id";
    /** 同一时刻合并提醒的多条药品 id（long[]） */
    public static final String EXTRA_MED_IDS = "med_ids";
    public static final String EXTRA_TIME = "time";
    public static final String EXTRA_INDEX = "index";
    public static final String EXTRA_SNOOZE = "is_snooze";
    public static final String EXTRA_RE_REMIND = "is_re_remind";
    /** 闹钟通知快捷操作：已服用 / 贪睡 / 停止响铃 */
    public static final String EXTRA_ALARM_ACTION = "alarm_action";
    public static final String ALARM_ACTION_TAKEN = "alarm_taken";
    public static final String ALARM_ACTION_SNOOZE = "alarm_snooze";
    public static final String ALARM_ACTION_DISMISS = "alarm_dismiss";
    /** 贪睡时长（与全屏响铃页一致） */
    public static final long SNOOZE_DELAY_MS = 5 * 60 * 1000L;

    // 渠道设置（铃声/震动/免打扰绕过）创建后不可修改，旧渠道若曾以静音参数创建将永久静音，
    // 故使用带版本号的渠道 ID，保证首次升级后即建立带闹钟铃声、可绕过免打扰的新渠道。
    private static final String CHANNEL_NOTIFICATION = "medication_notification_v4";
    private static final String CHANNEL_ALARM = "medication_alarm_v4";

    private static final int MAX_TIMES_PER_DAY = 8;
    private static final int SNOOZE_SLOT = 98;
    private static final int RE_REMIND_SLOT = 97;

    private NotificationService() {
    }

    /** 为一条药品注册所有时间点的每日闹钟（今天已过的时间点从明天开始） */
    public static void scheduleForMedication(Context context, Medication medication) {
        ensureChannels(context);
        AlarmManager alarmManager = alarmManager(context);
        if (alarmManager == null) {
            return;
        }

        List<String> times = medication.getTimes();
        Log.d(TAG, "scheduleForMedication: id=" + medication.getId()
                + " name=" + medication.getName() + " times=" + times);
        for (int i = 0; i < times.size() && i < MAX_TIMES_PER_DAY; i++) {
            String time = times.get(i);
            long triggerAt = TimeUtils.nextTriggerMillis(time, 0);
            if (triggerAt <= System.currentTimeMillis() + 60_000L) {
                triggerAt = TimeUtils.nextTriggerMillis(time, 1);
            }
            PendingIntent operation = buildPendingIntent(
                    context, medication.getId(), time, i, false, false);
            setDailyAlarm(alarmManager, triggerAt, operation, context);
        }
    }

    /** 触发后安排明天同一时间的下一次提醒（每日循环） */
    public static void scheduleNextDay(Context context, long medicationId,
                                       String time, int index) {
        AlarmManager alarmManager = alarmManager(context);
        if (alarmManager == null || index >= MAX_TIMES_PER_DAY) {
            return;
        }
        long triggerAt = TimeUtils.nextTriggerMillis(time, 1);
        PendingIntent operation = buildPendingIntent(
                context, medicationId, time, index, false, false);
        setDailyAlarm(alarmManager, triggerAt, operation, context);
    }

    /** 贪睡：delayMillis 后只响一次，不影响每日循环 */
    public static void scheduleSnooze(Context context, long medicationId, long delayMillis) {
        AlarmManager alarmManager = alarmManager(context);
        if (alarmManager == null) {
            return;
        }
        long triggerAt = System.currentTimeMillis() + delayMillis;
        PendingIntent operation = buildPendingIntent(
                context, medicationId, "", SNOOZE_SLOT, true, false);
        setDailyAlarm(alarmManager, triggerAt, operation, context);
    }

    /** 贪睡（同一时刻的一批药品）：delayMillis 后整批再提醒一次 */
    public static void scheduleSnoozeGroup(Context context, long[] medicationIds,
                                           String time, long delayMillis) {
        AlarmManager alarmManager = alarmManager(context);
        if (alarmManager == null || medicationIds == null || medicationIds.length == 0) {
            return;
        }
        long triggerAt = System.currentTimeMillis() + delayMillis;
        PendingIntent operation = buildGroupPendingIntent(
                context, medicationIds, time, SNOOZE_SLOT);
        setDailyAlarm(alarmManager, triggerAt, operation, context);
    }

    /** 用户已服用后，取消尚未触发的整批贪睡提醒 */
    public static void cancelGroupSnooze(Context context) {
        AlarmManager alarmManager = alarmManager(context);
        if (alarmManager == null) {
            return;
        }
        PendingIntent existing = PendingIntent.getBroadcast(
                context, requestCode(0, SNOOZE_SLOT),
                new Intent(context, ReminderReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (existing != null) {
            alarmManager.cancel(existing);
            existing.cancel();
        }
    }

    /**
     * 漏服重提醒：delayMillis 后再次提醒，直到用户打卡已服用为止。
     * 与每日循环闹钟使用不同 requestCode，互不干扰。
     */
    public static void scheduleReReminder(Context context, long medicationId, long delayMillis) {
        AlarmManager alarmManager = alarmManager(context);
        if (alarmManager == null) {
            return;
        }
        long triggerAt = System.currentTimeMillis() + delayMillis;
        PendingIntent operation = buildPendingIntent(
                context, medicationId, "", RE_REMIND_SLOT, false, true);
        setDailyAlarm(alarmManager, triggerAt, operation, context);
    }

    /** 用户已服用后，取消尚未触发的漏服重提醒 */
    public static void cancelReReminder(Context context, long medicationId) {
        AlarmManager alarmManager = alarmManager(context);
        if (alarmManager == null) {
            return;
        }
        cancelOne(alarmManager, context, medicationId, RE_REMIND_SLOT, false);
    }

    /** 根据提醒方式与权限选择最合适的调度 API，保证任何权限状态下都不崩溃 */
    private static void setDailyAlarm(AlarmManager alarmManager, long triggerAt,
                                      PendingIntent operation, Context context) {
        SettingsManager settings = new SettingsManager(context);
        boolean exactAllowed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && alarmManager.canScheduleExactAlarms();
        try {
            if (exactAllowed && settings.getReminderMode() == SettingsManager.MODE_NOTIFICATION) {
                alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, operation);
                Log.d(TAG, "setExactAndAllowWhileIdle triggerAt=" + triggerAt);
            } else if (exactAllowed) {
                // 闹钟方式：setAlarmClock 系统展示更醒目
                alarmManager.setAlarmClock(
                        new AlarmManager.AlarmClockInfo(triggerAt, null), operation);
                Log.d(TAG, "setAlarmClock triggerAt=" + triggerAt);
            } else {
                // Android 14 起 setAlarmClock 同样需要精确闹钟权限，
                // 无权限时回退到免权限的 setWindow（10 分钟窗口内触发，Doze 下仍可用）
                alarmManager.setWindow(
                        AlarmManager.RTC_WAKEUP, triggerAt, 10 * 60_000L, operation);
                Log.w(TAG, "无精确闹钟权限，使用 setWindow 回退 triggerAt=" + triggerAt);
            }
        } catch (SecurityException e) {
            // 权限可能在调度间隙被用户撤销，兜底保证不崩溃
            Log.e(TAG, "调度闹钟权限异常，使用 setWindow 兜底", e);
            alarmManager.setWindow(
                    AlarmManager.RTC_WAKEUP, triggerAt, 15 * 60_000L, operation);
        }
    }

    /** 取消一条药品的全部闹钟（含贪睡） */
    public static void cancelForMedication(Context context, long medicationId) {
        AlarmManager alarmManager = alarmManager(context);
        if (alarmManager == null) {
            return;
        }
        for (int i = 0; i < MAX_TIMES_PER_DAY; i++) {
            cancelOne(alarmManager, context, medicationId, i, false);
        }
        cancelOne(alarmManager, context, medicationId, SNOOZE_SLOT, true);
        cancelOne(alarmManager, context, medicationId, RE_REMIND_SLOT, false);
    }

    private static void cancelOne(AlarmManager alarmManager, Context context,
                                  long medicationId, int slot, boolean snooze) {
        PendingIntent existing = PendingIntent.getBroadcast(
                context, requestCode(medicationId, slot),
                new Intent(context, ReminderReceiver.class),
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (existing != null) {
            alarmManager.cancel(existing);
            existing.cancel();
        }
    }

    /** 开机 / 更新 / 设置变更后重新注册所有开启中的药品提醒 */
    public static void rescheduleAll(Context context) {
        ensureChannels(context);
        MedicationDatabase database = MedicationDatabase.getInstance(context);
        List<Medication> all = database.getAllMedications();
        int scheduled = 0;
        for (Medication medication : all) {
            if (medication.isEnabled()) {
                scheduleForMedication(context, medication);
                scheduled++;
            }
        }
        Log.d(TAG, "rescheduleAll: 共 " + all.size()
                + " 条药品，其中 " + scheduled + " 条已开启提醒");
        AlarmManager manager = alarmManager(context);
        boolean exactAllowed = manager != null
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && manager.canScheduleExactAlarms();
        Log.i(TAG, "rescheduleAll 完成，精确闹钟权限=" + exactAllowed
                + " 提醒方式=" + new SettingsManager(context).getReminderMode());
    }

    /** 同一时刻（HH:mm）需要服用的全部启用药品，用于合并提醒 */
    public static List<Medication> medicationsAt(Context context, String time) {
        List<Medication> result = new ArrayList<>();
        if (time == null) {
            return result;
        }
        for (Medication medication : MedicationDatabase.getInstance(context).getAllMedications()) {
            if (medication.isEnabled() && medication.getTimes().contains(time)) {
                result.add(medication);
            }
        }
        return result;
    }

    /** 某时间点在药品提醒时间列表中的序号（用于重排下一天） */
    public static int indexOfTime(Medication medication, String time) {
        int index = medication.getTimes().indexOf(time);
        return index < 0 ? 0 : index;
    }

    /** 消息通知形式（单条药品）：状态栏横幅通知 */
    public static void showReminderNotification(Context context, Medication medication) {
        showReminderNotification(context, Collections.singletonList(medication),
                (int) medication.getId());
    }

    /** 消息通知形式（同一时刻的全部药品）：状态栏横幅通知 */
    public static void showReminderNotification(Context context,
                                                List<Medication> medications, int notificationId) {
        if (medications == null || medications.isEmpty()) {
            return;
        }
        ensureChannels(context);

        Intent openApp = new Intent(context, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                context, notificationId, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = buildReminderText(medications);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_NOTIFICATION)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle("用药提醒：" + reminderTitle(medications))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(contentIntent);

        notifySafely(context, notificationId, builder);
    }

    /** 闹钟形式（单条药品）：全屏响铃页通知 */
    public static NotificationCompat.Builder buildAlarmNotification(
            Context context, Medication medication) {
        return buildAlarmNotification(context, Collections.singletonList(medication),
                (int) medication.getId(), "");
    }

    /** 闹钟形式（同一时刻的全部药品）：全屏响铃页通知（由 AlarmService 启动为前台通知） */
    public static NotificationCompat.Builder buildAlarmNotification(
            Context context, List<Medication> medications, int notificationId, String time) {
        Intent fullScreenIntent = new Intent(context, AlarmAlertActivity.class);
        fullScreenIntent.putExtra(AlarmAlertActivity.EXTRA_MED_IDS, idsOf(medications));
        fullScreenIntent.putExtra(AlarmService.EXTRA_TIME, time == null ? "" : time);
        fullScreenIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent fullScreenPendingIntent = PendingIntent.getActivity(
                context, notificationId, fullScreenIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String text = buildReminderText(medications);
        long[] ids = idsOf(medications);
        return new NotificationCompat.Builder(context, CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_stat_reminder)
                .setContentTitle("用药闹钟：" + reminderTitle(medications))
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setOngoing(true)
                .setAutoCancel(false)
                // 锁屏上也完整显示内容与操作按钮
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setFullScreenIntent(fullScreenPendingIntent, true)
                .setContentIntent(fullScreenPendingIntent)
                // 亮屏未自动弹出全屏页时，可直接在横幅/通知上处理
                .addAction(R.drawable.ic_stat_reminder, "已服用",
                        buildAlarmActionPendingIntent(context, ids, time,
                                notificationId, 0, ALARM_ACTION_TAKEN))
                .addAction(R.drawable.ic_stat_reminder, "5分钟后再提醒",
                        buildAlarmActionPendingIntent(context, ids, time,
                                notificationId, 1, ALARM_ACTION_SNOOZE))
                .addAction(R.drawable.ic_stat_reminder, "停止响铃",
                        buildAlarmActionPendingIntent(context, ids, time,
                                notificationId, 2, ALARM_ACTION_DISMISS));
    }

    /** 闹钟通知快捷操作的广播 PendingIntent，三个操作使用不同 requestCode 防止互相覆盖 */
    private static PendingIntent buildAlarmActionPendingIntent(
            Context context, long[] ids, String time,
            int notificationId, int actionIndex, String action) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(EXTRA_MED_IDS, ids);
        intent.putExtra(EXTRA_TIME, time == null ? "" : time);
        intent.putExtra(EXTRA_ALARM_ACTION, action);
        int requestCode = 800_000_000
                + (int) Math.abs((notificationId * 7L + actionIndex * 13L) % 190_000_000L);
        return PendingIntent.getBroadcast(
                context, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public static void cancelAlarmNotification(Context context, long medicationId) {
        NotificationManagerCompat.from(context).cancel((int) medicationId);
    }

    public static void cancelAlarmNotification(Context context, int notificationId) {
        NotificationManagerCompat.from(context).cancel(notificationId);
    }

    /** 同一时刻多条药品合并为一个通知时使用的通知 ID */
    public static int groupNotificationId(String time) {
        String key = time == null ? "" : time;
        return 60_000 + Math.abs(key.hashCode() % 20_000);
    }

    /** 通知/语音统一文案（单条）：每次剂量 + 服用方法 */
    public static String buildReminderText(Medication m) {
        return detailOf(m);
    }

    /** 通知文案：单条仅列剂量/方法；多条编号列出各药名称、剂量、方法 */
    public static String buildReminderText(List<Medication> medications) {
        if (medications == null || medications.isEmpty()) {
            return "请按时服药";
        }
        if (medications.size() == 1) {
            return detailOf(medications.get(0));
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < medications.size(); i++) {
            if (i > 0) {
                text.append("\n");
            }
            text.append(i + 1).append(". ").append(medications.get(i).getName())
                    .append("：").append(detailOf(medications.get(i)));
        }
        return text.toString();
    }

    private static String detailOf(Medication m) {
        StringBuilder text = new StringBuilder();
        if (m.getDosage() != null && !m.getDosage().isEmpty()) {
            text.append("每次").append(m.getDosage());
        }
        if (m.getNote() != null && !m.getNote().isEmpty()) {
            if (text.length() > 0) {
                text.append("，");
            }
            text.append(m.getNote());
        }
        if (text.length() == 0) {
            text.append("请按时服药");
        }
        return text.toString();
    }

    /** 语音播报文案（单条）：完整包含药品名称、剂量、服用方法 */
    public static String buildSpeechText(Medication m) {
        return "用药提醒，该服用" + m.getName() + "了"
                + speechDetailOf(m);
    }

    /** 语音播报文案（同一时刻多条）：连续播报每一种药的名称、剂量、服用方法 */
    public static String buildSpeechText(List<Medication> medications) {
        if (medications == null || medications.isEmpty()) {
            return "";
        }
        if (medications.size() == 1) {
            return buildSpeechText(medications.get(0));
        }
        StringBuilder speech = new StringBuilder("用药提醒，到服药时间了，共需要服用")
                .append(medications.size()).append("种药物。");
        for (int i = 0; i < medications.size(); i++) {
            Medication m = medications.get(i);
            speech.append("第").append(i + 1).append("种，").append(m.getName())
                    .append(speechDetailOf(m)).append("。");
        }
        return speech.toString();
    }

    private static String speechDetailOf(Medication m) {
        StringBuilder speech = new StringBuilder();
        if (m.getDosage() != null && !m.getDosage().isEmpty()) {
            speech.append("，每次").append(m.getDosage());
        }
        if (m.getNote() != null && !m.getNote().isEmpty()) {
            speech.append("，").append(m.getNote());
        }
        return speech.toString();
    }

    private static String reminderTitle(List<Medication> medications) {
        if (medications.size() == 1) {
            return "该服用 " + medications.get(0).getName() + " 了";
        }
        return "该服用 " + medications.size() + " 种药物了";
    }

    private static long[] idsOf(List<Medication> medications) {
        long[] ids = new long[medications.size()];
        for (int i = 0; i < medications.size(); i++) {
            ids[i] = medications.get(i).getId();
        }
        return ids;
    }

    private static void notifySafely(Context context, int id,
                                     NotificationCompat.Builder builder) {
        try {
            NotificationManagerCompat.from(context).notify(id, builder.build());
        } catch (SecurityException e) {
            // 未授予 POST_NOTIFICATIONS 时静默失败，闹钟方式仍可由全屏页/语音提醒
        }
    }

    /** 按当前震动设置（重新）创建两个通知渠道 */
    public static void ensureChannels(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        boolean vibrate = new SettingsManager(context).isVibrateEnabled();
        long[] pattern = {0, 600, 400, 600};

        NotificationChannel messageChannel = new NotificationChannel(
                CHANNEL_NOTIFICATION, "用药提醒通知", NotificationManager.IMPORTANCE_HIGH);
        messageChannel.setDescription("每日服药的消息通知");
        // 请求绕过系统静音/免打扰：需用户在设置页授予“勿扰访问”权限后才生效
        messageChannel.setBypassDnd(true);
        messageChannel.enableVibration(vibrate);
        if (vibrate) {
            messageChannel.setVibrationPattern(pattern);
        }
        Uri messageSound = resolveSoundUri(context, RingtoneManager.TYPE_NOTIFICATION);
        messageChannel.setSound(messageSound,
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        manager.createNotificationChannel(messageChannel);

        Uri alarmSound = resolveAlarmSoundUri(context);
        Log.i(TAG, "通知渠道已创建，通知音=" + messageSound + " 闹钟音=" + alarmSound);
        if (messageSound == null) {
            Log.w(TAG, "系统没有可用通知铃声，消息通知将静音；建议改用闹钟提醒方式");
        }
        NotificationChannel alarmChannel = new NotificationChannel(
                CHANNEL_ALARM, "用药闹钟", NotificationManager.IMPORTANCE_HIGH);
        alarmChannel.setDescription("全屏响铃式的服药闹钟");
        alarmChannel.setBypassDnd(true);
        alarmChannel.enableVibration(vibrate);
        if (vibrate) {
            alarmChannel.setVibrationPattern(pattern);
        }
        alarmChannel.setSound(alarmSound,
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build());
        manager.createNotificationChannel(alarmChannel);
    }

    /**
     * 震动等渠道设置变更后必须删除重建才能生效
     * （已创建渠道的震动/铃声设置无法直接修改）。
     */
    public static void refreshChannels(Context context) {
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.deleteNotificationChannel(CHANNEL_NOTIFICATION);
        manager.deleteNotificationChannel(CHANNEL_ALARM);
        ensureChannels(context);
    }

    private static AlarmManager alarmManager(Context context) {
        return (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    }

    /**
     * 逐级回退解析系统默认铃声，尽量保证返回非 null。
     * 铃声为 null 会导致渠道与播放器静音，是"到点无声"的常见根因。
     */
    private static Uri resolveSoundUri(Context context, int type) {
        Uri uri = RingtoneManager.getDefaultUri(type);
        if (uri == null) {
            uri = RingtoneManager.getActualDefaultRingtoneUri(context, type);
        }
        if (uri == null && type == RingtoneManager.TYPE_ALARM) {
            uri = Settings.System.DEFAULT_ALARM_ALERT_URI;
        }
        if (uri == null && type == RingtoneManager.TYPE_RINGTONE) {
            uri = Settings.System.DEFAULT_RINGTONE_URI;
        }
        if (uri == null && type == RingtoneManager.TYPE_NOTIFICATION) {
            uri = Settings.System.DEFAULT_NOTIFICATION_URI;
        }
        return uri;
    }

    /**
     * 闹钟铃声：优先用户选择的铃声（内置旋律），否则按
     * TYPE_ALARM → 来电铃声 → 通知铃声逐级回退，供渠道与 AlarmService 共用。
     */
    public static Uri resolveAlarmSoundUri(Context context) {
        String ringer = new SettingsManager(context).getRinger();
        if (!SettingsManager.RINGER_SYSTEM.equals(ringer)) {
            Uri builtin = builtinToneUri(context, ringer);
            if (builtin != null) {
                return builtin;
            }
        }
        Uri uri = resolveSoundUri(context, RingtoneManager.TYPE_ALARM);
        if (uri == null) {
            uri = resolveSoundUri(context, RingtoneManager.TYPE_RINGTONE);
        }
        if (uri == null) {
            uri = resolveSoundUri(context, RingtoneManager.TYPE_NOTIFICATION);
        }
        return uri;
    }

    /** 内置旋律名 -> android.resource:// 资源 URI，非内置返回 null */
    private static Uri builtinToneUri(Context context, String ringer) {
        int resId;
        if (SettingsManager.RINGER_TONE_1.equals(ringer)) {
            resId = R.raw.alarm_tone_1;
        } else if (SettingsManager.RINGER_TONE_2.equals(ringer)) {
            resId = R.raw.alarm_tone_2;
        } else if (SettingsManager.RINGER_TONE_3.equals(ringer)) {
            resId = R.raw.alarm_tone_3;
        } else {
            return null;
        }
        return Uri.parse("android.resource://" + context.getPackageName() + "/" + resId);
    }

    private static PendingIntent buildPendingIntent(Context context, long medicationId,
                                                    String time, int slot,
                                                    boolean snooze, boolean reRemind) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(EXTRA_MED_ID, medicationId);
        intent.putExtra(EXTRA_TIME, time);
        intent.putExtra(EXTRA_INDEX, slot);
        intent.putExtra(EXTRA_SNOOZE, snooze);
        intent.putExtra(EXTRA_RE_REMIND, reRemind);
        return PendingIntent.getBroadcast(
                context, requestCode(medicationId, slot), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 一批药品共用的贪睡提醒（requestCode 以 0 号伪 id 区分于单条药品） */
    private static PendingIntent buildGroupPendingIntent(Context context, long[] medicationIds,
                                                         String time, int slot) {
        Intent intent = new Intent(context, ReminderReceiver.class);
        intent.putExtra(EXTRA_MED_IDS, medicationIds);
        intent.putExtra(EXTRA_TIME, time);
        intent.putExtra(EXTRA_INDEX, slot);
        intent.putExtra(EXTRA_SNOOZE, true);
        intent.putExtra(EXTRA_RE_REMIND, false);
        return PendingIntent.getBroadcast(
                context, requestCode(0, slot), intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static int requestCode(long medicationId, int slot) {
        return (int) (medicationId * 100L + slot);
    }
}
