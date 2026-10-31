package com.along.livesubtitle;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/**
 * Shizuku shell 封装。
 *
 * 用反射调用 Shizuku.newProcess（private），以 Shizuku(shell) 身份执行命令。
 * 之所以不用 ShizukuBinderWrapper 直连系统 @hide 服务：
 * deviceidle 等 @hide 服务会校验 caller 与 binder 归属，wrapper 换身份后会被拒。
 * 走 shell 命令则完全等价于 adb shell，权限通路已验证可用。
 */
public final class ShizukuShell {

    private ShizukuShell() {}

    /** 命令执行结果 */
    public static final class Result {
        public final int code;
        public final String out;
        public Result(int code, String out) {
            this.code = code;
            this.out = out == null ? "" : out;
        }
        public boolean ok() { return code == 0; }
        public boolean contains(String s) {
            return s != null && out.contains(s);
        }
        @Override public String toString() { return "code=" + code + " out=" + out; }
    }

    private static final Result FAIL = new Result(-1, "");

    /** Shizuku 是否可用（服务和权限都就绪） */
    public static boolean available() {
        try {
            Class<?> c = Class.forName("rikka.shizuku.Shizuku");
            boolean ping = (Boolean) c.getMethod("pingBinder").invoke(null);
            if (!ping) return false;
            int perm = (Integer) c.getMethod("checkSelfPermission").invoke(null);
            return perm == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 以 Shizuku(shell) 身份执行命令并同步读取输出。
     * 超时或异常返回 FAIL（code=-1）。
     */
    public static Result exec(String[] cmd, long timeoutMs) {
        Process p = null;

        // ==== 步骤 1：加载 Shizuku 类 ====
        Class<?> c = null;
        try {
            c = Class.forName("rikka.shizuku.Shizuku");
        } catch (Throwable t) {
            return new Result(-3, "ClassNotFound:" + diag(t));
        }

        // ==== 步骤 2：查找 newProcess 方法 ====
        Method m = null;
        try {
            try {
                m = c.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            } catch (Throwable e1) {
                m = null;
            }
            if (m == null) {
                for (Method cand : c.getDeclaredMethods()) {
                    if (!"newProcess".equals(cand.getName())) continue;
                    if (cand.getParameterTypes().length == 3) { m = cand; break; }
                    if (m == null) m = cand;
                }
            }
        } catch (Throwable t) {
            return new Result(-5, "GetMethodFail:" + diag(t));
        }
        if (m == null) {
            StringBuilder sig = new StringBuilder();
            try {
                for (Method cand : c.getDeclaredMethods()) {
                    if (!"newProcess".equals(cand.getName())) continue;
                    sig.append(cand.getName()).append('(');
                    Class<?>[] cp = cand.getParameterTypes();
                    for (int k = 0; k < cp.length; k++) {
                        if (k > 0) sig.append(',');
                        sig.append(cp[k].getName());
                    }
                    sig.append(") ");
                }
            } catch (Throwable ignored) {
            }
            return new Result(-5, "NoSuchMethod:newProcess [cands=" + (sig.length() == 0 ? "none" : sig.toString().trim()) + "]");
        }

        // ==== 步骤 3：解除访问限制（hidden API 重点雷区） ====
        try {
            m.setAccessible(true);
        } catch (Throwable t) {
            return new Result(-6, "SetAccessibleDenied:" + diag(t));
        }

        // ==== 步骤 4：按实际参数类型构造实参 ====
        Class<?>[] pt;
        try {
            pt = m.getParameterTypes();
        } catch (Throwable t) {
            return new Result(-7, "GetParamTypesFail:" + diag(t));
        }
        Object[] args = new Object[pt.length];
        int strArrSeen = 0;
        for (int i = 0; i < pt.length; i++) {
            if (pt[i] == String[].class) {
                if (strArrSeen == 0) args[i] = cmd;
                else args[i] = new String[0];
                strArrSeen++;
            } else if (pt[i] == String.class) {
                args[i] = "/";
            } else {
                args[i] = null;
            }
        }

        // ==== 步骤 5：反射调用 ====
        Object o = null;
        Throwable invokeErr = null;
        try {
            o = m.invoke(null, args);
        } catch (Throwable t1) {
            invokeErr = t1;
        }
        if (o == null && strArrSeen >= 2) {
            Object[] args2 = new Object[pt.length];
            int seen2 = 0;
            for (int i = 0; i < pt.length; i++) {
                if (pt[i] == String[].class) {
                    args2[i] = (seen2 == 0) ? cmd : new String[0];
                    seen2++;
                } else {
                    args2[i] = null;
                }
            }
            try {
                o = m.invoke(null, args2);
            } catch (Throwable t2) {
                invokeErr = t2;
            }
        }
        if (o == null) {
            return new Result(-4, "InvokeFail:" + diag(invokeErr));
        }
        if (!(o instanceof Process)) {
            return new Result(-8, "NotProcess:" + (o == null ? "null" : o.getClass().getName()));
        }
        p = (Process) o;

        // ==== 步骤 6：读取输出 ====
        try {
            final StringBuilder sb = new StringBuilder();
            final Process fp = p;
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        InputStream is = fp.getInputStream();
                        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line).append(10);
                        }
                        try {
                            InputStream es = fp.getErrorStream();
                            if (es != null) {
                                BufferedReader eb = new BufferedReader(new InputStreamReader(es, "UTF-8"));
                                String el;
                                while ((el = eb.readLine()) != null) {
                                    sb.append(el).append(10);
                                }
                            }
                        } catch (Throwable ignored2) {
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            reader.setDaemon(true);
            reader.start();
            long deadline = System.currentTimeMillis() + timeoutMs;
            boolean done = false;
            while (System.currentTimeMillis() < deadline) {
                try {
                    int rc = p.exitValue();
                    done = true;
                    try { reader.join(500); } catch (InterruptedException ignoreJoin) {}
                    String body = sb.toString();
                    if (body.length() == 0) {
                        // [diag] 进程已退出但没读到任何输出：把 stderr 也一起抛出来
                        // 情况：命令自身无输出 / 读流被提前关闭 / reader 尚未 flushed
                        return new Result(rc, "EmptyOut:rc=" + rc);
                    }
                    return new Result(rc, body);
                } catch (Throwable stillRunning) {
                    // [diag] 关键修复（alpha11）：
                    //   Java 的 Process.exitValue() 在“进程尚未结束”时抛的是
                    //   java.lang.IllegalArgumentException: process hasn't exited
                    //   （不是 IllegalThreadStateException，那是 Thread 类的异常）。
                    //   旧代码 catch 类型写错 -> 异常逃到最外层 -> 恒返回 -9 ReadFail，
                    //   且 exitValue 第一轮就抛，轮询从未生效、sb 一个字符都没攒。
                    //   这里统一 catch Throwable 接住两种实现差异，由 deadline 保证不会死循环。
                    try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                    continue;
                }
            }
            if (!done) {
                try { p.destroy(); } catch (Throwable ignored) {}
                return new Result(-2, "Timeout:" + sb.toString());
            }
            return new Result(-1, "UnknownExit:" + sb.toString());
        } catch (Throwable t) {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
            return new Result(-9, "ReadFail:" + diag(t));
        }
    }

    /** 把异常展开成「类名:message <- cause类名:cause message」，绝不返回裸信息 */
    private static String diag(Throwable t) {
        if (t == null) return "null";
        String s = t.getClass().getName() + ":" + t.getMessage();
        Throwable c = (t instanceof java.lang.reflect.InvocationTargetException) ? t.getCause() : t.getCause();
        if (c != null && c != t) {
            s = s + " <- " + c.getClass().getName() + ":" + c.getMessage();
        }
        return s;
    }

    /** 执行单条命令（默认 8 秒超时） */
    public static Result exec(String cmd) {
        return exec(new String[]{"/system/bin/sh", "-c", cmd}, 8000L);
    }

    /** 给包名加电池白名单；成功判定为输出含 Added */
    public static boolean addPowerWhitelist(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        Result r = exec("cmd deviceidle whitelist +" + pkg);
        if (r.contains("Added:") || r.contains("already")) return true;
        // 命令可能无输出（部分 ROM 静默成功），用查询复核一次
        return isPowerWhitelisted(pkg);
    }

    /**
     * 诊断麦克风是否被别人占用。
     * 注意：ColorOS 会裁掉 dumpsys audio 的 recorders 段，因此这里只做「能不能拿到麦」的事实层判断，
     * 不猜「是谁」，避免误报。返回 null 表示无法判定（不提示），返回非空字符串表示有占用者提示文案。
     */
    public static String micBusyHint(String selfPkg) {
        if (!available()) return null;
        Result r = exec("dumpsys audio");
        if (!r.ok() || r.out.length() == 0) return null;
        String out = r.out;
        int idx = out.indexOf("recorders:");
        if (idx < 0) return null;
        int end = -1;
        String[] marks = new String[]{"players:", "ducked players", "banned uids", "allowed capture"};
        for (int mi = 0; mi < marks.length; mi++) {
            int t = out.indexOf(marks[mi], idx + 20);
            if (t > idx && (end < 0 || t < end)) end = t;
        }
        String seg = end > idx ? out.substring(idx, end) : out.substring(idx);
        if (seg.indexOf("AudioRecordConfiguration") < 0) return null;
        String[] lines = seg.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.indexOf("AudioRecordConfiguration") < 0) continue;
            if (ln.indexOf("state:") < 0) continue;
            int p = ln.indexOf("u/pid:");
            if (p < 0) continue;
            String tail = ln.substring(p + 6);
            int sp = tail.indexOf(' ');
            String upid = sp > 0 ? tail.substring(0, sp) : tail;
            int sl = upid.indexOf('/');
            if (sl < 0) continue;
            String uidStr = upid.substring(0, sl);
            String pidStr = upid.substring(sl + 1);
            if (uidStr.length() == 0 || pidStr.length() == 0) continue;
            try {
                int uid = Integer.parseInt(uidStr.trim());
                String pkg = uidToPkg(uid);
                if (pkg == null || pkg.length() == 0) pkg = "uid " + uid;
                if (pkg.equals(selfPkg)) continue;
                return pkg + " 正在录音";
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** uid 转包名，取不到返回 null（不抛异常） */
    private static String uidToPkg(int uid) {
        try {
            Class<?> c = Class.forName("rikka.shizuku.Shizuku");
            Method m = c.getMethod("getPackagesForUid", int.class);
            Object o = m.invoke(null, uid);
            if (o instanceof String[]) {
                String[] arr = (String[]) o;
                if (arr.length > 0) return arr[0];
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 查询该包是否已在白名单 */
    public static boolean isPowerWhitelisted(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        // 通路1: cmd deviceidle whitelist
        Result r = exec("cmd deviceidle whitelist");
        if (r.ok() && r.out != null && r.out.contains(pkg)) return true;
        // 通路2: 兜底读 dumpsys（部分 ROM 下 cmd 无输出或不可用）
        Result r2 = exec("dumpsys deviceidle whitelist");
        if (r2.out != null && r2.out.contains(pkg)) return true;
        return false;
    }

    /** 诊断用：返回最近一次白名单查询结果的原始文本，供 UI 展示 */
    public static String debugWhitelistRaw() {
        StringBuilder sb = new StringBuilder();
        Result r1 = exec("cmd deviceidle whitelist");
        sb.append("[cmd] code=").append(r1.code).append(" out=").append(clip(r1.out)).append("\n");
        Result r2 = exec("dumpsys deviceidle whitelist");
        sb.append("[dumpsys] code=").append(r2.code).append(" out=").append(clip(r2.out)).append("\n");
        return sb.toString();
    }

    /** 截断过长文本，避免 Toast 撑爆 */
    private static String clip(String s) {
        if (s == null) return "null";
        if (s.length() <= 300) return s;
        return s.substring(0, 300) + "...(len=" + s.length() + ")";
    }
}
