package com.example.medicationreminderassistant.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 提醒相关设置（SharedPreferences 持久化）：
 * - 提醒方式：消息通知 / 闹钟（全屏响铃）
 * - 是否震动
 * - 是否语音播报（药品名称 + 剂量 + 服用方法）
 * - 漏服（未打卡）时是否延迟重提醒、重提醒间隔分钟数
 * - 闹钟铃声来源（跟随系统 / 内置旋律）与响铃持续时间
 */
public class SettingsManager {

    public static final int MODE_NOTIFICATION = 0;
    public static final int MODE_ALARM = 1;

    /** 可选的漏服重提醒间隔（分钟） */
    public static final int[] REMIND_AGAIN_MINUTES = {10, 15, 20, 30};

    /** 可选的响铃持续时间（分钟） */
    public static final int[] RING_DURATION_MINUTES = {1, 5, 10, 15};

    /** 闹钟铃声：跟随系统闹钟 */
    public static final String RINGER_SYSTEM = "system";
    /** 闹钟铃声：内置旋律（对应 res/raw/alarm_tone_1..3） */
    public static final String RINGER_TONE_1 = "tone_1";
    public static final String RINGER_TONE_2 = "tone_2";
    public static final String RINGER_TONE_3 = "tone_3";
    public static final String[] RINGER_TONES = {RINGER_TONE_1, RINGER_TONE_2, RINGER_TONE_3};

    private static final String PREFS_NAME = "reminder_settings";
    private static final String KEY_MODE = "reminder_mode";
    private static final String KEY_VIBRATE = "vibrate";
    private static final String KEY_TTS = "tts_enabled";
    private static final String KEY_REMIND_AGAIN = "remind_again";
    private static final String KEY_REMIND_AGAIN_MINUTES = "remind_again_minutes";
    private static final String KEY_RINGER = "alarm_ringer";
    private static final String KEY_RING_DURATION_MINUTES = "ring_duration_minutes";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 消息通知(0) 或 闹钟(1)，默认消息通知 */
    public int getReminderMode() {
        return prefs.getInt(KEY_MODE, MODE_NOTIFICATION);
    }

    public void setReminderMode(int mode) {
        prefs.edit().putInt(KEY_MODE, mode).apply();
    }

    public boolean isVibrateEnabled() {
        return prefs.getBoolean(KEY_VIBRATE, true);
    }

    public void setVibrateEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_VIBRATE, enabled).apply();
    }

    public boolean isTtsEnabled() {
        return prefs.getBoolean(KEY_TTS, true);
    }

    public void setTtsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TTS, enabled).apply();
    }

    /** 未打卡时是否延迟重复提醒，默认开启 */
    public boolean isRemindAgainEnabled() {
        return prefs.getBoolean(KEY_REMIND_AGAIN, true);
    }

    public void setRemindAgainEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_REMIND_AGAIN, enabled).apply();
    }

    /** 漏服重提醒间隔分钟数，默认 15 分钟 */
    public int getRemindAgainMinutes() {
        return prefs.getInt(KEY_REMIND_AGAIN_MINUTES, 15);
    }

    public void setRemindAgainMinutes(int minutes) {
        prefs.edit().putInt(KEY_REMIND_AGAIN_MINUTES, minutes).apply();
    }

    /** 闹钟铃声来源，默认跟随系统闹钟 */
    public String getRinger() {
        String value = prefs.getString(KEY_RINGER, RINGER_SYSTEM);
        return value != null ? value : RINGER_SYSTEM;
    }

    public void setRinger(String ringer) {
        prefs.edit().putString(KEY_RINGER, ringer).apply();
    }

    /** 响铃持续时间（分钟），默认 5 分钟 */
    public int getRingDurationMinutes() {
        return prefs.getInt(KEY_RING_DURATION_MINUTES, 5);
    }

    public void setRingDurationMinutes(int minutes) {
        prefs.edit().putInt(KEY_RING_DURATION_MINUTES, minutes).apply();
    }
}
