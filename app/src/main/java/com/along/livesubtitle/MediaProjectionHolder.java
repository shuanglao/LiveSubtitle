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

    /** 同一个 resultCode+Intent 只能成功 create 一次，创建后立刻清令牌 */
    public static MediaProjection createProjection(MediaProjectionManager mgr) {
        if (mgr == null) return null;
        if (sProjection != null) return sProjection;
        int rc = sResultCode;
        Intent data = sData;
        if (data == null || rc == 0) return null;
        sResultCode = 0;
        sData = null;
        try {
            MediaProjection mp = mgr.getMediaProjection(rc, data);
            if (mp == null) return null;
            sProjection = mp;
            return mp;
        } catch (Throwable e) {
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
