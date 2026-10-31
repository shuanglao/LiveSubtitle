package com.along.livesubtitle;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import rikka.shizuku.Shizuku;

/**
 * 字幕窗外观设置。
 * 所有改动实时写入 Prefs，并通知正在运行的 CaptionService 刷新（ACTION_REFRESH_STYLE）。
 * 注意：窗口位置/大小由「拖动 + 右下角缩放」直接操作，这里只做视觉参数。
 */
public class SettingsActivity extends Activity {

    // ---------------- v1.6 毛玻璃浅色风配色 ----------------
    private static final int C_BG_TOP = 0xFFF7FAF9;      // 页面底色（上）
    private static final int C_BG_BOTTOM = 0xFFEDF2F1;   // 页面底色（下）
    private static final int C_GLOW = 0x140E5F5C;        // 顶部墨绿光晕
    private static final int C_CARD = 0xF2FFFFFF;        // 卡片：半透明白
    private static final int C_CARD_EDGE = 0x66FFFFFF;   // 卡片高光内边
    private static final int C_CARD_SHADOW = 0x0D0E5F5C; // 卡片模拟投影
    private static final int C_ACCENT = 0xFF0E5F5C;
    private static final int C_TEXT = 0xFF1D2B2A;
    private static final int C_SUB = 0xFF7A8B89;
    private static final int C_TRACK = 0xFFDDE7E5;
    private static final int C_PILL_BG = 0x0F0E5F5C;     // 数值胶囊底
    private static final int C_DANGER = 0xFFD9534F;
    private static final int C_SEG_IDLE = 0x14000000;    // 分段控件未选底

    private Prefs prefs;

    // 预设的文字颜色（含白、浅黄、青、绿）
    private static final int[] TEXT_COLORS = {
            0xFFFFFFFF, 0xFFFFF3B0, 0xFF7CE7FF, 0xFF8BE39B, 0xFFFFB3B3
    };
    private static final String[] TEXT_COLOR_NAMES = {
            "白色", "暖黄", "青色", "浅绿", "浅粉"
    };

    // 预设的背景基础色
    private static final int[] BG_COLORS = {
            0xFF000000, 0xFF0E2B2A, 0xFF13233A, 0xFF2B1B2E
    };
    private static final String[] BG_COLOR_NAMES = {
            "纯黑", "墨绿", "深蓝", "深紫"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        buildUi();
    }

    // ============================================================
    //  UI
    // ============================================================

    private void buildUi() {
        // 外层容器：承载页面底色渐变 + 顶部墨绿光晕
        FrameLayout page = new FrameLayout(this);
        page.setBackground(buildPageBackground());

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.TRANSPARENT);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(34));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题区：小字距大字 + 左侧墨绿竖条副标题
        TextView title = new TextView(this);
        title.setText("字幕窗外观");
        title.setTextSize(25f);
        title.setTextColor(C_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setLetterSpacing(-0.02f);
        root.addView(title);

        // Shizuku 权限状态（只读）
        addShizukuCard(root);

        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams subRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subRowLp.topMargin = dp(8);
        subRowLp.bottomMargin = dp(16);
        root.addView(subRow, subRowLp);

        View bar = new View(this);
        bar.setBackground(rounded(C_ACCENT, 1.5f));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(dp(3), dp(14));
        barLp.rightMargin = dp(7);
        subRow.addView(bar, barLp);

        TextView sub = new TextView(this);
        sub.setText("拖动顶部条移动 · 拖右下角改变大小");
        sub.setTextSize(12.5f);
        sub.setTextColor(C_SUB);
        subRow.addView(sub);

        // ---------------- 透明度：背景墙 / 字幕文字（分开控制） ----------------
        LinearLayout card1 = card();
        root.addView(card1, cardLp());

        final TextView tvAlpha = cardTitle(card1, "背景墙不透明度", prefs.getBgAlpha() + " / 255");
        SeekBar sbAlpha = seek(card1, 0, 255, prefs.getBgAlpha());
        sbAlpha.setOnSeekBarChangeListener(new SimpleSeek(0) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                setPill(tvAlpha, val + " / 255");
                if (fromUser) {
                    prefs.setBgAlpha(val);
                    notifyStyleChanged();
                }
            }
        });

        // 字幕文字透明度：独立于背景墙，拉低只影响字，底板不受影响
        final TextView tvTAlpha = cardTitle(card1, "字幕文字不透明度", prefs.getTextAlpha() + " / 255");
        SeekBar sbTAlpha = seek(card1, 0, 255, prefs.getTextAlpha());
        sbTAlpha.setOnSeekBarChangeListener(new SimpleSeek(0) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                setPill(tvTAlpha, val + " / 255");
                if (fromUser) {
                    prefs.setTextAlpha(val);
                    notifyStyleChanged();
                }
            }
        });

        // ---------------- v1.12 功能5：字幕颜色预设 ----------------
        LinearLayout cardPreset = card();
        root.addView(cardPreset, cardLp());
        cardTitle(cardPreset, "字幕颜色预设", "一键切换文字+背景配色");
        final String[] presetNames = {"经典", "高对比", "夜间"};
        final int[] presetText = {0xFFFFFFFF, 0xFF000000, 0xFFFFE8C8};
        final int[] presetBg = {0xFF000000, 0xFFFFE14D, 0xFF000000};
        LinearLayout presetSeg = new LinearLayout(this);
        presetSeg.setOrientation(LinearLayout.HORIZONTAL);
        presetSeg.setBackground(rounded(C_SEG_IDLE, 16));
        presetSeg.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams presetSegLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        presetSegLp.topMargin = dp(10);
        cardPreset.addView(presetSeg, presetSegLp);
        int curPreset = prefs.getColorPreset();
        for (int i = 0; i < presetNames.length; i++) {
            final int pi = i;
            boolean sel = (curPreset == i);
            TextView b = new TextView(this);
            b.setText(presetNames[i]);
            b.setTextSize(13.5f);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(null, sel ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            b.setTextColor(sel ? Color.WHITE : C_SUB);
            b.setBackground(rounded(sel ? C_ACCENT : Color.TRANSPARENT, 12));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(36), 1f);
            if (i > 0) blp.leftMargin = dp(4);
            presetSeg.addView(b, blp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setTextColor(presetText[pi]);
                    prefs.setBgColor(presetBg[pi]);
                    prefs.setColorPreset(pi);
                    notifyStyleChanged();
                    rebuildLater();
                    Toast.makeText(SettingsActivity.this,
                            "颜色预设：" + presetNames[pi], Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ---------------- 字号 ----------------
        LinearLayout card2 = card();
        root.addView(card2, cardLp());

        final TextView tvFont = cardTitle(card2, "字号", (int) prefs.getFontSize() + " sp");
        SeekBar sbFont = seek(card2, 10, 40, (int) prefs.getFontSize());
        sbFont.setOnSeekBarChangeListener(new SimpleSeek(10) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                setPill(tvFont, val + " sp");
                if (fromUser) {
                    prefs.setFontSize((float) val);
                    notifyStyleChanged();
                }
            }
        });
        // v1.12 功能6：字号快捷档位
        final int[] fontPreset = {14, 20, 28, 34};
        final String[] fontPresetName = {"小", "中", "大", "超大"};
        LinearLayout fontSeg = new LinearLayout(this);
        fontSeg.setOrientation(LinearLayout.HORIZONTAL);
        fontSeg.setBackground(rounded(C_SEG_IDLE, 16));
        fontSeg.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams fontSegLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fontSegLp.topMargin = dp(10);
        card2.addView(fontSeg, fontSegLp);
        int curFont = (int) prefs.getFontSize();
        for (int i = 0; i < fontPreset.length; i++) {
            final int fv = fontPreset[i];
            boolean sel = (Math.abs(curFont - fv) <= 1);
            TextView b = new TextView(this);
            b.setText(fontPresetName[i]);
            b.setTextSize(13f);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(null, sel ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            b.setTextColor(sel ? Color.WHITE : C_SUB);
            b.setBackground(rounded(sel ? C_ACCENT : Color.TRANSPARENT, 12));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            if (i > 0) blp.leftMargin = dp(4);
            fontSeg.addView(b, blp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setFontSize((float) fv);
                    notifyStyleChanged();
                    rebuildLater();
                }
            });
        }

        // ---------------- 行数 ----------------
        LinearLayout card3 = card();
        root.addView(card3, cardLp());

        final TextView tvLines = cardTitle(card3, "最多显示行数", prefs.getMaxLines() + " 行");
        // 超过 8 行时窗口内自动滚动，旧行会被裁掉（受窗口高度限制）
        cardTitle(card3, "滚动说明", "超过 8 行可上下滑动查看，超出部分会被自动移除");
        SeekBar sbLines = seek(card3, 1, 20, prefs.getMaxLines());
        sbLines.setOnSeekBarChangeListener(new SimpleSeek(1) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                setPill(tvLines, val + " 行");
                if (fromUser) {
                    prefs.setMaxLines(val);
                    notifyStyleChanged();
                }
            }
        });

        // ---------------- 字幕行间距 ----------------
        LinearLayout cardLs = card();
        root.addView(cardLs, cardLp());

        final TextView tvLs = cardTitle(cardLs, "字幕行间距",
                String.format(java.util.Locale.US, "%.2f 倍", prefs.getLineSpacingPct() / 100f));
        SeekBar sbLs = seek(cardLs, 100, 250, prefs.getLineSpacingPct());
        sbLs.setOnSeekBarChangeListener(new SimpleSeek(100) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                setPill(tvLs, String.format(java.util.Locale.US, "%.2f 倍", val / 100f));
                if (fromUser) {
                    prefs.setLineSpacingPct(val);
                    notifyStyleChanged();
                }
            }
        });

        final TextView tvGap = cardTitle(cardLs, "行间额外间隙", prefs.getLineGapDp() + " dp");
        SeekBar sbGap = seek(cardLs, 0, 16, prefs.getLineGapDp());
        sbGap.setOnSeekBarChangeListener(new SimpleSeek(0) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                setPill(tvGap, val + " dp");
                if (fromUser) {
                    prefs.setLineGapDp(val);
                    notifyStyleChanged();
                }
            }
        });

        // ---------------- 文字颜色 ----------------
        LinearLayout card4 = card();
        root.addView(card4, cardLp());
        cardTitle(card4, "文字颜色", "");

        LinearLayout colorRow = new LinearLayout(this);
        colorRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        rowLp.topMargin = dp(8);
        card4.addView(colorRow, rowLp);

        final int curText = prefs.getTextColor();
        for (int i = 0; i < TEXT_COLORS.length; i++) {
            final int color = TEXT_COLORS[i];
            final String name = TEXT_COLOR_NAMES[i];
            View dot = colorDot(color, curText == color);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            dotLp.rightMargin = dp(8);
            colorRow.addView(dot, dotLp);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setTextColor(color);
                    notifyStyleChanged();
                    rebuildLater();
                    Toast.makeText(SettingsActivity.this, "文字颜色：" + name,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ---------------- 背景基础色 ----------------
        LinearLayout card5 = card();
        root.addView(card5, cardLp());
        cardTitle(card5, "背景色调", "");

        LinearLayout bgRow = new LinearLayout(this);
        bgRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bgRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        bgRowLp.topMargin = dp(8);
        card5.addView(bgRow, bgRowLp);

        final int curBg = prefs.getBgColor();
        for (int i = 0; i < BG_COLORS.length; i++) {
            final int color = BG_COLORS[i];
            final String name = BG_COLOR_NAMES[i];
            View dot = colorDot(color, curBg == color);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(0, dp(40), 1f);
            dotLp.rightMargin = dp(8);
            bgRow.addView(dot, dotLp);
            dot.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setBgColor(color);
                    notifyStyleChanged();
                    rebuildLater();
                    Toast.makeText(SettingsActivity.this, "背景色调：" + name,
                            Toast.LENGTH_SHORT).show();
                }
            });
        }

        // ---------------- 显示历史行 ----------------
        LinearLayout card6 = card();
        root.addView(card6, cardLp());
        cardTitle(card6, "显示历史行", "已定稿的旧句子会往上方滚");

        Switch swHistory = new Switch(this);
        // 标题已在 cardTitle 里显示，这里不再贴文字，避免重复
        swHistory.setChecked(prefs.isShowHistory());
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        swLp.topMargin = dp(6);
        card6.addView(swHistory, swLp);
        swHistory.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                prefs.setShowHistory(checked);
                notifyStyleChanged();
            }
        });

        // ---------------- 音频模式 ----------------
        LinearLayout card7 = card();
        root.addView(card7, cardLp());
        cardTitle(card7, "声音来源", "内录对部分 App 可能不可用，会自动降级麦克风");

        final String[] modeNames = {"麦克风", "内录", "自动"};
        final String[] modeFull = {"麦克风（听周围声音）",
                "内录（抓 App 播放的声音）",
                "自动（先内录，失败降级）"};

        // 分段控件：浅墨绿轨道 + 滑动的墨绿实心胶囊
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(C_SEG_IDLE, 16));
        seg.setPadding(dp(4), dp(4), dp(4), dp(4));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        segLp.topMargin = dp(12);
        card7.addView(seg, segLp);

        int curMode = prefs.getAudioMode();
        for (int i = 0; i < modeNames.length; i++) {
            final int mode = i;
            boolean sel = (curMode == i);
            TextView b = new TextView(this);
            b.setText(modeNames[i]);
            b.setTextSize(13.5f);
            b.setGravity(Gravity.CENTER);
            b.setTypeface(null, sel ? android.graphics.Typeface.BOLD
                    : android.graphics.Typeface.NORMAL);
            b.setTextColor(sel ? Color.WHITE : C_SUB);
            b.setBackground(rounded(sel ? C_ACCENT : Color.TRANSPARENT, 12));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    0, dp(36), 1f);
            if (i > 0) blp.leftMargin = dp(4);
            seg.addView(b, blp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setAudioMode(mode);
                    Toast.makeText(SettingsActivity.this,
                            "已切换为：" + modeFull[mode] + "（重新开启字幕后生效）",
                            Toast.LENGTH_LONG).show();
                    rebuildLater();
                }
            });
        }

        // ---------------- 恢复默认位置 ----------------
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        resetLp.topMargin = dp(18);
        root.addView(dangerButton("重置字幕窗位置与大小",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        prefs.resetWindowPos();
                        prefs.setWindowWidthDp(0);
                        prefs.setWindowHeightDp(160);
                        Toast.makeText(SettingsActivity.this,
                                "已重置，重新开启字幕后恢复默认位置",
                                Toast.LENGTH_SHORT).show();
                    }
                }), resetLp);

        page.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(page);
    }

    // ============================================================
    //  小组件
    // ============================================================

    /**
     * 页面底色：垂直微渐变 + 顶部墨绿光晕（用叠加层模拟）。
     */
    private android.graphics.drawable.Drawable buildPageBackground() {
        GradientDrawable base = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_BG_TOP, C_BG_BOTTOM});
        base.setCornerRadius(0f);

        // 顶部光晕：一块从墨绿到透明的竖向渐变，贴在屏幕顶部
        GradientDrawable glow = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{C_GLOW, 0x000E5F5C});
        glow.setCornerRadius(0f);

        android.graphics.drawable.Drawable[] layers = {base, glow};
        return new android.graphics.drawable.LayerDrawable(layers);
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(cardBackground());
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            c.setElevation(dp(2));
        }
        return c;
    }

    /**
     * 卡片背景：半透明白 + 大圆角 + 高光内边 + 偏移 2dp 的模拟投影。
     * 用 LayerDrawable 叠两层实现（老 android.jar 没有 MaterialCardView 可用）。
     */
    private android.graphics.drawable.Drawable cardBackground() {
        // 投影层：整体下移 2dp 的暗色圆角块
        GradientDrawable shadow = new GradientDrawable();
        shadow.setShape(GradientDrawable.RECTANGLE);
        shadow.setColor(C_CARD_SHADOW);
        shadow.setCornerRadius(dp(20));

        // 主体层：半透明白 + 1dp 高光内边
        GradientDrawable body = new GradientDrawable();
        body.setShape(GradientDrawable.RECTANGLE);
        body.setColor(C_CARD);
        body.setCornerRadius(dp(20));
        body.setStroke(dp(1), C_CARD_EDGE);

        android.graphics.drawable.LayerDrawable ld =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{shadow, body});
        // 主体上移，仅露出下方 2dp 当投影
        ld.setLayerInset(0, 0, dp(2), 0, 0);
        ld.setLayerInset(1, 0, 0, 0, dp(2));
        return ld;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        return lp;
    }

    /**
     * 卡片标题：左侧标题文字 + 右侧数值胶囊。
     * value 为空时只显示标题（用于「文字颜色」「背景色调」这类纯色点卡片）。
     */
    /**
     * 卡片标题：左侧标题文字 + 右侧数值胶囊。
     * value 为空时只显示标题（用于「文字颜色」「背景色调」这类纯色点卡片）。
     * 返回的 TextView 即标题本身；胶囊挂在其 tag 上，刷新数值用 setPill(tv, ...)。
     */
    // ============================================================
    //  Shizuku 状态（只读，不主动申请权限）
    // ============================================================
    private TextView tvShizuku;   // 状态行文本
    private boolean shizukuListenerBound = false;
    private TextView tvShizukuAuth;    // 申请授权按钮
    private TextView tvShizukuWhite;   // 电池白名单按钮
    private static final int REQ_SHIZUKU = 1001;

    private void addShizukuCard(LinearLayout root) {
        LinearLayout c = card();
        root.addView(c, cardLp());
        cardTitle(c, "Shizuku 权限", "");

        tvShizuku = new TextView(this);
        tvShizuku.setTextSize(13f);
        tvShizuku.setTextColor(C_SUB);
        tvShizuku.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        c.addView(tvShizuku, lp);


        tvShizukuAuth = accentButton("申请授权", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    Shizuku.requestPermission(REQ_SHIZUKU);
                } catch (Throwable t) {
                    Toast.makeText(SettingsActivity.this,
                            "申请授权失败：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
                }
            }
        });
        LinearLayout.LayoutParams lpA = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lpA.topMargin = dp(10);
        c.addView(tvShizukuAuth, lpA);

        tvShizukuWhite = accentButton("加入电池白名单", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyBatteryWhitelist();
            }
        });
        LinearLayout.LayoutParams lpW = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42));
        lpW.topMargin = dp(8);
        c.addView(tvShizukuWhite, lpW);

        bindShizukuListeners();
        refreshShizukuState();
    }

    /** 注册一次性监听，Shizuku 连上/断开/授权变化时自动刷新 */
    private void bindShizukuListeners() {
        if (shizukuListenerBound) return;
        shizukuListenerBound = true;
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener);
            Shizuku.addBinderDeadListener(binderDeadListener);
            Shizuku.addRequestPermissionResultListener(permResultListener);
        } catch (Throwable ignored) {
        }
    }

    private final Shizuku.OnBinderReceivedListener binderReceivedListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    refreshShizukuState();
                }
            };

    private final Shizuku.OnBinderDeadListener binderDeadListener =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    refreshShizukuState();
                }
            };

    private final Shizuku.OnRequestPermissionResultListener permResultListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    refreshShizukuState();
                }
            };

    /** 刷新状态文本；任何异常都不能崩掉设置页 */
    private void refreshShizukuState() {
        if (tvShizuku == null) return;
        String text;
        try {
            if (!Shizuku.pingBinder()) {
                text = "● 未连接：Shizuku 服务未运行或未安装";
                tvShizuku.setTextColor(C_DANGER);
            } else {
                int ver = Shizuku.getVersion();
                if (Shizuku.isPreV11()) {
                    text = "● 已连接（旧版 v" + ver + "，不支持授权）";
                    tvShizuku.setTextColor(C_SUB);
                } else if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    text = "● 已授权：Shizuku v" + ver + "，可用于电池白名单";
                    tvShizuku.setTextColor(C_ACCENT);
                } else {
                    text = "● 未授权：Shizuku v" + ver + "，尚未允许本应用";
                    tvShizuku.setTextColor(C_SUB);
                }
            }
        } catch (Throwable t) {
            text = "● 无法获取状态：" + t.getClass().getSimpleName();
            tvShizuku.setTextColor(C_SUB);
        }
        // 按钮显隐：未授权只显示授权按钮
        boolean authed = false;
        try {
            authed = Shizuku.pingBinder() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
        }
        if (tvShizukuAuth != null) tvShizukuAuth.setVisibility(authed ? View.GONE : View.VISIBLE);
        if (tvShizukuWhite != null) tvShizukuWhite.setVisibility(authed ? View.VISIBLE : View.GONE);
        tvShizuku.setText(text);
    }

    @Override
    protected void onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
            Shizuku.removeRequestPermissionResultListener(permResultListener);
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }

    private TextView cardTitle(LinearLayout parent, String label, String value) {
        boolean hasValue = value != null && value.length() > 0;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        parent.addView(row);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(14.5f);
        tv.setTextColor(C_TEXT);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tvLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(tv, tvLp);

        if (!hasValue) return tv;

        TextView pill = new TextView(this);
        pill.setText(value);
        pill.setTextSize(12f);
        pill.setTextColor(C_ACCENT);
        pill.setPadding(dp(9), dp(3), dp(9), dp(3));
        GradientDrawable pg = new GradientDrawable();
        pg.setShape(GradientDrawable.RECTANGLE);
        pg.setColor(C_PILL_BG);
        pg.setCornerRadius(dp(99));
        pill.setBackground(pg);
        row.addView(pill);

        tv.setTag(pill);
        return tv;
    }

    /**
     * 刷新卡片标题右侧胶囊的数值。
     * title 必须是 cardTitle() 返回的那个 TextView。
     */
    private void setPill(TextView title, String value) {
        if (title == null) return;
        Object tag = title.getTag();
        if (tag instanceof TextView) {
            ((TextView) tag).setText(value == null ? "" : value);
        }
    }

    private SeekBar seek(LinearLayout parent, int min, int max, int cur) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        // 自绘：4dp 浅轨道 + 12dp 墨绿圆拇指；清除系统默认配色
        sb.setSplitTrack(false);
        GradientDrawable bgBar = new GradientDrawable();
        bgBar.setCornerRadius(dp(2));
        bgBar.setColor(C_TRACK);
        GradientDrawable pgBar = new GradientDrawable();
        pgBar.setCornerRadius(dp(2));
        pgBar.setColor(C_ACCENT);
        android.graphics.drawable.LayerDrawable track =
                new android.graphics.drawable.LayerDrawable(
                        new android.graphics.drawable.Drawable[]{bgBar, pgBar});
        track.setId(0, android.R.id.background);
        track.setId(1, android.R.id.progress);
        // 上下各内缩 7dp，让 4dp 细条居中于 34dp 触摸区
        track.setLayerInset(0, 0, dp(7), 0, dp(7));
        track.setLayerInset(1, 0, dp(7), 0, dp(7));
        sb.setProgressDrawable(track);

        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(C_ACCENT);
        thumb.setStroke(dp(2), 0xFFFFFFFF);
        thumb.setSize(dp(16), dp(16));
        sb.setThumb(thumb);
        sb.setThumbOffset(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(34));
        lp.topMargin = dp(6);
        parent.addView(sb, lp);
        return sb;
    }

    /** SeekBar 用了 min 偏移，real() 把 progress 还原成真实值 */
    private abstract class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        private final int min;

        SimpleSeek(int min) {
            this.min = min;
        }

        int real(int progress) {
            return progress + min;
        }

        @Override
        public void onStartTrackingTouch(SeekBar sb) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar sb) {
        }
    }

    private View colorDot(final int color, boolean selected) {
        View v = new View(this);
        if (selected) {
            // 选中：外墨绿环 + 白色间隙 + 中间实色，三层同心圆
            GradientDrawable outer = new GradientDrawable();
            outer.setShape(GradientDrawable.OVAL);
            outer.setColor(0x00000000);
            outer.setStroke(dp(2), C_ACCENT);

            GradientDrawable gapRing = new GradientDrawable();
            gapRing.setShape(GradientDrawable.OVAL);
            gapRing.setColor(0xFFFFFFFF);

            GradientDrawable inner = new GradientDrawable();
            inner.setShape(GradientDrawable.OVAL);
            inner.setColor(color);

            android.graphics.drawable.LayerDrawable ld =
                    new android.graphics.drawable.LayerDrawable(
                            new android.graphics.drawable.Drawable[]{outer, gapRing, inner});
            int gap = dp(3);
            ld.setLayerInset(0, 0, 0, 0, 0);
            ld.setLayerInset(1, gap, gap, gap, gap);
            ld.setLayerInset(2, gap + dp(2), gap + dp(2), gap + dp(2), gap + dp(2));
            v.setBackground(ld);
        } else {
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(color);
            g.setStroke(dp(1), 0x1A000000);
            v.setBackground(g);
        }
        return v;
    }

    /** 危险操作按钮：透明底 + 1dp 红描边 + 红字（比实心红按钮更轻） */
    private TextView dangerButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14f);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(C_DANGER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(0x00000000);
        g.setStroke(dp(1), C_DANGER);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    private GradientDrawable rounded(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        return g;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ============================================================
    //  刷新联动
    // ============================================================

    /** 通知正在运行的字幕服务立刻刷新样式 */
    private void notifyStyleChanged() {
        if (!prefs.isEnabled()) return;
        Intent i = new Intent(this, CaptionService.class);
        i.setAction(CaptionService.ACTION_REFRESH_STYLE);
        try {
            startService(i);
        } catch (Throwable ignored) {
        }
    }

    /** 重建界面以更新选中态（延时一点，避免在点击回调里立刻销毁自己） */
    private void rebuildLater() {
        Toast.makeText(this, "", Toast.LENGTH_SHORT).cancel();
        getWindow().getDecorView().postDelayed(new Runnable() {
            @Override
            public void run() {
                recreate();
            }
        }, 150);
    }

    /** 主色描边按钮，照抄操作 */
    private TextView accentButton(String text, View.OnClickListener l) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextSize(14f);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(C_ACCENT);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.RECTANGLE);
        g.setColor(0x00000000);
        g.setStroke(dp(1), C_ACCENT);
        g.setCornerRadius(dp(14));
        b.setBackground(g);
        b.setOnClickListener(l);
        return b;
    }

    /** 借 Shizuku 的 shell 权限加白名单 */
    private void applyBatteryWhitelist() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Shizuku 未运行", Toast.LENGTH_SHORT).show();
                return;
            }
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "请先授权", Toast.LENGTH_SHORT).show();
                return;
            }
            Process pr = Shizuku.newProcess(
                    new String[]{"sh", "-c", "dumpsys deviceidle whitelist +com.along.livesubtitle"}, null, null);
            boolean done = ((rikka.shizuku.ShizukuRemoteProcess) pr).waitForTimeout(5, java.util.concurrent.TimeUnit.SECONDS);
            int code = done ? pr.exitValue() : -1;
            if (!done) pr.destroy();
            if (code == 0) {
                Toast.makeText(this, "已加入电池白名单", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "失败：" + code, Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "异常：" + t.getClass().getSimpleName(), Toast.LENGTH_SHORT).show();
        }
    }
}
