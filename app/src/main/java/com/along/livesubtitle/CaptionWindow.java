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
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.ScrollView;

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

    // ============================================================
    //  静态登记表：记录本进程内所有「已 addView 但可能未移除」的窗口
    //  用途：Service 被系统强杀（不走 onDestroy）或新旧实例交替时，
    //        新实例启动前先 forceRemoveAll()，把残留 View 从 WindowManager 强拆，
    //        避免「旧窗口不关、又新建一个」的叠加问题。
    // ============================================================
    private static final ArrayList<CaptionWindow> ACTIVE = new ArrayList<CaptionWindow>();

    /**
     * 强制移除本进程内所有残留悬浮窗。
     * 在 startCaption() 建新窗之前调用，作为「重复建窗」的第二道防线。
     * @return 被移除的窗口数量
     */
    public static int forceRemoveAll() {
        int n = 0;
        ArrayList<CaptionWindow> copy;
        synchronized (ACTIVE) {
            copy = new ArrayList<CaptionWindow>(ACTIVE);
            ACTIVE.clear();
        }
        for (CaptionWindow w : copy) {
            if (w == null) continue;
            try {
                if (w.added) {
                    w.wlog("forceRemoveAll: removing leftover window");
                    try {
                        w.wm.removeViewImmediate(w.root);
                    } catch (Throwable t) {
                        try { w.wm.removeView(w.root); } catch (Throwable ignore) {}
                    }
                    w.added = false;
                    n++;
                }
            } catch (Throwable t) {
                Log.e("LiveSubtitle", "forceRemoveAll failed", t);
            }
        }
        return n;
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
    /** 新台词到来时强制滚底一次 */
    private boolean broadcastNewLine = false;
    private ScrollView scv;         // 历史记录滚动容器（可上下滑动查看）
    private TextView tvBody;        // 临时（正在说）的文字
    private View pbLoading;   // 模型下载/引擎准备进度条（自绘细条，v1.6 毛玻璃）
    /** 引擎处于准备阶段（下载/加载/等待）时为 true，此时进度条可见。出字幕或就绪后必须置 false */
    private boolean engineBusy = false;
    /** 顶部操作条（整条可按住拖动，按钮区域自动放行） */
    private LinearLayout barTop;
    private TextView tvLockBtn;
    private TextView tvClose;
    private TextView dragIcon;
    private View handle;
    /** 视图层（纯控件构造，无事件无状态） */
    private WindowView wv;

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

    /** 写 svc.log（统一委托 Lg，与 CaptionService 共用同一文件） */
    private void wlog(String msg) {
        Lg.svc(ctx, "[win] " + msg);
    }


    public CaptionWindow(Context ctx, Prefs prefs) {
        this.ctx = ctx;
        this.prefs = prefs;
        this.wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        // 兜底：Service 里 getDisplayMetrics 可能是 0，改用真实屏幕尺寸
        // 全程反射，不依赖编译期 API（规避老 android.jar 未知方法问题）
        if (screenW <= 0 || screenH <= 0) {
            try {
                Object disp = wm.getClass().getMethod("getDefaultDisplay").invoke(wm);
                DisplayMetrics rm = new DisplayMetrics();
                disp.getClass().getMethod("getRealMetrics", DisplayMetrics.class).invoke(disp, rm);
                if (rm.widthPixels > 0) screenW = rm.widthPixels;
                if (rm.heightPixels > 0) screenH = rm.heightPixels;
            } catch (Throwable ignore) {
            }
        }
        if (screenW <= 0 || screenH <= 0) {
            try {
                Object wmx = wm.getClass().getMethod("getMaximumWindowMetrics").invoke(wm);
                Object bounds = wmx.getClass().getMethod("getBounds").invoke(wmx);
                int bw = ((android.graphics.Rect) bounds).width();
                int bh = ((android.graphics.Rect) bounds).height();
                if (bw > 0) screenW = bw;
                if (bh > 0) screenH = bh;
            } catch (Throwable ignore) {
            }
        }
        if (screenW <= 0) screenW = 1080;
        if (screenH <= 0) screenH = 1920;
        wlog("ctor screen=" + screenW + "x" + screenH + " density=" + dm.density);
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
        wlog("show() addView begin: type=" + lp.type + " x=" + lp.x + " y=" + lp.y
                + " w=" + lp.width + " h=" + lp.height + " flags=0x" + Integer.toHexString(lp.flags)
                + " gravity=" + lp.gravity + " screen=" + screenW + "x" + screenH);
        try {
            wm.addView(root, lp);
            added = true;
            synchronized (ACTIVE) {
                if (!ACTIVE.contains(this)) ACTIVE.add(this);
            }
            // 注意：addView 之后 View 还未 measure/layout，此处 getWidth/getHeight 必为 0，
            // 不能当作「窗口尺寸为0」的证据（之前就是被这条日志误导的）。
            // 真正尺寸必须在下一帧 post 之后再取。
            wlog("show() addView SUCCESS, root.parent=" + (root.getParent() != null)
                    + " visibility=" + root.getVisibility()
                    + " (size pending, will log next frame)");
            root.post(new Runnable() {
                @Override
                public void run() {
                    wlog("show() measured after layout: w=" + root.getWidth()
                            + " h=" + root.getHeight()
                            + " visibility=" + root.getVisibility()
                            + " attached=" + (root.getParent() != null));
                }
            });
            applyLockedState(locked, false);
            refreshStyle();
        } catch (Throwable t) {
            added = false;
            wlog("show() addView FAILED: " + Log.getStackTraceString(t));
        }
    }

    public void hide() {
        if (!added) {
            synchronized (ACTIVE) { ACTIVE.remove(this); }
            return;
        }
        try {
            wm.removeView(root);
        } catch (Throwable ignore) {
        }
        added = false;
        synchronized (ACTIVE) { ACTIVE.remove(this); }
    }

    public void setInitialLocked(boolean v) {
        locked = v;
        if (added) applyLockedState(v, true);
    }

    // ============================================================
    //  构建视图
    // ============================================================

    private void buildViews() {
        // 视图构建全部下沉到 WindowView（纯造控件，无事件无状态）
        wv = new WindowView(ctx, prefs);
        root = wv.root;
        boxCard = wv.boxCard;
        barTop = wv.barTop;
        dragIcon = wv.dragIcon;
        tvLockBtn = wv.tvLockBtn;
        tvClose = wv.tvClose;
        scv = wv.scv;
        tvFinal = wv.tvFinal;
        tvBody = wv.tvBody;
        pbLoading = wv.pbLoading;
        handle = wv.handle;
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

        // 关闭按钮：防误触改造
        // 问题背景：窗口全屏宽且 root.setClickable(true)，用户点击屏幕时触摸会被
        // overlay 吃掉，一旦恰好落在这个极窄的 × 上就会直接 stopSelf（表现为
        // “启动几秒后字幕窗自己消失”）。改为长按关闭 + 单击提示，杜绝误触。
        tvClose.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                wlog("tvClose CLICK (ignored, long-press required)");
                android.widget.Toast.makeText(ctx, "长按 × 才可关闭字幕",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });
        tvClose.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                wlog("tvClose LONG_CLICK -> onClose confirmed by user");
                if (listener != null) listener.onClose();
                return true;
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
        // v1.7：顶部条整条可按住拖动 —— 回到最朴素的原生监听写法。
        //       setClickable(true) 是关键：LinearLayout 默认 clickable=false，
        //       空白区域收到 DOWN 后若无人消费会返回 false，导致后续 MOVE 不再派发，
        //       表现就是"按下去没反应"。设成 true 让它稳稳吃掉整串手势。
        barTop.setClickable(true);
        barTop.setLongClickable(false);
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

        // 尺寸（全部做兜底，杜绝 0 或负数导致窗口隐形）
        int wDp = prefs.getWindowWidthDp();
        int hDp = prefs.getWindowHeightDp();
        int wPx = wDp > 0 ? dp(wDp) : (screenW - dp(24));
        int hPx = hDp > 0 ? dp(hDp) : dp(160);
        if (wPx <= 0) wPx = (int) (screenW * 0.9f);
        if (wPx <= 0) wPx = 900;
        if (hPx <= 0) hPx = dp(160);
        if (hPx <= 0) hPx = 480;
        if (wPx > screenW) wPx = screenW;
        if (hPx > screenH) hPx = screenH;
        p.width = wPx;
        p.height = hPx;
        if (p.width <= 0 || p.height <= 0) {
            p.width = 900;
            p.height = 480;
        }
        wlog("buildLp size wDp=" + wDp + " hDp=" + hDp + " -> w=" + p.width + " h=" + p.height
                + " screen=" + screenW + "x" + screenH);

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
        // v1.7 修复：原来这里把文字改成「已锁定 / 锁定」两个/三个汉字，
        // 而 tvLockBtn 是 30dp 的定尺寸圆（lockLp），文字必然溢出到圆外。
        // 改为单字符圆点：未锁定 = ○（空心，墨绿），已锁定 = ●（实心，灰）。
        // 语义靠颜色 + 实心与否表达，永不溢出。
        if (tvLockBtn != null) {
            tvLockBtn.setText(v ? "\u25CF" : "\u25CB");
            tvLockBtn.setTextSize(v ? 11f : 15f);
            tvLockBtn.setTextColor(v ? 0xFF9A9A9A : 0xFF0E5F5C);
            tvLockBtn.setBackground(wv.makeCircle(v ? 0x14000000 : 0x140E5F5C));
        }
        // 锁定时隐藏所有窗口内交互件（拖动/缩放/解锁/关闭），全窗触摸穿透，
        // 解锁统一走通知栏 ACTION_TOGGLE_LOCK（窗口级 FLAG_NOT_TOUCHABLE 收不到触摸，
        // 窗口内按钮即使画出来也点不到，必须靠通知栏这条系统通道）
        if (dragIcon != null) {
            dragIcon.setVisibility(v ? View.GONE : View.VISIBLE);
        }
        if (handle != null) {
            handle.setVisibility(v ? View.GONE : View.VISIBLE);
        }
        if (tvLockBtn != null) {
            tvLockBtn.setVisibility(v ? View.GONE : View.VISIBLE);
        }
        if (tvClose != null) {
            tvClose.setVisibility(v ? View.GONE : View.VISIBLE);
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
                        // 有一句真正定稿了：强制跟到最新行
                        broadcastNewLine = true;
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
        // 出字幕/界面重绘即代表引擎已就绪，收掉准备态进度条
        if (engineBusy) {
            engineBusy = false;
            hideProgress();
        }

        if (prefs.isShowHistory() && !finalLines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < finalLines.size(); i++) {
                if (i > 0) sb.append('\n');
                sb.append(finalLines.get(i));
            }
            tvFinal.setText(sb.toString());
            tvFinal.setVisibility(View.VISIBLE);
            // 异步滚到最新：但只有「原本就贴底」时才跟随，
            // 避免用户正在上滑翻历史时被强行拽回底部。
            if (scv != null) {
                final boolean atBottom = scv.getChildCount() > 0
                        && (scv.getScrollY() + scv.getHeight()
                            >= scv.getChildAt(0).getHeight() - dp(24));
                if (atBottom || broadcastNewLine) {
                    tvFinal.post(new Runnable() {
                        @Override public void run() {
                            if (scv != null) scv.fullScroll(View.FOCUS_DOWN);
                        }
                    });
                }
            }
            broadcastNewLine = false;
        } else {
            tvFinal.setText("");
            tvFinal.setVisibility(View.GONE);
        }

        if (TextUtils.isEmpty(pendingText)) {
            if (finalLines.isEmpty()) {
                tvBody.setText("等待声音…");
                tvBody.setTextColor(applyTextAlpha(0xFFFFFFFF) & 0x66FFFFFF);
            } else {
                tvBody.setText("");
            }
        } else {
            tvBody.setText(pendingText);
            tvBody.setTextColor(applyTextAlpha(prefs.getTextColor()));
        }
    }

    /** 收起进度条（唯一出口） */
    private void hideProgress() {
        if (pbLoading != null) pbLoading.setVisibility(View.GONE);
    }

    /**
     * 统一的进度条显隐控制：整个类只有这里会改 pbLoading 的可见性。
     * 由 AsrEngine.StatusKind 直接决定，不再匹配任何文案字符串。
     */
    private void applyProgress(AsrEngine.StatusKind kind, int pct) {
        if (pbLoading == null) return;
        if (kind != null && kind.showProgress()) {
            engineBusy = true;
            pbLoading.setVisibility(View.VISIBLE);
            // v1.6：进度条改为自绘细条，没有「不确定态转圈」这种系统控件语义。
            // pct < 0（尚未拿到进度）时保留上一次进度值，首次则为 0（空轨道），
            // 视觉上表现为一条静止的底轨，等真实进度到达后再往前走。
            if (pct >= 0 && wv != null) wv.setProgress(pct);
        } else {
            engineBusy = false;
            hideProgress();
        }
    }

    /**
     * 引擎状态回调（结构化版本）。
     * 进度条只由 kind 决定，文案仅用于显示。
     */
    public void onEngineStatusKind(final String msg, final int pct,
                                  final AsrEngine.StatusKind kind) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (tvBody == null) return;
                applyProgress(kind, pct);
                tvBody.setVisibility(View.VISIBLE);

                if (kind == AsrEngine.StatusKind.READY) {
                    tvBody.setText(TextUtils.isEmpty(msg) ? "正在聆听…" : msg);
                    tvBody.setTextColor(applyTextAlpha(0xFFFFFFFF) & 0x66FFFFFF);
                    return;
                }
                if (kind == AsrEngine.StatusKind.ERROR) {
                    tvBody.setTextColor(0xFFFF6B6B);
                    tvBody.setText(pct >= 0 ? (msg + " " + pct + "%") : msg);
                    return;
                }
                // PREPARING / RUNTIME：正常信息色
                tvBody.setTextColor(applyTextAlpha(0xFFFFFFFF) & 0x99FFFFFF);
                tvBody.setText(pct >= 0 ? (msg + " " + pct + "%") : msg);
            }
        });
    }

    /**
     * 兼容旧回调：把 level/pct 翻译成 StatusKind 再走新逻辑。
     * 保留此方法，保证旧引擎实现也能正常工作。
     */
    public void onEngineStatus(final String msg, final int pct, final int level) {
        AsrEngine.StatusKind kind;
        if (level == 1) kind = AsrEngine.StatusKind.READY;
        else if (level == 2) kind = AsrEngine.StatusKind.ERROR;
        else kind = pct >= 0 ? AsrEngine.StatusKind.PREPARING : AsrEngine.StatusKind.RUNTIME;
        onEngineStatusKind(msg, pct, kind);
    }

    /** 在字幕区显示一行状态（引擎错误/下载进度等），让用户能看见程序在干什么 */
    public void setStatusText(final String text) {
        ui.post(new Runnable() {
            @Override public void run() {
                if (tvBody == null) return;
                tvBody.setVisibility(View.VISIBLE);
                tvBody.setTextColor(0xFFFF6B6B);
                tvBody.setText(text);
                // 错误提示是终态，顺手收掉准备态进度条
                applyProgress(AsrEngine.StatusKind.ERROR, -1);
            }
        });
    }

    // ============================================================
    //  样式
    // ============================================================

    /** 应用透明背景 / 字号等外观设置（设置页改动后调用） */
    public void refreshStyle() {
        if (boxCard == null) return;

        // v1.6：卡片皮肤统一交给 WindowView.applyCardBackground 绘制
        // （垂直渐变 + 大圆角 + 高光内边 + elevation 阴影 + 裁剪）。
        // 此处若再手搓 GradientDrawable 会把毛玻璃皮肤整个盖掉，故一律不碰背景。
        if (wv != null) wv.applyCardBackground(prefs.getBgColor(), prefs.getBgAlpha());

        if (tvBody != null) {
            tvBody.setTextSize(prefs.getFontSize());
            tvBody.setTextColor(applyTextAlpha(prefs.getTextColor()));
            tvBody.setMaxLines(prefs.getMaxLines());
        }
        if (tvFinal != null) {
            tvFinal.setTextSize(prefs.getFontSize());
            // 与 WindowView 构造时的口径保持一致：文字色取用户选色 × 0.66
            tvFinal.setTextColor(wv.applyTextAlpha(prefs.getTextColor(), 0.66f));
        }
        // 右下角手柄：v1.6 起是自绘斜纹（View.onDraw），
        // 不能再 setBackground / setAlpha，否则会盖掉自绘内容。
    }

    /**
     * 统一生效行距。
     * 原因：旧代码 setLineSpacing(dp(4), 1f) 的第二个参数是「行高倍数」，
     * 写 1f 等于行高 == 字体高度，所以多行看起来几乎贴在一起。
     * 现在从 Prefs 读取倍数（默认 1.35）+ 额外 dp 间隙。
     */
    private void applyLineSpacing(TextView tv) {
        wv.applyLineSpacing(tv);
    }

    /**
     * 把「用户选的文字颜色」的 alpha 替换成独立的文字透明度设置。
     * 背景墙透明度（getBgAlpha）只作用于 boxCard，两者互不影响。
     */
    private int applyTextAlpha(int baseColor) {
        return wv.applyTextAlpha(baseColor);
    }

    private GradientDrawable makePill(int color) {
        return wv.makePill(color);
    }

    private int dp(float v) {
        return wv.dp(v);
    }
}
