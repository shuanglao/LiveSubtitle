package com.along.livesubtitle;

import android.content.Intent;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;

/**
 * v1.16: 跨组件传递屏幕捕获授权令牌的小持有区。
 * Activity 拿到 resultCode + Intent 后交给 Service 去 getMediaProjection()。
 * Intent 作为 Parcelable 在部分 ColorOS 版本跨组件传递会被剥掉，
 * 所以用进程内静态持有，Activity 写、Service 读。
 */
public final class MediaProjectionHolder {

    private static volatile int sResultCode = 0;
    private static volatile Intent sData = null;
    private static volatile MediaProjection sProjection = null;

    private MediaProjectionHolder() {
    }

    public static void put(int resultCode, Intent data) {
        sResultCode = resultCode;
        sData = (data == null) ? null : new Intent(data);
        // v1.16-alpha5 埋点：确认授权回调真的写进来了
        log("[holder] put rc=" + resultCode
                + " data=" + (data != null)
                + " hasToken=" + hasToken()
                + " dataAction=" + (data == null ? "null" : data.getAction()));
    }

    /** v1.16-alpha5：埋点用 Context（App 启动时注入；为空则只打 logcat） */
    private static volatile android.content.Context CTX = null;

    public static void attachContext(android.content.Context c) {
        CTX = (c == null) ? null : c.getApplicationContext();
    }

    private static void log(String msg) {
        android.content.Context c = CTX;
        if (c != null) {
            Lg.svc(c, msg);
        } else {
            android.util.Log.i("LiveSubtitle", msg);
        }
    }

    public static boolean hasToken() {
        return sData != null && sResultCode != 0;
    }

    public static boolean hasProjection() {
        return sProjection != null;
    }

    public static MediaProjection getProjection() {
        return sProjection;
    }

    /**
     * 兑换 MediaProjection 实体。
     * v1.16-alpha5 修复：原实现「先清令牌再 getMediaProjection」，一旦系统抛异常
     * （ColorOS 上初始化未完成时常见），令牌被清空且不回滚 → 永久丢失、再也拿不到。
     * 现改为「先创建成功，再清令牌」，失败时保留令牌可重试。
     */
    public static MediaProjection createProjection(MediaProjectionManager mgr) {
        if (mgr == null) { log("[holder] createProjection SKIP mgr=null"); return null; }
        if (sProjection != null) { log("[holder] createProjection SKIP already=" + sProjection); return sProjection; }
        int rc = sResultCode;
        Intent data = sData;
        if (data == null || rc == 0) {
            log("[holder] createProjection SKIP no-token rc=" + rc + " data=" + (data != null));
            return null;
        }
        try {
            MediaProjection mp = mgr.getMediaProjection(rc, data);
            if (mp == null) {
                log("[holder] createProjection FAILED mgr returned null (token kept for retry)");
                return null;
            }
            // 只有成功才清令牌（同一 resultCode+Intent 只能用一次）
            sResultCode = 0;
            sData = null;
            sProjection = mp;
            log("[holder] createProjection OK mp=" + mp);
            return mp;
        } catch (Throwable e) {
            log("[holder] createProjection EXCEPTION " + e + " (token kept for retry)");
            return null;
        }
    }

    /** 停止字幕/退出时释放，避免通知栏一直挂着「正在录制或投放」 */
    public static void release() {
        MediaProjection mp = sProjection;
        sProjection = null;
        sResultCode = 0;
        sData = null;
        if (mp != null) {
            try { mp.stop(); } catch (Throwable ignore) {}
        }
    }
}
