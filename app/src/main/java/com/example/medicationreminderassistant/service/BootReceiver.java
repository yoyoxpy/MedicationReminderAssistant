package com.example.medicationreminderassistant.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 设备重启或应用更新后，系统会清除已注册的闹钟，这里统一重新注册全部开启中的提醒。
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {

            final PendingResult asyncResult = goAsync();
            new Thread(() -> {
                try {
                    NotificationService.rescheduleAll(context);
                } finally {
                    asyncResult.finish();
                }
            }).start();
        }
    }
}
