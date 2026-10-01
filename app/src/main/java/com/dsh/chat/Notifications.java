package com.dsh.chat;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** 通知渠道与通知构造。 */
final class Notifications {

    private Notifications() {
    }

    static final String CHANNEL_DONE = "dsh_task_done";
    static final String CHANNEL_RUNNING = "dsh_running";

    static final int ID_DONE = 1001;
    static final int ID_RUNNING = 1002;

    /** 创建通知渠道（幂等）。 */
    static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        NotificationChannel done = new NotificationChannel(
                CHANNEL_DONE, "任务完成提醒", NotificationManager.IMPORTANCE_HIGH);
        done.setDescription("DSH 智能体完成任务时提醒你");
        done.enableVibration(true);
        nm.createNotificationChannel(done);

        NotificationChannel running = new NotificationChannel(
                CHANNEL_RUNNING, "后台运行中", NotificationManager.IMPORTANCE_LOW);
        running.setDescription("任务运行期间保持连接，以便及时提醒");
        running.setShowBadge(false);
        nm.createNotificationChannel(running);
    }

    private static PendingIntent openApp(Context ctx, int flags) {
        Intent i = new Intent(ctx, MainActivity.class);
        i.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(ctx, 0, i, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 任务完成通知。 */
    static void showTaskDone(Context ctx) {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;

        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CHANNEL_DONE)
                : new Notification.Builder(ctx);

        b.setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("DSH 任务已完成")
                .setContentText("智能体已结束本轮工作，点击查看结果")
                .setAutoCancel(true)
                .setContentIntent(openApp(ctx, PendingIntent.FLAG_UPDATE_CURRENT));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_HIGH);
        }
        b.setDefaults(Notification.DEFAULT_VIBRATE);
        nm.notify(ID_DONE, b.build());
    }

    /** 前台服务常驻通知（低优先级、不可滑动清除）。 */
    static Notification buildRunning(Context ctx) {
        Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ? new Notification.Builder(ctx, CHANNEL_RUNNING)
                : new Notification.Builder(ctx);

        b.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("DSH 正在运行任务")
                .setContentText("保持连接中，完成后会提醒你")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openApp(ctx, PendingIntent.FLAG_UPDATE_CURRENT));

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            b.setPriority(Notification.PRIORITY_LOW);
        }
        return b.build();
    }
}
