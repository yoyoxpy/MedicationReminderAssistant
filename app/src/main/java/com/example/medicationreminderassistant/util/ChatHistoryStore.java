package com.example.medicationreminderassistant.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 聊天记录本地留存：以 JSON 数组存储在 SharedPreferences，
 * 每条消息带时间戳，仅保留最近 7 天，读取与写入时自动清理过期数据。
 */
public class ChatHistoryStore {

    private static final String PREFS_NAME = "ai_chat_history";
    private static final String KEY_MESSAGES = "messages";
    private static final long RETENTION_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_STORED = 200;

    public static class Entry {
        public final String role;
        public final String content;

        public Entry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    private final SharedPreferences prefs;

    public ChatHistoryStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** 读取最近 7 天的消息，并顺带清除过期数据 */
    public List<Entry> loadRecent() {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        JSONArray arr = loadArray();
        JSONArray kept = new JSONArray();
        List<Entry> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.optLong("ts", 0) < cutoff) {
                continue;
            }
            kept.put(o);
            out.add(new Entry(o.optString("role"), o.optString("content")));
        }
        if (kept.length() != arr.length()) {
            save(kept);
        }
        return out;
    }

    /** 追加一条消息（自动清理过期消息并限制总量） */
    public void append(String role, String content) {
        long cutoff = System.currentTimeMillis() - RETENTION_MS;
        JSONArray arr = loadArray();
        JSONArray kept = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && o.optLong("ts", 0) >= cutoff) {
                kept.put(o);
            }
        }
        try {
            JSONObject o = new JSONObject();
            o.put("role", role);
            o.put("content", content);
            o.put("ts", System.currentTimeMillis());
            kept.put(o);
        } catch (Exception ignored) {
            return;
        }
        // 超出上限时丢弃最旧的消息
        int start = Math.max(0, kept.length() - MAX_STORED);
        JSONArray trimmed = new JSONArray();
        for (int i = start; i < kept.length(); i++) {
            trimmed.put(kept.opt(i));
        }
        save(trimmed);
    }

    private JSONArray loadArray() {
        String raw = prefs.getString(KEY_MESSAGES, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void save(JSONArray arr) {
        prefs.edit().putString(KEY_MESSAGES, arr.toString()).apply();
    }
}
