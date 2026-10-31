package com.along.capprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 前台服务：Android 14 起 MediaProjection 必须在
 * foregroundServiceType=mediaProjection 的前台服务里使用。
 *
 * 采样逻辑全部在这里，结果写到 files/probe_report.txt，
 * 同时通过 broadcast 回传给 Activity 显示。
 */
public class ProbeService extends Service {

    public static final String ACTION_START = "com.along.capprobe.START";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String ACTION_REPORT = "com.along.capprobe.REPORT";
    public static final String EXTRA_REPORT = "report";

    private static final int SAMPLE_RATE = 16000;
    private static final int PROBE_SECONDS = 10;
    private static final String CH_ID = "capprobe";
    private static final int NOTI_ID = 1001;

    private volatile boolean started = false;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            Slog.w("ProbeService onStartCommand intent==null, stopSelf");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) {
            Slog.w("已在探测中，忽略重复请求");
            return START_NOT_STICKY;
        }
        started = true;

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        Slog.w("onStartCommand resultCode=" + resultCode
                + " data=" + (resultData != null));

        // 必须先在极短时间内 startForeground，带上 mediaProjection 类型
        Notification n = buildNotification("探测中…");
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTI_ID, n);
            }
            Slog.w("startForeground OK type=mediaProjection");
        } catch (Throwable t) {
            Slog.w("startForeground 失败: " + t);
            stopSelf();
            return START_NOT_STICKY;
        }

        final Intent data = resultData;
        final int rc = resultCode;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runProbe(rc, data);
                } catch (Throwable t) {
                    Slog.w("runProbe 外层异常: " + t);
                } finally {
                    started = false;
                    stopSelf();
                }
            }
        }, "probe-thread").start();

        return START_NOT_STICKY;
    }

    private void runProbe(int resultCode, Intent data) {
        MediaProjection mp = null;
        AudioRecord ar = null;
        int total = 0;
        int rounds = 0;
        int peak = 0;
        long nonzero = 0;
        long sumAbs = 0;
        String failMsg = null;

        try {
            if (data == null) {
                failMsg = "resultData 为 null（投屏授权数据丢失）";
                Slog.w(failMsg);
            } else {
                MediaProjectionManager mpm =
                        (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
                Slog.w("getMediaProjection 开始 rc=" + resultCode);
                mp = mpm.getMediaProjection(resultCode, data);
                if (mp == null) {
                    failMsg = "getMediaProjection 返回 null";
                    Slog.w(failMsg);
                } else {
                    Slog.w("getMediaProjection OK");
                    AudioPlaybackCaptureConfiguration acc =
                            new AudioPlaybackCaptureConfiguration.Builder(mp)
                                    .addMatchingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                                    .build();
                    Slog.w("CaptureConfig 构建完成");

                    int minBuf = AudioRecord.getMinBufferSize(
                            SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    int bufSize = Math.max(minBuf, SAMPLE_RATE);
                    Slog.w("minBuf=" + minBuf + " bufSize=" + bufSize);

                    ar = new AudioRecord.Builder()
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .build())
                            .setBufferSizeInBytes(bufSize * 2)
                            .setAudioPlaybackCaptureConfig(acc)
                            .build();

                    Slog.w("AudioRecord state=" + ar.getState()
                            + " (1=INITIALIZED)");

                    if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                        failMsg = "AudioRecord 未初始化 state=" + ar.getState()
                                + "（可能是系统阉割了 PlaybackCapture）";
                        Slog.w(failMsg);
                    } else {
                        ar.startRecording();
                        Slog.w("startRecording OK, 采样 " + PROBE_SECONDS + "s");

                        short[] buf = new short[1600]; // 100ms @16k
                        long deadline = SystemClock.elapsedRealtime()
                                + PROBE_SECONDS * 1000L;

                        while (SystemClock.elapsedRealtime() < deadline) {
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
                                Slog.w("read 返回错误码 " + n);
                                failMsg = "read 错误码 " + n;
                                break;
                            }
                        }
                        Slog.w("采样结束 total=" + total + " rounds=" + rounds
                                + " peak=" + peak + " nonzero=" + nonzero);
                    }
                }
            }
        } catch (Throwable t) {
            failMsg = "异常: " + t.getClass().getName() + " / " + t.getMessage();
            Slog.w("runProbe 异常: " + failMsg);
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

        String report = buildReport(verdict, failMsg, total, rounds, peak, nonzero, sumAbs);
        Slog.w("VERDICT=" + verdict);
        writeReportFile(report);
        broadcastReport(report);
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
            File f = new File(getFilesDir(), "probe_report.txt");
            FileWriter fw = new FileWriter(f, true);
            fw.write(report);
            fw.flush();
            fw.close();
            Slog.w("报告已写入 " + f.getAbsolutePath());
        } catch (Throwable t) {
            Slog.w("写报告失败: " + t);
        }
    }

    private void broadcastReport(String report) {
        try {
            Intent i = new Intent(ACTION_REPORT);
            i.setPackage(getPackageName());
            i.putExtra(EXTRA_REPORT, report);
            sendBroadcast(i);
        } catch (Throwable t) {
            Slog.w("广播失败: " + t);
        }
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "CapProbe", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, CH_ID);
        } else {
            b = new Notification.Builder(this);
        }
        b.setContentTitle("CapProbe 探测中")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true);
        return b.build();
    }
}
