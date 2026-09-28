package com.spazpeek;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 头追接管编排器
 * ==============
 * 1) 确保 /data/local/tmp/vtracker3 存在（缺失时从 assets 部署，经 Shizuku）
 * 2) 以 AirPods MAC 伪装启动 vtracker3（--send --bridge --uuid bt:<MAC>）
 * 3) 连接 TCP 桥 127.0.0.1:53987
 * 4) 连接 AirPods AACP → Start Head Tracking
 * 5) 每帧姿态（度）→ 换算 HID 原始值 → 桥 → 系统空间音频
 */
public final class HeadTrackController {

    public interface Ui {
        void onStatus(String s);
        void onPose(float pitchDeg, float yawDeg, long htFrames, long totalFrames);
        void onTelemetry(String t);
    }

    private static final String TRACKER_PATH = "/data/local/tmp/vtracker3";

    /**
     * 发送符号映射（实测标定 2026-09-25）：
     *   pitch = -1：系统侧上下正确；
     *   yaw   = -1：实测声场需反向——不翻会"跟头"（左转时声源也向左移动），
     *               翻转后声源固定于空间、相对头反向转动。
     * 注：可视化显示层不经过此映射（onPose 给 UI 的为"跟头语义"角）。
     * v27：四元数解码输出与真值正相关（解密报告 vs XM5 r=+0.998），符号直接取 +1；
     *      若听感方向反了，改回 -1 并重新实测。
     */
    private static final String PREF = "spz";
    private static final String K_PITCH_SIGN = "dir_pitch_sign";
    private static final String K_YAW_SIGN = "dir_yaw_sign";
    /** 发送符号映射：可在 App 内「方向标定」自动测定 / 一键反向，持久化到 SharedPreferences。 */
    private volatile float pitchSign = -1f;
    private volatile float yawSign = -1f;
    /**
     * pitch 发送增益：2026-09-28 起 pitch 源为世界系欧拉 eP（幅度真实，无需补偿）。
     * 此前视线法 el 压缩点头（±10°），才需要 2.0 增益；eP 点头 [-59,+34] 已是真实角度，增益归 1。
     */
    private static final float PITCH_GAIN = 1.0f;
    /** 发送增益：可视化保持 1:1 真实角度，送系统的幅度按听感压缩（2026-09-28 实测偏大） */
    private volatile float sendGain = 0.6f; private static final String K_SEND_GAIN = "send_gain";

    /** 软限幅：|v|≤soft 线性通过；soft~hard 压缩（传感器深角度区存在非线性/象限跳变）；超过 hard 截断。 */
    private static float softLimit(float v, float soft, float hard) {
        float a = Math.abs(v);
        if (a <= soft) return v;
        float adj = soft + (a - soft) * 0.15f;
        if (adj > hard) adj = hard;
        return v < 0 ? -adj : adj;
    }

    /**
     * 深角度 yaw 映射 + 帧间限速（实测：传感器 o3 在 ±21000 饱和，
     * 右转数据止步 ~85°、左转可达 ~140°，极限区存在象限跳变）。
     * 映射：|y|≤60° 1:1；60~100° 以 3× 增益推向 180°；>100° 锁定 180°。
     * 限速：相邻帧输出变化 ≤15°，抑制跳变。
     */
    private float lastMappedYaw = 0f;

    private float yawMapDeep(float y) {
        float a = Math.abs(y);
        float s = (y < 0) ? -1f : 1f;
        float m;
        if (a <= 60f) m = a;
        else if (a <= 100f) m = 60f + (a - 60f) * 3f;
        else m = 180f;
        m *= s;
        float d = m - lastMappedYaw;
        if (d > 15f) d = 15f;
        else if (d < -15f) d = -15f;
        lastMappedYaw += d;
        return lastMappedYaw;
    }

    /** 发送用 yaw：1:1 仅限速（防深角度象限跳变），不做深角映射。 */
    private float lastSendYaw = 0f;

    private float yawRateOnly(float y) {
        float d = y - lastSendYaw;
        if (d > 15f) d = 15f;
        else if (d < -15f) d = -15f;
        lastSendYaw += d;
        return lastSendYaw;
    }

    private final Context ctx;
    private final Ui ui;
    private final PoseBridge bridge = new PoseBridge();
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final AtomicBoolean active = new AtomicBoolean(false);
    private volatile AacpHeadTracker tracker;
    private long lastPoseLogAt = 0;
    private long warmupStartMs = 0;
    private long warmupFrames = 0;
    private long lastWarmupTipAt = 0;

    public HeadTrackController(Context ctx, Ui ui) {
        this.ctx = ctx.getApplicationContext();
        this.ui = ui;
        android.content.SharedPreferences sp = this.ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        pitchSign = sp.getFloat(K_PITCH_SIGN, -1f); sendGain = sp.getFloat(K_SEND_GAIN, 0.6f);
        yawSign = sp.getFloat(K_YAW_SIGN, -1f);
    }

    public boolean isActive() {
        return active.get();
    }

    public void start(final String mac) {
        if (!active.compareAndSet(false, true)) return;
        io.execute(new Runnable() {
            @Override public void run() {
                try {
                    if (!ShizukuShell.hasPermission()) {
                        status("Shizuku 未授权：无法部署/启动 vtracker3，请先授权");
                        return;
                    }
                    deployBinary();

                    final String mac12 = mac.replace(":", "").toUpperCase();
                    status(ensureTracker(mac12));

                    boolean ok = false;
                    for (int i = 0; i < 15 && active.get(); i++) {
                        if (bridge.connect()) { ok = true; break; }
                        sleep(200);
                    }
                    if (ok) {
                        status("TCP 桥已连接（127.0.0.1:" + PoseBridge.PORT + "）");
                    } else {
                        status("TCP 桥连接失败：vtracker3 未监听？");
                    }

                    for (int attempt = 1; attempt <= 3 && active.get(); attempt++) {
                        HTLog.log("HTC", "AACP session attempt " + attempt + "/3");
                        AacpHeadTracker t = new AacpHeadTracker(listener);
                        tracker = t;
                        boolean got = t.runSession(ctx, mac);
                        tracker = null;
                        if (!active.get()) break;
                        if (got) break;
                        status("AACP 会话结束（尚无头追数据），2 秒后重试 " + attempt + "/3");
                        sleep(2000);
                    }
                } catch (Throwable t) {
                    status("接管异常：" + t);
                } finally {
                    if (active.get()) status("接管流程结束");
                }
            }
        });
    }

    /** 手动重新校准（以当前头姿为零点）。需会话运行中。
     * 同步清零 yaw 限速器状态，否则限速器会把校准前的残差慢慢"追"回来，
     * 可视化与发送端在校准后出现幽灵漂移（2026-09-28）。 */
    public void recalibrate() {
        lastMappedYaw = 0f;
        lastSendYaw = 0f;
        AacpHeadTracker t = tracker;
        if (t != null) {
            t.recalibrate();
        } else {
            status("校准需在接管运行中操作（先打开开关）");
        }
    }

    /* ─────────────────────────────── 方向标定（坐标系自适应） */

    /**
     * 方向标定向导（2026-09-28）：每次耳机重新配对后四元数坐标系可能变化，
     * 导致左右/上下方向颠倒。本向导让用户依次做「头正 / 左转 / 抬头」三个动作，
     * 自动测定 yaw、pitch 的符号并持久化，无需重编译。
     * 约定：向左转头 → yaw 为正；抬头 → pitch 为正（右手系标准）。
     * 用独立线程（io 线程被会话阻塞占用）。
     */
    public void startDirectionCalib() {
        final AacpHeadTracker t = tracker;
        if (t == null || !active.get()) {
            status("方向标定需在接管运行中操作（先打开开关，等校准完成后）");
            return;
        }
        Thread th = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    status("方向标定①：保持头正、目视前方，别动（2 秒）…");
                    sleep(2000);
                    double[] base = t.calibSample(700);
                    if (base == null) { status("方向标定失败：暂无姿态数据，请等校准完成再试"); return; }

                    status("方向标定②：慢慢向左转头约 45°，保持不动（3 秒）…");
                    sleep(1500);
                    double[] left = t.calibSample(1200);

                    status("方向标定③：回到正前方，再慢慢抬头约 30°，保持（3 秒）…");
                    sleep(1500);
                    double[] up = t.calibSample(1200);

                    if (left == null || up == null) { status("方向标定失败：采样为空，请重试"); return; }

                    double dyaw = left[1] - base[1];
                    double dpitch = up[0] - base[0];
                    boolean okY = Math.abs(dyaw) >= 8.0;
                    boolean okP = Math.abs(dpitch) >= 3.0;

                    // 声场固定语义：声源不动，发送角必须与头动反向。
                    // 左转 raw yaw 为正（多配对稳定）→ 正确发送为负；抬头 raw eP 为正 → 正确发送为负。
                    // 所以符号 = 原始动作极性的反号（此前误写成同号，标定会收敛到错误符号，已修正）。
                    if (okY) { yawSign = dyaw > 0 ? -1f : 1f; }
                    if (okP) { pitchSign = dpitch > 0 ? -1f : 1f; }
                    if (okY || okP) saveSigns();

                    HTLog.log("HTC", String.format(Locale.US,
                            "dircalib: dyaw=%.1f dpitch=%.1f -> yawSign=%+.0f pitchSign=%+.0f",
                            dyaw, dpitch, yawSign, pitchSign));

                    if (!okY && !okP) {
                        status(String.format(Locale.US,
                                "方向标定失败：动作太小/未识别（左转 %.0f° · 抬头 %.0f°），请重试",
                                dyaw, dpitch));
                        return;
                    }
                    String sy = okY ? ("左右" + (yawSign < 0 ? "正常" : "已反向")) : "左右未识别(保留)";
                    String sp2 = okP ? ("上下" + (pitchSign < 0 ? "正常" : "已反向")) : "上下未识别(保留)";
                    status("方向标定完成：" + sy + " · " + sp2);
                } catch (Throwable e) {
                    status("方向标定异常：" + e);
                }
            }
        }, "ht-dircalib");
        th.setDaemon(true);
        th.start();
    }

    /** 一键反向（左右 + 上下同时取反）并持久化，立即生效。 */
    public void flipDirectionSign() {
        yawSign = -yawSign;
        pitchSign = -pitchSign;
        saveSigns();
        status("方向已反向：左右 " + (yawSign > 0 ? "+" : "-") + " · 上下 " + (pitchSign > 0 ? "+" : "-"));
    }

    /** 仅左右反向（上下不动），立即生效并持久化。 */
    public void flipYawSign() {
        yawSign = -yawSign;
        saveSigns();
        status("左右已反向：yaw " + (yawSign > 0 ? "+" : "-") + "（上下保持 " + (pitchSign > 0 ? "+" : "-") + "）");
    }

    /** 仅上下反向（左右不动），立即生效并持久化。 */
    public void flipPitchSign() {
        pitchSign = -pitchSign;
        saveSigns();
        status("上下已反向：pitch " + (pitchSign > 0 ? "+" : "-") + "（左右保持 " + (yawSign > 0 ? "+" : "-") + "）");
    }

    public void setSendGain(float g) { if (g < 0.2f) g = 0.2f; if (g > 1.2f) g = 1.2f; sendGain = g; ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putFloat(K_SEND_GAIN, g).apply(); status(String.format(java.util.Locale.US, "发送幅度 x%.2f 已生效" + (active.get() ? "（热更新中）" : "（下次接管生效，已保存）"), g)); HTLog.log("HT", "sendGain=" + g); }
    public float getSendGain() { return sendGain; }
    private void saveSigns() {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putFloat(K_PITCH_SIGN, pitchSign)
                .putFloat(K_YAW_SIGN, yawSign)
                .apply();
    }

    public void stop() {
        if (!active.compareAndSet(true, false)) return;
        lastMappedYaw = 0f;
        lastSendYaw = 0f;
        final AacpHeadTracker t = tracker;
        tracker = null;
        // ⚠️ 本方法会在 UI 线程被调用（开关回调 / onDestroy）。
        // 绝不能在这里直接做任何 socket I/O —— Android 会抛
        // NetworkOnMainThreadException 直接闪退（真机实测崩溃根因）。
        // 用独立线程：先关 AACP socket 打断 io 线程的阻塞读，再归零、关闭桥。
        Thread th = new Thread(new Runnable() {
            @Override public void run() {
                HTLog.log("HTC", "stop: closing AACP socket");
                if (t != null) {
                    try { t.stop(); } catch (Throwable ignored) {}
                }
                try { bridge.sendNeutral(); } catch (Throwable ignored) {}   // 归零，避免声场偏在一边
                bridge.close();
                HTLog.log("HTC", "stop done");
                status("已停止（vtracker3 保留在后台运行）");
            }
        }, "ht-stop");
        th.setDaemon(true);
        th.start();
    }

    /* ─────────────────────────────── 数据流 */

    private final AacpHeadTracker.Listener listener = new AacpHeadTracker.Listener() {
        @Override public void onState(String state) {
            status(state);
        }

        @Override public void onPose(float pitchDeg, float yawDeg, long htFrames, long totalFrames) {
            if (!active.get()) return;
            if (!bridge.isConnected() && !bridge.connect()) return;   // 帧丢弃

            // ═══ v27：四元数解码输出已数学纯净（与 XM5 真值 r>0.99）═══
            // 移除全部"旧信号补丁"（假低头补偿 / 1.3× 增益 / 深角垂直压缩），避免人为注入耦合
                        if (warmupStartMs == 0) warmupStartMs = System.currentTimeMillis();
            warmupFrames++;
            long wElapsed = System.currentTimeMillis() - warmupStartMs;
            float wFps = wElapsed > 0 ? (warmupFrames * 1000f / wElapsed) : 0f;
            boolean warmed = wFps >= 40f || wElapsed > 4000;
            if (!warmed) {
                ui.onPose(pitchDeg * PITCH_GAIN, yawDeg, htFrames, totalFrames);
                if (System.currentTimeMillis() - lastWarmupTipAt > 800) {
                    lastWarmupTipAt = System.currentTimeMillis();
                    status("warmup " + (int)wFps + " Hz");
                }
                return;
            }
            float p1 = pitchDeg * PITCH_GAIN;

            // 软限幅：|角|>≈135° 区域非线性，压缩这段响应
            float lp = softLimit(p1, 85f, 100f);
            // 发送：1:1 限速（防跳变）
            float lySend = yawRateOnly(yawDeg);

            // 发送符号映射（v27：新解码输出与真值正相关，符号取 +1）
            float mp = pitchSign * lp * sendGain;
            float my = yawSign * lySend * sendGain;
            bridge.sendPoseDegrees(mp, my);
            // UI 监控（v27：显示发送值，MainActivity 读取 PoseBridge.last*）
            ui.onPose(lp, lySend, htFrames, totalFrames);

            // 姿态数据记录（100ms 节流）——用于方向标定
            long nowMs = System.currentTimeMillis();
            if (nowMs - lastPoseLogAt >= 100) {
                lastPoseLogAt = nowMs;
                HTLog.log("POSE", String.format(Locale.US,
                        "raw p=%+.1f y=%+.1f | send p=%+.1f y=%+.1f",
                        pitchDeg, yawDeg, mp, my));
            }
        }

        @Override public void onTelemetry(String line) {
            ui.onTelemetry(line);
        }

        @Override public void onFatal(String error) {
            status("错误：" + error);
        }
    };

    /* ─────────────────────────────── vtracker3 进程管理 */

    private String ensureTracker(String mac12) {
        String have = ShizukuShell.oneLine("if [ -x " + TRACKER_PATH + " ]; then echo yes; else echo no; fi").trim();
        if (!"yes".equals(have)) return "缺少 vtracker3 可执行文件（assets 部署失败，请检查 Shizuku）";

        String pid = ShizukuShell.oneLine("pidof vtracker3").trim();
        if (!pid.isEmpty()) {
            String p0 = pid.split("\\s+")[0];
            String cmd = ShizukuShell.oneLine("cat /proc/" + p0 + "/cmdline 2>/dev/null | tr '\\0' ' '");
            if (cmd.contains("bt:" + mac12)) {
                return "vtracker3 已在运行（复用旧进程，指纹 bt:" + mac12 + "）";
            }
        }
        // v29.1：pkill 用 -x（精确进程名）替代 -f——避免 -f 匹配到"命令行含 vtracker3 字样的本 shell 自身"导致启动命令自杀中断
        ShizukuShell.sh("pkill -x vtracker3; sleep 0.3; nohup " + TRACKER_PATH
                + " --send --bridge --uuid bt:" + mac12
                + " > /data/local/tmp/vtracker3_app.log 2>&1 &");
        sleep(800);
        String pid2 = ShizukuShell.oneLine("pidof vtracker3").trim();
        if (pid2.isEmpty()) return "vtracker3 启动失败（查看 /data/local/tmp/vtracker3_app.log）";
        return "vtracker3 已启动 · 伪装 " + mac12 + " · pid " + pid2;
    }

    private void deployBinary() {
        try {
            String ls = ShizukuShell.oneLine("ls " + TRACKER_PATH + " 2>/dev/null").trim();
            if (ls.contains("vtracker3")) return;   // 已部署则跳过（避免 Text file busy）
            File dst = new File(ctx.getFilesDir(), "vtracker3");
            InputStream in = ctx.getAssets().open("vtracker3");
            FileOutputStream fos = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) fos.write(buf, 0, n);
            fos.close();
            in.close();
            ShizukuShell.sh("cp '" + dst.getAbsolutePath() + "' " + TRACKER_PATH
                    + " && chmod 755 " + TRACKER_PATH);
        } catch (Throwable ignored) {
            // assets 缺失时忽略：视为已由外部部署
        }
    }

    private void status(String s) {
        Log.i("HeadTrackController", s);
        HTLog.log("HTC", s);
        ui.onStatus(s);
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}