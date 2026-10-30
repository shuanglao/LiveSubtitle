package com.along.livesubtitle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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
 * 对外只暴露 public 字段，宿主通过 wv.root、wv.tvLockBtn
 * 等直接访问，事件绑定与状态机仍由 CaptionWindow 掌管。
 */
final class WindowView {

    // ---------------- 对外暴露的控件 ----------------
    final FrameLayout root;
    final LinearLayout boxCard;
    final LinearLayout barTop;
    final TextView dragIcon;
    final TextView tvLockBtn;
    final TextView tvClose;
    final ScrollView scv;
    final TextView tvFinal;
    final TextView tvBody;
    final ProgressBar pbLoading;
    final View handle;

    private final Context ctx;
    private final Prefs prefs;

    WindowView(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;

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
        dragIcon.setText("\u283f");
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

        // 关闭按钮（单击无效，长按才关，防误触）
        tvClose = new TextView(ctx);
        tvClose.setText("×");
        tvClose.setTextSize(16f);
        tvClose.setTextColor(0xFF9A9A9A);
        tvClose.setPadding(dp(10), dp(6), dp(10), dp(6));
        // 保证足够触摸面积（避免又窄又小被误命中）
        tvClose.setMinWidth(dp(36));
        tvClose.setMinHeight(dp(36));
        tvClose.setGravity(Gravity.CENTER);
        barTop.addView(tvClose, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

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
        tvFinal.setTextColor(applyTextAlpha(0xFFFFFFFF));
        tvFinal.setTextSize(prefs.getFontSize());
        applyLineSpacing(tvFinal);
        // 高度 WRAP_CONTENT：让内容自然撑开，ScrollView 才有东西可滚
        ScrollView.LayoutParams finalLp = new ScrollView.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        scv.addView(tvFinal, finalLp);

        // ---- 临时文字（正在说的话，高亮）----
        tvBody = new TextView(ctx);
        tvBody.setTextColor(applyTextAlpha(prefs.getTextColor()));
        tvBody.setTextSize(prefs.getFontSize());
        applyLineSpacing(tvBody);
        tvBody.setMaxLines(prefs.getMaxLines());
        tvBody.setEllipsize(TextUtils.TruncateAt.START);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        boxCard.addView(tvBody, bodyLp);

        // ---- 准备阶段进度条（默认隐藏，状态机驱动）----
        pbLoading = new ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal);
        pbLoading.setMax(100);
        pbLoading.setProgress(0);
        pbLoading.setVisibility(View.GONE);
        LinearLayout.LayoutParams pbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(3));
        pbLp.topMargin = dp(4);
        boxCard.addView(pbLoading, pbLp);

        // ---- 右下角缩放手柄 ----
        handle = new View(ctx);
        FrameLayout.LayoutParams handleLp = new FrameLayout.LayoutParams(dp(22), dp(22));
        handleLp.gravity = Gravity.RIGHT | Gravity.BOTTOM;
        root.addView(handle, handleLp);
    }

    // ============================================================
    //  样式工具（原本在 CaptionWindow，只依赖 ctx + prefs，随视图层迁入）
    // ============================================================

    /**
     * 把“用户选的文字颜色”的 alpha 替换成独立的文字透明度设置。
     * 背景墙透明度（getBgAlpha）只作用于 boxCard，两者互不影响。
     */
    int applyTextAlpha(int baseColor) {
        int a = prefs.getTextAlpha();
        if (a < 0) a = 0;
        if (a > 255) a = 255;
        return Color.argb(a, Color.red(baseColor), Color.green(baseColor), Color.blue(baseColor));
    }

    /**
     * 行距：从 Prefs 读取倍数（默认 1.35）* 100 + 额外 dp 间隙。
     * 旧代码 setLineSpacing(dp(4), 1f) 第二参数写死 1f 等于行高==字高，会贴在一起。
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

    int dp(float v) {
        float d = 0f;
        try {
            d = ctx.getResources().getDisplayMetrics().density;
        } catch (Throwable ignore) {
        }
        if (d <= 0f) {
            // density 拿不到（Service 上下文偶发），按 3.0 兜底，避免所有尺寸归零
            try {
                d = ctx.getResources().getDisplayMetrics().densityDpi / 160f;
            } catch (Throwable ignore) {
            }
        }
        if (d <= 0f) d = 3.0f;
        return Math.round(v * d);
    }
}
