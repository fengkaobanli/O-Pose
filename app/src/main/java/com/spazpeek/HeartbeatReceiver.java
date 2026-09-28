package com.spazpeek;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 保活心跳接收器（由 AlarmManager 定期触发）。
 * ====================
 * 作用：周期性"唤醒"被系统冻结的进程——广播投递时系统会先解冻目标进程，
 * 让冻结期间停摆的数据流/看门狗获得执行机会；同时幂等重启前台服务（若已被杀）。
 * 注意：使用 setAndAllowWhileIdle（非精确），无需 SCHEDULE_EXACT_ALARM 权限。
 */
public class HeartbeatReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            HTLog.log("KEEP", "heartbeat");
            HeadTrackService.start(ctx);
        } catch (Throwable t) {
            HTLog.log("KEEP", "heartbeat fail: " + t);
        }
    }
}