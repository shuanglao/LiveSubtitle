package com.along.livesubtitle;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 字幕窗外观设置。
 * 所有改动实时写入 Prefs，并通知正在运行的 CaptionService 刷新（ACTION_REFRESH_STYLE）。
 * 注意：窗口位置/大小由「拖动 + 右下角缩放」直接操作，这里只做视觉参数。
 */
public class SettingsActivity extends Activity {

    private static final int C_BG = 0xFFEEF2F1;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_ACCENT = 0xFF0E5F5C;
    private static final int C_TEXT = 0xFF1D2B2A;
    private static final int C_SUB = 0xFF7A8B89;
    private static final int C_TRACK = 0xFFD7E3E1;

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
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(30));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // 标题
        TextView title = new TextView(this);
        title.setText("字幕窗外观");
        title.setTextSize(24f);
        title.setTextColor(C_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("拖动顶部条移动 · 拖右下角改变大小");
        sub.setTextSize(13f);
        sub.setTextColor(C_SUB);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        subLp.bottomMargin = dp(18);
        root.addView(sub, subLp);

        // ---------------- 背景不透明度 ----------------
        LinearLayout card1 = card();
        root.addView(card1, cardLp());

        final TextView tvAlpha = cardTitle(card1, "背景不透明度", prefs.getBgAlpha() + " / 255");
        SeekBar sbAlpha = seek(card1, 0, 255, prefs.getBgAlpha());
        sbAlpha.setOnSeekBarChangeListener(new SimpleSeek(0) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                tvAlpha.setText("背景不透明度    " + val + " / 255");
                if (fromUser) {
                    prefs.setBgAlpha(val);
                    notifyStyleChanged();
                }
            }
        });

        // ---------------- 字号 ----------------
        LinearLayout card2 = card();
        root.addView(card2, cardLp());

        final TextView tvFont = cardTitle(card2, "字号", (int) prefs.getFontSize() + " sp");
        SeekBar sbFont = seek(card2, 10, 40, (int) prefs.getFontSize());
        sbFont.setOnSeekBarChangeListener(new SimpleSeek(10) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                tvFont.setText("字号    " + val + " sp");
                if (fromUser) {
                    prefs.setFontSize((float) val);
                    notifyStyleChanged();
                }
            }
        });

        // ---------------- 行数 ----------------
        LinearLayout card3 = card();
        root.addView(card3, cardLp());

        final TextView tvLines = cardTitle(card3, "最多显示行数", prefs.getMaxLines() + " 行");
        SeekBar sbLines = seek(card3, 1, 10, prefs.getMaxLines());
        sbLines.setOnSeekBarChangeListener(new SimpleSeek(1) {
            @Override
            public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                int val = real(p);
                tvLines.setText("最多显示行数    " + val + " 行");
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
                tvLs.setText(String.format(java.util.Locale.US, "字幕行间距    %.2f 倍", val / 100f));
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
                tvGap.setText("行间额外间隙    " + val + " dp");
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
        swHistory.setText("显示历史行");
        swHistory.setTextSize(14f);
        swHistory.setTextColor(C_TEXT);
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

        final String[] modeNames = {"麦克风（听周围声音）", "内录（抓 App 播放的声音）", "自动（先内录，失败降级）"};
        LinearLayout modeBox = new LinearLayout(this);
        modeBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.topMargin = dp(6);
        card7.addView(modeBox, modeLp);

        int curMode = prefs.getAudioMode();
        for (int i = 0; i < modeNames.length; i++) {
            final int mode = i;
            Button b = new Button(this);
            b.setText(modeNames[i]);
            b.setAllCaps(false);
            b.setTextSize(14f);
            boolean sel = (curMode == i);
            b.setTextColor(sel ? Color.WHITE : C_ACCENT);
            b.setBackground(rounded(sel ? C_ACCENT : C_CARD, 14));
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
            blp.topMargin = dp(8);
            modeBox.addView(b, blp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    prefs.setAudioMode(mode);
                    Toast.makeText(SettingsActivity.this,
                            "已切换为：" + modeNames[mode] + "（重新开启字幕后生效）",
                            Toast.LENGTH_LONG).show();
                    rebuildLater();
                }
            });
        }

        // ---------------- 恢复默认位置 ----------------
        Button btnReset = new Button(this);
        btnReset.setText("重置字幕窗位置与大小");
        btnReset.setAllCaps(false);
        btnReset.setTextSize(15f);
        btnReset.setTextColor(0xFFD9534F);
        btnReset.setBackground(rounded(C_CARD, 14));
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        resetLp.topMargin = dp(20);
        root.addView(btnReset, resetLp);
        btnReset.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                prefs.resetWindowPos();
                prefs.setWindowWidthDp(0);
                prefs.setWindowHeightDp(160);
                Toast.makeText(SettingsActivity.this,
                        "已重置，重新开启字幕后恢复默认位置", Toast.LENGTH_SHORT).show();
            }
        });

        setContentView(sv);
    }

    // ============================================================
    //  小组件
    // ============================================================

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        c.setBackground(rounded(C_CARD, 16));
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        return lp;
    }

    private TextView cardTitle(LinearLayout parent, String label, String value) {
        TextView tv = new TextView(this);
        tv.setText(value == null || value.length() == 0 ? label : (label + "    " + value));
        tv.setTextSize(14.5f);
        tv.setTextColor(C_TEXT);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        parent.addView(tv);
        return tv;
    }

    private SeekBar seek(LinearLayout parent, int min, int max, int cur) {
        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(cur - min);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
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
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (selected) {
            g.setStroke(dp(3), C_ACCENT);
        } else {
            g.setStroke(dp(1), 0x33000000);
        }
        v.setBackground(g);
        return v;
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
}
