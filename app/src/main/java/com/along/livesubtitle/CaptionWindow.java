package com.along.livesubtitle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * 悬浮字幕窗。
 *
 * 布局结构（FrameLayout root）：
 *   ├─ tvBody        : 字幕正文（多行，滚动历史）
 *   ├─ tvFinal       : 已定稿的行（灰色/次要色）
 *   ├─ barTop        : 顶部操作条（未锁定时显示：锁定按钮 / 关闭按钮 / 拖动手柄）
 *   └─ handle        : 右下角缩放手柄（未锁定时显示）
 *
 * 锁定状态 = 窗口加 FLAG_NOT_TOUCHABLE，全部触摸穿透到下层 App。
 */
public class CaptionWindow {

    public interface Listener {
        /** 用户点了锁定/解锁按钮 */
        void onLockToggle(boolean locked);

        /** 用户点了关闭按钮 */
        void onClose();

        /** 用户在未锁定状态下拖动/缩放了窗口，需要保存位置尺寸 */
        void onGeometryChanged(int x, int y, int wDp, int hDp);
    }

    private static final int ID_BODY = 0x7f0a0001;
    private static final int ID_FINAL = 0x7f0a0002;
    private static final int ID_BAR = 0x7f0a0003;
    private static final int ID_HANDLE = 0x7f0a0004;

    private final Context ctx;
    private final Prefs prefs;
    private final WindowManager wm;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Listener listener;

    private FrameLayout root;
    private LinearLayout boxCard;   // 背景卡片
    private TextView tvFinal;       // 已定稿文字
    private TextView tvBody;        // 临时（正在说）的文字
    private LinearLayout barTop;
    private TextView tvLockBtn;
    private TextView dragIcon;
    private View handle;

    private WindowManager.LayoutParams lp;
    private boolean added = false;
    private boolean locked = false;

    /** 已定稿的历史行 */
    private final ArrayList<String> finalLines = new ArrayList<String>();
    /** 当前临时文本 */
    private String pendingText = "";

    // 拖动/缩放用的临时状态
    private float downRawX, downRawY;
    private int downLpX, downLpY, downW, downH;
    private boolean dragging = false;
    private boolean resizing = false;

    private int screenW, screenH;

    public CaptionWindow(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public boolean isLocked() {
        return locked;
    }

    public boolean isShowing() {
        return added;
    }

    // ============================================================
    //  显示 / 移除
    // ============================================================

    public void show() {
        if (added) return;
        buildViews();
        lp = buildLp();
        try {
            wm.addView(root, lp);
            added = true;
            applyLockedState(locked, false);
            refreshStyle();
        } catch (Throwable t) {
            added = false;
        }
    }

    public void hide() {
        if (!added) return;
        try {
            wm.removeView(root);
        } catch (Throwable ignore) {
        }
        added = false;
    }

    public void setInitialLocked(boolean v) {
        locked = v;
        if (added) applyLockedState(v, true);
    }

    // ============================================================
    //  构建视图
    // ============================================================

    private void buildViews() {
        int pad = dp(12);
        int padS = dp(8);

        root = new FrameLayout(ctx);

        // ---- 卡片背景容器 ----
        boxCard = new LinearLayout(ctx);
        boxCard.setOrientation(LinearLayout.VERTICAL);
        boxCard.setPadding(pad, padS, pad, padS);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        root.addView(boxCard, cardLp);

        // ---- 顶部操作条（未锁定时可见）----
        barTop = new LinearLayout(ctx);
        barTop.setOrientation(LinearLayout.HORIZONTAL);
        barTop.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.bottomMargin = dp(4);
        boxCard.addView(barTop, barLp);

        // 拖动手柄（⋮⋮ 样式）
        dragIcon = new TextView(ctx);
        dragIcon.setText("⠿");
        dragIcon.setTextSize(16f);
        dragIcon.setTextColor(0xFFB0B0B0);
        dragIcon.setPadding(dp(4), 0, dp(8), 0);
        LinearLayout.LayoutParams dragLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        barTop.addView(dragIcon, dragLp);

        // 中间弹性空白
        View spacer = new View(ctx);
        barTop.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        // 锁定按钮
        tvLockBtn = new TextView(ctx);
        tvLockBtn.setText("锁定");
        tvLockBtn.setTextSize(12f);
        tvLockBtn.setTextColor(0xFF0E5F5C);
        tvLockBtn.setBackground(makePill(0xFFD6EDEC));
        tvLockBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lockLp.rightMargin = dp(6);
        barTop.addView(tvLockBtn, lockLp);

        // 关闭按钮
        TextView tvClose = new TextView(ctx);
        tvClose.setText("×");
        tvClose.setTextSize(16f);
        tvClose.setTextColor(0xFF9A9A9A);
        tvClose.setPadding(dp(8), 0, dp(4), 0);
        barTop.addView(tvClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ---- 已定稿文字 ----
        tvFinal = new TextView(ctx);
        tvFinal.setTextColor(0xCCFFFFFF);
        tvFinal.setTextSize(prefs.getFontSize());
        tvFinal.setLineSpacing(dp(4), 1f);
        LinearLayout.LayoutParams finalLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f);
        boxCard.addView(tvFinal, finalLp);

        // ---- 临时文字（正在说的话，高亮）----
        tvBody = new TextView(ctx);
        tvBody.setTextColor(prefs.getTextColor());
        tvBody.setTextSize(prefs.getFontSize());
        tvBody.setLineSpacing(dp(4), 1f);
        tvBody.setMaxLines(prefs.getMaxLines());
        tvBody.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        boxCard.addView(tvBody, bodyLp);

        // ---- 右下角缩放手柄 ----
        handle = new View(ctx);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        handleLp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        root.addView(handle, handleLp);

        // ============ 事件绑定 ============

        // 锁定按钮
        tvLockBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean next = !locked;
                applyLockedState(next, true);
                prefs.setLocked(next);
                if (listener != null) listener.onLockToggle(next);
            }
        });

        // 关闭按钮
        tvClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) listener.onClose();
            }
        });

        // 顶部条拖动
        View.OnTouchListener dragTouch = new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (locked) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        downLpX = lp.x;
                        downLpY = lp.y;
                        dragging = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!dragging) return false;
                        int nx = downLpX + (int) (e.getRawX() - downRawX);
                        int ny = downLpY + (int) (e.getRawY() - downRawY);
                        clampPos(nx, ny);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (dragging) {
                            dragging = false;
                            saveGeometry();
                        }
                        return true;
                }
                return false;
            }
        };
        dragIcon.setOnTouchListener(dragTouch);
        barTop.setOnTouchListener(dragTouch);

        // 右下角缩放
        handle.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (locked) return false;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = e.getRawX();
                        downRawY = e.getRawY();
                        downW = lp.width;
                        downH = lp.height;
                        resizing = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!resizing) return false;
                        int nw = downW + (int) (e.getRawX() - downRawX);
                        int nh = downH + (int) (e.getRawY() - downRawY);
                        lp.width = Math.max(dp(120), nw);
                        lp.height = Math.max(dp(60), nh);
                        if (added) {
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Throwable ignore) {
                            }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        if (resizing) {
                            resizing = false;
                            saveGeometry();
                        }
                        return true;
                }
                return false;
            }
        });

        // 未锁定时整个窗口可点（避免触摸穿透），锁定后由 FLAG 统一处理
        root.setClickable(true);

        applyTextToViews();
    }

    // ============================================================
    //  窗口参数
    // ============================================================

    private WindowManager.LayoutParams buildLp() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;

        // 尺寸
        int wDp = prefs.getWindowWidthDp();
        p.width = wDp > 0 ? dp(wDp) : (screenW - dp(24));
        p.height = dp(prefs.getWindowHeightDp());

        // 位置
        int sx = prefs.getWindowX();
        int sy = prefs.getWindowY();
        if (sx < 0 || sy < 0) {
            p.x = dp(12);
            p.y = screenH - p.height - dp(140);
        } else {
            p.x = sx;
            p.y = sy;
        }
        return p;
    }

    /** 把位置限制在屏幕内 */
    private void clampPos(int nx, int ny) {
        if (lp.width <= 0 || lp.height <= 0) return;
        int maxX = Math.max(0, screenW - lp.width);
        int maxY = Math.max(0, screenH - lp.height);
        lp.x = Math.max(0, Math.min(nx, maxX));
        lp.y = Math.max(0, Math.min(ny, maxY));
        if (added) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable ignore) {
            }
        }
    }

    private void saveGeometry() {
        if (lp == null) return;
        int wDp = Math.round(lp.width / ctx.getResources().getDisplayMetrics().density);
        int hDp = Math.round(lp.height / ctx.getResources().getDisplayMetrics().density);
        prefs.setWindowWidthDp(wDp);
        prefs.setWindowHeightDp(hDp);
        prefs.setWindowPos(lp.x, lp.y);
        if (listener != null) listener.onGeometryChanged(lp.x, lp.y, wDp, hDp);
    }

    // ============================================================
    //  锁定
    // ============================================================

    /**
     * @param v         是否锁定
     * @param touchMode true=锁定开关由本方法主动切换（会更新视图布局）
     */
    private void applyLockedState(boolean v, boolean touchMode) {
        locked = v;
        if (lp == null) return;

        int baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (v) {
            baseFlags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
        }
        lp.flags = baseFlags;

        // UI 提示
        if (tvLockBtn != null) {
            tvLockBtn.setText(v ? "已锁定" : "锁定");
            tvLockBtn.setTextColor(v ? 0xFF9A9A9A : 0xFF0E5F5C);
            tvLockBtn.setBackground(makePill(v ? 0xFFEEEEEE : 0xFFD6EDEC));
        }
        // 锁定时只隐藏拖动手柄和缩放手柄，锁定/关闭按钮保持可见，
        // 保证任何时候都能从屏幕上点回“解锁”（避免锁死后找不到出口）
        if (dragIcon != null) {
            dragIcon.setVisibility(v ? View.GONE : View.VISIBLE);
        }
        if (handle != null) {
            handle.setVisibility(v ? View.GONE : View.VISIBLE);
        }

        if (touchMode && added) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable ignore) {
            }
        }
    }

    // ============================================================
    //  文字刷新
    // ============================================================

    /** 引擎回调：收到识别结果 */
    public void onAsrResult(final String text, final boolean isFinal) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (isFinal) {
                    if (!TextUtils.isEmpty(text)) {
                        finalLines.add(text.trim());
                        int keep = Math.max(1, prefs.getMaxLines());
                        while (finalLines.size() > keep) {
                            finalLines.remove(0);
                        }
                    }
                    pendingText = "";
                } else {
                    pendingText = text == null ? "" : text;
                }
                applyTextToViews();
            }
        });
    }

    /** 清空所有文字 */
    public void clearText() {
        ui.post(new Runnable() {
            @Override
            public void run() {
                finalLines.clear();
                pendingText = "";
                applyTextToViews();
            }
        });
    }

    private void applyTextToViews() {
        if (tvFinal == null || tvBody == null) return;

        if (prefs.isShowHistory() && !finalLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < finalLines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(finalLines.get(i));
            }
            tvFinal.setText(sb.toString());
            tvFinal.setVisibility(View.VISIBLE);
        } else {
            tvFinal.setText("");
            tvFinal.setVisibility(View.GONE);
        }

        if (TextUtils.isEmpty(pendingText)) {
            if (finalLines.isEmpty()) {
                tvBody.setText("等待声音…");
                tvBody.setTextColor(0x88FFFFFF);
            } else {
                tvBody.setText("");
            }
        } else {
            tvBody.setText(pendingText);
            tvBody.setTextColor(prefs.getTextColor());
        }
    }

    // ============================================================
    //  样式
    // ============================================================

    /** 应用透明背景 / 字号等外观设置（设置页改动后调用） */
    public void refreshStyle() {
        if (boxCard == null) return;
        int bg = prefs.getBgColor();
        int alpha = prefs.getBgAlpha();
        int color = Color.argb(alpha,
                Color.red(bg), Color.green(bg), Color.blue(bg));

        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        g.setStroke(dp(1), 0x33FFFFFF);
        boxCard.setBackground(g);

        if (tvBody != null) {
            tvBody.setTextSize(prefs.getFontSize());
            tvBody.setTextColor(prefs.getTextColor());
            tvBody.setMaxLines(prefs.getMaxLines());
        }
        if (tvFinal != null) {
            tvFinal.setTextSize(prefs.getFontSize());
        }
        // 右下角手柄可视化
        if (handle != null) {
            GradientDrawable hg = new GradientDrawable();
            hg.setColor(0x88FFFFFF);
            hg.setCornerRadius(dp(6));
            handle.setBackground(hg);
            handle.setAlpha(0.55f);
            handle.setPadding(0, 0, 0, 0);
        }
    }

    private GradientDrawable makePill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(20));
        return g;
    }

    private int dp(float v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
