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
        /**
         * 准备阶段结构化状态（下载模型 / 拷贝模型 / 加载引擎 等）。
         * @param msg   人话描述，直接显示给用户
         * @param pct   进度百分比 0~100；-1 表示进度未知（转圈/不确定态）
         * @param level 0=普通信息 1=就绪完成 2=错误
         */
        void onStatus(String msg, int pct, int level);
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