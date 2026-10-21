package com.example.medicationreminderassistant.service;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.ToneGenerator;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import com.example.medicationreminderassistant.AlarmAlertActivity;
import com.example.medicationreminderassistant.model.Medication;
import com.example.medicationreminderassistant.util.SettingsManager;

import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 闹钟形式提醒的前台服务：持续响铃 + 震动 + 语音播报。
 * - 同一时刻需要服用的多种药物会合并到同一个响铃与播报中；
 * - 响铃持续到用户点击"已服用/稍后提醒/停止响铃"，
 *   或达到用户设置的"响铃持续时间"后自动停止（漏服重提醒已由接收器预先排定，会自动生效）。
 */
public class AlarmService extends Service {

    public static final String EXTRA_MED_ID = "alarm_med_id";
    public static final String EXTRA_MED_IDS = "alarm_med_ids";
    public static final String EXTRA_NOTIF_ID = "alarm_notif_id";
    public static final String EXTRA_TIME = "alarm_time";
    /** 设置页“测试提醒”使用：只响 10 秒，用于验证铃声链路 */
    public static final String EXTRA_TEST = "alarm_test";
    private static final long TEST_DURATION_MS = 10_000L;

    private static final String TAG = "AlarmService";
    private static final long[] VIBRATION_PATTERN = {0, 800, 600, 800};

    private final Handler handler = new Handler(Looper.getMainLooper());

    private MediaPlayer mediaPlayer;
    private ToneGenerator toneGenerator;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private NotificationManager notificationManager;
    private int savedInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    private int notificationId = -1;

    /** 当前正在响铃的一批药品（同进程内静态可见），供界面层检测并跳转全屏响铃页 */
    private static volatile long[] activeIds;
    private static volatile String activeTime = "";

    /** 是否有一批闹钟正在响铃 */
    public static boolean hasActiveAlarm() {
        return activeIds != null && activeIds.length > 0;
    }

    public static long[] getActiveIds() {
        return activeIds;
    }

    public static String getActiveTime() {
        return activeTime == null ? "" : activeTime;
    }

    /** 同一时刻的一条或多条药品提醒 */
    public static void start(Context context, long[] medicationIds,
                             int notificationId, String time) {
        Intent intent = new Intent(context, AlarmService.class);
        intent.putExtra(EXTRA_MED_IDS, medicationIds);
        intent.putExtra(EXTRA_NOTIF_ID, notificationId);
        intent.putExtra(EXTRA_TIME, time == null ? "" : time);
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AlarmService.class));
    }

    /** 立即响一次（10 秒）用于验证铃声链路，不依赖定时触发 */
    public static void startTest(Context context, long medicationId) {
        Intent intent = new Intent(context, AlarmService.class);
        intent.putExtra(EXTRA_MED_IDS, new long[]{medicationId});
        intent.putExtra(EXTRA_NOTIF_ID, (int) medicationId);
        intent.putExtra(EXTRA_TIME, "");
        intent.putExtra(EXTRA_TEST, true);
        ContextCompat.startForegroundService(context, intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "Medication:alarm");
            // 最长响铃时长（15 分钟）加缓冲，保证响铃期间 CPU 不睡
            wakeLock.acquire(20 * 60 * 1000L);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        long[] ids = intent != null
                ? intent.getLongArrayExtra(EXTRA_MED_IDS) : null;
        if (ids == null || ids.length == 0) {
            stopSelf();
            return START_NOT_STICKY;
        }
        notificationId = intent.getIntExtra(EXTRA_NOTIF_ID, (int) ids[0]);
        String time = intent.getStringExtra(EXTRA_TIME);
        boolean testMode = intent.getBooleanExtra(EXTRA_TEST, false);

        List<Medication> medications = loadMedications(ids);
        if (medications.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }

        SettingsManager settings = new SettingsManager(this);

        // 1. 确保闹钟通知渠道存在（含闹钟铃声），否则通知会落到默认渠道而无声
        NotificationService.ensureChannels(this);

        // 2. 成为带全屏通知的前台服务（失败也继续响铃，尽力保证提醒送达）
        try {
            ServiceCompat.startForeground(this, notificationId,
                    NotificationService.buildAlarmNotification(
                            this, medications, notificationId, time).build(),
                    Build.VERSION.SDK_INT >= 34
                            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0);
        } catch (Exception e) {
            // 含 SecurityException 与后台启动限制异常，记录后继续响铃
            Log.e(TAG, "启动前台闹钟服务失败，仍继续响铃", e);
        }

        // 3. 免打扰/静音下也要响：已授勿扰权限时，响铃期间临时放开中断过滤，停铃后恢复
        try {
            overrideDndIfNeeded();
        } catch (Exception e) {
            Log.w(TAG, "免打扰状态处理异常，不影响响铃", e);
        }

        // 4. 响铃（闹钟音量通道、循环播放）
        try {
            playAlarmSound();
        } catch (Exception e) {
            Log.e(TAG, "响铃启动异常，尝试备用提示音", e);
            try {
                startFallbackTone();
            } catch (Exception ignored) {
            }
        }

        // 5. 按设置震动
        try {
            if (settings.isVibrateEnabled()) {
                startVibration();
            }
        } catch (Exception e) {
            Log.w(TAG, "震动启动异常", e);
        }

        // 6. 按设置语音播报（连续播报全部药品的名称、剂量、服用方法）
        try {
            if (settings.isTtsEnabled()) {
                TTSService.get(this).speakWithSettings(
                        NotificationService.buildSpeechText(medications), settings);
            }
        } catch (Exception e) {
            Log.w(TAG, "语音播报启动异常", e);
        }

        // 7. 显式拉起全屏响铃页：不依赖系统对 FullScreenIntent 的策略
        // （精确闹钟触发后通常仍允许短暂后台启动；失败时通知栏点按仍可打开）
        activeIds = ids;
        activeTime = time == null ? "" : time;
        launchAlertActivity(ids, time);

        // 8. 到达"响铃持续时间"仍无人操作时自动停铃
        // 先清掉上一批提醒遗留的定时器，避免新提醒被旧定时器提前停掉
        handler.removeCallbacksAndMessages(null);
        long durationMs = testMode
                ? TEST_DURATION_MS
                : settings.getRingDurationMinutes() * 60_000L;
        Log.i(TAG, "响铃开始 test=" + testMode + " durationMs=" + durationMs
                + " tts=" + settings.isTtsEnabled() + " vibrate=" + settings.isVibrateEnabled());
        handler.postDelayed(this::stopSelf, durationMs);

        return START_STICKY;
    }

    /**
     * 已授予勿扰访问权限且当前处于免打扰时，临时切到"全部允许"，
     * 让铃声/震动不被系统或厂商 ROM 的免打扰策略拦截，停铃时恢复原状态。
     */
    private void overrideDndIfNeeded() {
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null || !notificationManager.isNotificationPolicyAccessGranted()) {
            return;
        }
        try {
            int current = notificationManager.getCurrentInterruptionFilter();
            if (current != NotificationManager.INTERRUPTION_FILTER_ALL) {
                savedInterruptionFilter = current;
                notificationManager.setInterruptionFilter(
                        NotificationManager.INTERRUPTION_FILTER_ALL);
            }
        } catch (Exception e) {
            Log.w(TAG, "临时放开免打扰失败，保持系统原策略", e);
        }
    }

    private void restoreDnd() {
        if (notificationManager == null || !notificationManager.isNotificationPolicyAccessGranted()) {
            return;
        }
        try {
            if (savedInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                notificationManager.setInterruptionFilter(savedInterruptionFilter);
            }
        } catch (Exception e) {
            Log.w(TAG, "恢复免打扰状态失败", e);
        } finally {
            savedInterruptionFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
        }
    }

    private void launchAlertActivity(long[] ids, String time) {
        try {
            AlarmAlertActivity.show(this, ids, time);
        } catch (Exception e) {
            // 个别 ROM 严格限制后台启动；此时仍可从通知栏的全屏通知点按进入
            Log.w(TAG, "直接拉起全屏响铃页被系统拦截，可点通知打开", e);
        }
    }

    private List<Medication> loadMedications(long[] ids) {
        List<Medication> result = new ArrayList<>();
        MedicationDatabase database = MedicationDatabase.getInstance(this);
        for (long id : ids) {
            Medication medication = database.getMedication(id);
            if (medication != null && medication.isEnabled()) {
                result.add(medication);
            }
        }
        return result;
    }

    private void playAlarmSound() {
        // 闹钟音量为 0 是"权限都正常但完全不响"的最常见原因，先纠正再播放
        ensureAudibleAlarmVolume();

        Uri alarmUri = NotificationService.resolveAlarmSoundUri(this);
        Log.i(TAG, "准备播放闹钟铃声 uri=" + alarmUri);
        if (alarmUri == null) {
            // 设备没有可用铃声时直接用合成音，保证一定有声音
            startFallbackTone();
            return;
        }
        try {
            releaseMediaPlayer();
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setDataSource(this, alarmUri);
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mediaPlayer.setLooping(true);
            mediaPlayer.setOnPreparedListener(mp -> {
                try {
                    mp.start();
                    Log.i(TAG, "铃声已开始播放 playing=" + mp.isPlaying());
                } catch (Exception e) {
                    Log.e(TAG, "铃声启动失败，改用合成提示音", e);
                    releaseMediaPlayer();
                    startFallbackTone();
                }
            });
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "铃声播放错误 what=" + what + " extra=" + extra);
                releaseMediaPlayer();
                startFallbackTone();
                return true;
            });
            // 异步准备避免在主线程阻塞导致 ANR
            mediaPlayer.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "设置铃声失败，改用合成提示音", e);
            releaseMediaPlayer();
            startFallbackTone();
        }
    }

    /** 检查闹钟音量；为 0 时临时提升，避免"设置了铃声却完全没声" */
    private void ensureAudibleAlarmVolume() {
        try {
            AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (audioManager == null) {
                return;
            }
            int current = audioManager.getStreamVolume(AudioManager.STREAM_ALARM);
            int max = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            Log.i(TAG, "闹钟音量=" + current + "/" + max);
            if (current == 0 && max > 0) {
                int target = Math.max(1, Math.round(max * 0.7f));
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0);
                Log.w(TAG, "闹钟音量为 0，已临时提升到 " + target + "/" + max);
            }
        } catch (Exception e) {
            Log.w(TAG, "检查或提升闹钟音量失败", e);
        }
    }

    private void startFallbackTone() {
        try {
            toneGenerator = new ToneGenerator(AudioManager.STREAM_ALARM, ToneGenerator.MAX_VOLUME);
            // 时长为 -1 表示持续鸣响，直到 stopTone() 被调用
            toneGenerator.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, -1);
        } catch (Exception e) {
            Log.e(TAG, "备用提示音播放失败", e);
            toneGenerator = null;
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
                // 释放失败无碍后续兜底播放
            }
            mediaPlayer = null;
        }
    }

    private void startVibration() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager)
                    getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) {
                vibrator = manager.getDefaultVibrator();
            }
        } else {
            vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        }
        if (vibrator != null && vibrator.hasVibrator()) {
            vibrator.vibrate(VibrationEffect.createWaveform(VIBRATION_PATTERN, 0));
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        activeIds = null;
        activeTime = "";
        // 恢复用户原来的免打扰状态
        restoreDnd();
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.stop();
                }
                mediaPlayer.release();
            } catch (Exception e) {
                Log.w(TAG, "释放铃声播放器异常", e);
            }
            mediaPlayer = null;
        }
        if (toneGenerator != null) {
            try {
                toneGenerator.stopTone();
                toneGenerator.release();
            } catch (Exception e) {
                Log.w(TAG, "释放提示音异常", e);
            }
            toneGenerator = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
            vibrator = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
        }
        if (notificationId >= 0) {
            NotificationService.cancelAlarmNotification(this, notificationId);
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
