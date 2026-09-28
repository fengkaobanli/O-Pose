package com.spazpeek;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 文件日志（调试专用）
 * ====================
 * 真机实测：ColorOS 上 SpazPeek 的应用层 Logcat 输出不可见（系统组件日志正常，
 * 应用自定义 tag 日志丢失，疑似 ROM 日志策略）。为可靠诊断，所有关键日志
 * 同步写入 App 私有目录文件（root 可读）：
 *     /data/data/com.spazpeek/files/ht_debug.log
 *
 * 注意：仅用于低频关键事件（state / 连接结果 / 异常）。高频帧数据不写。
 */
final class HTLog {

    private static final String PATH = "/data/data/com.spazpeek/files/ht_debug.log";
    private static final Object LOCK = new Object();
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private HTLog() {}

    static void log(String tag, String msg) {
        synchronized (LOCK) {
            FileWriter fw = null;
            try {
                File f = new File(PATH);
                if (f.getParentFile() != null) f.getParentFile().mkdirs();
                fw = new FileWriter(f, true);
                fw.write(FMT.format(new Date()) + " [" + tag + "] " + msg + "\n");
            } catch (Throwable ignored) {
            } finally {
                if (fw != null) { try { fw.close(); } catch (Throwable ignored) {} }
            }
        }
    }

    /** 记录异常（含堆栈，截断至 ~4KB） */
    static void log(String tag, String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg);
        if (t != null) {
            sb.append(" :: ").append(t.getClass().getName());
            if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < st.length && sb.length() < 4000; i++) {
                sb.append("\n    at ").append(st[i]);
            }
        }
        log(tag, sb.toString());
    }
}
