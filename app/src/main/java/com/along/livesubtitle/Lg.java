package com.along.livesubtitle;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 统一日志门面（v1.4 第 6 条优化）。
 *
 * 背景：改造前 CaptionService / CaptionWindow / SherpaAsrEngine 各自实现了一套
 * 开 FileOutputStream 追加写文件的 logf，代码重复多处，且无统一开关。
 * 现全部收口到这里。
 *
 *   svc() -> svc.log   服务/窗口类日志
 *   eng() -> dllog.txt 引擎内部日志
 *   err() -> dl_err.txt 下载失败专用
 *
 * 单文件超 256KB 自动截断；DEBUG=false 时彻底静默（不写盘、不调 Log）。
 *
 * 注意：Android 侧 logcat 常被系统限制（logcat -g 显示 0 B readable），
 * 所以落盘日志是唯一可靠的取证手段，DEBUG 切勿随手关死。
 */
public final class Lg {

    /** 发版前把这里置 false，则所有落盘与 logcat 输出全部静默 */
    /** v1.11 第3条：由 BuildConfig.DEBUG 自动判定 */
    private static volatile boolean DEBUG = true;
    static {
        try {
            DEBUG = BuildConfig.DEBUG;
        } catch (Throwable ignore) {
            DEBUG = true;
        }
    }

    /** 单个日志文件上限，超了截断重写 */
    private static final long MAX_BYTES = 256L * 1024L;

    private static final SimpleDateFormat TS =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private Lg() {}

    public static boolean isDebug() { return DEBUG; }

    public static void setDebug(boolean d) { DEBUG = d; }

    /**
     * v1.13 第3条：启动时按用户偏好覆盖 BuildConfig 默认值。
     * 在 CaptionService / SettingsActivity / MainActivity 入口 onCreate 调用一次即可，
     * 重复调用无副作用。
     * 注意：这是「运行期开关」，不改 Lg 的写盘逻辑，日志铁律不变——
     *      开关打开时依然写全链路日志，只是决定是否静默。
     */
    public static void initFromPrefs(Context c) {
        if (c == null) return;
        try {
            DEBUG = new Prefs(c).isDebugLog();
        } catch (Throwable ignore) {
            // 读取失败就保持原值，绝不因为开关而丢日志能力
        }
        Log.i("Lg", "debug log = " + DEBUG);
    }

    /** 服务/窗口日志，写 files/svc.log（仅 Debug 包）*/
    public static void svc(Context c, String msg) {
        if (!DEBUG) return;
        write(c, "svc.log", msg);
    }

    /** 引擎日志，写 files/dllog.txt（仅 Debug 包）*/
    public static void eng(Context c, String msg) {
        if (!DEBUG) return;
        write(c, "dllog.txt", msg);
    }

    /** 下载失败专用，写 files/dl_err.txt（永远写）*/
    public static void err(Context c, String msg) {
        write(c, "dl_err.txt", msg);
    }

    /** v1.11 第3条：关键事件，Release 包也要写，写 files/svc.log */
    public static void critical(Context c, String msg) {
        write(c, "svc.log", "[CRIT] " + msg);
    }

    /** 核心写盘；任何 IO 异常都吞掉，日志绝不影响主流程 */
    private static void write(Context c, String name, String msg) {
        // v1.11 第3条：早退已上移到 svc()/eng()，此处不再拦，
        // 否则 err()/critical() 在 Release 包会连取证记录都丢掉。
        if (c == null || msg == null) return;
        try {
            Log.d("LiveSubtitle", msg);
        } catch (Throwable ignore) {
        }
        try {
            File f = new File(c.getFilesDir(), name);
            if (f.length() > MAX_BYTES) {
                FileOutputStream cut = new FileOutputStream(f, false);
                cut.write((ts() + " --- log rotated (over 256KB) ---\n").getBytes("UTF-8"));
                cut.close();
            }
            FileOutputStream fos = new FileOutputStream(f, true);
            fos.write((ts() + " " + msg + "\n").getBytes("UTF-8"));
            fos.close();
        } catch (Throwable ignore) {
        }
    }

    private static String ts() {
        try {
            return TS.format(new Date());
        } catch (Throwable t) {
            return "??";
        }
    }
}
