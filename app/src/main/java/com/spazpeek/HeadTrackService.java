package com.spazpeek;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 头追前台服务（v14 保活强化版）。
 * ====================
 * 组合拳（对抗 ColorOS 激进冻结）：
 *   1. 前台服务 + connectedDevice 类型（基础）
 *   2. PARTIAL_WAKE_LOCK（防 CPU 休眠；提高系统冻结门槛）
 *   3. Alarm 心跳（每 60s 一次"解冻泵"：广播投递会先解冻目标进程，
 *      让冻结期间停摆的数据流/看门狗周期性恢复执行）
 *   4. oom_score_adj 免疫尝试（经 Shizuku/root 写 -800，失败静默）
 *   5. 通知重要度 LOW + 每 30s 刷新（活跃信标）
 */
public class HeadTrackService extends Service {

    private static final String CH_ID = "spz_headtrack";
    private static final int NOTIF_ID = 4211;
    private static final long HB_PERIOD_MS = 60_000L;

    private PowerManager.WakeLock wakeLock;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Runnable notifTick;

    public static void start(Context ctx) {
        try {
            Intent i = new Intent(ctx, HeadTrackService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Throwable t) {
            HTLog.log("KEEP", "service start fail: " + t);
        }
    }

    public static void stop(Context ctx) {
        try {
            ctx.stopService(new Intent(ctx, HeadTrackService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            ensureChannel();
            try {
                startForegroundCompat("空间音频桥接保持后台运行");
            } catch (Throwable t) {
                // Android 12+ 后台启动 FGS 受限时：退化为普通服务（下一次心跳/回前台自动转正）
                HTLog.log("KEEP", "startForeground blocked: " + t);
            }

            // 2) WakeLock
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SpazPeek:headtrack");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            } catch (Throwable t) {
                HTLog.log("KEEP", "wakelock fail: " + t);
            }

            // 4) oom_adj 免疫尝试（经 Shizuku/root）
            applyOomAdjImmunity();

            // 5) 通知定时刷新（活跃信标）
            notifTick = new Runnable() {
                @Override public void run() {
                    try {
                        startForegroundCompat("运行中 · " + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()));
                    } catch (Throwable ignored) {}
                    ui.postDelayed(this, 30_000L);
                }
            };
            ui.postDelayed(notifTick, 30_000L);

            // 3) Alarm 心跳
            scheduleHeartbeat();

            HTLog.log("KEEP", "service started v14 (wakelock+alarm+adj)");
        } catch (Throwable t) {
            HTLog.log("KEEP", "service init fail: " + t);
            stopSelf();
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel old = nm.getNotificationChannel(CH_ID);
            if (old == null || old.getImportance() < NotificationManager.IMPORTANCE_LOW) {
                if (old != null) nm.deleteNotificationChannel(CH_ID);
                NotificationChannel ch = new NotificationChannel(CH_ID, "头追运行中", NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.enableVibration(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        } catch (Throwable ignored) {}
    }

    private void startForegroundCompat(String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CH_ID)
                .setContentTitle("SpazPeek 头追运行中")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_auto)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    private void applyOomAdjImmunity() {
        try {
            final int pid = android.os.Process.myPid();
            new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        ShizukuShell.sh("echo -800 > /proc/" + pid + "/oom_score_adj");
                        String now = ShizukuShell.sh("cat /proc/" + pid + "/oom_score_adj");
                        HTLog.log("KEEP", "oom_adj now=" + (now == null ? "?" : now.trim()));
                    } catch (Throwable t) {
                        HTLog.log("KEEP", "oom_adj fail: " + t);
                    }
                }
            }, "spz-adj").start();
        } catch (Throwable ignored) {}
    }

    private PendingIntent hbIntent() {
        Intent i = new Intent(this, HeartbeatReceiver.class).setAction("com.spazpeek.HB");
        return PendingIntent.getBroadcast(this, 1, i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private void scheduleHeartbeat() {
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            long at = SystemClock.elapsedRealtime() + HB_PERIOD_MS;
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, hbIntent());
        } catch (Throwable t) {
            HTLog.log("KEEP", "alarm fail: " + t);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        scheduleHeartbeat();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        try { if (notifTick != null) ui.removeCallbacks(notifTick); } catch (Throwable ignored) {}
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Throwable ignored) {}
        try {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            PendingIntent pi = PendingIntent.getBroadcast(this, 1,
                    new Intent(this, HeartbeatReceiver.class).setAction("com.spazpeek.HB"),
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
            if (pi != null) am.cancel(pi);
        } catch (Throwable ignored) {}
        HTLog.log("KEEP", "service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}