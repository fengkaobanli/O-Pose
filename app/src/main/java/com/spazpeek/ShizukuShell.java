package com.spazpeek;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 桥：以 shell(uid 2000) 身份执行命令。
 * 只要 Shizuku 服务在跑，就不需要 root。
 */
public final class ShizukuShell {

    public static final int REQUEST_CODE = 4242;

    private ShizukuShell() {}

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            if (!binderAlive()) return false;
            // 只认服务端的真实裁决：0=GRANTED / -1=DENIED
            // 注意：checkRemotePermission() 在 root 服务（serverUid==0）下会无条件返回 GRANTED，
            // 那是个假信号，不能用来判授权。
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {}
    }

    /** 把反射包装异常拆开，拿到真实 cause。 */
    public static String unwrap(Throwable t) {
        Throwable c = t;
        int guard = 0;
        while ((c instanceof InvocationTargetException)
                && ((InvocationTargetException) c).getCause() != null
                && guard++ < 8) {
            c = ((InvocationTargetException) c).getCause();
        }
        String m = c.getMessage();
        return c.getClass().getName() + (m == null ? "" : ": " + m);
    }

    /**
     * Shizuku 13.x 把 Shizuku.newProcess 收成了 private（官方希望业务走 UserService），
     * 但它是同一 dex 里的类，反射调用完全合法且稳定。
     */
    private static Method NEW_PROCESS;

    static {
        try {
            NEW_PROCESS = Shizuku.class.getDeclaredMethod(
                    "newProcess", String[].class, String[].class, String.class);
            NEW_PROCESS.setAccessible(true);
        } catch (Throwable t) {
            NEW_PROCESS = null;
        }
    }

    private static Process exec(String cmd) throws Exception {
        if (NEW_PROCESS == null) {
            throw new IllegalStateException("Shizuku.newProcess 不可用");
        }
        return (Process) NEW_PROCESS.invoke(null, (Object) new String[]{"sh", "-c", cmd}, null, null);
    }

    /**
     * 流式执行：返回 Process，由调用方自行读取 stdout（用于 cat /dev/hidrawN 之类长连接）。
     * 注意：完成后必须 destroy()。
     */
    public static Process stream(String cmd) throws Exception {
        return exec(cmd);
    }

    /** 执行命令并收集 stdout。失败不会抛异常，而是把错误写进返回值。 */
    public static String sh(String cmd) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
        } catch (Throwable t) {
            sb.append("[!] ").append(unwrap(t)).append('\n');
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Throwable ignored) {}
            }
        }
        return sb.toString();
    }

    /** 只取第一行（用于 id 之类）。 */
    public static String oneLine(String cmd) {
        String s = sh(cmd);
        if (s == null) return "";
        s = s.trim();
        int i = s.indexOf('\n');
        return i > 0 ? s.substring(0, i) : s;
    }

    private static String tryI(String name, Object value) {
        return name + " = " + value + "\n";
    }

    /** 逐项打印 Shizuku API 真实返回 / 真实异常，用于定位。 */
    public static String diag() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Shizuku 诊断 ---\n");
        sb.append(tryI("pingBinder", safe(() -> Shizuku.pingBinder())));
        sb.append(tryI("getBinder", safe(() -> Shizuku.getBinder())));
        sb.append(tryI("isPreV11", safe(() -> Shizuku.isPreV11())));
        sb.append(tryI("getUid", safe(() -> Shizuku.getUid())));
        sb.append(tryI("getVersion", safe(() -> Shizuku.getVersion())));
        sb.append(tryI("getLatestServiceVersion", safe(() -> Shizuku.getLatestServiceVersion())));
        sb.append(tryI("getSELinuxContext", safe(() -> Shizuku.getSELinuxContext())));
        sb.append(tryI("checkSelfPermission", safe(() -> Shizuku.checkSelfPermission())));
        sb.append(tryI("checkRemotePermission", safe(
                () -> Shizuku.checkRemotePermission("moe.shizuku.manager.permission.API_V23"))));
        sb.append(tryI("shouldShowRationale", safe(() -> Shizuku.shouldShowRequestPermissionRationale())));
        sb.append(tryI("NEW_PROCESS", NEW_PROCESS == null ? "null" : "ok"));
        // 直接试跑一条命令，把真实异常打出来
        sb.append("exec(id) => ");
        try {
            Process p = exec("id");
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l = r.readLine();
            r.close();
            sb.append(l).append("\n");
        } catch (Throwable t) {
            sb.append("[!] ").append(unwrap(t)).append("\n");
        }
        sb.append("---------------------\n");
        return sb.toString();
    }

    private interface Getter { Object get() throws Throwable; }

    private static String safe(Getter g) {
        try {
            return String.valueOf(g.get());
        } catch (Throwable t) {
            return "[!] " + unwrap(t);
        }
    }
}