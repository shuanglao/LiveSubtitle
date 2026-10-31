package com.along.livesubtitle;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 配置存储：字幕窗外观 + 识别模式等。
 */
public class Prefs {

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences("live_subtitle", Context.MODE_PRIVATE);
    }

    // ---------- 启停 ----------
    public boolean isEnabled() {
        return sp.getBoolean("enabled", false);
    }

    public void setEnabled(boolean v) {
        // 关键：必须用 commit() 同步落盘。
        // 之前用 apply() 异步写，服务起来后立刻被系统杀掉，
        // 异步写盘来不及完成 → 重启后读到旧值 → 服务自杀 → 死螺旋。
        sp.edit().putBoolean("enabled", v).commit();
    }

    // ---------- sticky 重启硬闸（落盘，跨进程有效） ----------
    /** 本分钟内 sticky 自动重启次数（static 在新进程里会重置为 0，必须落盘） */
    public int getStickyCount() {
        return sp.getInt("sticky_count", 0);
    }
    public void setStickyCount(int v) {
        sp.edit().putInt("sticky_count", v).commit();
    }
    /** 计数窗口起始时刻（毫秒），用于判断是否需要重置计数 */
    public long getStickyWindowAt() {
        return sp.getLong("sticky_window_at", 0L);
    }
    public void setStickyWindowAt(long v) {
        sp.edit().putLong("sticky_window_at", v).commit();
    }

    // ---------- 心跳复活（故障自恢复） ----------
    /** 是否开启心跳自恢复（默认关，用户主动开才生效） */
    public boolean isHeartbeat() {
        return sp.getBoolean("heartbeat", false);
    }

    public void setHeartbeat(boolean v) {
        sp.edit().putBoolean("heartbeat", v).commit();
    }

    // ---------- 调试日志开关（v1.13 第3条） ----------
    /**
     * 是否开启落盘调试日志。
     * 默认值跟随 BuildConfig.DEBUG：debug 包默认 true，release 包默认 false。
     * 用户可在设置页手动切换，切换后立即生效（同步 Lg.setDebug）。
     */
    public boolean isDebugLog() {
        boolean def = true;
        try {
            def = BuildConfig.DEBUG;
        } catch (Throwable ignore) {
            def = true;
        }
        return sp.getBoolean("debug_log", def);
    }
    public void setDebugLog(boolean v) {
        sp.edit().putBoolean("debug_log", v).commit();
    }

    // ---------- 音频模式 ----------
    /**
     * 兼容旧字段（1.14 起不再使用）。
     * 历史原因：设置页曾有「麦克风 / 内录 / 自动」三段选择，但内录引擎从未实现，
     * 三个选项实际全部走麦克风，属误导性假开关，已下线。
     * 保留读取方法仅为兼容老配置文件，避免残留 Toast 逻辑编译不过。
     */
    public int getAudioMode() {
        return sp.getInt("audio_mode", 0);
    }

    public void setAudioMode(int v) {
        sp.edit().putInt("audio_mode", v).commit();
    }

    // ---------- 回声消除（AEC） ----------
    /**
     * 是否启用回声消除。
     * 原理：走 AudioSource.VOICE_COMMUNICATION 而非 MIC，该源由系统音频策略
     * 挂上 AEC（声学回声消除），能把自己外放出来的声音从采集流里压掉，
     * 只留下对方（以及环境）的声音，避免"自问自答"式字幕。
     * 默认 true：这是通话字幕的正解路径。
     */
    public boolean isAecEnabled() {
        return sp.getBoolean("aec_enabled", true);
    }

    public void setAecEnabled(boolean v) {
        sp.edit().putBoolean("aec_enabled", v).commit();
    }

    // ---------- 麦克风灵敏度（软件增益） ----------
    /**
     * 软件增益倍数 * 100（默认 100 = 1.0 倍不放大）。
     * 范围 50~400，即 0.5x ~ 4.0x。
     * 用于对方声音偏小（免提音量不够、距离远）时提高识别率。
     * 注意：放大过猛会削顶失真反而更差，故上限锁 4.0x。
     */
    public int getMicGainPct() {
        return sp.getInt("mic_gain_pct", 100);
    }

    public void setMicGainPct(int v) {
        if (v < 50) v = 50;
        if (v > 400) v = 400;
        sp.edit().putInt("mic_gain_pct", v).commit();
    }

    // ---------- 字幕窗外观 ----------
    /** 字幕窗宽度（dp，0 表示铺满屏宽减边距） */
    public int getWindowWidthDp() {
        return sp.getInt("win_w", 0);
    }

    public void setWindowWidthDp(int v) {
        sp.edit().putInt("win_w", v).apply();
    }

    public int getWindowHeightDp() {
        return sp.getInt("win_h", 160);
    }

    public void setWindowHeightDp(int v) {
        sp.edit().putInt("win_h", v).apply();
    }

    /** 窗口左上角 X（px，-1 表示默认居中） */
    public int getWindowX() {
        return sp.getInt("win_x", -1);
    }

    /** 窗口左上角 Y（px，-1 表示默认贴底部） */
    public int getWindowY() {
        return sp.getInt("win_y", -1);
    }

    public void setWindowPos(int x, int y) {
        sp.edit().putInt("win_x", x).putInt("win_y", y).apply();
    }

    public void resetWindowPos() {
        sp.edit().putInt("win_x", -1).putInt("win_y", -1).apply();
    }

    /** 背景墙不透明度 0-255（默认 80，偏透；与文字透明度相互独立） */
    public int getBgAlpha() {
        return sp.getInt("bg_alpha", 80);
    }

    public void setBgAlpha(int v) {
        sp.edit().putInt("bg_alpha", v).apply();
    }

    /** 字幕文字不透明度 0-255（默认 255 完全不透明；与背景墙完全解耦） */
    public int getTextAlpha() {
        return sp.getInt("text_alpha", 255);
    }

    public void setTextAlpha(int v) {
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        sp.edit().putInt("text_alpha", v).apply();
    }

    /** 字号 sp */
    public float getFontSize() {
        return sp.getFloat("font_size", 20f);
    }

    public void setFontSize(float v) {
        sp.edit().putFloat("font_size", v).apply();
    }

    /**
     * 行间距倍数 * 100（默认 135 = 1.35 倍行高）。
     * 之前 setLineSpacing 第二参数写死 1f，行高被压成字高，多行看起来贴在一起。
     * 存成整数避免 SharedPreferences 浮点精度差异，读取时再 /100。
     */
    public int getLineSpacingPct() {
        return sp.getInt("line_spacing_pct", 135);
    }
    public void setLineSpacingPct(int v) {
        if (v < 100) v = 100;
        if (v > 250) v = 250;
        sp.edit().putInt("line_spacing_pct", v).apply();
    }
    /** 行与行之间额外的像素间隙（dp，默认 2），在行高倍数之外再撑开一点 */
    public int getLineGapDp() {
        return sp.getInt("line_gap_dp", 2);
    }
    public void setLineGapDp(int v) {
        if (v < 0) v = 0;
        if (v > 16) v = 16;
        sp.edit().putInt("line_gap_dp", v).apply();
    }

    /** 最多显示几行 */
    public int getMaxLines() {
        return sp.getInt("max_lines", 4);
    }

    public void setMaxLines(int v) {
        sp.edit().putInt("max_lines", v).apply();
    }

    /** 是否显示已锁定的旧行（滚动历史） */
    public boolean isShowHistory() {
        return sp.getBoolean("show_history", true);
    }

    public void setShowHistory(boolean v) {
        sp.edit().putBoolean("show_history", v).apply();
    }

    /** 是否记住锁定状态（锁定后下次启动仍锁定） */
    public boolean isLockedRemembered() {
        return sp.getBoolean("locked", false);
    }

    public void setLocked(boolean v) {
        sp.edit().putBoolean("locked", v).apply();
    }

    /** 文字颜色 */
    public int getTextColor() {
        return sp.getInt("text_color", 0xFFFFFFFF);
    }

    public void setTextColor(int v) {
        sp.edit().putInt("text_color", v).apply();
    }

    /** 字幕背景色（不含 alpha，alpha 单独存） */
    public int getBgColor() {
        return sp.getInt("bg_color", 0xFF000000);
    }

    public void setBgColor(int v) {
        sp.edit().putInt("bg_color", v).apply();
    }

    /**
     * v1.12 字幕颜色预设：0=经典 1=高对比 2=夜间。
     * 这个值只用于设置页高亮当前选中项，
     * 实际颜色仍以 text_color / bg_color 为准（手动改色后预设会被重置为 -1）。
     */
    public int getColorPreset() {
        return sp.getInt("color_preset", 0);
    }
    public void setColorPreset(int v) {
        sp.edit().putInt("color_preset", v).apply();
    }
}