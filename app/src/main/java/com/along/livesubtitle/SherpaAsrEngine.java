package com.along.livesubtitle;

import android.content.Context;
import android.content.res.AssetManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
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
    /** 静音判定阈值（RMS 归一化后），约 -38dB：低于此值视为没人说话 */
    private static final float SILENCE_LEVEL = 0.012f;
    /** 连续静音超过这么多秒才进入暂停（避免句中换气被误判）
     *  v1.14: 6s -> 12s，视频对白间隙常达数秒，6s 会误暂停丢音频 */
    private static final float SILENCE_HOLD_SEC = 12.0f;
    /** 当前是否处于「静音暂停」态：停止向识别器喂数据以省电 */
    private volatile boolean pausedBySilence = false;
    /** 已连续静音的秒数累计 */
    private float silenceAccum = 0f;
    private static final float TAIL_PADDING_SEC = 0.8f;

    /** v1.14: AudioRecord 缓冲区倍数（默认仅 1 秒，decode 一慢就丢帧） */
    private static final int AUDIO_BUF_SECONDS = 4;
    /** v1.14: 连续 read 失败多少次后重建 AudioRecord（防线程静默死亡） */
    private static final int READ_FAIL_LIMIT = 20;
    /** v1.14: 单次 read 耗时超过该毫秒数则记日志（异常阻塞告警） */
    private static final long READ_SLOW_MS = 500;
    /** v1.14: 单次 decode 耗时超过该毫秒数则记日志 */
    private static final long DECODE_SLOW_MS = 500;
    /** v1.14: 两次回调间隔超过该毫秒数则记日志（断流告警） */
    private static final long GAP_WARN_MS = 500;

    private final Context context;
    private final Prefs prefs;
    private AsrEngine.Callback callback;

    private OnlineRecognizer recognizer;
    private OnlineStream stream;
    private AudioRecord audioRecord;
    private Thread worker;

    /** 麦克风增益（1.0 = 原始音量），由 Prefs.micGainPct 同步 */
    private volatile float micGain = 1.0f;
    /** 实际使用的音频源，用于日志与诊断 */
    private volatile int usedAudioSource = -1;

    private volatile boolean running = false;
    private volatile boolean ready = false;

    public SherpaAsrEngine(Context context) {
        this.context = context.getApplicationContext();
        this.prefs = new Prefs(this.context);
        this.micGain = prefs.getMicGainPct() / 100f;
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
            logf("doWork enter ready=" + ready);
            if (!ready) {
                try {
                    logf("ensureModel BEGIN");
                    ensureModel();
                    logf("ensureModel OK");
                } catch (Throwable e) {
                    Log.e(TAG, "ensureModel error", e);
                    emitError(ERR_MODEL, "模型准备失败：" + e.getMessage());
                    running = false;
                    return;
                }
                try {
                    logf("initRecognizer BEGIN");
                    initRecognizer();
                    logf("initRecognizer OK");
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

    /**
     * 确保模型文件已就绪：优先使用 filesDir 中已下载的模型；
     * 若 APK 内仍有内嵌 assets 模型则解压；否则首次启动联网下载。
     * 说明：模型约 190MB，内嵌进 APK 会使安装 dexopt 阶段内存峰值过高，
     * 被 LMK 杀掉导致安装永远无法完成，故 APK 内不再携带模型。
     */
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

        boolean hasAsset = false;
        try {
            String[] children = context.getAssets().list(MODEL_DIR);
            hasAsset = children != null && children.length > 0;
        } catch (Throwable ignore) {
        }
        if (hasAsset) {
            emitStatus("\u6b63\u5728\u91ca\u653e\u5185\u7f6e\u6a21\u578b\uff0c\u8bf7\u7a0d\u5019\u2026");
            copyAssetsDir(MODEL_DIR, target);
            return;
        }

        emitStatus("首次启动需下载语音模型（约 190MB），预计 1-3 分钟，请勿退出…");
        downloadModel(target);
    }

    /**
     * 模型下载基址列表（按优先级尝试）。
     * 国内直连 huggingface.co 基本不通，故优先走 hf-mirror 镜像，
     * 官方地址放在最后作为海外网络环境下的兜底。
     */
    private static final String[] MODEL_BASE_URLS = new String[]{
            // Modelscope 国内直连，实测 1.2MB/s 且支持 Range 续传（当前主源）
            "https://www.modelscope.cn/models/pkufool/" + MODEL_DIR + "/resolve/master/",
            // Modelscope 备用 API 形式
            "https://www.modelscope.cn/api/v1/models/pkufool/" + MODEL_DIR
                    + "/repo?Revision=master&FilePath=",
            // 海外网络环境兜底
            "https://huggingface.co/csukuangfj/" + MODEL_DIR + "/resolve/main/"
    };

    /** 统一下载日志：统一委托 Lg.eng（写 filesDir/dllog.txt） */
    private void logf(String msg) {
        Lg.eng(context, msg);
    }

    /** 逐个下载模型文件，已存在且有效的跳过，支持断点续传 */
    private void downloadModel(File target) throws Exception {
        logf("=== downloadModel start, dir=" + target.getAbsolutePath()
                + ", sources=" + MODEL_BASE_URLS.length);
        String[] names = new String[]{ENCODER, DECODER, JOINER, TOKENS};
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            File out = new File(target, name);
            if (out.exists() && out.length() > 1024) {
                continue;
            }
            File part = new File(target, name + ".part");
            long startAt = part.exists() ? part.length() : 0L;
            emitStatus("\u4e0b\u8f7d\u6a21\u578b " + (i + 1) + "/" + names.length + "\uff1a" + name);
            Exception lastErr = null;
            boolean ok = false;
            for (int u = 0; u < MODEL_BASE_URLS.length; u++) {
                String base = MODEL_BASE_URLS[u];
                logf("try [" + (u + 1) + "/" + MODEL_BASE_URLS.length + "] " + name
                        + " resume@" + (part.exists() ? part.length() : 0L) + " from " + base);
                try {
                    long at = part.exists() ? part.length() : 0L;
                    long expected = downloadOne(base + name, part, at);
                    long actual = part.exists() ? part.length() : 0L;
                    if (expected > 0 && actual != expected) {
                        // 服务端声明的总长与实际落盘不符：丢掉残片，换源重下
                        logf("BAD " + name + " expected=" + expected + " actual=" + actual);
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        throw new java.io.IOException("incomplete " + name
                                + " " + actual + "/" + expected);
                    }
                    if (actual > 1024) {
                        ok = true;
                        logf("ok " + name + " bytes=" + actual
                                + (expected > 0 ? " (verified)" : " (len unknown)"));
                        break;
                    } else {
                        logf("empty " + name + " bytes=" + actual);
                    }
                } catch (Exception e) {
                    lastErr = e;
                    String why = e.getClass().getSimpleName() + ": " + e.getMessage();
                    Log.e(TAG, "download failed from " + base + " : " + why);
                    // 把真实失败原因落到文件（统一委托 Lg.err）
                    Lg.err(context, "[" + (u + 1) + "/" + MODEL_BASE_URLS.length + "] " + base + "  " + why);
                    String shortWhy = why.length() > 26 ? why.substring(0, 26) : why;
                    emitStatus("\u5931\u8d25" + (u + 1) + "/" + MODEL_BASE_URLS.length
                            + "\uff1a" + shortWhy);
                }
            }
            if (!ok) {
                throw new java.io.IOException("\u6a21\u578b\u4e0b\u8f7d\u5931\u8d25\uff1a" + name
                        + (lastErr != null ? " (" + lastErr.getMessage() + ")" : ""));
            }
            if (part.exists() && part.length() > 1024) {
                //noinspection ResultOfMethodCallIgnored
                if (out.exists()) out.delete();
                if (!part.renameTo(out)) {
                    copyFile(part, out);
                    //noinspection ResultOfMethodCallIgnored
                    part.delete();
                }
            }
        }
        Log.i(TAG, "model download finished");
    }

    /** 宽松 SSL 上下文（仅用于下载公开模型文件，不涉及任何账号凭据） */
    private static javax.net.ssl.SSLSocketFactory LENIENT_FACTORY;
    private static javax.net.ssl.HostnameVerifier LENIENT_HOSTS;

    private static synchronized void initLenientSsl() {
        if (LENIENT_FACTORY != null) return;
        try {
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                    new javax.net.ssl.X509TrustManager() {
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                                       String authType) {
                        }

                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                                       String authType) {
                        }

                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[0];
                        }
                    }
            };
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAll, new java.security.SecureRandom());
            LENIENT_FACTORY = sc.getSocketFactory();
            LENIENT_HOSTS = new javax.net.ssl.HostnameVerifier() {
                public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                    return true;
                }
            };
        } catch (Throwable e) {
            Log.e(TAG, "initLenientSsl failed: " + e.getMessage());
        }
    }

    /** 单文件下载，startAt>0 时用 Range 续传 */
    private long downloadOne(String urlStr, File dest, long startAt) throws Exception {
        return downloadOneInner(urlStr, dest, startAt);
    }

    /** 实际下载实现 */
    private long downloadOneInner(String urlStr, File dest, long startAt) throws Exception {
        java.net.HttpURLConnection conn = null;
        InputStream in = null;
        OutputStream os = null;
        try {
            java.net.URL url = new java.net.URL(urlStr);
            conn = (java.net.HttpURLConnection) url.openConnection();
            // 逐连接设置宽松 SSL，避免污染全局 HTTPS 栈
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                initLenientSsl();
                javax.net.ssl.HttpsURLConnection https =
                        (javax.net.ssl.HttpsURLConnection) conn;
                if (LENIENT_FACTORY != null) {
                    https.setSSLSocketFactory(LENIENT_FACTORY);
                }
                if (LENIENT_HOSTS != null) {
                    https.setHostnameVerifier(LENIENT_HOSTS);
                }
            }
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) LiveSubtitle/1.0");
            conn.setRequestProperty("Accept", "*/*");
            if (startAt > 0) {
                conn.setRequestProperty("Range", "bytes=" + startAt + "-");
            }
            int code = conn.getResponseCode();
            String ctype = conn.getContentType();
            logf("resp code=" + code + " type=" + ctype
                    + " len=" + conn.getContentLengthLong() + " url=" + urlStr);
            if (ctype != null && ctype.toLowerCase().contains("text/html")) {
                // 镜像站失效时常返回 200 + 空 HTML 页，必须当作失败，否则会误判成功
                throw new java.io.IOException("bad mirror (html) " + urlStr);
            }
            if (code == 206) {
                os = new FileOutputStream(dest, true);
            } else if (code == 200) {
                startAt = 0L;
                os = new FileOutputStream(dest, false);
            } else {
                throw new java.io.IOException("HTTP " + code + " for " + urlStr);
            }
            in = conn.getInputStream();
            byte[] buf = new byte[65536];
            long total = conn.getContentLengthLong() + startAt;
            long done = startAt;
            long lastEmit = 0L;
            int n;
            while ((n = in.read(buf)) > 0) {
                os.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastEmit > 800) {
                    lastEmit = now;
                    int pct = total > 0 ? (int) (done * 100 / total) : -1;
                    emitStatus("正在下载语音模型 "
                            + (done / 1048576) + "MB / " + (total / 1048576) + "MB",
                            pct, 0);
                }
            }
            os.flush();
            return total;
        } finally {
            closeQuietly(in);
            closeQuietly(os);
            if (conn != null) conn.disconnect();
        }
    }

    private void copyFile(File src, File dst) throws Exception {
        InputStream in = null;
        OutputStream os = null;
        try {
            in = new java.io.FileInputStream(src);
            os = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(os);
        }
    }

    /** 向界面回报准备阶段状态（无百分比）*/
    private void emitStatus(String msg) {
        emitStatus(msg, -1, 0);
    }
    /** 向界面回报结构化状态：文案 + 百分比 + 级别 */
    private void emitStatus(String msg, int pct, int level) {
        Log.i(TAG, "status[" + pct + "%," + level + "]: " + msg);
        AsrEngine.Callback cb = callback;
        if (cb != null) {
            // 同步走新接口：由引擎自己声明“这是什么状态”，UI 不再猜文案
            AsrEngine.StatusKind kind = kindOf(level, pct);
            try {
                cb.onStatusKind(msg, pct, kind);
            } catch (Throwable ignore) {
            }
            try {
                cb.onStatus(msg, pct, level);
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * 把旧的三元 level/pct 映射为语义化状态类型。
     * level=1 就绪、level=2 错误；level=0 时按 pct 判定是“准备中”还是“运行时提示”。
     */
    private static AsrEngine.StatusKind kindOf(int level, int pct) {
        if (level == 1) return AsrEngine.StatusKind.READY;
        if (level == 2) return AsrEngine.StatusKind.ERROR;
        // level == 0：带百分比 === 在下载/加载；不带百分比 === 运行时提示（静音/恢复等）
        return pct >= 0 ? AsrEngine.StatusKind.PREPARING : AsrEngine.StatusKind.RUNTIME;
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

    /**
     * 按 sherpa-onnx AAR 真实签名构造识别器。
     * 注意：发布版 AAR 由 Kotlin 编译（OnlineRecognizer.kt），没有 builder()，
     * 只有无参构造器 + setter，以及 OnlineRecognizer(AssetManager, config)。
     * createStream 只有带参重载，传 "" 即默认。
     */
    private void initRecognizer() {
        logf("[init] step1 System.loadLibrary(sherpa-onnx-jni)");
        try {
            System.loadLibrary("sherpa-onnx-jni");
            logf("[init] step1 loadLibrary OK");
        } catch (Throwable t) {
            logf("[init] step1 loadLibrary FAILED: " + t);
            throw new RuntimeException("loadLibrary failed", t);
        }
        logf("[init] step2 build configs");
        String modelDir = new File(context.getFilesDir(), MODEL_DIR).getAbsolutePath();

        OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
        transducer.setEncoder(modelDir + "/" + ENCODER);
        transducer.setDecoder(modelDir + "/" + DECODER);
        transducer.setJoiner(modelDir + "/" + JOINER);

        OnlineModelConfig modelConfig = new OnlineModelConfig();
        modelConfig.setTransducer(transducer);
        modelConfig.setTokens(modelDir + "/" + TOKENS);
        modelConfig.setModelType("zipformer");
        modelConfig.setNumThreads(2);
        // v1.14: 必须关闭 native debug 日志！开着会每帧往 logcat 狂写，
        // 而本机 logcat 无人消费（缓冲区 0B readable），写入端被反压 →
        // decode 线程被拖住 → 周期性卡壳。这是「卡一下-恢复-再卡」的主因。
        modelConfig.setDebug(false);
        modelConfig.setProvider("cpu");

        EndpointRule rule1 = new EndpointRule(false, 2.4f, 0f);
        EndpointRule rule2 = new EndpointRule(true, 1.2f, 0f);
        EndpointRule rule3 = new EndpointRule(false, 0f, 20f);
        EndpointConfig endpointConfig = new EndpointConfig();
        endpointConfig.setRule1(rule1);
        endpointConfig.setRule2(rule2);
        endpointConfig.setRule3(rule3);

        OnlineRecognizerConfig config = new OnlineRecognizerConfig();
        FeatureConfig featConfig = new FeatureConfig();
        featConfig.setSampleRate(SAMPLE_RATE);
        featConfig.setFeatureDim(80);
        featConfig.setDither(0.0f);
        config.setFeatConfig(featConfig);
        config.setModelConfig(modelConfig);
        config.setEndpointConfig(endpointConfig);
        config.setEnableEndpoint(true);
        config.setDecodingMethod("greedy_search");

        logf("[init] step3a verify model files exist");
        String[] musts = {ENCODER, DECODER, JOINER, TOKENS};
        boolean allOk = true;
        for (String f : musts) {
            File mf = new File(modelDir, f);
            logf("[init]   " + f + " exists=" + mf.exists() + " len=" + mf.length());
            if (!mf.exists() || mf.length() == 0) allOk = false;
        }
        if (!allOk) throw new RuntimeException("model files missing");

        logf("[init] step3b new OnlineRecognizer(null, config) -> newFromFile 路径");
        long t0 = System.currentTimeMillis();
        recognizer = new OnlineRecognizer(null, config);
        logf("[init] step3b ctor returned in " + (System.currentTimeMillis()-t0) + "ms");

        logf("[init] step4 createStream");
        stream = recognizer.createStream("");
        logf("[init] createStream OK");
        Log.i(TAG, "recognizer ready, modelDir=" + modelDir);
    }

    private boolean openAudio() {
        // 每次开麦都重新读一遍设置，改完设置下次开麦即生效
        micGain = prefs.getMicGainPct() / 100f;
        boolean aec = prefs.isAecEnabled();
        int primary = aec ? MediaRecorder.AudioSource.VOICE_COMMUNICATION
                          : MediaRecorder.AudioSource.MIC;
        int backup = aec ? MediaRecorder.AudioSource.MIC
                         : MediaRecorder.AudioSource.VOICE_COMMUNICATION;

        // 首选源
        if (tryOpenAudio(primary)) {
            usedAudioSource = primary;
            logf("[audio] opened source=" + srcName(primary)
                    + " aec=" + aec + " gain=" + micGain
                    + "(" + prefs.getMicGainPct() + "%)");
            return true;
        }
        logf("[audio] primary source FAILED: " + srcName(primary));

        // 兜底换源（部分机型 VOICE_COMMUNICATION 初始化失败）
        if (tryOpenAudio(backup)) {
            usedAudioSource = backup;
            logf("[audio] fallback opened source=" + srcName(backup)
                    + " gain=" + micGain);
            return true;
        }
        logf("[audio] backup source FAILED: " + srcName(backup));

        // 最后兜底：MIC（最原始的源，几乎必然可用）
        if (primary != MediaRecorder.AudioSource.MIC && backup != MediaRecorder.AudioSource.MIC
                && tryOpenAudio(MediaRecorder.AudioSource.MIC)) {
            usedAudioSource = MediaRecorder.AudioSource.MIC;
            logf("[audio] last-resort opened source=MIC gain=" + micGain);
            return true;
        }

        logf("[audio] ALL SOURCES FAILED, audio unavailable");
        releaseAudio();
        return false;
    }

    /** 尝试用指定音频源打开 AudioRecord，成功返回 true */
    private boolean tryOpenAudio(int source) {
        try {
            releaseAudio();
            int minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                logf("[audio] getMinBufferSize failed for " + srcName(source) + " ret=" + minBuf);
                return false;
            }
            // v1.14: 原为 max(minBuf*2, SAMPLE_RATE*2) 仅约 1 秒缓冲，
            // decode 一旦慢于 100ms 环形缓冲就会溢出丢帧 → 识别跳字。
            // 扩到 4 秒，给 decode 足够的容错空间。
            int bufSize = Math.max(minBuf * 2, SAMPLE_RATE * 2 * AUDIO_BUF_SECONDS);
            elog("[audio] getMinBufferSize=" + minBuf + " bufSize=" + bufSize
                    + " (" + AUDIO_BUF_SECONDS + "s) src=" + srcName(source));
            AudioRecord ar = new AudioRecord(
                    source,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufSize);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                logf("[audio] AudioRecord not initialized for " + srcName(source)
                        + " state=" + ar.getState());
                try { ar.release(); } catch (Throwable ignore) {}
                return false;
            }
            ar.startRecording();
            if (ar.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                logf("[audio] startRecording did not enter RECORDING for " + srcName(source)
                        + " recState=" + ar.getRecordingState());
                try { ar.release(); } catch (Throwable ignore) {}
                return false;
            }
            audioRecord = ar;
            return true;
        } catch (Throwable e) {
            Log.e(TAG, "tryOpenAudio error " + srcName(source), e);
            logf("[audio] tryOpenAudio exception " + srcName(source) + " : "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    private static String srcName(int source) {
        if (source == MediaRecorder.AudioSource.MIC) return "MIC";
        if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) return "VOICE_COMMUNICATION";
        return "SRC_" + source;
    }

    private void loop() {
        int chunk = (int) (SAMPLE_RATE * INTERVAL_SEC); // 1600
        short[] buffer = new short[chunk];
        float[] samples = new float[chunk];

        final String tname = Thread.currentThread().getName();
        long readFail = 0;           // 连续 read 失败计数
        long totalRead = 0;          // 累计读取字节（采样点数）
        long lastCbMs = 0L;          // 上次成功 decode 的时间戳
        long lastBeatMs = System.currentTimeMillis();
        long iter = 0;               // 迭代计数
        elog("[loop] enter chunk=" + chunk + " thread=" + tname
                + " silenceHold=" + SILENCE_HOLD_SEC + "s");

        while (running) {
            iter++;
            AudioRecord ar = audioRecord;
            if (ar == null) {
                elog("[loop] audioRecord==null, try reopen");
                if (!openAudio()) {
                    elog("[loop] reopen FAILED, sleep 500ms");
                    try { Thread.sleep(500); } catch (InterruptedException ignore) {}
                    continue;
                }
                ar = audioRecord;
                if (ar == null) continue;
            }

            int n;
            long tRead0 = System.currentTimeMillis();
            try {
                n = ar.read(buffer, 0, chunk);
            } catch (Throwable e) {
                // v1.14: 原本没有这层保护，read 抛异常会让整个 worker 线程静默死亡
                elog("[loop] read EXCEPTION #" + (readFail + 1) + ": " + e);
                n = -1;
            }
            long readCost = System.currentTimeMillis() - tRead0;

            if (n <= 0) {
                readFail++;
                long gap = System.currentTimeMillis() - lastBeatMs;
                if (readFail == 1 || readFail % 50 == 0 || gap > 3000) {
                    elog("[loop] read ret " + n + " fail#" + readFail
                            + " cost=" + readCost + "ms gapSinceOk=" + gap + "ms");
                }
                if (n == AudioRecord.ERROR_INVALID_OPERATION
                        || n == AudioRecord.ERROR_BAD_VALUE
                        || readFail >= READ_FAIL_LIMIT) {
                    // v1.14: 原来直接 break 退出循环 → 线程死亡但 running 仍 true，
                    // 表现为「字幕彻底不动」且无任何报错。改为重建 AudioRecord。
                    elog("[loop] FATAL read state n=" + n + " fail=" + readFail
                            + " -> rebuild AudioRecord");
                    releaseAudio();
                    try { Thread.sleep(300); } catch (InterruptedException ignore) {}
                    if (!openAudio()) {
                        elog("[loop] rebuild openAudio FAILED, sleep 1s");
                        try { Thread.sleep(1000); } catch (InterruptedException ignore) {}
                    } else {
                        elog("[loop] rebuild openAudio OK");
                        readFail = 0;
                    }
                    lastBeatMs = System.currentTimeMillis();
                    continue;
                }
                try { Thread.sleep(20); } catch (InterruptedException ignore) {}
                continue;
            }

            // 读成功
            readFail = 0;
            lastBeatMs = System.currentTimeMillis();
            totalRead += n;
            if (readCost > READ_SLOW_MS) {
                elog("[loop] SLOW READ cost=" + readCost + "ms n=" + n
                        + " iter=" + iter + " total=" + totalRead);
            }

            // ---- 麦克风增益：放大原始 PCM，同时饱和钳制防削波 ----
            // 放在 calcLevel 之前，让音量条与静音检测都基于增益后的真实电平
            float g = micGain;
            if (g != 1.0f) {
                for (int i = 0; i < n; i++) {
                    int v = (int) (buffer[i] * g);
                    if (v > 32767) v = 32767;
                    else if (v < -32768) v = -32768;
                    buffer[i] = (short) v;
                }
            }

            float lvl = calcLevel(buffer, n);
            emitVolume(lvl);
            // ---- 静音检测：连续 SILENCE_HOLD_SEC 秒无声则暂停喂流（降功耗）----
            if (lvl < SILENCE_LEVEL) {
                silenceAccum += INTERVAL_SEC;
                if (!pausedBySilence && silenceAccum >= SILENCE_HOLD_SEC) {
                    pausedBySilence = true;
                    elog("[loop] silence pause after " + (int) silenceAccum + "s lvl=" + lvl);
                    emitStatus("已静音，等待声音…");
                }
            } else {
                silenceAccum = 0f;
                if (pausedBySilence) {
                    pausedBySilence = false;
                    elog("[loop] silence resume lvl=" + lvl);
                    // v1.14: 暂停期间音频被丢弃，stream 内部还留着暂停前的状态，
                    // 恢复时必须 reset，否则第一句话可能被错误拼接或整句丢失。
                    try {
                        if (stream != null) {
                            recognizer.reset(stream);
                            elog("[loop] stream reset after silence resume");
                        }
                    } catch (Throwable e) {
                        elog("[loop] reset after resume error: " + e);
                    }
                    emitStatus("已恢复听写");
                }
            }
            if (pausedBySilence) {
                // 暂停期间只读音频丢弃，不做解码，CPU 占用接近 0
                continue;
            }

            for (int i = 0; i < n; i++) {
                samples[i] = buffer[i] / 32768.0f;
            }
            float[] input = new float[n];
            System.arraycopy(samples, 0, input, 0, n);

            long tDec0 = System.currentTimeMillis();
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

                long decCost = System.currentTimeMillis() - tDec0;
                if (decCost > DECODE_SLOW_MS) {
                    elog("[loop] SLOW DECODE cost=" + decCost + "ms n=" + n
                            + " iter=" + iter);
                }
                long now = System.currentTimeMillis();
                if (lastCbMs > 0 && (now - lastCbMs) > GAP_WARN_MS) {
                    elog("[loop] GAP " + (now - lastCbMs) + "ms since last decode"
                            + " iter=" + iter + " lvl=" + lvl);
                }
                lastCbMs = now;
            } catch (Throwable e) {
                elog("[loop] decode EXCEPTION iter=" + iter + ": " + e);
                Log.e(TAG, "decode error", e);
                try { Thread.sleep(50); } catch (InterruptedException ignore) {}
            }
        }
        elog("[loop] exit iter=" + iter + " totalRead=" + totalRead
                + " running=" + running);
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

    /** 引擎侧落盘（统一委托 Lg.svc，带 [eng] 前缀） */
    private void elog(String msg) {
        Lg.svc(context, "[eng] " + msg);
    }
    private void emitError(int code, String msg) {
        Log.w(TAG, "error " + code + ": " + msg);
        elog("ERROR code=" + code + " msg=" + msg);
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
