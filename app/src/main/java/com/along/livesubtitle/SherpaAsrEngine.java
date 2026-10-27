package com.along.livesubtitle;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 基于 sherpa-onnx 的离线流式语音识别引擎（麦克风输入）。
 * API 依据 sherpa-onnx master 分支 java-api 的 Builder 风格写法。
 */
public class SherpaAsrEngine implements AsrEngine {

    private static final String TAG = "SherpaAsrEngine";

    /** 错误码 */
    private static final int ERR_MODEL = 2001;
    private static final int ERR_AUDIO = 2002;
    private static final int ERR_RUNTIME = 2003;

    /** assets / filesDir 下的模型目录名 */
    private static final String MODEL_DIR = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20";

    private static final String ENCODER = "encoder-epoch-99-avg-1.int8.onnx";
    private static final String DECODER = "decoder-epoch-99-avg-1.onnx";
    private static final String JOINER = "joiner-epoch-99-avg-1.int8.onnx";
    private static final String TOKENS = "tokens.txt";

    private static final int SAMPLE_RATE = 16000;
    /** 每次读取的音频时长（秒） */
    private static final float INTERVAL_SEC = 0.1f;
    /** endpoint 后补的静音时长（秒），用于捞回尾字 */
    private static final float TAIL_PADDING_SEC = 0.8f;

    private final Context context;
    private AsrEngine.Callback callback;

    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private AudioRecord audioRecord;
    private Thread worker;

    private volatile boolean running = false;
    private volatile boolean ready = false;

    public SherpaAsrEngine(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public void start(AsrEngine.Callback cb) {
        if (running) return;
        this.callback = cb;
        running = true;
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                doWork();
            }
        }, "sherpa-asr");
        worker.start();
    }

    @Override
    public void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null && t != Thread.currentThread()) {
            try {
                t.join(2000);
            } catch (InterruptedException ignore) {
            }
        }
        releaseAudio();
        releaseRecognizer();
    }

    @Override
    public void release() {
        stop();
        callback = null;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getSourceMode() {
        return 0; // 0 = 麦克风
    }

    // ==================== 内部实现 ====================

    private void doWork() {
        try {
            if (!ready) {
                try {
                    ensureModel();
                } catch (Throwable e) {
                    Log.e(TAG, "ensureModel error", e);
                    emitError(ERR_MODEL, "模型准备失败：" + e.getMessage());
                    running = false;
                    return;
                }
                try {
                    initRecognizer();
                } catch (Throwable e) {
                    Log.e(TAG, "initRecognizer error", e);
                    emitError(ERR_MODEL, "识别器初始化失败：" + e.getMessage());
                    running = false;
                    return;
                }
                ready = true;
            }

            if (!openAudio()) {
                emitError(ERR_AUDIO, "麦克风打开失败，请检查录音权限");
                running = false;
                return;
            }

            loop();

        } catch (Throwable e) {
            Log.e(TAG, "doWork error", e);
            emitError(ERR_RUNTIME, "识别引擎异常：" + e.getMessage());
        } finally {
            running = false;
            releaseAudio();
        }
    }

    /** 确保模型已复制到 filesDir（首次启动时复制，之后跳过） */
    private void ensureModel() throws Exception {
        File target = new File(context.getFilesDir(), MODEL_DIR);
        File encoder = new File(target, ENCODER);
        File decoder = new File(target, DECODER);
        File joiner = new File(target, JOINER);
        File tokens = new File(target, TOKENS);

        if (encoder.exists() && decoder.exists() && joiner.exists() && tokens.exists()
                && encoder.length() > 1024) {
            return;
        }
        if (!target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.mkdirs();
        }
        copyAssetsDir(MODEL_DIR, target);
    }

    /** 递归复制 assets 子目录到目标目录 */
    private void copyAssetsDir(String assetPath, File destDir) throws Exception {
        AssetManager am = context.getAssets();
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            copyAssetFile(assetPath, destDir);
            return;
        }
        if (!destDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            destDir.mkdirs();
        }
        for (String child : children) {
            String childAssetPath = assetPath + "/" + child;
            File childDest = new File(destDir, child);
            String[] grand = am.list(childAssetPath);
            if (grand != null && grand.length > 0) {
                copyAssetsDir(childAssetPath, childDest);
            } else {
                copyAssetFile(childAssetPath, destDir);
            }
        }
    }

    private void copyAssetFile(String assetPath, File destDir) throws Exception {
        String name = assetPath.substring(assetPath.lastIndexOf('/') + 1);
        File out = new File(destDir, name);
        InputStream in = null;
        OutputStream os = null;
        try {
            in = context.getAssets().open(assetPath);
            os = new FileOutputStream(out);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
            }
            os.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(os);
        }
    }

    /** 按 master 分支 Builder 风格构造识别器 */
    private void initRecognizer() {
        String modelDir = new File(context.getFilesDir(), MODEL_DIR).getAbsolutePath();

        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(modelDir + "/" + ENCODER)
                .setDecoder(modelDir + "/" + DECODER)
                .setJoiner(modelDir + "/" + JOINER)
                .build();

        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(modelDir + "/" + TOKENS)
                .setModelType("zipformer")
                .setNumThreads(2)
                .setDebug(false)
                .setProvider("cpu")
                .build();

        EndpointRule rule1 = EndpointRule.builder()
                .setMustContainNonSilence(false)
                .setMinTrailingSilence(2.4f)
                .setMinUtteranceLength(0f)
                .build();
        EndpointRule rule2 = EndpointRule.builder()
                .setMustContainNonSilence(true)
                .setMinTrailingSilence(1.2f)
                .setMinUtteranceLength(0f)
                .build();
        EndpointRule rule3 = EndpointRule.builder()
                .setMustContainNonSilence(false)
                .setMinTrailingSilence(0f)
                .setMinUtteranceLength(20f)
                .build();
        EndpointConfig endpointConfig = EndpointConfig.builder()
                .setRule1(rule1)
                .setRule2(rule2)
                .setRule3(rule3)
                .build();

        OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                .setOnlineModelConfig(modelConfig)
                .setEndpointConfig(endpointConfig)
                .setEnableEndpoint(true)
                .setDecodingMethod("greedy_search")
                .build();

        recognizer = new OnlineRecognizer(config);
        stream = recognizer.createStream();
        Log.i(TAG, "recognizer ready, modelDir=" + modelDir);
    }

    private boolean openAudio() {
        try {
            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            int bufSize = Math.max(minBuf * 2, SAMPLE_RATE * 2);
            audioRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
            if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
                releaseAudio();
                return false;
            }
            audioRecord.startRecording();
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "openAudio error", e);
            releaseAudio();
            return false;
        }
    }

    private void loop() {
        int chunk = (int) (SAMPLE_RATE * INTERVAL_SEC); // 1600
        short[] buffer = new short[chunk];
        float[] samples = new float[chunk];

        while (running) {
            int n = audioRecord.read(buffer, 0, chunk);
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) {
                    break;
                }
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ignore) {
                }
                continue;
            }

            emitVolume(calcLevel(buffer, n));

            for (int i = 0; i < n; i++) {
                samples[i] = buffer[i] / 32768.0f;
            }
            float[] input = new float[n];
            System.arraycopy(samples, 0, input, 0, n);

            try {
                stream.acceptWaveform(input, SAMPLE_RATE);
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream);
                }
                String text = recognizer.getResult(stream).getText();
                boolean isEndpoint = recognizer.isEndpoint(stream);

                if (isEndpoint) {
                    // 补一段静音，把最后几个字解出来再定格（官方同款做法）
                    int pad = (int) (TAIL_PADDING_SEC * SAMPLE_RATE);
                    float[] tail = new float[pad];
                    stream.acceptWaveform(tail, SAMPLE_RATE);
                    while (recognizer.isReady(stream)) {
                        recognizer.decode(stream);
                    }
                    String tailText = recognizer.getResult(stream).getText();
                    if (tailText != null && tailText.length() >= text.length()) {
                        text = tailText;
                    }
                    if (text != null && text.length() > 0) {
                        emitResult(text, true);
                    }
                    recognizer.reset(stream);
                } else if (text != null && text.length() > 0) {
                    emitResult(text, false);
                }
            } catch (Throwable e) {
                Log.e(TAG, "decode error", e);
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    /** 计算音量 0.0~1.0 */
    private float calcLevel(short[] buf, int n) {
        if (n <= 0) return 0f;
        long sum = 0;
        for (int i = 0; i < n; i++) {
            sum += (long) buf[i] * buf[i];
        }
        double rms = Math.sqrt(sum / (double) n);
        float level = (float) (rms / 32768.0);
        if (level > 1f) level = 1f;
        return level;
    }

    private void releaseAudio() {
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try {
                if (ar.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    ar.stop();
                }
            } catch (Throwable ignore) {
            }
            try {
                ar.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private void releaseRecognizer() {
        OnlineStream s = stream;
        stream = null;
        if (s != null) {
            try {
                s.release();
            } catch (Throwable ignore) {
            }
        }
        OnlineRecognizer r = recognizer;
        recognizer = null;
        if (r != null) {
            try {
                r.release();
            } catch (Throwable ignore) {
            }
        }
    }

    private void emitResult(String text, boolean isFinal) {
        AsrEngine.Callback cb = callback;
        if (cb != null) {
            try {
                cb.onResult(text, isFinal);
            } catch (Throwable e) {
                Log.e(TAG, "callback onResult error", e);
            }
        }
    }

    private void emitError(int code, String msg) {
        Log.w(TAG, "error " + code + ": " + msg);
        AsrEngine.Callback cb = callback;
        if (cb != null) {
            try {
                cb.onError(code, msg);
            } catch (Throwable e) {
                Log.e(TAG, "callback onError error", e);
            }
        }
    }

    private void emitVolume(float level) {
        AsrEngine.Callback cb = callback;
        if (cb != null) {
            try {
                cb.onVolume(level);
            } catch (Throwable ignore) {
            }
        }
    }

    private void closeQuietly(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignore) {
            }
        }
    }
}