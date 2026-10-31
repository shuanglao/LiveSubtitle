package com.along.capprobe;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 探测 APK：验证 AudioPlaybackCapture 能否抓到其他 App（微信）的通话/播放声音。
 * 结论写入 files/probe_report.txt，同时显示在界面上。
 */
public class MainActivity extends Activity {

    private static final int REQ_PROJ = 1001;
    private static final int REQ_AUDIO = 1002;
    private static final int SAMPLE_RATE = 16000;
    private static final int PROBE_SECONDS = 10;

    private TextView tvOut;
    private StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, pad, pad, pad);

        Button btn = new Button(this);
        btn.setText("开始探测(先开微信通话/播放,再点这里)");
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
        log("设备: " + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                + " / Android " + android.os.Build.VERSION.RELEASE);
        log("用法: 先让微信处于视频/语音通话或播放状态,再点按钮。");
        refresh();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void startProjectionRequest() {
        // 先要录音权限，再要投屏授权
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            log("申请录音权限...");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            refresh();
            return;
        }
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
        if (requestCode != REQ_AUDIO) return;
        boolean ok = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        log("录音权限: " + (ok ? "已授予" : "被拒绝"));
        refresh();
        if (ok) {
            // 权限拿到，继续走投屏授权
            startProjectionRequest();
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
        log("投屏授权 OK,开始采样 " + PROBE_SECONDS + " 秒...");
        final Intent d = data;
        new Thread(new Runnable() {
            @Override
            public void run() {
                runProbe(d);
            }
        }, "probe-thread").start();
    }

    private void runProbe(Intent data) {
        MediaProjection mp = null;
        AudioRecord ar = null;
        int total = 0;
        int rounds = 0;
        int peak = 0;
        long nonzero = 0;
        long sumAbs = 0;
        String failMsg = null;

        try {
            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            mp = mpm.getMediaProjection(Activity.RESULT_OK, data);
            if (mp == null) {
                failMsg = "getMediaProjection 返回 null";
                return;
            }

            AudioPlaybackCaptureConfiguration acc =
                    new AudioPlaybackCaptureConfiguration.Builder(mp)
                            .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                            .build();

            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf, SAMPLE_RATE);

            ar = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufSize * 2)
                    .setAudioPlaybackCaptureConfig(acc)
                    .build();

            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                failMsg = "AudioRecord 未初始化 state=" + ar.getState()
                        + " (可能是系统阉割了 PlaybackCapture)";
                return;
            }

            ar.startRecording();
            log("AudioRecord 已启动, sampleRate=" + SAMPLE_RATE + " mono PCM16");

            short[] buf = new short[1600];
            long deadline = System.currentTimeMillis() + PROBE_SECONDS * 1000L;

            while (System.currentTimeMillis() < deadline) {
                int n = ar.read(buf, 0, buf.length);
                if (n > 0) {
                    rounds++;
                    total += n;
                    for (int i = 0; i < n; i++) {
                        int v = buf[i];
                        int a = v < 0 ? -v : v;
                        if (a > peak) peak = a;
                        if (v != 0) {
                            nonzero++;
                            sumAbs += a;
                        }
                    }
                } else if (n < 0) {
                    log("read 返回错误码 " + n);
                    break;
                }
            }

        } catch (Throwable t) {
            failMsg = "异常: " + t.getClass().getName() + " / " + t.getMessage();
        } finally {
            try {
                if (ar != null) {
                    if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                        ar.stop();
                    }
                    ar.release();
                }
            } catch (Throwable ignored) {
            }
            try {
                if (mp != null) mp.stop();
            } catch (Throwable ignored) {
            }
        }

        String verdict;
        if (failMsg != null) {
            verdict = "FAIL";
        } else if (total == 0) {
            verdict = "NO_DATA";
        } else if (peak == 0) {
            verdict = "SILENT";
        } else if (nonzero * 100L / Math.max(total, 1) < 1) {
            verdict = "TINY";
        } else {
            verdict = "AUDIO_OK";
        }

        final String report = buildReport(verdict, failMsg, total, rounds, peak, nonzero, sumAbs);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                log(report);
                refresh();
            }
        });
        writeReportFile(report);
    }

    private String buildReport(String verdict, String failMsg, int total, int rounds,
                               int peak, long nonzero, long sumAbs) {
        StringBuilder sb = new StringBuilder();
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        sb.append("\n===== 探测报告 ").append(ts).append(" =====\n");
        sb.append("VERDICT: ").append(verdict).append("\n");
        if (failMsg != null) sb.append("FAIL_MSG: ").append(failMsg).append("\n");
        sb.append("totalSamples: ").append(total).append("\n");
        sb.append("readRounds: ").append(rounds).append("\n");
        sb.append("peakAbs: ").append(peak).append("  (满刻度 32767)\n");
        sb.append("nonzeroSamples: ").append(nonzero).append("\n");
        sb.append("nonzeroRatio: ").append(total > 0 ? (nonzero * 100.0 / total) : 0)
                .append("%\n");
        sb.append("avgAbsWhenNonzero: ").append(nonzero > 0 ? (sumAbs / nonzero) : 0).append("\n");
        sb.append(verdictText(verdict)).append("\n");
        sb.append("=====================\n");
        return sb.toString();
    }

    private String verdictText(String v) {
        if ("AUDIO_OK".equals(v)) {
            return "结论: 抓到有效音频! PlaybackCapture 通路可用, 可以改 LiveSubtitle 主线。";
        } else if ("TINY".equals(v)) {
            return "结论: 有微弱数据, 需要换场景再测。";
        } else if ("SILENT".equals(v)) {
            return "结论: 抓到数据但全是静音 0。通话走的是不可捕获通路。";
        } else if ("NO_DATA".equals(v)) {
            return "结论: 完全拿不到数据。系统可能阉割了 PlaybackCapture。";
        } else {
            return "结论: 探测本身失败, 看 FAIL_MSG。";
        }
    }

    private void writeReportFile(String report) {
        try {
            final File f = new File(getFilesDir(), "probe_report.txt");
            FileWriter fw = new FileWriter(f, true);
            fw.write(report);
            fw.flush();
            fw.close();
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    log("报告已写入: " + f.getAbsolutePath());
                    refresh();
                }
            });
        } catch (Throwable t) {
            final String msg = "写报告失败: " + t;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    log(msg);
                    refresh();
                }
            });
        }
    }

    private void log(String s) {
        logBuf.append(s).append("\n");
        if (tvOut != null) tvOut.setText(logBuf.toString());
    }

    private void refresh() {
        if (tvOut != null) tvOut.setText(logBuf.toString());
    }
}