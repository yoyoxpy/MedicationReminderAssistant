package com.example.medicationreminderassistant.util;

import android.app.Activity;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * 系统导航栏 insets 适配：Android 15+ 强制 edge-to-edge 后页面内容会延伸到导航栏下方，
 * 此工具给内容层自动垫上导航栏高度——开启三键导航栏时底部内容自动贴合其上方，
 * 关闭导航栏（全面屏手势）后自动回到底部。
 * Android 14 等由系统自行避让导航栏的设备上 insets 已被消费，本工具不做任何改动。
 */
public final class InsetsHelper {

    private InsetsHelper() {
    }

    public static void applyBottomNavInset(Activity activity) {
        try {
            ViewGroup content = activity.findViewById(android.R.id.content);
            if (content == null) {
                return;
            }
            int baseBottom = content.getPaddingBottom();
            boolean[] applied = {false};
            ViewCompat.setOnApplyWindowInsetsListener(content, (v, insets) -> {
                int nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
                // nav>0 说明系统未自行避让（edge-to-edge），需要自行垫高；
                // nav=0 时仅在之前垫过的情况下复位，避免覆盖框架在非 edge-to-edge 下的避让
                if (nav > 0 || applied[0]) {
                    v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                            v.getPaddingRight(), baseBottom + nav);
                    applied[0] = nav > 0;
                }
                return insets;
            });
        } catch (Exception ignored) {
            // insets 适配失败不影响页面本身
        }
    }
}
