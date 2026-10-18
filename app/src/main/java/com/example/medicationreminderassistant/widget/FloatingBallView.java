package com.example.medicationreminderassistant.widget;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.appcompat.widget.AppCompatTextView;

import com.example.medicationreminderassistant.ChatActivity;

/**
 * 应用内 AI 小助手悬浮球：附加到 Activity 内容层之上，
 * 按住可拖动（限制在屏幕内），单击打开 AI 聊天页。
 * 属于应用内视图，无需悬浮窗权限。
 */
public class FloatingBallView extends AppCompatTextView {

    private static final int SIZE_DP = 54;
    private static final int MARGIN_DP = 16;

    private float downRawX, downRawY;
    private float startTranslationX, startTranslationY;
    private boolean dragging;
    private final int touchSlop;

    /** 把悬浮球添加到 Activity 根内容层，初始位置右下角 */
    public static void attach(Activity activity) {
        try {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            FloatingBallView ball = new FloatingBallView(activity);
            int size = dp(activity, SIZE_DP);
            content.addView(ball, new FrameLayout.LayoutParams(size, size));
            ball.post(() -> {
                try {
                    float x = Math.max(0, content.getWidth() - size - dp(activity, MARGIN_DP));
                    // 初始位置放在屏幕中下部（约 55% 高度），避开底部导航栏和"拍照识别"按钮
                    float y = Math.max(0, content.getHeight() * 0.55f - size / 2f);
                    ball.setTranslationX(x);
                    ball.setTranslationY(y);
                } catch (Exception ignored) {
                    // 初始定位失败时保持默认位置
                }
            });
        } catch (Exception ignored) {
            // 悬浮球失败不影响主流程
        }
    }

    public FloatingBallView(Context context) {
        super(context);
        setText("🤖");
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        setGravity(Gravity.CENTER);
        setClickable(true);
        setElevation(dp(context, 8));
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF3AA88A, 0xFF2E7D66});
        bg.setShape(GradientDrawable.OVAL);
        setBackground(bg);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                startTranslationX = getTranslationX();
                startTranslationY = getTranslationY();
                dragging = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && Math.hypot(dx, dy) > touchSlop) {
                    dragging = true;
                }
                if (dragging) {
                    ViewGroup parent = (ViewGroup) getParent();
                    if (parent != null) {
                        float nx = Math.max(0, Math.min(startTranslationX + dx,
                                parent.getWidth() - getWidth()));
                        float ny = Math.max(0, Math.min(startTranslationY + dy,
                                parent.getHeight() - getHeight()));
                        setTranslationX(nx);
                        setTranslationY(ny);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!dragging) {
                    performClick();
                }
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    @Override
    public boolean performClick() {
        super.performClick();
        Context context = getContext();
        context.startActivity(new Intent(context, ChatActivity.class));
        return true;
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics());
    }
}
