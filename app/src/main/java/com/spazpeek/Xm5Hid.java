package com.spazpeek;

import java.io.InputStream;

/**
 * XM5 头追 HID 读取器（Shizuku/root 模式）
 * =========================================
 * Sony WH-1000XM5 对外暴露为"Android 标准头追 HID 设备"（虚拟设备与系统
 * 均认可，见 headtrack-re/TAKEOVER_EXPERIMENT.md）。内核为其创建
 * /dev/hidrawN 节点，输入报告 14 字节 @ ~25Hz：
 *
 *   byte0     = Report ID (0x01)
 *   byte1-6   = 3×int16 LE 姿态量（实测：X=上下 / Y=倾斜 / Z=左右）
 *   byte7-12  = 3×int16 LE 第二组（当前恒为 0）
 *   byte13    = 有效标志 (0x01)
 *
 * 读取方式：Shizuku(root) 执行 `cat /dev/hidrawN`，流式缓冲 + 滑动解码。
 * 目的：与 AirPods(AACP) / 手机 IMU 数据同轴记录，做黄金标准标定拟合。
 *
 * 实测要点（2026-09-26）：
 *  - 设备放歌（A2DP 播放中）才输出数据，否则休眠（hidraw 读到 0 报告）
 *  - 25Hz，静止时噪声 ±50 以内；三轴动作干净分离
 */
final class Xm5Hid {

    interface Ui {
        void onXm5State(String s);          // 状态文本（UI 展示）
        void onXm5Frame(long ts, int a, int b, int c, int disc); // 每帧回调（可空实现）
    }

    private static final int REPORT_LEN = 14;

    private volatile boolean running = false;
    private Thread worker;
    private Process proc;
    private Ui ui;

    boolean isRunning() { return running; }

    void setUi(Ui ui) { this.ui = ui; }

    void start() {
        if (running) return;
        running = true;
        worker = new Thread(this::loop, "xm5-hid");
        worker.setDaemon(true);
        worker.start();
    }

    void stop() {
        running = false;
        try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
        try { if (worker != null) worker.interrupt(); } catch (Throwable ignored) {}
        setState("已停止");
    }

    private void setState(String s) {
        if (ui != null) {
            final String v = s;
            try { ui.onXm5State(v); } catch (Throwable ignored) {}
        }
    }

    /** 主循环：找节点 → 流式读 → 断线重试。 */
    private void loop() {
        HTLog.log("XM5", "reader start");
        setState("启动中…");
        while (running) {
            String node = findNode();
            if (node == null) {
                setState("未找到 WH-1000XM5（hidraw）");
                sleep(3000);
                continue;
            }
            HTLog.log("XM5", "node = " + node);
            StreamResult r = streamOnce(node);
            if (!running) break;
            if (r.frames > 0) {
                HTLog.log("XM5", "stream ended: frames=" + r.frames + " " + r.err);
                setState("流中断，重试中…（已收 " + r.frames + " 帧）");
            } else {
                HTLog.log("XM5", "no data: " + r.err + "（提示：XM5 需放歌才触发）");
                setState("无数据（XM5 需播放音乐触发）");
            }
            sleep(2000);
        }
        HTLog.log("XM5", "reader exit");
    }

    /** 在 /sys/class/hidraw 下各 uevent 中找 WH-1000XM5 对应的 /dev 节点。 */
    private String findNode() {
        try {
            String out = ShizukuShell.sh(
                    "for f in /sys/class/hidraw/hidraw*/device/uevent; do " +
                    "  if grep -q 'WH-1000XM5' \"$f\" 2>/dev/null; then " +
                    "    d=$(echo \"$f\" | sed 's#/device/uevent##'); " +
                    "    echo /dev/$(basename $d); " +
                    "  fi; " +
                    "done");
            if (out != null) {
                for (String line : out.split("\n")) {
                    String s = line.trim();
                    if (s.startsWith("/dev/hidraw")) return s;
                }
            }
        } catch (Throwable t) {
            HTLog.log("XM5", "findNode err", t);
        }
        return null;
    }

    private static final class StreamResult {
        long frames = 0;
        String err = "";
    }

    /** 单次流式读取（阻塞直到出错/停止）。 */
    private StreamResult streamOnce(String node) {
        StreamResult r = new StreamResult();
        InputStream in = null;
        try {
            proc = ShizukuShell.stream("cat " + node);
            in = proc.getInputStream();
            setState("流中 ✓");

            byte[] buf = new byte[4096];
            byte[] acc = new byte[REPORT_LEN * 2];
            int accLen = 0;
            long lastStat = 0;

            while (running) {
                int n = in.read(buf);
                if (n < 0) { r.err = "EOF"; break; }
                if (n == 0) continue;

                // 累积
                if (accLen + n > acc.length) {
                    byte[] na = new byte[Math.max(acc.length * 2, accLen + n)];
                    System.arraycopy(acc, 0, na, 0, accLen);
                    acc = na;
                }
                System.arraycopy(buf, 0, acc, accLen, n);
                accLen += n;

                // 滑动解码 14 字节报告
                int pos = 0;
                while (accLen - pos >= REPORT_LEN) {
                    if (acc[pos] == 0x01 && acc[pos + 13] == 0x01) {
                        int a = s16(acc, pos + 1);
                        int b = s16(acc, pos + 3);
                        int c = s16(acc, pos + 5);
                        r.frames++;
                        long now = System.currentTimeMillis();
                        try {
                            HTLog.log("XM5", a + "," + b + "," + c + " |1");
                            if (ui != null) ui.onXm5Frame(now, a, b, c, 1);
                        } catch (Throwable ignored) {}
                        lastStat = now;
                        pos += REPORT_LEN;
                    } else {
                        pos++; // 未对齐：滑动
                    }
                }
                // 保留残余
                if (pos > 0) {
                    System.arraycopy(acc, pos, acc, 0, accLen - pos);
                    accLen -= pos;
                }
                if (accLen > 4096) accLen = 0; // 防异常膨胀
            }
            if (lastStat == 0 && r.frames == 0) r.err = "无帧";
        } catch (Throwable t) {
            r.err = ShizukuShell.unwrap(t);
        } finally {
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
            try { if (proc != null) proc.destroy(); } catch (Throwable ignored) {}
            proc = null;
        }
        return r;
    }

    private static int s16(byte[] b, int i) {
        return (short) ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8));
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
