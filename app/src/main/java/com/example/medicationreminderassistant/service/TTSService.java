package com.example.medicationreminderassistant.service;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.util.Log;

import com.example.medicationreminderassistant.util.SettingsManager;

import java.util.Locale;
import java.util.Set;

/**
 * 系统 TTS 的单例封装。提醒触发时语音播报提醒内容；
 * 在引擎初始化完成前到达的播报请求会缓存，初始化成功后补播。
 *
 * 支持按“女声/男声”选择系统中文音色，并结合用户设置的语速与音调，
 * 从而在不依赖联网音频资源的前提下区分播报音色。
 */
public class TTSService {

    private static final String TAG = "TTSService";
    private static TTSService instance;

    private final Context appContext;
    private TextToSpeech tts;
    private boolean ready;

    private String pendingText;
    private int pendingGender = SettingsManager.GENDER_FEMALE;
    private float pendingPitch = SettingsManager.DEFAULT_PITCH_FEMALE;
    private float pendingRate = 1.0f;

    private TTSService(Context context) {
        appContext = context.getApplicationContext();
        tts = new TextToSpeech(appContext, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int result = tts.setLanguage(Locale.CHINESE);
                ready = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED;
                if (ready && pendingText != null) {
                    String text = pendingText;
                    pendingText = null;
                    speakInternal(text, pendingGender, pendingPitch, pendingRate);
                } else if (!ready) {
                    Log.w(TAG, "系统未安装可用的中文语音引擎");
                }
            } else {
                Log.w(TAG, "TTS 引擎初始化失败: " + status);
            }
        });
    }

    public static synchronized TTSService get(Context context) {
        if (instance == null) {
            instance = new TTSService(context.getApplicationContext());
        }
        return instance;
    }

    /**
     * 打断当前播报并立即按指定音色/语速/音调播放。
     *
     * @param gender {@link SettingsManager#GENDER_FEMALE} 或 {@link SettingsManager#GENDER_MALE}
     */
    public void speak(String text, int gender, float pitch, float rate) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (ready) {
            speakInternal(text, gender, pitch, rate);
        } else {
            // 引擎还没初始化好，缓存最近一条，初始化完成后补播
            pendingText = text;
            pendingGender = gender;
            pendingPitch = pitch;
            pendingRate = rate;
        }
    }

    /** 按当前设置中的音色/语速/音调播报 */
    public void speakWithSettings(String text, SettingsManager settings) {
        speak(text, settings.getTtsGender(), settings.getTtsPitch(), settings.getTtsRate());
    }

    private void speakInternal(String text, int gender, float pitch, float rate) {
        applyVoice(gender);
        tts.setPitch(clamp(pitch));
        tts.setSpeechRate(clamp(rate));
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null,
                "medication_" + System.currentTimeMillis());
    }

    /**
     * 按性别挑选系统中文音色：优先匹配 features 中含 female/male 的音色，
     * 找不到时保持默认音色，由音调（pitch）体现音色差异。
     */
    private void applyVoice(int gender) {
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) {
                return;
            }
            boolean wantMale = gender == SettingsManager.GENDER_MALE;
            Voice best = null;
            for (Voice voice : voices) {
                Locale locale = voice.getLocale();
                if (locale == null || !"zh".equals(locale.getLanguage())) {
                    continue;
                }
                if (voiceMatchesGender(voice, wantMale)) {
                    best = voice;
                    break;
                }
            }
            if (best != null) {
                tts.setVoice(best);
            }
        } catch (Exception e) {
            Log.w(TAG, "选择语音音色失败", e);
        }
    }

    private boolean voiceMatchesGender(Voice voice, boolean wantMale) {
        String name = voice.getName() == null ? "" : voice.getName().toLowerCase(Locale.ROOT);
        StringBuilder featureText = new StringBuilder(name);
        Set<String> features = voice.getFeatures();
        if (features != null) {
            for (String feature : features) {
                featureText.append(' ').append(feature.toLowerCase(Locale.ROOT));
            }
        }
        String text = featureText.toString();
        if (wantMale) {
            // 注意 "female" 含 "male" 子串，须排除
            return text.contains("male") && !text.contains("female");
        }
        return text.contains("female");
    }

    private static float clamp(float value) {
        if (value < 0.5f) {
            return 0.5f;
        }
        if (value > 2.0f) {
            return 2.0f;
        }
        return value;
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
