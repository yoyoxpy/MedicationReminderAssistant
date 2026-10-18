package com.example.medicationreminderassistant.util;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenAI 兼容格式的对话接口客户端，默认对接智谱 GLM-4.7-Flash 免费模型。
 * 使用 SSE 流式响应，逐步回调增量文本实现打字机效果；
 * 回调统一在主线程执行，网络请求在单线程后台执行。
 */
public class AiClient {

    private static final String TAG = "AiClient";
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 流式回调：onDelta 收到增量文本，onDone 正常结束，onError 失败 */
    public interface StreamCallback {
        void onDelta(String piece);

        void onDone();

        void onError(String message);
    }

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    /**
     * 发起对话请求。
     *
     * @param apiKey   API 密钥（Bearer 认证）
     * @param baseUrl  接口根地址，如 https://open.bigmodel.cn/api/paas/v4
     * @param model    模型名，如 glm-4.7-flash
     * @param messages 消息数组，元素为 {"role": "...", "content": "..."}
     */
    public void chat(String apiKey, String baseUrl, String model,
                     JSONArray messages, StreamCallback callback) {
        cancelled = false;
        EXECUTOR.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(baseUrl + "/chat/completions");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
                conn.setRequestProperty("Accept", "text/event-stream");

                JSONObject body = new JSONObject();
                body.put("model", model);
                body.put("messages", messages);
                body.put("stream", true);
                body.put("temperature", 0.7);
                body.put("max_tokens", 2048);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    String err = readAll(conn.getErrorStream());
                    String message = "HTTP " + code;
                    try {
                        message = new JSONObject(err)
                                .getJSONObject("error").optString("message", message);
                    } catch (Exception ignored) {
                        // 保留 HTTP 状态码作为错误信息
                    }
                    postError(callback, "请求失败：" + message);
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (cancelled) {
                            return;
                        }
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        String data = line.substring(5).trim();
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        try {
                            JSONObject json = new JSONObject(data);
                            JSONArray choices = json.getJSONArray("choices");
                            if (choices.length() == 0) {
                                continue;
                            }
                            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                            if (delta == null) {
                                continue;
                            }
                            String piece = delta.optString("content", "");
                            if (!piece.isEmpty()) {
                                MAIN.post(() -> callback.onDelta(piece));
                            }
                        } catch (Exception ignored) {
                            // 单个分片解析失败不影响整体流
                        }
                    }
                }
                if (!cancelled) {
                    MAIN.post(callback::onDone);
                }
            } catch (Exception e) {
                Log.e(TAG, "AI 请求失败", e);
                if (!cancelled) {
                    postError(callback, "网络异常：" + e.getMessage());
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    private void postError(StreamCallback callback, String message) {
        MAIN.post(() -> callback.onError(message));
    }

    private static String readAll(InputStream is) {
        if (is == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
