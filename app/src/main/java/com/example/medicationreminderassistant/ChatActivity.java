package com.example.medicationreminderassistant;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.medicationreminderassistant.adapter.ChatAdapter;
import com.example.medicationreminderassistant.service.MedicationDatabase;
import com.example.medicationreminderassistant.util.AiClient;
import com.example.medicationreminderassistant.util.AiContextBuilder;
import com.example.medicationreminderassistant.util.AiSettings;
import com.example.medicationreminderassistant.util.ChatHistoryStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * AI 健康助手聊天页：每次请求自动携带药品清单与最近 7 天服药记录作为系统上下文，
 * 回复采用流式输出。首次使用引导粘贴智谱 API Key。
 */
public class ChatActivity extends AppCompatActivity {

    private static final int MAX_HISTORY = 10;

    private ChatAdapter adapter;
    private AiClient client;
    private AiSettings settings;
    private ChatHistoryStore historyStore;
    private boolean hasGreeting;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 让应用自主处理系统栏与键盘 insets，避免 adjustResize 在手势导航/edge-to-edge 下失效
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_chat);

        // 监听键盘与状态栏高度，把底部输入条顶到键盘正上方，标题栏避开状态栏
        View root = findViewById(R.id.chatRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            int statusBarTop = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            int navBarBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            // 键盘弹出时取键盘高度，否则取导航栏高度（手势导航时为 0）
            int bottom = Math.max(imeBottom, navBarBottom);
            v.setPadding(v.getPaddingLeft(), statusBarTop, v.getPaddingRight(), bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        settings = new AiSettings(this);
        client = new AiClient();
        historyStore = new ChatHistoryStore(this);

        adapter = new ChatAdapter();
        RecyclerView rv = findViewById(R.id.rvChat);
        rv.setLayoutManager(new LinearLayoutManager(this));
        rv.setAdapter(adapter);

        // 恢复最近 7 天的对话记录；没有记录时才显示开场白
        List<ChatHistoryStore.Entry> history = historyStore.loadRecent();
        if (history.isEmpty()) {
            hasGreeting = true;
            adapter.add(new ChatAdapter.Msg("assistant",
                    "你好，我是你的用药小助手 🤖\n可以问我：\n· 我今天该吃什么药？\n· 我最近的服药情况怎么样？\n· 这几种药一起吃要注意什么？"));
        } else {
            for (ChatHistoryStore.Entry entry : history) {
                adapter.add(new ChatAdapter.Msg(entry.role, entry.content));
            }
            // 历史记录较多时定位到最新一条
            scrollToBottom();
        }

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnKey).setOnClickListener(v -> promptApiKey());
        findViewById(R.id.btnSend).setOnClickListener(v -> send());
    }

    private void send() {
        EditText input = findViewById(R.id.etInput);
        String text = input.getText().toString().trim();
        if (TextUtils.isEmpty(text) || busy) {
            return;
        }
        if (TextUtils.isEmpty(settings.getApiKey())) {
            promptApiKey();
            return;
        }

        input.setText("");
        adapter.add(new ChatAdapter.Msg("user", text));
        historyStore.append("user", text);
        adapter.add(new ChatAdapter.Msg("assistant", "…"));
        scrollToBottom();
        busy = true;

        try {
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", AiContextBuilder.buildSystemPrompt(
                    MedicationDatabase.getInstance(this)));
            messages.put(system);

            // 携带最近几轮对话（不含开场白与占位气泡），保持上下文连贯
            int size = adapter.getItems().size();
            int from = Math.max(hasGreeting ? 1 : 0, size - 1 - MAX_HISTORY);
            for (int i = from; i < size - 1; i++) {
                ChatAdapter.Msg msg = adapter.getItems().get(i);
                JSONObject o = new JSONObject();
                o.put("role", msg.role);
                o.put("content", msg.content);
                messages.put(o);
            }

            StringBuilder received = new StringBuilder();
            client.chat(settings.getApiKey(), settings.getBaseUrl(), settings.getModel(),
                    messages, new AiClient.StreamCallback() {
                        @Override
                        public void onDelta(String piece) {
                            received.append(piece);
                            updateLastAiMessage(received.toString());
                        }

                        @Override
                        public void onDone() {
                            busy = false;
                            if (received.length() == 0) {
                                updateLastAiMessage("（AI 没有返回内容，请稍后再试）");
                            } else {
                                historyStore.append("assistant", received.toString());
                            }
                        }

                        @Override
                        public void onError(String message) {
                            busy = false;
                            updateLastAiMessage("出错了：" + message);
                        }
                    });
        } catch (Exception e) {
            busy = false;
            updateLastAiMessage("出错了：" + e.getMessage());
        }
    }

    private void updateLastAiMessage(String text) {
        runOnUiThread(() -> {
            if (adapter.getItems().isEmpty()) {
                return;
            }
            adapter.getItems().get(adapter.getItems().size() - 1).content = text;
            adapter.refreshLast();
            scrollToBottom();
        });
    }

    /**
     * 微信式跟随滚动：等布局完成后，若最后一条气泡底部超出可视区，
     * 按超出量上推列表；气泡尚未上屏时直接跳到最后一项。
     * 内容不足一屏时不产生任何滚动，消息自然从顶部排列。
     */
    private void scrollToBottom() {
        RecyclerView rv = findViewById(R.id.rvChat);
        if (rv == null || adapter.getItemCount() == 0) {
            return;
        }
        rv.post(() -> {
            int last = adapter.getItemCount() - 1;
            LinearLayoutManager lm = (LinearLayoutManager) rv.getLayoutManager();
            View lastView = lm != null ? lm.findViewByPosition(last) : null;
            if (lastView == null) {
                rv.scrollToPosition(last);
                return;
            }
            int overflow = lastView.getBottom() - (rv.getHeight() - rv.getPaddingBottom());
            if (overflow > 0) {
                rv.scrollBy(0, overflow);
            }
        });
    }

    /** 首次使用或点击右上角“密钥”时填写 API Key */
    private void promptApiKey() {
        final EditText et = new EditText(this);
        et.setHint("粘贴 API Key");
        et.setTextSize(14);
        FrameLayout container = new FrameLayout(this);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(pad, pad / 2, pad, 0);
        container.addView(et, lp);

        new AlertDialog.Builder(this)
                .setTitle("设置 AI 助手 API Key")
                .setMessage("免费获取步骤：\n1. 打开 bigmodel.cn 并注册登录\n2. 进入“API 密钥”页面创建密钥\n3. 复制密钥粘贴到下方\n\n默认使用免费的 GLM-4.7-Flash 模型")
                .setView(container)
                .setPositiveButton("保存", (d, w) -> {
                    String key = et.getText().toString().trim();
                    if (!key.isEmpty()) {
                        settings.setApiKey(key);
                        Toast.makeText(this, "API Key 已保存", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        client.cancel();
    }
}
