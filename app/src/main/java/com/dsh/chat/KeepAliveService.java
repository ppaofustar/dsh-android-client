package com.dsh.chat;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * 任务运行期间的前台服务：让进程不被系统冻结，
 * 从而保证 WebView 里的 WebSocket 持续接收事件，任务完成时能及时提醒。
 */
public class KeepAliveService extends Service {

    static final String ACTION_START = "com.dsh.chat.action.KEEPALIVE_START";
    static final String ACTION_STOP = "com.dsh.chat.action.KEEPALIVE_STOP";

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopForegroundCompat();
            stopSelf();
            return START_NOT_STICKY;
        }

        Notifications.ensureChannels(this);
        // ACTION_START 或系统重启服务时都进入前台
        startForegroundCompat();

        // 被系统杀掉后不自动重启：任务状态已丢失，重启也无意义
        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        Notification n = Notifications.buildRunning(this);
        // specialUse 类型是 API 34 才引入的；更低版本不传类型即可（34 以下不做强制校验）
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifications.ID_RUNNING, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(Notifications.ID_RUNNING, n);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }

    @Override
    public void onDestroy() {
        stopForegroundCompat();
        super.onDestroy();
    }
}
