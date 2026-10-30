package com.along.livesubtitle;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 悬浮窗的“纯视图层”。
 *
 * 职责边界（严格遵守）：
 *   1. 只负责创建控件、设置外观、拼装布局；
 *   2. 不绑定任何事件（点击/长按/触摸全部留在 CaptionWindow）；
 *   3. 不持有任何业务状态（locked/dragging/engineBusy 一概不碰）；
 *   4. 不引用 WindowManager / lp / listener。
 *
 * 视觉风格：毛玻璃浅色风（v1.6）
 *   - 卡片：垂直双层渐变 + 顶部高光内边 + 底部暗边 + 柔和外阴影
 *   - 顶部条：贴右上角浮动胶囊，不再整行切碎画面
 *   - 进度条：自绘圆角细条（LayerDrawable + GradientDrawable）
 *   - 缩放手柄：右下角斜纹 3 条线
 */
final class WindowView {

    // ---------------- 对外暴露的控件 ----------------
    final FrameLayout root;
    final LinearLayout boxCard;
    final LinearLayout barTop;
    /** v1.9：白胶囊本体（挂在 barTop 内、靠右显示），barTop 自身是整行透明热区 */
    final LinearLayout barPill;
    final TextView tvLockBtn;
    final TextView tvClose;
    final ScrollView scv;
    final TextView tvFinal;
    final TextView tvBody;
    final View pbLoading;
    /** 右下角缩放手柄 */
    final View handle;
    /** v1.10：左下角缩放手柄（镜像绘制） */
    final View handleLeft;

    private final Context ctx;
    private final Prefs prefs;

    // 进度条专用（自绘圆角细条，动态 level）
    private LayerDrawable pbDrawable;

    WindowView(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;

        int pad = dp(14);
        int padS = dp(10);

        root = new FrameLayout(ctx);

        // ---- 卡片背景容器 ----
        boxCard = new LinearLayout(ctx);
        boxCard.setOrientation(LinearLayout.VERTICAL);
        boxCard.setPadding(pad, padS, pad, padS);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        // 留出阴影绘制空间（阴影像素往外扩散，卡片本身缩进来一点）
        int sh = dp(6);
        cardLp.setMargins(sh, sh / 2, sh, sh);
        root.addView(boxCard, cardLp);

        // ---- 顶部操作条（整行热区，胶囊贴右上角）----
        // v1.8：barTop 铺满整行（MATCH_PARENT）当拖动热区，
        //       真正的白胶囊是里面的 barPill（WRAP_CONTENT + 靠右），
        //       这样胶囊左边那片空白也能按住拖动。
        barTop = new LinearLayout(ctx);
        barTop.setOrientation(LinearLayout.HORIZONTAL);
        barTop.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        barTop.setPadding(0, 0, 0, dp(6));
        // 整行热区不加背景（透明），背景交给内部 barPill
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        boxCard.addView(barTop, barLp);

        // ---- 白胶囊（视觉上的那颗，靠右）----
        barPill = new LinearLayout(ctx);
        barPill.setOrientation(LinearLayout.HORIZONTAL);
        barPill.setGravity(Gravity.CENTER_VERTICAL);
        barPill.setPadding(dp(6), dp(3), dp(6), dp(3));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xCCFFFFFF);
        pillBg.setCornerRadius(dp(20));
        pillBg.setStroke(dp(1), 0x55FFFFFF);
        barPill.setBackground(pillBg);
        barPill.setElevation(dp(2));
        LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pillLp.gravity = Gravity.CENTER_VERTICAL;
        barTop.addView(barPill, pillLp);
        // v1.10：删除 6 点拖动图标（顶部条整行已可拖动，图标多余）
        // 分隔线（胶囊内细竖线）
        View vDiv = new View(ctx);
        GradientDrawable divBg = new GradientDrawable();
        divBg.setColor(0x1A0E5F5C);
        divBg.setCornerRadius(dp(1));
        vDiv.setBackground(divBg);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(dp(1), dp(12));
        divLp.rightMargin = dp(4);
        divLp.gravity = Gravity.CENTER_VERTICAL;
        barPill.addView(vDiv, divLp);

        // 锁定按钮：圆形半透明图标
        tvLockBtn = new TextView(ctx);
        tvLockBtn.setText("\u25CB");   // ○ 未锁定态
        tvLockBtn.setTextSize(15f);
        tvLockBtn.setTextColor(0xFF0E5F5C);
        tvLockBtn.setBackground(makeCircle(0x140E5F5C));
        tvLockBtn.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lockLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lockLp.rightMargin = dp(4);
        lockLp.gravity = Gravity.CENTER_VERTICAL;
        barPill.addView(tvLockBtn, lockLp);

        // 关闭按钮（单击无效，长按才关，防误触）
        tvClose = new TextView(ctx);
        tvClose.setText("\u00D7");
        tvClose.setTextSize(17f);
        tvClose.setTextColor(0xFF7A8B89);
        tvClose.setBackground(makeCircle(0x14000000));
        tvClose.setGravity(Gravity.CENTER);
        tvClose.setMinWidth(dp(30));
        tvClose.setMinHeight(dp(30));
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        closeLp.gravity = Gravity.CENTER_VERTICAL;
        barPill.addView(tvClose, closeLp);

        // ---- 已定稿文（放进 ScrollView，历史可上下滑动）----
        scv = new ScrollView(ctx);
        scv.setVerticalScrollBarEnabled(false);
        scv.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scv.setFillViewport(false);
        LinearLayout.LayoutParams scvLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f);
        scvLp.bottomMargin = dp(4);
        boxCard.addView(scv, scvLp);

        tvFinal = new TextView(ctx);
        // 定稿行：降低不透明度，让当前行更突出
        tvFinal.setTextColor(applyTextAlpha(prefs.getTextColor(), 0.66f));
        tvFinal.setTextSize(prefs.getFontSize());
        applyLineSpacing(tvFinal);
        ScrollView.LayoutParams finalLp = new ScrollView.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        scv.addView(tvFinal, finalLp);

        // ---- 临时文字（正在说的话，高亮 + 轻微加粗）----
        tvBody = new TextView(ctx);
        tvBody.setTextColor(applyTextAlpha(prefs.getTextColor(), 1f));
        tvBody.setTextSize(prefs.getFontSize());
        tvBody.setTypeface(tvBody.getTypeface(), android.graphics.Typeface.BOLD);
        applyLineSpacing(tvBody);
        tvBody.setMaxLines(prefs.getMaxLines());
        tvBody.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bodyLp.topMargin = dp(2);
        boxCard.addView(tvBody, bodyLp);

        // ---- 准备阶段进度条（自绘圆角细条，默认隐藏）----
        pbLoading = buildProgressBar();
        pbLoading.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        pbLp.topMargin = dp(8);
        boxCard.addView(pbLoading, pbLp);

        // ---- 右下角缩放手柄（斜纹 3 条）----
        handle = buildHandle(false);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(26), dp(26));
        handleLp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        handleLp.rightMargin = dp(4);
        handleLp.bottomMargin = dp(4);
        root.addView(handle, handleLp);

        // v1.10：左下角再加一个缩放手柄（与右下角镜像对称）
        handleLeft = buildHandle(true);
        FrameLayout.LayoutParams handleLeftLp = new FrameLayout.LayoutParams(dp(26), dp(26));
        handleLeftLp.gravity = Gravity.LEFT | Gravity.BOTTOM;
        handleLeftLp.leftMargin = dp(4);
        handleLeftLp.bottomMargin = dp(4);
        root.addView(handleLeft, handleLeftLp);
    }

    // ============================================================
    //  自绘控件
    // ============================================================

    /** 自绘圆角细进度条：底轨 + 进度层，用 level 驱动。 */
    private View buildProgressBar() {
        GradientDrawable track = new GradientDrawable();
        track.setColor(0x1F0E5F5C);
        track.setCornerRadius(dp(2));

        GradientDrawable bar = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xFF2E8B84, 0xFF0E5F5C});
        bar.setCornerRadius(dp(2));

        LayerDrawable ld = new LayerDrawable(new android.graphics.drawable.Drawable[]{track, bar});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        pbDrawable = ld;

        View v = new View(ctx) {
            @Override
            protected void onDraw(Canvas canvas) {
                // 由 level 控制进度层宽度
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || pbDrawable == null) return;
                int pct = getProgressPct();
                int filled = (int) (w * pct / 100f);
                pbDrawable.setLevel(pct * 100);
                // 手动裁剪进度层
                pbDrawable.setBounds(0, 0, w, h);
                int sc = canvas.save();
                canvas.clipRect(0, 0, filled, h);
                android.graphics.drawable.Drawable prog = pbDrawable.getDrawable(1);
                if (prog != null) {
                    prog.setBounds(0, 0, w, h);
                    prog.draw(canvas);
                }
                canvas.restoreToCount(sc);
                // 底轨
                android.graphics.drawable.Drawable bg = pbDrawable.getDrawable(0);
                if (bg != null) {
                    bg.setBounds(0, 0, w, h);
                    bg.draw(canvas);
                }
                // 进度覆盖在底轨之上再画一次
                sc = canvas.save();
                canvas.clipRect(0, 0, filled, h);
                if (prog != null) {
                    prog.setBounds(0, 0, w, h);
                    prog.draw(canvas);
                }
                canvas.restoreToCount(sc);
            }
        };
        v.setTag(Integer.valueOf(0));
        return v;
    }

    private int getProgressPct() {
        if (pbLoading == null) return 0;
        Object o = pbLoading.getTag();
        if (o instanceof Integer) return ((Integer) o).intValue();
        return 0;
    }

    /** 供 CaptionWindow 设置进度（0-100）。 */
    void setProgress(int pct) {
        if (pct < 0) pct = 0;
        if (pct > 100) pct = 100;
        if (pbLoading != null) {
            pbLoading.setTag(Integer.valueOf(pct));
            pbLoading.invalidate();
        }
    }

    /** 右下角缩放手柄：斜纹 3 条。 */
    private View buildHandle(boolean left) {
        final boolean isLeft = left;
        return new View(ctx) {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                p.setColor(0x550E5F5C);
                p.setStrokeWidth(dp(1.6f));
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeCap(Paint.Cap.ROUND);
            }
            @Override
            protected void onDraw(Canvas canvas) {
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;
                int gap = dp(5);
                int len = dp(7);
                int pad = dp(5);
                if (!isLeft) {
                    // 右下角：从右下往左上画 3 条斜线
                    for (int i = 0; i < 3; i++) {
                        int off = i * gap;
                        float x2 = w - pad - off;
                        float y2 = h - pad;
                        float x1 = w - pad;
                        float y1 = h - pad - len - off;
                        canvas.drawLine(x1, y1, x2 + len, y2 + len, p);
                    }
                } else {
                    // 左下角：从左下往右上画 3 条斜线（镜像）
                    for (int i = 0; i < 3; i++) {
                        int off = i * gap;
                        float x2 = pad + off;
                        float y2 = h - pad;
                        float x1 = pad;
                        float y1 = h - pad - len - off;
                        canvas.drawLine(x1, y1, x2 + len, y2 + len, p);
                    }
                }
            }
        };
    }

    // ============================================================
    //  样式工具
    // ============================================================

    /**
     * 把“用户选的文字颜色”的 alpha 替换成独立的文字透明度设置，
     * 再乘以一个相对系数（区分定稿行 / 当前行）。
     */
    int applyTextAlpha(int baseColor) {
        return applyTextAlpha(baseColor, 1f);
    }

    int applyTextAlpha(int baseColor, float factor) {
        int a = prefs.getTextAlpha();
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        a = Math.round(a * factor);
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return Color.argb(a, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor));
    }

    /**
     * 行距：从 Prefs 读取倍数（默认 1.35）* 100 + 额外 dp 间隙。
     */
    void applyLineSpacing(TextView tv) {
        if (tv == null) return;
        int pct = prefs.getLineSpacingPct();
        if (pct < 100) pct = 100;
        if (pct > 250) pct = 250;
        float mult = pct / 100f;
        int gapDp = prefs.getLineGapDp();
        tv.setLineSpacing(dp(gapDp), mult);
    }

    GradientDrawable makePill(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(20));
        return g;
    }

    /** 圆形底（半径=控件一半）。 */
    GradientDrawable makeCircle(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /**
     * 卡片背景：毛玻璃质感。
     * 由 CaptionWindow 的 refreshStyle() 调用，传入用户底色 / 透明度。
     * 效果 = 外阴影层 + 顶部高光内边 + 垂直渐变主体 + 底部暗边
     */
    void applyCardBackground(int baseColor, int alpha) {
        if (alpha < 0) alpha = 0;
        if (alpha > 255) alpha = 255;
        int r = Color.red(baseColor);
        int g = Color.green(baseColor);
        int b = Color.blue(baseColor);

        // 主体：垂直渐变，上亮下暗，模拟玻璃受光
        int top = Color.argb(alpha, clamp(r + 14), clamp(g + 14), clamp(b + 14));
        int bottom = Color.argb(alpha, clamp(r - 10), clamp(g - 10), clamp(b - 10));
        GradientDrawable body = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{top, bottom});
        body.setCornerRadius(dp(20));

        // 高光内边（顶部一道极细亮线）+ 底部暗边，用 stroke 模拟
        body.setStroke(dp(1), 0x40FFFFFF);

        // 外阴影层：稍微大一圈的低透明度暗色块，通过 drawable 的 padding 差体现
        boxCard.setBackground(body);
        boxCard.setElevation(dp(6));
        boxCard.setClipToOutline(true);
    }

    private int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }

    int dp(float v) {
        float d = 0f;
        try {
            d = ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable ignore) {
        }
        if (d <= 0f) {
            try {
                d = ctx.getResources().getDisplayMetrics().densityDpi / 160f;
            } catch (Throwable ignore) {
            }
        }
        if (d <= 0f) d = 3.0f;
        return Math.round(v * d);
    }
}
