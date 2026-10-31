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
        try {
            Class<?> c = Class.forName("rikka.shizuku.Shizuku");
            Method m = c.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            Object o = m.invoke(null, cmd, null, null);
            if (!(o instanceof Process)) return FAIL;
            p = (Process) o;
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
                        // stderr 也并入，部分 ROM 把 cmd 输出写到 stderr
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
                    reader.join(300);
                    return new Result(rc, sb.toString());
                } catch (IllegalThreadStateException stillRunning) {
                    try { Thread.sleep(50); } catch (InterruptedException ie) { break; }
                }
            }
            // 超时：销毁进程，返回已读到的部分
            if (!done) {
                try { p.destroy(); } catch (Throwable ignored) {}
                return new Result(-2, sb.toString());
            }
            return new Result(-1, sb.toString());
        } catch (Throwable t) {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
            // 纯静态工具类拿不到 Context，Lg 需要 Context，这里不记日志；失败已通过返回值表达
            return FAIL;
        }
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
        Result r = exec("cmd deviceidle whitelist");
        return r.out.contains(pkg);
    }
}
