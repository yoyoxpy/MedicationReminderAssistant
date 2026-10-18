package com.example.medicationreminderassistant.service;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.example.medicationreminderassistant.util.SettingsManager;

import java.util.Locale;

/**
 * 系统 TTS 的单例封装。提醒触发时语音播报提醒内容；
 * 在引擎初始化完成前到达的播报请求会缓存，初始化成功后补播。
 *
 * 直接使用系统默认中文语音引擎与默认音色（跟随系统“文字转语音”设置），
 * 不再自选音色，避免选中未下载的联网音色导致播报静默失败。
 */
public class TTSService {

    private static final String TAG = "TTSService";
    private static TTSService instance;

    private final Context appContext;
    private TextToSpeech tts;
    private boolean ready;

    private String pendingText;

    private TTSService(Context context) {
        appContext = context.getApplicationContext();
        tts = new TextToSpeech(appContext, status -> {
            if (status != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS 引擎初始化失败: " + status);
                return;
            }
            // 优先简体中文，不支持时回退系统语言，尽量保证有声音
            int result = tts.setLanguage(Locale.SIMPLIFIED_CHINESE);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                result = tts.setLanguage(Locale.getDefault());
            }
            ready = result != TextToSpeech.LANG_MISSING_DATA
                    && result != TextToSpeech.LANG_NOT_SUPPORTED;
            Log.i(TAG, "TTS 初始化完成 ready=" + ready + " lang=" + result
                    + " engine=" + tts.getDefaultEngine());
            if (ready && pendingText != null) {
                String text = pendingText;
                pendingText = null;
                speakInternal(text);
            } else if (!ready) {
                Log.w(TAG, "当前 TTS 引擎缺少中文语音数据，请在系统“文字转语音”设置中检查默认引擎");
            }
        });
    }

    public static synchronized TTSService get(Context context) {
        if (instance == null) {
            instance = new TTSService(context.getApplicationContext());
        }
        return instance;
    }

    /** 打断当前播报并立即以系统默认音色播放 */
    public void speak(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (ready) {
            speakInternal(text);
        } else {
            // 引擎还没初始化好，缓存最近一条，初始化完成后补播
            pendingText = text;
        }
    }

    /** 兼容原有调用：是否播报由调用方判断，此处直接使用系统默认音色 */
    public void speakWithSettings(String text, SettingsManager settings) {
        speak(text);
    }

    private void speakInternal(String text) {
        try {
            int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                    "medication_" + System.currentTimeMillis());
            if (result != TextToSpeech.SUCCESS) {
                Log.w(TAG, "TTS speak 返回异常: " + result);
            }
        } catch (Exception e) {
            Log.w(TAG, "TTS 播报异常", e);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public static synchronized void shutdown() {
        if (instance != null) {
            instance.stopAndShutdown();
            instance = null;
        }
    }

    private void stopAndShutdown() {
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception e) {
            Log.w(TAG, "释放 TTS 异常", e);
        }
    }
}
