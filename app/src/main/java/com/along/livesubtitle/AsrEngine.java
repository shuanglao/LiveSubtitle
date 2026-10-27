package com.along.livesubtitle;

/**
 * ASR 引擎统一接口。
 * 第一版用 SystemAsrEngine（系统 SpeechRecognizer）实现，
 * 后续换成 sherpa-onnx 时只需新增一个实现类，上层无需改动。
 */
public interface AsrEngine {

    /** 识别结果回调 */
    interface Callback {
        /**
         * @param text    当前识别到的文本
         * @param isFinal true=这句说完了（已定稿）；false=还在说（临时结果）
         */
        void onResult(String text, boolean isFinal);

        /** 发生错误（引擎会尝试自动重启） */
        void onError(int code, String msg);

        /** 音量变化 0.0~1.0，用于绘制音量指示 */
        void onVolume(float level);
    }

    /** 开始识别 */
    void start(Callback cb);

    /** 停止识别 */
    void stop();

    /** 释放资源 */
    void release();

    /** 引擎是否正在运行 */
    boolean isRunning();

    /** 当前音频源模式：0=麦克风 1=内录 */
    int getSourceMode();
}