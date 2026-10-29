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

    // ---------- 音频模式 ----------
    /** 0 = 麦克风，1 = 内录（MediaProjection），2 = 自动（内录失败降级麦克风） */
    public int getAudioMode() {
        return sp.getInt("audio_mode", 2);
    }

    public void setAudioMode(int v) {
        sp.edit().putInt("audio_mode", v).apply();
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

    /** 背景不透明度 0-255（默认 150，半透明） */
    public int getBgAlpha() {
        return sp.getInt("bg_alpha", 150);
    }

    public void setBgAlpha(int v) {
        sp.edit().putInt("bg_alpha", v).apply();
    }

    /** 字号 sp */
    public float getFontSize() {
        return sp.getFloat("font_size", 20f);
    }

    public void setFontSize(float v) {
        sp.edit().putFloat("font_size", v).apply();
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
}