package com.along.livesubtitle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

/**
 * 字幕前台服务。
 *
 * 职责：
 *  1) 保活（前台通知），保证后台不被杀
 *  2) 创建并管理悬浮字幕窗（CaptionWindow）
 *  3) 创建并驱动识别引擎（AsrEngine）
 *  4) 把引擎结果转发给字幕窗
 */
public class CaptionService extends Service implements CaptionWindow.Listener {

    public static final String ACTION_START = "com.along.livesubtitle.START";
    public static final String ACTION_STOP = "com.along.livesubtitle.STOP";
    public static final String ACTION_REFRESH_STYLE = "com.along.livesubtitle.REFRESH_STYLE";
    public static final String ACTION_TOGGLE_LOCK = "com.along.livesubtitle.TOGGLE_LOCK";

    private static final String CHANNEL_ID = "live_subtitle";
    /** PendingIntent 不可变标志（Android 12+ 必需，否则点击无响应） */
    private static final int PI_IMMUTABLE = 0x04000000;
    private static final int NOTI_ID = 1001;

    private Prefs prefs;
    private CaptionWindow window;
    private AsrEngine engine;
    private boolean running = false;
    /** 记录上一个是否是「真实用户触发」的 START；用于识别 START_STICKY 僵尸重启 */
    private boolean userStarted = false;
    private long aliveAt = System.currentTimeMillis();

    // ============================================================
    //  生命周期
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        createChannel();
        installCrashHandler();
        logf("onCreate DONE pid=" + android.os.Process.myPid() + " enabled=" + prefs.isEnabled());
    }

    /** 崩溃取证：把未捕获异常写进 files/crash.log。本机 logcat 读不到，只能自己落盘。 */
    private void installCrashHandler() {
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread t, Throwable e) {
                try {
                    String txt = android.text.format.DateFormat.format("MM-dd HH:mm:ss", System.currentTimeMillis())
                            + " THREAD=" + t.getName() + "\n"
                            + Log.getStackTraceString(e) + "\n\n";
                    java.io.File f = new java.io.File(getFilesDir(), "crash.log");
                    if (f.exists() && f.length() > 512 * 1024) f.delete();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
                    fos.write(txt.getBytes("UTF-8"));
                    fos.flush();
                    fos.close();
                } catch (Throwable ignore) {
                }
                if (prev != null) prev.uncaughtException(t, e);
            }
        });
    }

    // 硬闸：sticky 自动重启每个进程生命周期内最多重建一次，防止 1~2 秒一轮的死螺旋
    private static boolean stickyRebuilt = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 关键修复：intent == null 表示系统按 START_STICKY 自动拉起（僵尸重启）
        // 此时 App 已无前台组件，startForeground 必被 DENIED，强启只会再次被杀形成螺旋
        // 因此直接 stopSelf() 打断螺旋，等用户下次在 App 内真正点「开始」再走完整链路
        if (intent == null) {
            // 系统按 START_STICKY 自动拉起（被杀后恢复），intent 为 null。
            // 旧逻辑 stopSelf 会永久停止，导致「窗口消失且永不恢复」。
            // 新逻辑：若用户此前开启了字幕（prefs.isEnabled），则重建前台通知 + 窗口 + 引擎。
            boolean want = prefs.isEnabled();
            logf("sticky restart (intent=null), wantEnabled=" + want + ", stickyRebuilt=" + stickyRebuilt);
            if (!want) {
                logf("user stopped, stopSelf");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (stickyRebuilt) {
                // 本进程已经自动重建过一轮，再次被拉起说明前面那轮没活下来，
                // 继续重建只会形成 1~2 秒一轮的死螺旋（会把整个 App 弹出去），直接收手。
                logf("sticky restart ABORT (already rebuilt once this process), stopSelf");
                stopSelf();
                return START_NOT_STICKY;
            }
            stickyRebuilt = true;
            startForegroundSafe();
            if (!running) startCaption();
            // 一次性：不再自续，避免被杀后无限拉起
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (action == null) action = ACTION_START;
        if (ACTION_STOP.equals(action)) {
            userStarted = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH_STYLE.equals(action)) {
            if (window != null && window.isShowing()) window.refreshStyle();
            return START_STICKY;
        }
        if (ACTION_TOGGLE_LOCK.equals(action)) {
            if (window != null && window.isShowing()) {
                boolean next = !window.isLocked();
                window.setInitialLocked(next);
                prefs.setLocked(next);
                updateNotification(next);
            }
            return START_STICKY;
        }
        // ACTION_START：真实用户触发
        userStarted = true;
        startForegroundSafe();
        if (!running) {
            startCaption();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopCaption();
        // 注意：只有用户主动 ACTION_STOP 才把 enabled 置 false（在 onStartCommand 里已处理）
        // 系统回收/崩溃导致的 onDestroy 不能清标记，否则重启后不会自动恢复
        logf("onDestroy (userStarted=" + userStarted + ", prefsEnabled=" + prefs.isEnabled() + ", running=" + running + ", stickyRebuilt=" + stickyRebuilt + ", pid=" + android.os.Process.myPid() + ", aliveMs=" + (System.currentTimeMillis() - aliveAt) + ")");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ============================================================
    //  启动 / 停止字幕
    // ============================================================

    private void startCaption() {
        // 幂等守卫：窗口已在显示且引擎已起，直接返回，避免反复重建窗口
        if (running && window != null && window.isShowing()) {
            logf("startCaption SKIP (already running & showing)");
            return;
        }
        running = true;
        // 提前落盘：只要决定开字幕，立刻把 enabled 写成 true。
        // 之前写在 startEngine 之后，引擎一抛异常这行就可能被跳过，
        // 造成“窗口开了但 enabled=false”的不一致状态。
        prefs.setEnabled(true);
        logf("startCaption enter (enabled=true committed)");
        // 1) 悬浮窗
        try {
            if (window == null) {
                window = new CaptionWindow(this, prefs);
                window.setListener(this);
            }
            window.setInitialLocked(prefs.isLockedRemembered());
            window.show();
            if (window.isShowing()) {
                logf("CaptionWindow show OK (isShowing=true)");
            } else {
                logf("CaptionWindow show FAILED (isShowing=false, addView 内部失败，见 [win] 日志)");
                toast("悬浮窗添加失败，请看 svc.log");
            }
        } catch (Throwable t) {
            logf("CaptionWindow FAILED: " + Log.getStackTraceString(t));
            toast("悬浮窗显示失败：" + t.getClass().getSimpleName());
        }
        // 2) 引擎
        try {
            startEngine();
            logf("startEngine OK");
        } catch (Throwable t) {
            logf("startEngine FAILED: " + Log.getStackTraceString(t));
        }
        try {
            updateNotification(window != null && window.isLocked());
        } catch (Throwable t) {
            logf("updateNotification FAILED: " + Log.getStackTraceString(t));
        }
        logf("startCaption done");
    }

    private void startEngine() {
        stopEngine();

        int mode = prefs.getAudioMode();

        if (mode == 1 || mode == 2) {
            // 内录模式（含自动降级）
            if (isProjectionReady()) {
                // 注意：PlaybackCaptureEngine 属第二阶段（需要 sherpa-onnx 喂 PCM）
                // 现阶段若用户选内录但引擎未就绪，直接降级并提示
                if (mode == 1) {
                    toast("内录引擎尚未就绪，已切换为麦克风");
                }
            } else if (mode == 1) {
                toast("未授权录屏，已切换为麦克风");
            }
        }

        // 麦克风模式（第一阶段默认路径）
        engine = new SherpaAsrEngine(this);
        engine.start(new AsrEngine.Callback() {
            @Override
            public void onResult(String text, boolean isFinal) {
                if (window != null) window.onAsrResult(text, isFinal);
            }

            @Override
            public void onError(int code, String msg) {
                // 不再静默：落盘 + 窗口提示 + toast（本机 logcat 读不到，必须显式落盘）
                logf("ASR ENGINE ERROR code=" + code + " msg=" + msg);
                if (window != null) window.setStatusText("引擎错误 " + code + ": " + msg);
                toast("引擎错误：" + msg);
            }

            @Override
            public void onVolume(float level) {
                // 预留：可选在窗口上画音量条
            }
        });
    }

    private void stopEngine() {
        if (engine != null) {
            try {
                engine.release();
            } catch (Throwable ignore) {
            }
            engine = null;
        }
    }

    private void stopCaption() {
        running = false;
        // 清脏状态：字幕都停了，用户启动意图必须归零。
        // 否则 onDestroy 可能读到上一次遗留的 true/false 不一致状态，
        // 导致 sticky 重建时的判断失准。
        userStarted = false;
        stopEngine();
        if (window != null) {
            window.hide();
            window = null;
        }
    }

    /** 录屏授权是否已就绪（第二阶段内录使用） */
    private boolean isProjectionReady() {
        // 第一阶段：始终返回 false，等 PlaybackCaptureEngine 落地后再改
        return false;
    }

    // ============================================================
    //  CaptionWindow.Listener
    // ============================================================

    @Override
    public void onLockToggle(boolean locked) {
        updateNotification(locked);
            if (locked) {
            toast("已锁定：触摸将穿透字幕窗");
        } else {
            toast("已解锁：可拖动调整");
        }
    }

    @Override
    public void onClose() {
        stopSelf();
    }

    @Override
    public void onGeometryChanged(int x, int y, int wDp, int hDp) {
        // 位置尺寸已由 CaptionWindow 存入 Prefs，这里无需额外处理
    }

    // ============================================================
    //  通知
    // ============================================================

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "实时字幕", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("实时字幕运行状态");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundSafe() {
        try {
            Notification n = buildNotification(false);
            if (Build.VERSION.SDK_INT >= 29) {
                // Android 10+：显式声明 microphone 类型，避免系统因类型不匹配拒授 FGS 身份
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(NOTI_ID, n);
            }
            logf("startForeground OK type=microphone (explicit)");
        } catch (Throwable t) {
            logf("startForeground FAILED: " + Log.getStackTraceString(t));
            try {
                Notification n = buildNotification(false);
                startForeground(NOTI_ID, n);
                logf("startForeground retry OK (2-arg)");
            } catch (Throwable t2) {
                logf("startForeground retry FAILED: " + Log.getStackTraceString(t2));
            }
        }
    }

    /** 服务侧取证日志：写 files/svc.log，超 256KB 自动清空 */
    private void logf(String msg) {
        String line = android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())
                + " " + msg + "\n";
        Log.i("LiveSubtitleSvc", msg);
        try {
            java.io.File f = new java.io.File(getFilesDir(), "svc.log");
            if (f.exists() && f.length() > 256 * 1024) f.delete();
            java.io.FileOutputStream fos = new java.io.FileOutputStream(f, true);
            fos.write(line.getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    private void updateNotification(boolean locked) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        try {
            nm.notify(NOTI_ID, buildNotification(locked));
        } catch (Throwable ignore) {
        }
    }

    private Notification buildNotification(boolean locked) {
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent piOpen = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PI_IMMUTABLE);

        Intent lock = new Intent(this, CaptionService.class);
        lock.setAction(ACTION_TOGGLE_LOCK);
        PendingIntent piLock = PendingIntent.getService(
                this, 1, lock, PendingIntent.FLAG_UPDATE_CURRENT | PI_IMMUTABLE);

        Intent stop = new Intent(this, CaptionService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent piStop = PendingIntent.getService(
                this, 2, stop, PendingIntent.FLAG_UPDATE_CURRENT | PI_IMMUTABLE);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }

        b.setContentTitle("实时字幕运行中")
                .setContentText(locked ? "字幕窗已锁定（触摸穿透）" : "字幕窗未锁定，可拖动调整")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(piOpen)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_lock_lock,
                        locked ? "解锁" : "锁定", piLock)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止", piStop);

        return b.build();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}