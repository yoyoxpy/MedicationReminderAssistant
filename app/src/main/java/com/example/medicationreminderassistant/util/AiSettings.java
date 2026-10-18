package com.example.medicationreminderassistant.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * AI 助手连接配置。
 * 默认接入智谱开放平台的免费模型 GLM-4.7-Flash（bigmodel.cn 注册即送 Key）；
 * 如需切换 DeepSeek：BASE_URL 改为 https://api.deepseek.com，MODEL 改为 deepseek-flash，
 * 请求格式完全兼容，无需改动其他代码。
 */
public class AiSettings {

    public static final String DEFAULT_BASE_URL = "https://open.bigmodel.cn/api/paas/v4";
    public static final String DEFAULT_MODEL = "glm-4.7-flash";

    private static final String PREFS_NAME = "ai_settings";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_MODEL = "model";

    private final SharedPreferences prefs;

    public AiSettings(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getApiKey() {
        String key = prefs.getString(KEY_API_KEY, "");
        return key != null ? key.trim() : "";
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key == null ? "" : key.trim()).apply();
    }

    public String getBaseUrl() {
        String url = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
        return url != null && !url.isEmpty() ? url : DEFAULT_BASE_URL;
    }

    public void setBaseUrl(String url) {
        prefs.edit().putString(KEY_BASE_URL, url == null ? DEFAULT_BASE_URL : url.trim()).apply();
    }

    public String getModel() {
        String model = prefs.getString(KEY_MODEL, DEFAULT_MODEL);
        return model != null && !model.isEmpty() ? model : DEFAULT_MODEL;
    }

    public void setModel(String model) {
        prefs.edit().putString(KEY_MODEL, model == null ? DEFAULT_MODEL : model.trim()).apply();
    }
}
