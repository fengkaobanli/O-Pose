package com.spazpeek;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * vtracker3 TCP 桥客户端
 * ======================
 * 把姿态写进 127.0.0.1:53987，帧格式：
 *   "rx ry rz vx vy vz disc\n"   （整数，空格分隔）
 *
 * 单位换算（实测标定）：
 *   HID 原始值满量程 32768 = 180°，即 raw = deg * 32768 / 180
 *   轴映射（audio_policy 姿态标签）：rx→pitch，ry→roll，rz→yaw
 */
public final class PoseBridge {

    public static final int PORT = 53987;
    public static final float RAW_PER_DEG = 32768f / 180f;

    private Socket socket;
    private OutputStream out;

    /** 最近一次发送给系统（vtracker3/audioserver）的值——供 UI 监控显示 */
    public static volatile float lastPitchDeg, lastYawDeg;
    public static volatile int lastRx, lastRz;

    public synchronized boolean connect() {
        close();
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            s.setTcpNoDelay(true);
            socket = s;
            out = s.getOutputStream();
            return true;
        } catch (IOException e) {
            socket = null;
            out = null;
            return false;
        }
    }

    public synchronized boolean isConnected() {
        return socket != null && socket.isConnected() && !socket.isClosed();
    }

    public synchronized boolean send(int rx, int ry, int rz, int vx, int vy, int vz, int disc) {
        if (out == null) return false;
        try {
            String line = rx + " " + ry + " " + rz + " " + vx + " " + vy + " " + vz + " " + disc + "\n";
            out.write(line.getBytes("US-ASCII"));
            out.flush();
            return true;
        } catch (IOException e) {
            close();
            return false;
        }
    }

    /** 姿态角（度）→ HID 原始值 → 桥。rx=pitch，rz=yaw，ry 暂空（roll 未使用）。 */
    public synchronized void sendPoseDegrees(float pitchDeg, float yawDeg) {
        int rx = clampRaw(Math.round(pitchDeg * RAW_PER_DEG));
        int rz = clampRaw(Math.round(yawDeg * RAW_PER_DEG));
        lastPitchDeg = pitchDeg;
        lastYawDeg = yawDeg;
        lastRx = rx;
        lastRz = rz;
        send(rx, 0, rz, 0, 0, 0, 1);
    }

    /** 归零（停止接管时调用，避免声场偏在一边） */
    public synchronized void sendNeutral() {
        send(0, 0, 0, 0, 0, 0, 1);
    }

    private static int clampRaw(int v) {
        return Math.max(-32767, Math.min(32767, v));
    }

    public synchronized void close() {
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
        }
        socket = null;
        out = null;
    }
}