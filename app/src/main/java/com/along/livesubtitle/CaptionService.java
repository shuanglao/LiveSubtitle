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
    /** 通知栏：显示/隐藏字幕窗（窗口穿透或挡屏时仍能唤回） */
    public static final String ACTION_TOGGLE_SHOW = "com.along.livesubtitle.TOGGLE_SHOW";
    /** 通知栏：一键重启字幕（服务异常/卡死时救命） */
    public static final String ACTION_RESTART = "com.along.livesubtitle.RESTART";
    /** v1.12 功能8：通知栏一键复制当前全部字幕 */
    public static final String ACTION_COPY = "com.along.livesubtitle.COPY";

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
    /** 用户通过通知栏手动隐藏了窗口（穿透后仍可唤回，与锁定状态独立） */
    private boolean hiddenByUser = false;
    private long aliveAt = System.currentTimeMillis();
    /** 服务是否真实存活（供 MainActivity 判断主按钮状态，
     *  避免 prefs.enabled 与真实运行态脱钩导致界面不同步）。*/
    public static volatile boolean ALIVE = false;

    // ============================================================
    //  生命周期
    // ============================================================

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        createChannel();
        installCrashHandler();
        ALIVE = true;
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

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 关键修复：intent == null 表示系统按 START_STICKY 自动拉起（僵尸重启）
        // 此时 App 已无前台组件，startForeground 必被 DENIED，强启只会再次被杀形成螺旋
        // 因此直接 stopSelf() 打断螺旋，等用户下次在 App 内真正点「开始」再走完整链路
        if (intent == null) {
            // 系统按 START_STICKY 自动拉起（被杀后恢复），intent 为 null。
            // ============================================================
            // 硬闸（v2，落盘版）：
            // 旧版用 static boolean stickyRebuilt 做闸，但每次被拉起都是「新进程」，
            // static 重新初始化为 false，硬闸完全失效 → 形成 4~6 秒一轮的死螺旋。
            // 现改为落盘计数 + 60 秒时间窗：窗口内第 2 次被拉起立即收手。
            // ============================================================
            boolean want = prefs.isEnabled();
            long now = System.currentTimeMillis();
            long winAt = prefs.getStickyWindowAt();
            int cnt = prefs.getStickyCount();
            if (winAt <= 0L || now - winAt > 60000L) {
                // 新窗口：重置计数
                winAt = now;
                cnt = 0;
                prefs.setStickyWindowAt(winAt);
            }
            cnt++;
            prefs.setStickyCount(cnt);
            logf("sticky restart (intent=null), wantEnabled=" + want
                    + ", stickyCount=" + cnt + " (window " + (now - winAt) + "ms)");
            if (!want) {
                logf("user stopped (enabled=false), stopSelf");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (cnt > 1) {
                // 60 秒内第 2 次被拉起 = 上一轮没活下来。继续重建只会死螺旋，
                // 直接把 enabled 清掉并收手，让用户重新在 App 内点「开始」。
                // v1.11: 不再清 enabled —— 清开关会让用户下次打开 App 看到「开关自己关了」的困惑状态，且手机压力大时字幕会永久停住。这里只 stopSelf，用户点「开始」随时能重新走完整链路，防螺旋效果与旧版完全一致。
                logf("sticky restart ABORT (count=" + cnt + " in 60s), stopSelf (enabled kept)");
                stopSelf();
                return START_NOT_STICKY;
            }
            startForegroundSafe();
            if (!running) startCaption();
            // 一次性：不再自续，避免被杀后无限拉起
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (action == null) action = ACTION_START;
        if (ACTION_STOP.equals(action)) {
            userStarted = false;
            // ③ 层２：用户主动停止，断开心跳，永不复活
            HeartbeatReceiver.cancel(this);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REFRESH_STYLE.equals(action)) {
            if (window != null && window.isShowing()) window.refreshStyle();
            return START_STICKY;
        }
        if (ACTION_TOGGLE_LOCK.equals(action)) {
            // 通知栏解锁/锁定：窗口可能被“隐藏”了，隐藏时也允许改状态，
            // 这样即使窗口不可见，用户仍能从通知栏把 locked 复位，避免锁死。
            boolean next = window != null ? !window.isLocked() : !prefs.isLockedRemembered();
            if (window != null) window.setInitialLocked(next);
            prefs.setLocked(next);
            logf("ACTION_TOGGLE_LOCK -> locked=" + next + " (window=" + (window != null)
                    + ", showing=" + (window != null && window.isShowing()) + ")");
            updateNotification(next);
            toast(next ? "已锁定：触摸穿透，解锁请点通知栏" : "已解锁：可拖动调整");
            return START_STICKY;
        }
        if (ACTION_COPY.equals(action)) {
            // v1.12 功能8：通知栏一键复制全部字幕
            if (window == null) {
                logf("ACTION_COPY ignored (window==null)");
                toast("字幕窗尚未就绪");
                return START_STICKY;
            }
            try {
                window.copyAll();
                logf("ACTION_COPY done");
            } catch (Throwable t) {
                logf("ACTION_COPY FAILED: " + Log.getStackTraceString(t));
            }
            return START_STICKY;
        }
        if (ACTION_TOGGLE_SHOW.equals(action)) {
            // 通知栏显示/隐藏：锁定穿透后窗口看不见也摸不到，这里是唯一可靠的唤回入口。
            if (window == null) {
                logf("ACTION_TOGGLE_SHOW ignored (window==null)");
                return START_STICKY;
            }
            boolean nowShowing = window.isShowing() && !hiddenByUser;
            if (nowShowing) {
                hiddenByUser = true;
                try {
                    window.hide();
                } catch (Throwable t) {
                    logf("ACTION_TOGGLE_SHOW hide FAILED: " + Log.getStackTraceString(t));
                }
                logf("ACTION_TOGGLE_SHOW -> hidden by user");
                toast("字幕窗已隐藏，点通知栏可唤回");
            } else {
                hiddenByUser = false;
                try {
                    window.setInitialLocked(prefs.isLockedRemembered());
                    window.show();
                } catch (Throwable t) {
                    logf("ACTION_TOGGLE_SHOW show FAILED: " + Log.getStackTraceString(t));
                }
                logf("ACTION_TOGGLE_SHOW -> shown (locked=" + prefs.isLockedRemembered() + ")");
            }
            updateNotification(window.isLocked());
            return START_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            // 救命通道：服务被硬闸收手、卡死、或窗口异常时，不需要进 App 里操作。
            // 这里把 sticky 计数清零（让硬闸重新可用），强制 enabled=true，然后重建。
            logf("ACTION_RESTART -> rebuilding (running=" + running + ", shown=" + (window != null && window.isShowing()) + ")");
            prefs.setStickyCount(0);
            prefs.setStickyWindowAt(System.currentTimeMillis());
            prefs.setEnabled(true);
            userStarted = true;
            hiddenByUser = false;
            try {
                if (running) stopCaption();
            } catch (Throwable t) {
                logf("ACTION_RESTART stopCaption FAILED: " + Log.getStackTraceString(t));
            }
            try {
                startForegroundSafe();
                startCaption();
                toast("字幕已重启");
                logf("ACTION_RESTART -> started");
            } catch (Throwable t) {
                logf("ACTION_RESTART startCaption FAILED: " + Log.getStackTraceString(t));
                toast("重启失败，请进 App 内重试");
            }
            return START_STICKY;
        }
        // ACTION_START：真实用户触发
        // 启动时清掉 sticky 计数窗口：用户主动点了「开始」，说明这是一次全新意愿，
        // 之前积累的自动重启次数不应再影响判断。
        prefs.setStickyCount(0);
        prefs.setStickyWindowAt(System.currentTimeMillis());
        userStarted = true;
        startForegroundSafe();
        if (!running) {
            startCaption();
        }
        // 止血核心：返回 START_NOT_STICKY。
        // 之前返回 START_STICKY，一旦进程被系统杀掉，系统会拿 null intent 反复拉起，
        // 而每轮都是新进程、static 硬闸失效 → 4~6 秒一轮的死螺旋。
        // 现在死了就安静地死，用户想用再在 App 里点「开始」。
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        ALIVE = false;
        // ③ 层1：进程销毁前摘掉巡检，避免 Handler 泄漏
        stopWatchdog();
        // 关键修复（窗口泄漏）：旧代码先把 window 置 null 再调 stopCaption()，
        // 导致 stopCaption() 里的 window.hide() 永远走不到 → View 从不 removeView，
        // 一直挂在 WindowManager 上。Service 死了窗口还在，下次启动又建一个 → 窗口越叠越多。
        // 正确顺序：先摘 listener（防 hide→onClose 递归），再真正 hide() 移除 View，最后才置 null。
        if (window != null) {
            logf("onDestroy: hiding leftover window before release");
            window.setListener(null);
            try {
                window.hide();
            } catch (Throwable t) {
                logf("onDestroy: window.hide FAILED: " + Log.getStackTraceString(t));
            }
            window = null;
        }
        stopCaption();
        // 注意：只有用户主动 ACTION_STOP 才把 enabled 置 false（在 onStartCommand 里已处理）
        // 系统回收/崩溃导致的 onDestroy 不能清标记，否则重启后不会自动恢复
        logf("onDestroy (userStarted=" + userStarted + ", prefsEnabled=" + prefs.isEnabled() + ", running=" + running + ", stickyCount=" + prefs.getStickyCount() + ", pid=" + android.os.Process.myPid() + ", aliveMs=" + (System.currentTimeMillis() - aliveAt) + ")");
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
        // 注意：只有「同一 Service 实例」内有效，跨实例/跨进程要靠下面的 forceRemoveAll()
        if (running && window != null && window.isShowing()) {
            logf("startCaption SKIP (already running & showing)");
            return;
        }
        // 第二道防线（关键）：先强制清掉本进程所有残留悬浮窗。
        // 场景：Service 被系统强杀（不走 onDestroy）→ 旧 View 仍挂在 WindowManager 上，
        //       新实例启动时 window==null 就会再建一个 → 用户看到「旧窗口不关、新的叠上来」。
        int removed = CaptionWindow.forceRemoveAll();
        if (removed > 0) {
            logf("startCaption: force removed " + removed + " leftover window(s)");
        }
        // 第三道防线：本实例持有的旧 window 也要先彻底 hide 掉
        if (window != null) {
            logf("startCaption: hiding previous instance window before rebuild");
            try {
                window.setListener(null);
                window.hide();
            } catch (Throwable t) {
                logf("startCaption: previous window hide FAILED: " + Log.getStackTraceString(t));
            }
            window = null;
        }
        // ② 麦克风体检：开字幕前先看一眼有没有别人在录音。
        // 不阻断流程（稳字优先），只提示；取不到信息就不提示，不谎报。
        try {
            final String selfPkg = getPackageName();
            final String hint = ShizukuShell.micBusyHint(selfPkg);
            if (hint != null && hint.length() > 0) {
                logf("micBusyHint: " + hint);
                toast("麦克风可能被占用：" + hint);
            }
        } catch (Throwable t) {
            logf("micBusyHint FAILED: " + Log.getStackTraceString(t));
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
        // ③ 层1：启动自愈巡检（重复调用安全，内部会先 stop）
        startWatchdog();
        // ③ 层２：订阅下一次心跳（进程死了也能复活）
        HeartbeatReceiver.schedule(this, HeartbeatReceiver.INTERVAL_MS);
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
            @Override
            public void onStatus(String msg, int pct, int level) {
                // 旧接口保留兼容；新接口 onStatusKind 会带上语义类型，UI 走那条
                logf("STATUS pct=" + pct + " level=" + level + " msg=" + msg);
            }

            @Override
            public void onStatusKind(String msg, int pct, AsrEngine.StatusKind kind) {
                logf("STATUS " + kind + " pct=" + pct + " msg=" + msg);
                if (window != null) window.onEngineStatusKind(msg, pct, kind);
            }
        });
    }

    // ============================================================
    //  ③ 层1：进程内自愈巡检
    //  场景：Service 活着（进程在、通知在），但引擎已挂 / 窗口已失效。
    //  老版本只能靠用户手动去通知栏点「重启」或重开 App，这里自动兜住。
    //  安全阀：连续 3 次自愈无效就停手并提示，绝不进入死循环。
    // ============================================================
    private static final long WATCHDOG_INTERVAL_MS = 30000L;
    private static final int WATCHDOG_MAX_RECOVER = 3;
    private android.os.Handler watchdogHandler = null;
    private Runnable watchdogTask = null;
    private int watchdogRecoverCount = 0;
    private boolean watchdogStopped = false;

    private void startWatchdog() {
        try {
            stopWatchdog();
            if (watchdogHandler == null) {
                watchdogHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
            final Runnable[] holder = new Runnable[1];
            holder[0] = new Runnable() {
                @Override
                public void run() {
                    try {
                        watchdogTick();
                    } catch (Throwable t) {
                        logf("watchdog tick FAILED: " + Log.getStackTraceString(t));
                    }
                    if (!watchdogStopped && watchdogHandler != null) {
                        watchdogHandler.postDelayed(holder[0], WATCHDOG_INTERVAL_MS);
                    }
                }
            };
            watchdogTask = holder[0];
            watchdogStopped = false;
            watchdogHandler.postDelayed(watchdogTask, WATCHDOG_INTERVAL_MS);
            logf("watchdog started (interval=" + WATCHDOG_INTERVAL_MS + "ms)");
        } catch (Throwable t) {
            logf("watchdog start FAILED: " + Log.getStackTraceString(t));
        }
    }

    private void stopWatchdog() {
        watchdogStopped = true;
        if (watchdogHandler != null && watchdogTask != null) {
            try {
                watchdogHandler.removeCallbacks(watchdogTask);
            } catch (Throwable ignore) {
            }
        }
        watchdogTask = null;
    }

    /** 巡检一次：只判断「该活却没活着」这一种情况，不做任何聪明事 */
    private void watchdogTick() {
        if (!running) {
            // 用户没在跑字幕（或已 stop），巡检没有意义
            return;
        }
        if (!prefs.isEnabled()) {
            // 用户总闸关了，绝不自愈，避免把用户主动停掉的服务又拉起来
            logf("watchdog: prefs.enabled=false, self-heal skipped");
            return;
        }
        boolean engineDead = (engine == null);
        boolean winDead = (window == null) || (!window.isShowing());
        if (!engineDead && !winDead) {
            // 一切正常，清空失败计数
            if (watchdogRecoverCount != 0) {
                watchdogRecoverCount = 0;
            }
            return;
        }
        if (watchdogRecoverCount >= WATCHDOG_MAX_RECOVER) {
            logf("watchdog: recoverCount reached " + WATCHDOG_MAX_RECOVER + ", give up (engineDead=" + engineDead + ", winDead=" + winDead + ")");
            toast("字幕自愈多次失败，请手动重启服务");
            watchdogStopped = true;
            return;
        }
        watchdogRecoverCount++;
        logf("watchdog: self-heal #" + watchdogRecoverCount + " (engineDead=" + engineDead + ", winDead=" + winDead + ")");
        try {
            startCaption();
            logf("watchdog: startCaption re-entered OK");
        } catch (Throwable t) {
            logf("watchdog: startCaption re-enter FAILED: " + Log.getStackTraceString(t));
        }
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
        // 用户主动关掉窗口 = 明确不再需要字幕。
        // 必须把 enabled 落盘清掉，否则系统 sticky 拉起后读到 true 又会重建 → 死循环。
        // 加调用栈：以后要一眼看清到底是谁触发的（防止再次出现“无人操作却自己关掉”）。
        logf("onClose TRIGGERED, stack=" + android.util.Log.getStackTraceString(new Throwable("onClose")));
        logf("onClose: user closed window, clear enabled & stopSelf");
        prefs.setEnabled(false);
        userStarted = false;
        prefs.setStickyCount(0);
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

    /** 服务侧取证日志：统一委托 Lg（写 files/svc.log，超 256KB 自动轮转） */
    private void logf(String msg) {
        Lg.svc(this, msg);
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
        boolean showing = window != null && window.isShowing() && !hiddenByUser;
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
        Intent showHide = new Intent(this, CaptionService.class);
        showHide.setAction(ACTION_TOGGLE_SHOW);
        PendingIntent piShowHide = PendingIntent.getService(
                this, 3, showHide, PendingIntent.FLAG_UPDATE_CURRENT | PI_IMMUTABLE);
        Intent restart = new Intent(this, CaptionService.class);
        restart.setAction(ACTION_RESTART);
        PendingIntent piRestart = PendingIntent.getService(
                this, 4, restart, PendingIntent.FLAG_UPDATE_CURRENT | PI_IMMUTABLE);
        Intent copy = new Intent(this, CaptionService.class);
        copy.setAction(ACTION_COPY);
        PendingIntent piCopy = PendingIntent.getService(
                this, 5, copy, PendingIntent.FLAG_UPDATE_CURRENT | PI_IMMUTABLE);

        String text;
        if (locked && showing) {
            text = "已锁定（触摸穿透）· 解锁请点下方按钮";
        } else if (locked && !showing) {
            text = "已锁定且窗口隐藏 · 点「显示」唤回";
        } else if (!locked && !showing) {
            text = "窗口已隐藏 · 点「显示」唤回";
        } else {
            text = "未锁定，可拖动调整";
        }

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("实时字幕运行中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentIntent(piOpen)
                .setOngoing(true)
                .setPriority(Notification.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_lock_lock,
                        locked ? "解锁" : "锁定", piLock)
                .addAction(android.R.drawable.ic_menu_view,
                        showing ? "隐藏" : "显示", piShowHide)
                // v1.12 功能8：复制放第 3 位，保证折叠态三个 Action 里能看到
                .addAction(android.R.drawable.ic_menu_save,
                        "复制", piCopy)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "停止", piStop)
                .addAction(android.R.drawable.ic_menu_rotate,
                        "重启", piRestart);
        return b.build();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}