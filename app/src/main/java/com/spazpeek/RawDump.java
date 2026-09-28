package com.spazpeek;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 原始帧全量转储（异步版）：数据线程零 I/O —— 仅入队字符串，
 * 专用写线程批量落盘（原同步 open/write/close 版本会拖累帧流）。
 * 每次会话开始重写；累计超上限后停止写入（防意外）。
 */
public class RawDump {

    private static final String PATH = "/data/data/com.spazpeek/files/ht_raw.log";
    private static final long MAX_BYTES = 8 * 1024 * 1024;
    private static final int QUEUE_CAP = 4096;
    private static final ArrayBlockingQueue<String> QUEUE = new ArrayBlockingQueue<>(QUEUE_CAP);
    private static volatile boolean running = false;
    private static long written = 0;
    private static Thread writer;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    /** 会话开始：清空并启动写线程。 */
    public static synchronized void reset(String note) {
        stopWriter();
        try {
            BufferedWriter w = new BufferedWriter(new FileWriter(PATH, false));
            w.write("# session " + note + "\n");
            w.close();
        } catch (Throwable ignored) {}
        written = 0;
        QUEUE.clear();
        running = true;
        writer = new Thread(new Runnable() {
            @Override public void run() {
                BufferedWriter w = null;
                try {
                    w = new BufferedWriter(new FileWriter(PATH, true), 1 << 16);
                    while (running || !QUEUE.isEmpty()) {
                        String s = QUEUE.poll(200, TimeUnit.MILLISECONDS);
                        if (s == null) continue;
                        w.write(s);
                        String s2;
                        while ((s2 = QUEUE.poll()) != null) w.write(s2);
                        w.flush();
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (w != null) { try { w.close(); } catch (Throwable ignored) {} }
                }
            }
        }, "rawdump");
        writer.setDaemon(true);
        writer.start();
    }

    private static void stopWriter() {
        running = false;
        Thread t = writer;
        if (t != null) {
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
        writer = null;
    }

    /** 追加一个包（完整 n 字节 hex）——仅入队，不阻塞数据线程。 */
    public static void packet(byte[] b, int n) {
        if (!running || written > MAX_BYTES) return;
        StringBuilder sb = new StringBuilder(n * 2 + 40);
        sb.append(FMT.format(new Date())).append(" len=").append(n).append(' ');
        for (int i = 0; i < n; i++) {
            int v = b[i] & 0xFF;
            sb.append(Character.forDigit(v >> 4, 16)).append(Character.forDigit(v & 0xF, 16));
        }
        sb.append('\n');
        String line = sb.toString();
        written += line.length();
        if (!QUEUE.offer(line)) {
            QUEUE.poll();      // 队列满：丢最旧，保最新
            QUEUE.offer(line);
        }
    }
}