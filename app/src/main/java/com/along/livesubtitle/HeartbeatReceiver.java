package com.along.livesubtitle;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/**
 * 心跳复活（故障自恢复 · 进程外层）
 *
 * 背景：CaptionService 已经彻底废弃 START_STICKY（会死螺旋），
 * 所以进程被系统杀掉后没有任何东西会把它拉起来。
 * 这里用 AlarmManager 做一个极低频的心跳，严格按以下闸门才复活：
 *   1. 用户开了心跳开关（prefs.isHeartbeat()）
 *   2. 用户确实想跑字幕（prefs.isEnabled()）
 *   3. 服务确实死了（!CaptionService.ALIVE）
 * 任何一条不满足就什么都不做，只把下一次闹钟续上。
 * 用户点「停止」→ enabled=false → 永不复活，物理上不可能出现死螺旋。
 */
public class HeartbeatReceiver extends BroadcastReceiver {

    public static final String ACTION_HEARTBEAT = "com.along.livesubtitle.HEARTBEAT";

    /** 心跳间隔：15 分钟。太短会耗电，太长复活迟钝。 */
    public static final long INTERVAL_MS = 15L * 60L * 1000L;

    private static final int REQ_CODE = 7301;

    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Lg.svc(context, "heartbeat tick received");
            Prefs prefs = new Prefs(context);
            boolean heartbeat = prefs.isHeartbeat();
            boolean enabled = prefs.isEnabled();
            boolean alive = CaptionService.ALIVE;
            Lg.svc(context, "heartbeat state: heartbeat=" + heartbeat + " enabled=" + enabled + " alive=" + alive);

            if (heartbeat && enabled && !alive) {
                // 三个闸门全过，才复活
                Lg.svc(context, "heartbeat -> restarting service");
                Intent svc = new Intent(context, CaptionService.class);
                svc.setAction(CaptionService.ACTION_RESTART);
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(svc);
                } else {
                    context.startService(svc);
                }
            }

            // 无论走不走复活，都把下一次闹钟续上，否则心跳会断
            if (heartbeat && enabled) {
                schedule(context, INTERVAL_MS);
            }
        } catch (Throwable t) {
            Lg.svc(context, "heartbeat tick FAILED: " + android.util.Log.getStackTraceString(t));
        }
    }

    /** 安排下一次心跳（可选延迟，0 = 立即下一个间隔） */
    public static void schedule(Context context, long delayMs) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            long at = SystemClock.elapsedRealtime() + (delayMs > 0 ? delayMs : INTERVAL_MS);
            PendingIntent pi = pendingIntent(context);
            if (pi == null) return;
            if (Build.VERSION.SDK_INT >= 23) {
                // setAndAllowWhileIdle：Doze 下也能穿透，系统对此类闹钟有频率限制（约 9~15 分钟），我们 15 分钟正好合格
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi);
            }
            Lg.svc(context, "heartbeat scheduled in " + (at - SystemClock.elapsedRealtime()) + "ms");
        } catch (Throwable t) {
            Lg.svc(context, "heartbeat schedule FAILED: " + android.util.Log.getStackTraceString(t));
        }
    }

    /** 取消心跳（用户主动停止 / 关开开关时调用） */
    public static void cancel(Context context) {
        try {
            AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            PendingIntent pi = pendingIntent(context);
            if (pi != null) {
                am.cancel(pi);
                pi.cancel();
            }
            Lg.svc(context, "heartbeat cancelled");
        } catch (Throwable t) {
            Lg.svc(context, "heartbeat cancel FAILED: " + android.util.Log.getStackTraceString(t));
        }
    }

    private static PendingIntent pendingIntent(Context context) {
        try {
            Intent i = new Intent(context, HeartbeatReceiver.class);
            i.setAction(ACTION_HEARTBEAT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                flags |= 0x04000000; // FLAG_IMMUTABLE
            }
            return PendingIntent.getBroadcast(context, REQ_CODE, i, flags);
        } catch (Throwable t) {
            Lg.svc(context, "heartbeat pendingIntent FAILED: " + android.util.Log.getStackTraceString(t));
            return null;
        }
    }
}
