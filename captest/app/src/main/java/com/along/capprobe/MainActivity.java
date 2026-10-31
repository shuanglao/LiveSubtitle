package com.along.capprobe;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 探测 APK 主界面：只负责「要权限 -> 要投屏授权 -> 启动前台服务 -> 显示报告」。
 * 真正的采样在 ProbeService 里跑（Android 14 强制要求）。
 */
public class MainActivity extends Activity {

    private static final int REQ_PROJ = 1001;
    private static final int REQ_AUDIO = 1002;
    private static final int REQ_NOTI = 1003;

    private TextView tvOut;
    private final StringBuilder logBuf = new StringBuilder();
    private BroadcastReceiver reportReceiver;
    private boolean receiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        Button btn = new Button(this);
        btn.setText("开始探测(先开抖音放声音,再点这里)");
        btn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startProjectionRequest();
            }
        });
        root.addView(btn);

        ScrollView sv = new ScrollView(this);
        tvOut = new TextView(this);
        tvOut.setTextSize(12);
        tvOut.setTextIsSelectable(true);
        sv.addView(tvOut);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(sv, lp);

        setContentView(root);

        log("=== CapProbe 启动 ===");
        log("设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE);
        log("用法: 先让抖音持续播放声音,再点上面的按钮。");

        registerReportReceiver();
        refresh();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void registerReportReceiver() {
        if (receiverRegistered) return;
        reportReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String rep = intent.getStringExtra(ProbeService.EXTRA_REPORT);
                if (rep != null) {
                    log("");
                    log("========== 服务回报 ==========");
                    log(rep);
                    refresh();
                }
            }
        };
        IntentFilter f = new IntentFilter(ProbeService.ACTION_REPORT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(reportReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(reportReceiver, f);
        }
        receiverRegistered = true;
    }

    /** 先要录音权限，再（Android13+）要通知权限，再要投屏授权 */
    private void startProjectionRequest() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            log("申请录音权限...");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            refresh();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                != PackageManager.PERMISSION_GRANTED) {
            log("申请通知权限(前台服务需要)...");
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTI);
            refresh();
            return;
        }
        askProjection();
    }

    private void askProjection() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            log("FAIL: MediaProjectionManager == null");
            refresh();
            return;
        }
        Intent it = mpm.createScreenCaptureIntent();
        try {
            startActivityForResult(it, REQ_PROJ);
            log("已发起投屏授权请求...");
        } catch (Throwable t) {
            log("FAIL: 发起授权异常 " + t);
        }
        refresh();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_AUDIO) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            log("录音权限: " + (ok ? "已授予" : "被拒绝"));
            refresh();
            if (ok) startProjectionRequest();
            return;
        }

        if (requestCode == REQ_NOTI) {
            boolean ok = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            log("通知权限: " + (ok ? "已授予" : "被拒绝(后台可能看不到通知,探测不受影响)"));
            refresh();
            askProjection();
            return;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJ) return;
        if (resultCode != RESULT_OK || data == null) {
            log("FAIL: 用户拒绝投屏授权 resultCode=" + resultCode);
            refresh();
            return;
        }
        log("投屏授权 OK,启动前台服务开始采样...");
        refresh();

        try {
            Intent svc = new Intent(this, ProbeService.class);
            svc.setAction(ProbeService.ACTION_START);
            svc.putExtra(ProbeService.EXTRA_RESULT_CODE, resultCode);
            svc.putExtra(ProbeService.EXTRA_RESULT_DATA, data);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            log("服务已启动,请立刻切回抖音让它持续出声(10 秒窗口)");
        } catch (Throwable t) {
            log("FAIL: 启动服务异常 " + t);
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (receiverRegistered && reportReceiver != null) {
            try {
                unregisterReceiver(reportReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
    }

    private void log(String s) {
        Slog.i(s);
        logBuf.append(s).append('\n');
    }

    private void refresh() {
        if (tvOut != null) tvOut.setText(logBuf.toString());
    }
}