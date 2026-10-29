package com.along.livesubtitle;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面：权限引导 + 一键启停。
 * 配色延续 EdgeSidebar 的墨绿浅色体系。
 */
public class MainActivity extends Activity {

    // ---- 配色（沿用 EdgeSidebar）----
    private static final int C_BG = 0xFFEEF2F1;
    private static final int C_CARD = 0xFFFFFFFF;
    private static final int C_ACCENT = 0xFF0E5F5C;
    private static final int C_SOFT = 0xFFA8D8E8;
    private static final int C_TEXT = 0xFF1D2B2A;
    private static final int C_SUB = 0xFF7A8B89;

    private static final int REQ_AUDIO = 100;
    /** 用户明确表达了“我要开始字幕”的意图，等权限回来后续启。
     *  用独立标志位而不是 !prefs.isEnabled() 判断，避免与 toggleCaption()
     *  内部的启停分支互相竞态，误走停止分支把自己的 enabled 抹掉。 */
    private boolean pendingStart = false;
    private static final String NL = String.valueOf((char) 10);

    private Prefs prefs;
    private TextView tvOverlayState;
    private TextView tvAudioState;
    private Button btnOverlay;
    private Button btnAudio;
    private Button btnToggle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        try {
            buildUi();
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder("UI build failed: ");
            sb.append(t.toString()).append(NL);
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && sb.length() < 2500; i++) {
                sb.append(st[i].toString()).append(NL);
            }
            TextView err = new TextView(this);
            err.setText(sb.toString());
            err.setTextSize(11f);
            err.setTextColor(0xFFB00020);
            err.setPadding(dp(16), dp(60), dp(16), dp(16));
            ScrollView sc = new ScrollView(this);
            sc.setBackgroundColor(0xFFFFF3F3);
            sc.addView(err);
            setContentView(sc);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    // ============================================================
    //  UI
    // ============================================================

    private void buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(C_BG);
        sv.setFillViewport(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        sv.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 标题 ----
        TextView title = new TextView(this);
        title.setText("实时字幕");
        title.setTextSize(28f);
        title.setTextColor(C_TEXT);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("听障辅助 · 全局悬浮字幕");
        sub.setTextSize(13f);
        sub.setTextColor(C_SUB);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(4);
        subLp.bottomMargin = dp(20);
        root.addView(sub, subLp);

        // ---- 状态卡 ----
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(rounded(C_CARD, 16));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        tvOverlayState = stateRow(card, "悬浮窗权限");
        tvAudioState = stateRow(card, "录音权限");

        // 空隙直接用 btnLp() 的 topMargin 实现，不再塞空 View（空 View 会导致测量异常、按钮不显示）

        // ---- 权限按钮 ----
        btnOverlay = actionButton("① 授予悬浮窗权限");
        root.addView(btnOverlay, btnLp());
        btnOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(i);
                } catch (Throwable t) {
                    Toast.makeText(MainActivity.this, "请到设置中手动开启悬浮窗权限",
                            Toast.LENGTH_LONG).show();
                }
            }
        });

        btnAudio = actionButton("② 授予录音权限");
        root.addView(btnAudio, btnLp());
        btnAudio.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAudioPermission();
            }
        });

        // ---- 主开关 ----
        btnToggle = new Button(this);
        btnToggle.setTextSize(17f);
        btnToggle.setTextColor(Color.WHITE);
        btnToggle.setAllCaps(false);
        btnToggle.setBackground(rounded(C_ACCENT, 26));
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        toggleLp.topMargin = dp(24);
        root.addView(btnToggle, toggleLp);
        btnToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleCaption();
            }
        });

        // ---- 设置按钮 ----
        Button btnSettings = actionButton("字幕窗外观设置");
        LinearLayout.LayoutParams setLp = btnLp();
        setLp.topMargin = dp(12);
        root.addView(btnSettings, setLp);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, SettingsActivity.class));
            }
        });

        // ---- 说明 ----
        TextView tip = new TextView(this);
        tip.setText("使用说明\n"
                + "· 开启后字幕窗浮在桌面上，可拖动顶部条移动、拖右下角缩放\n"
                + "· 点「锁定」后字幕窗触摸穿透，不影响打游戏和操作\n"
                + "· 锁定时可通过通知栏的「解锁」重新调整\n"
                + "· 麦克风模式识别的是手机周围的声音（外放的通话/游戏声也能收到）");
        tip.setTextSize(12.5f);
        tip.setTextColor(C_SUB);
        tip.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = dp(24);
        root.addView(tip, tipLp);

        setContentView(sv);
    }

    private TextView stateRow(LinearLayout parent, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(10);
        parent.addView(row, rowLp);

        TextView name = new TextView(this);
        name.setText(label);
        name.setTextSize(14f);
        name.setTextColor(C_TEXT);
        row.addView(name, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView state = new TextView(this);
        state.setTextSize(13f);
        state.setTextColor(C_SUB);
        row.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return state;
    }

    private Button actionButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(15f);
        b.setTextColor(C_ACCENT);
        b.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable g = rounded(C_CARD, 14);
        g.setStroke(dp(1.5f), 0xFFCBD8D6);
        b.setBackground(g);
        b.setMinHeight(dp(48));
        return b;
    }

    private LinearLayout.LayoutParams btnLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        lp.topMargin = dp(10);
        return lp;
    }

    private GradientDrawable rounded(int color, float rDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(rDp));
        return g;
    }

    // ============================================================
    //  状态刷新
    // ============================================================

    private void refreshState() {
        boolean overlay = canOverlay();
        tvOverlayState.setText(overlay ? "✓ 已授权" : "✗ 未授权");
        tvOverlayState.setTextColor(overlay ? 0xFF2E9E5B : 0xFFD9534F);
        btnOverlay.setVisibility(overlay ? View.GONE : View.VISIBLE);

        boolean audio = canRecord();
        tvAudioState.setText(audio ? "✓ 已授权" : "✗ 未授权");
        tvAudioState.setTextColor(audio ? 0xFF2E9E5B : 0xFFD9534F);
        btnAudio.setVisibility(audio ? View.GONE : View.VISIBLE);

        boolean on = prefs.isEnabled();
        btnToggle.setText(on ? "停止字幕" : "开启字幕");
        btnToggle.setBackground(rounded(on ? 0xFFD9534F : C_ACCENT, 26));
    }

    private boolean canOverlay() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return Settings.canDrawOverlays(this);
    }

    private boolean canRecord() {
        if (Build.VERSION.SDK_INT < 23) return true;
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ============================================================
    //  权限
    // ============================================================

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT < 23) return;
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        refreshState();
        if (req == REQ_AUDIO) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "录音权限已授予", Toast.LENGTH_SHORT).show();
                // 授权成功后自动继续启动字幕，免用户再点一次
                // 关键：只看 pendingStart，绝不能再拿 !prefs.isEnabled() 判断。
                // 否则 200ms 窗口期内 enabled 一旦变 true，toggleCaption()
                // 就会走进“停止”分支，把自己刚开的服务关掉 -> 启动即死循环。
                if (pendingStart) {
                    pendingStart = false;
                    btnToggle.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (!prefs.isEnabled()) toggleCaption();
                        }
                    }, 200);
                }
            } else {
                Toast.makeText(this, "实时字幕需要录音权限才能工作", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ============================================================
    //  启停
    // ============================================================

    private void toggleCaption() {
        Intent svc = new Intent(this, CaptionService.class);

        if (prefs.isEnabled()) {
            svc.setAction(CaptionService.ACTION_STOP);
            startService(svc);
            prefs.setEnabled(false);
        } else {
            if (!canOverlay()) {
                Toast.makeText(this, "请先开启悬浮窗权限，正在跳转设置...", Toast.LENGTH_LONG).show();
                Intent oi = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                try {
                    startActivity(oi);
                } catch (Throwable t) {
                    Toast.makeText(this, "请到系统设置中手动开启悬浮窗权限", Toast.LENGTH_LONG).show();
                }
                pendingStart = true;
                return;
            }
            if (!canRecord()) {
                Toast.makeText(this, "请在弹出的系统框中点「允许」录音权限", Toast.LENGTH_LONG).show();
                requestAudioPermission();
                pendingStart = true;
                return;
            }
            svc.setAction(CaptionService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(svc);
            } else {
                startService(svc);
            }
            prefs.setEnabled(true);
        }
        // 稍微延迟再刷新，等服务真正起来
        btnToggle.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshState();
            }
        }, 300);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}