package com.along.livesubtitle;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * 基于系统 SpeechRecognizer 的识别引擎。
 *
 * 设计要点：
 * 1) SpeechRecognizer 必须创建并运行在主线程（Looper.getMainLooper()）。
 * 2) 系统识别是「一句一停」的：说完一句就回调 onResults 并结束。
 *    为了实现「连续实时字幕」，每次识别结束后自动重启，形成循环。
 * 3) 出错（网络/无匹配/客户端异常）时延时重启，避免死循环打爆系统。
 * 4) 如果外部提供了 AudioRecord 音频（内录模式），本引擎不适用，
 *    改用 sherpa-onnx / Vosk 这类接受 PCM 流的引擎。
 */
public class SystemAsrEngine implements AsrEngine {

    /** 重启间隔（毫秒） */
    private static final long RESTART_DELAY_NORMAL = 150;
    private static final long RESTART_DELAY_ERROR = 1200;

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private SpeechRecognizer sr;
    private Callback cb;
    private volatile boolean running = false;
    private boolean listening = false;

    /** 防止重复排重启任务 */
    private boolean restartScheduled = false;

    /** 累计已定稿文本（用于拼接到临时结果后面） */
    private String committed = "";
    /** 最近一次临时结果 */
    private String partial = "";

    public SystemAsrEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    @Override
    public int getSourceMode() {
        return 0; // 麦克风
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public void start(final Callback callback) {
        this.cb = callback;
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (running) return;
                running = true;
                committed = "";
                partial = "";
                ensureRecognizer();
                startListening();
            }
        });
    }

    @Override
    public void stop() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                running = false;
                restartScheduled = false;
                ui.removeCallbacksAndMessages(null);
                stopListening();
                destroyRecognizer();
            }
        });
    }

    @Override
    public void release() {
        stop();
    }

    // ============================================================
    //  内部实现
    // ============================================================

    private void ensureRecognizer() {
        if (sr != null) return;
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) {
            running = false;
            if (cb != null) cb.onError(-1, "此设备不支持系统语音识别");
            return;
        }
        sr = SpeechRecognizer.createSpeechRecognizer(ctx);
        sr.setRecognitionListener(new RecognitionListener() {

            @Override
            public void onReadyForSpeech(Bundle params) {
                listening = true;
            }

            @Override
            public void onBeginningOfSpeech() {
            }

            @Override
            public void onRmsChanged(float rmsdB) {
                // rmsdB 大约 -2 ~ 10
                if (cb != null) {
                    float level = (rmsdB + 2f) / 12f;
                    if (level < 0f) level = 0f;
                    if (level > 1f) level = 1f;
                    cb.onVolume(level);
                }
            }

            @Override
            public void onBufferReceived(byte[] buffer) {
            }

            @Override
            public void onEndOfSpeech() {
                listening = false;
            }

            @Override
            public void onError(int error) {
                listening = false;
                if (!running) return;

                // 错误码见 SpeechRecognizer.ERROR_*
                String msg = errorText(error);

                // 无匹配 / 无语音 属于正常静音，不当作错误上报，直接静默重启
                if (error == SpeechRecognizer.ERROR_NO_MATCH
                        || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    scheduleRestart(RESTART_DELAY_NORMAL);
                    return;
                }

                if (cb != null) cb.onError(error, msg);
                scheduleRestart(RESTART_DELAY_ERROR);
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                ArrayList<String> list = results == null ? null
                        : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    String text = list.get(0);
                    if (text != null && text.length() > 0) {
                        committed += text;
                        partial = "";
                        if (cb != null) cb.onResult(committed, true);
                        // 定稿后清空累计，避免无限增长
                        committed = "";
                    }
                }
                if (running) scheduleRestart(RESTART_DELAY_NORMAL);
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                ArrayList<String> list = partialResults == null ? null
                        : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (list != null && !list.isEmpty()) {
                    String text = list.get(0);
                    if (text != null && text.length() > 0) {
                        partial = text;
                        if (cb != null) cb.onResult(text, false);
                    }
                }
            }

            @Override
            public void onEvent(int eventType, Bundle params) {
            }
        });
    }

    private void startListening() {
        if (sr == null || !running) return;
        try {
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
            // 长静音容忍：尽量延长单次会话时长
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L);
            i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 8000L);
            sr.startListening(i);
            listening = true;
        } catch (Throwable t) {
            if (cb != null) cb.onError(-2, "启动识别失败: " + t.getMessage());
            scheduleRestart(RESTART_DELAY_ERROR);
        }
    }

    private void stopListening() {
        if (sr == null) return;
        try {
            sr.stopListening();
        } catch (Throwable ignore) {
        }
        listening = false;
    }

    private void destroyRecognizer() {
        if (sr == null) return;
        try {
            sr.cancel();
            sr.destroy();
        } catch (Throwable ignore) {
        }
        sr = null;
        listening = false;
    }

    private void scheduleRestart(final long delay) {
        if (!running || restartScheduled) return;
        restartScheduled = true;
        ui.postDelayed(new Runnable() {
            @Override
            public void run() {
                restartScheduled = false;
                if (!running) return;
                // 每次重启前重建识别器，规避系统识别器的状态卡死
                destroyRecognizer();
                ensureRecognizer();
                startListening();
            }
        }, delay);
    }

    private static String errorText(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO: return "录音错误";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "缺少录音权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "无匹配结果";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别器忙";
            case SpeechRecognizer.ERROR_SERVER: return "服务器错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "长时间无语音";
            default: return "未知错误(" + error + ")";
        }
    }
}