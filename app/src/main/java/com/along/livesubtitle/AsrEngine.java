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

        /**
         * 带类型的状态回调（新接口）。实现类应优先调用这个版本，
         * 让 UI 无需再靠文案猜「该不该弹进度条」。
         */
        void onStatusKind(String msg, int pct, StatusKind kind);
    }

    /**
     * 状态类型：决定 UI 侧是否应该弹出/收起「准备中」进度条。
     * 用枚举替代字符串关键字匹配，文案怎么改都不影响逻辑。
     */
    enum StatusKind {
        /** 引擎准备中（下载/加载/拷贝模型），应显示进度条 */
        PREPARING,
        /** 运行时提示（已静音/已恢复/正在聆听等），绝不能弹进度条 */
        RUNTIME,
        /** 就绪，进度条必须收起 */
        READY,
        /** 错误，终态，进度条必须收起 */
        ERROR;

        /** 仅 PREPARING 需要显示进度条；其余类型必须收起 */
        public boolean showProgress() {
            return this == PREPARING;
        }

        /** 是否为终态（就绪/错误），终态必须收起进度条 */
        public boolean isTerminal() {
            return this == READY || this == ERROR;
        }
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