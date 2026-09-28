package com.spazpeek;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * AACP 头追读取器（AirPods → 姿态角）
 * =====================================
 * 复刻 LibrePods 的 AACP 通道机制：
 *  1) 反射构造 L2CAP CoC BluetoothSocket（type=3, PSM 4097,
 *     UUID 74ec2172-0bad-4d01-8f77-997b2be0722a），connect()
 *  2) 发送初始化序列（握手 / 特性位 / 通知 / 邻近密钥 / 0x29）
 *  3) 发送 Start Head Tracking（0x17, alternate 版；无数据时自动换原版重试）
 *  4) 读取 ≥70B 的 0x17 帧（v26 四元数版，2026-09-26 协议破译）：
 *       64B 块 @ 固定标记 3a3e08101a3a01（间距 64B，多块包逐块解析）
 *       x = [块+26..27], y = [块+28..29], z = [块+30..31]   (int16 LE)
 *       w = +√(1−x²−y²−z²) 隐式（固件按 w≥0 半球归一化）
 *  5) 双覆盖符号连续化 + 前 10 块校准四元数中性位，
 *       视线向量法输出 pitch(el)/yaw(az)（度）——转头后低头不混轴
 *
 * 正常 App 权限即可（BLUETOOTH_CONNECT + 已配对），无需 root。
 */
public class AacpHeadTracker {

    public interface Listener {
        /** 状态（人类可读，可能频繁） */
        void onState(String state);
        /** 校准后的姿态（度）与帧统计 */
        void onPose(float pitchDeg, float yawDeg, long htFrames, long totalFrames);
        /** 原始遥测（o1/o2/o3、包统计），约 2Hz 节流 */
        void onTelemetry(String line);
        /** 致命错误（连接层） */
        void onFatal(String error);
    }

    private static final String TAG = "AacpHeadTracker";
    public static final String AACP_UUID = "74ec2172-0bad-4d01-8f77-997b2be0722a";
    public static final int AACP_PSM = 4097;

    /* ---- LibrePods 报文（hex，逐字节复刻） ---- */
    private static final String P_HANDSHAKE = "00000400010002000000000000000000";
    private static final String P_SETFLAGS  = "040004004d00d70000000000000000";
    private static final String P_NOTIF     = "040004000f00ffffffff";
    private static final String P_PROXREQ   = "0400040030000500";
    private static final String P_EQ29      = "04000400290000ffffffffffffff";
    /* ---- 实验：上报周期可配（默认 40ms；20ms=高速实验档）----
     * 409c0000 = 40000μs = 40ms = 25Hz（旧默认档）。
     * 204e0000 = 20000μs = 20ms = 约 43~50Hz（高速档；v26 起四元数解码，方向正确）。
     * v28：恒定 20ms（界面开关已移除）。 */
    static volatile int periodUs = 20000;

    private static String le4(int v) {
        return String.format("%02x%02x%02x%02x", v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF, (v >> 24) & 0xFF);
    }

    /** 把 Start 包尾部的上报周期（1A0501 + 4B LE μs）替换为当前配置值。 */
    private static String withPeriod(String pkt) {
        return pkt.substring(0, pkt.length() - 8) + "1a0501" + le4(periodUs);
    }

    private static final String P_START_ALT = "040004001700000010000f000873420b081010021a0501409c0000";
    private static final String P_START_ORI = "04000400170000001000100008a102420b080e10021a0501409c0000";
    private static final String P_STOP_ALT  = "040004001700000010000f000875420b081010021a050100000000";
    private static final String P_STOP_ORI  = "040004001700000010001100087e1002420b084e10021a050100000000";

    /* ---- 实验：Start 变体×周期对照序列（一次会话自动跑完 5 段）----
     * 段0 ALT/40ms（对照） 段1 ORI/40ms 段2 ALT/20ms 段3 ORI/20ms 段4 ALT/10ms
     * 每段 15s，结果写 ht_debug.log（EXP seg 行）。设为 false 恢复正式逻辑。 */
    static final boolean EXP_SEQ = false;
    /* 速率自愈：Start 后周期性测帧率，不达标则重新 Stop→Start（最多 8 轮）。
     * v24 实验：耳机可能需多轮 Stop→Start 才被完全激活（EXP 实测 seg2 前有 2 轮）。*/
    static final boolean RATE_BOOST = true;
    private static volatile boolean boostActive = false;   // BOOST 运行中（watchdog 禁默）
    private long expFrames = 0;
    private static final String EXP_BASE_ALT = "040004001700000010000f000873420b081010021a0501";
    private static final String EXP_BASE_ORI = "04000400170000001000100008a102420b080e10021a0501";

    /* ---- 校准 ---- */
    private static final int CAL_N = 10;
    private static final int ORIENT_OFFSET = 5500;

    private final Listener listener;
    private volatile boolean running;
    private volatile boolean connectDone;      // connect 看门狗标志
    private volatile BluetoothSocket socket;   // 跨线程访问：io 读循环 / ht-stop 关闭线程
    private long framesTotal, framesHt, framesOther, framesRaw;
    private int lastOpcode = -1, lastLen;
    private int lastO1, lastO2, lastO3, lastAH, lastAV;
    private int o1N, o2N, o3N;
    private boolean calibrated;
    private long lastTelemetry;
    private long lastDataLog;
    private volatile boolean recalibRequested;   // 手动校准请求（UI线程置位，io线程消费）
    private volatile long lastFrameAtMs = 0;     // 最近收到任意包的时刻（断流看门狗用）
    private boolean manualCal;                   // 当前校准是否手动（5 帧快校准）
    private final List<int[]> calSamples = new ArrayList<>();

    /* ═══ v26 四元数解码状态（2026-09-26 协议破译版）═══ */
    private boolean quatHasPrev;                 // 符号连续化是否已有基准
    private float qPw, qPx, qPy, qPz;            // 上一块四元数（连续化基准）
    private int quatCalN;                        // 校准样本计数
    private float qCalW, qCalX, qCalY, qCalZ;    // 校准中性位四元数
    // 视线法基准 F0：校准时的头部朝前方向（水平向量；2026-09-26 实测 (-0,-1,0)）
    private static final float F0X = 0f, F0Y = -1f, F0Z = 0f;

    /* ═══ 方向标定向导采样（2026-09-28）：窗口内累加 az/el，供向导判定符号 ═══ */
    private volatile boolean calibSampling = false;
    private final Object calibLock = new Object();
    private double calibSumP, calibSumY;
    private int calibCnt;

    public AacpHeadTracker(Listener l) {
        this.listener = l;
    }

    /* ─────────────────────────────── 会话主流程（阻塞，直到断开） */

    @SuppressLint("MissingPermission")
    public boolean runSession(Context ctx, String mac) {
        if (running) return false;
        running = true;
        RawDump.reset("mac=" + mac);   // 全收集：新会话重写 ht_raw.log
        Log.i(TAG, "runSession mac=" + mac + " sdk=" + android.os.Build.VERSION.SDK_INT);
        HTLog.log(TAG, "runSession mac=" + mac + " sdk=" + android.os.Build.VERSION.SDK_INT);
        try {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                listener.onFatal("蓝牙未开启");
                return false;
            }
            BluetoothDevice device = findBonded(adapter, mac);
            if (device == null) {
                listener.onFatal("未找到已配对设备 " + mac);
                return false;
            }

            state("构造 L2CAP 通道…");
            socket = createL2capSocket(adapter, device);

            state("连接 AACP（PSM " + AACP_PSM + "）…");
            try {
                Method g = BluetoothSocket.class.getDeclaredMethod("getConnectionType");
                g.setAccessible(true);
                HTLog.log(TAG, "socket connectionType=" + g.invoke(socket));
            } catch (Throwable t) {
                HTLog.log(TAG, "getConnectionType fail: " + t);
            }
            final BluetoothSocket csock = socket;
            Thread wd = new Thread(new Runnable() {
                @Override public void run() {
                    sleep(12000);
                    if (!connectDone) {
                        HTLog.log(TAG, "connect() 超过 12s 未返回，强制关闭 socket 打断");
                        try { csock.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "aacp-connect-wd");
            wd.setDaemon(true);
            wd.start();
            long t0 = System.currentTimeMillis();
            socket.connect();
            connectDone = true;
            String cmsg = "connect() OK, 耗时 " + (System.currentTimeMillis() - t0) + "ms";
            Log.i(TAG, cmsg);
            HTLog.log(TAG, cmsg);
            state("AACP 已连接，发送初始化序列…");

            OutputStream out = socket.getOutputStream();
            send(out, P_HANDSHAKE);
            send(out, P_SETFLAGS);
            send(out, P_NOTIF);
            send(out, P_PROXREQ);
            sleep(200);
            send(out, P_HANDSHAKE);
            send(out, P_SETFLAGS);
            send(out, P_NOTIF);
            send(out, P_EQ29);
            send(out, P_PROXREQ);
            sleep(200);
            /* 正式流程 v2（2026-09-26 二次实验结论）：
             * 第一次 Start = 唤醒耳机（唤醒后停在 ~1.6Hz 慢速遥测）；
             * Stop→Start = 让它进入正常速率（EXP 实测 40ms→22fps / 20ms→35fps）。 */
            send(out, withPeriod(P_START_ALT));
            sleep(2500);
            send(out, P_STOP_ALT);
            sleep(400);
            send(out, withPeriod(P_START_ALT));
            state("已发送 Start Head Tracking（alternate，" + (periodUs / 1000) + "ms），等待数据…");

            /* ═══ 速率自愈 v2（每轮切换配置——耳机只在配置变化时重新初始化头追流；
             * 已激活后自动切到目标配置 periodUs）═══ */
            if (RATE_BOOST) {
                final OutputStream bo = out;
                Thread bt = new Thread(new Runnable() {
                    @Override public void run() {
                        sleep(2000);
                        boostActive = true;
                        final String[][] cfgs = {
                                {"alt", "40000"}, {"ori", "40000"},
                                {"alt", "10000"}, {"ori", "20000"},
                                {"alt", "30000"},
                        };
                        String curV = "alt"; int curUs = periodUs;
                        int ci = 0;
                        for (int round = 0; round < 10 && running; round++) {
                            long f0 = framesHt;
                            sleep(5000);
                            if (!running) return;
                            long got = framesHt - f0;
                            double fps = got / 5.0;
                            HTLog.log(TAG, "BOOST r" + round + " [" + curV + "/" + curUs + "] fps="
                                    + String.format(java.util.Locale.US, "%.1f", fps));
                            if (fps >= 15 && curV.equals("alt") && curUs == periodUs) {
                                boostActive = false;
                                HTLog.log(TAG, "BOOST 达标于目标配置(" + String.format(java.util.Locale.US, "%.1f", fps) + "fps)");
                                return;
                            }
                            String nv; int nu;
                            if (fps >= 15) {
                                nv = "alt"; nu = periodUs;      // 已激活：直接切到目标
                            } else {
                                nv = cfgs[ci % cfgs.length][0];
                                nu = Integer.parseInt(cfgs[ci % cfgs.length][1]);
                                ci++;
                            }
                            send(bo, curV.equals("ori") ? P_STOP_ORI : P_STOP_ALT);
                            sleep(400);
                            send(bo, (nv.equals("ori") ? EXP_BASE_ORI : EXP_BASE_ALT) + le4(nu));
                            HTLog.log(TAG, "BOOST switch -> " + nv + "/" + nu);
                            curV = nv; curUs = nu;
                        }
                        boostActive = false;
                        HTLog.log(TAG, "BOOST 用尽 10 轮");
                    }
                }, "rate-boost");
                bt.setDaemon(true);
                bt.start();
            }

            startRetryThread(out);

            /* ═══ 实验：变体×周期对照序列（EXP_SEQ 时自动跑 5 段）═══ */
            if (EXP_SEQ) {
                final OutputStream eo = out;
                Thread et = new Thread(new Runnable() {
                    @Override public void run() {
                        sleep(2000);
                        String[][] plan = {
                                {"alt", "40000"},
                                {"ori", "40000"},
                                {"alt", "20000"},
                                {"ori", "20000"},
                                {"alt", "10000"},
                        };
                        for (int i = 0; i < plan.length && running; i++) {
                            String v = plan[i][0];
                            int us = Integer.parseInt(plan[i][1]);
                            String stop = v.equals("ori") ? P_STOP_ORI : P_STOP_ALT;
                            String base = v.equals("ori") ? EXP_BASE_ORI : EXP_BASE_ALT;
                            send(eo, stop);
                            sleep(400);
                            long before = expFrames;
                            send(eo, base + le4(us));
                            long t0 = System.currentTimeMillis();
                            HTLog.log(TAG, "EXP seg" + i + " start " + v + "/" + us + "us");
                            while (running && System.currentTimeMillis() - t0 < 15000) sleep(250);
                            long got = expFrames - before;
                            HTLog.log(TAG, "EXP seg" + i + " done " + v + "/" + us
                                    + " frames=" + got + " avgFps=" + String.format(java.util.Locale.US, "%.2f", got / 15.0));
                        }
                        HTLog.log(TAG, "EXP all done (保持最后配置)");
                    }
                }, "exp-seq");
                et.setDaemon(true);
                et.start();
            }

            // 流看门狗：>3s 无任何包 → 重发 Start；>8s → Stop+Start 整轮恢复
            // （抗后台冻结积压 / 耳机端流憋死——实测发生过一次 4 分钟冻结后流彻底死亡）
            lastFrameAtMs = System.currentTimeMillis();
            final OutputStream wdOut = out;
            Thread swd = new Thread(new Runnable() {
                @Override public void run() {
                    while (running) {
                        sleep(1000);
                        if (!running) return;
                        if (EXP_SEQ || boostActive) continue;   // 实验/BООST 期间不干预
                        long idle = System.currentTimeMillis() - lastFrameAtMs;
                        if (idle > 8000) {
                            HTLog.log(TAG, "stream watchdog: idle " + idle + "ms, full re-init");
                            send(wdOut, P_STOP_ALT);
                            sleep(300);
                            send(wdOut, withPeriod(P_START_ALT));
                            lastFrameAtMs = System.currentTimeMillis();
                        } else if (idle > 3000) {
                            HTLog.log(TAG, "stream watchdog: idle " + idle + "ms, re-send Start");
                            send(wdOut, withPeriod(P_START_ALT));
                            lastFrameAtMs = System.currentTimeMillis();
                        }
                    }
                }
            }, "aacp-stream-watchdog");
            swd.setDaemon(true);
            swd.start();

            /* ═══ 首数据超时（2026-09-28：重连后第一次接管必失败根因）═══
             * 现象：蓝牙重连后 L2CAP connect() 正常（~70ms），Start 也发出去了，
             * 但耳机端 AACP 服务还没就绪，6 秒只回 1 个包、framesHt=0，read 永远阻塞，
             * runSession 不返回 → Controller 的 3 次重试永远触发不了，只能手动重开。
             * 修法：Start 后 12s 若仍无头追帧，主动关 socket 让会话返回 false，
             * Controller 自动进 attempt 2/3（此时耳机端已就绪，实测第二次必成）。 */
            Thread firstDataWd = new Thread(new Runnable() {
                @Override public void run() {
                    sleep(12000);
                    if (running && framesHt == 0) {
                        HTLog.log(TAG, "首数据超时：12s 无头追帧（framesTotal=" + framesTotal + "），主动断开触发自动重试");
                        state("首轮未收到头追数据，自动重试…");
                        try { BluetoothSocket s = socket; if (s != null) s.close(); } catch (Throwable ignored) {}
                    }
                }
            }, "aacp-firstdata-wd");
            firstDataWd.setDaemon(true);
            firstDataWd.start();

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[4096];
            while (running) {
                int n = in.read(buf);
                if (n < 0) break;
                if (n > 0) handleFrame(buf, n);
            }
        } catch (SecurityException se) {
            Log.e(TAG, "缺少蓝牙权限", se);
            HTLog.log(TAG, "SecurityException", se);
            listener.onFatal("蓝牙权限不足：" + se.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "session failed", t);
            HTLog.log(TAG, "session failed", t);
            if (running) listener.onFatal(shortErr(t));
        } finally {
            running = false;
            closeQuiet();
            String end = "会话结束: framesTotal=" + framesTotal + " ht=" + framesHt
                    + " other=" + framesOther + " raw=" + framesRaw;
            Log.i(TAG, end);
            HTLog.log(TAG, end);
            state("AACP 会话结束");
        }
        return framesHt > 0;
    }

    /**
     * 线程安全：立即关闭 socket 打断阻塞的 read（可在任意线程调用）。
     * 不做任何 write —— 避免 socket 半死时阻塞调用线程（UI 线程调用须永不阻塞）。
     */
    public void stop() {
        running = false;
        final BluetoothSocket s = socket;
        socket = null;
        try { if (s != null) s.close(); } catch (Throwable ignored) {}
    }

    public long framesHt() { return framesHt; }

    /** 请求手动重新校准：以当前头姿为新的中性位（5 帧平均，约 200ms）。 */
    public void recalibrate() {
        recalibRequested = true;
    }

    /**
     * 方向标定采样：阻塞 ms 毫秒，返回该窗口内 (pitch, yaw) 的均值（度）。
     * 需会话运行且已校准；无样本返回 null。供方向标定向导判符号用。
     */
    public double[] calibSample(long ms) {
        synchronized (calibLock) {
            calibSumP = 0; calibSumY = 0; calibCnt = 0; calibSampling = true;
        }
        sleep(ms);
        synchronized (calibLock) {
            calibSampling = false;
            if (calibCnt == 0) return null;
            return new double[] { calibSumP / calibCnt, calibSumY / calibCnt };
        }
    }

    /* ─────────────────────────────── L2CAP 反射构造 */

    private static BluetoothSocket createL2capSocket(BluetoothAdapter adapter, BluetoothDevice device)
            throws Exception {
        // 策略0：hiddenapi 反射过滤豁免（double-reflection 技巧，Android 9~16）
        boolean exempt = tryHiddenApiExempt();
        HTLog.log(TAG, "hiddenapi exempt=" + exempt);

        logL2capMethods();

        // 策略1：createUsingSocketSettings + Settings.Builder（SDK 可见；type=3 = BR/EDR L2CAP）
        try {
            BluetoothSocket s = viaSocketSettings(device);
            Log.i(TAG, "L2CAP via createUsingSocketSettings(3) OK");
            HTLog.log(TAG, "L2CAP via createUsingSocketSettings(3) OK");
            return s;
        } catch (Throwable e) {
            Log.w(TAG, "viaSocketSettings fail: " + e);
            HTLog.log(TAG, "viaSocketSettings fail", e);
            Throwable c = e.getCause();
            for (int i = 0; c != null && i < 4; i++) {
                HTLog.log(TAG, " viaSS cause[" + i + "] " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
        }

        // 策略2：hidden 方法 createL2capSocket（BLOCKED，豁免后可用；内部 type=3）
        try {
            Method m = BluetoothDevice.class.getDeclaredMethod("createL2capSocket", int.class);
            m.setAccessible(true);
            BluetoothSocket s = (BluetoothSocket) m.invoke(device, AACP_PSM);
            Log.i(TAG, "L2CAP via createL2capSocket() OK");
            HTLog.log(TAG, "L2CAP via createL2capSocket() OK");
            return s;
        } catch (Throwable e) {
            Log.w(TAG, "createL2capSocket() fail: " + e);
            HTLog.log(TAG, "createL2capSocket() fail", e);
        }

        // 策略3：反射构造器 (device, type=3, auth, encrypt, psm, uuid)——豁免后可用
        try {
            Constructor<BluetoothSocket> ctor = BluetoothSocket.class.getDeclaredConstructor(
                    BluetoothDevice.class, int.class, boolean.class, boolean.class, int.class, ParcelUuid.class);
            ctor.setAccessible(true);
            BluetoothSocket s = ctor.newInstance(device, 3, true, true, AACP_PSM, null);
            Log.i(TAG, "L2CAP via ctor(device,3..) OK");
            HTLog.log(TAG, "L2CAP via ctor(device,3..) OK");
            return s;
        } catch (Throwable e) {
            Log.w(TAG, "ctor(device,3..) fail: " + e);
            HTLog.log(TAG, "ctor(device,3..) fail", e);
        }

        // 策略3b：公开 API createInsecureL2capChannel（LE CoC 不加密兜底）
        try {
            BluetoothSocket s = device.createInsecureL2capChannel(AACP_PSM);
            Log.i(TAG, "L2CAP via createInsecureL2capChannel(LE) OK");
            HTLog.log(TAG, "L2CAP via createInsecureL2capChannel(LE) OK");
            return s;
        } catch (Throwable e) {
            Log.w(TAG, "createInsecureL2capChannel fail: " + e);
            HTLog.log(TAG, "createInsecureL2capChannel fail", e);
        }

        // 策略4：公开 API createL2capChannel（LE CoC 兜底）
        try {
            BluetoothSocket s = device.createL2capChannel(AACP_PSM);
            Log.i(TAG, "L2CAP via createL2capChannel(LE) fallback OK");
            HTLog.log(TAG, "L2CAP via createL2capChannel(LE) fallback OK");
            return s;
        } catch (Throwable e) {
            Log.w(TAG, "createL2capChannel fail: " + e);
            HTLog.log(TAG, "createL2capChannel fail", e);
        }

        logDeclaredCtors();
        throw new IllegalStateException("L2CAP 全策略失败（详见 ht_debug.log）");
    }

    private static void logDeclaredCtors() {
        HTLog.log(TAG, "BluetoothSocket isInterface=" + BluetoothSocket.class.isInterface()
                + " super=" + (BluetoothSocket.class.getSuperclass() == null ? "null" : BluetoothSocket.class.getSuperclass().getName()));
        StringBuilder sb = new StringBuilder();
        for (Constructor<?> c : BluetoothSocket.class.getDeclaredConstructors()) {
            sb.append('(');
            Class<?>[] ps = c.getParameterTypes();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(ps[i].getSimpleName());
            }
            sb.append(") ");
        }
        Log.i(TAG, "BluetoothSocket declared ctors: " + sb);
        HTLog.log(TAG, "BluetoothSocket declared ctors: " + sb);
        StringBuilder mb = new StringBuilder();
        for (Method m : BluetoothSocket.class.getDeclaredMethods()) {
            mb.append(m.getName()).append(';');
        }
        HTLog.log(TAG, "BluetoothSocket methods: " + mb);
    }

    private static void logL2capMethods() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Method m : BluetoothDevice.class.getDeclaredMethods()) {
                if (!m.getName().toLowerCase(Locale.US).contains("l2cap")) continue;
                sb.append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(ps[i].getSimpleName());
                }
                sb.append("):").append(m.getReturnType().getSimpleName()).append("; ");
            }
            Log.i(TAG, "BluetoothDevice l2cap methods: " + sb);
            HTLog.log(TAG, "BluetoothDevice l2cap methods: " + sb);
        } catch (Throwable t) {
            HTLog.log(TAG, "logL2capMethods fail: " + t);
        }
    }

    /** hiddenapi 反射过滤豁免（double-reflection 技巧）。成功返回 true。 */
    private static boolean tryHiddenApiExempt() {
        try {
            Method forName = Class.class.getDeclaredMethod("forName", String.class);
            Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntime, "getRuntime", new Class<?>[0]);
            Method setExempt = (Method) getDeclaredMethod.invoke(vmRuntime, "setHiddenApiExemptions",
                    new Class<?>[] { String[].class });
            Object runtime = getRuntime.invoke(null);
            setExempt.invoke(runtime, (Object) new String[] { "L" });
            return true;
        } catch (Throwable t) {
            HTLog.log(TAG, "hiddenapi豁免失败", t);
            Throwable c = t.getCause();
            for (int i = 0; c != null && i < 4; i++) {
                HTLog.log(TAG, " hiddenapi cause[" + i + "] " + c.getClass().getName() + ": " + c.getMessage());
                c = c.getCause();
            }
            return false;
        }
    }

    /** 策略1：createUsingSocketSettings + BluetoothSocketSettings.Builder（type=3 = BR/EDR L2CAP CoC） */
    private static BluetoothSocket viaSocketSettings(BluetoothDevice device) throws Exception {
        Class<?> ssc = Class.forName("android.bluetooth.BluetoothSocketSettings");
        Class<?> bld = Class.forName("android.bluetooth.BluetoothSocketSettings$Builder");
        Object builder = bld.getDeclaredConstructor().newInstance();
        HTLog.log(TAG, "settings builder created");
        bld.getDeclaredMethod("setSocketType", int.class).invoke(builder, 3);   // TYPE_L2CAP (BR/EDR)
        bld.getDeclaredMethod("setL2capPsm", int.class).invoke(builder, AACP_PSM);
        bld.getDeclaredMethod("setAuthenticationRequired", boolean.class).invoke(builder, true);
        bld.getDeclaredMethod("setEncryptionRequired", boolean.class).invoke(builder, true);
        HTLog.log(TAG, "settings configured type=3 psm=" + AACP_PSM);
        Object settings = bld.getDeclaredMethod("build").invoke(builder);
        Method m = BluetoothDevice.class.getDeclaredMethod("createUsingSocketSettings", ssc);
        m.setAccessible(true);
        return (BluetoothSocket) m.invoke(device, settings);
    }

    private static Class<?> prim(Class<?> c) {
        if (c == Integer.class) return int.class;
        if (c == Boolean.class) return boolean.class;
        if (c == Long.class) return long.class;
        return c;
    }

    @SuppressLint("MissingPermission")
    private static BluetoothDevice findBonded(BluetoothAdapter adapter, String mac) {
        Set<BluetoothDevice> bonded = adapter.getBondedDevices();
        if (bonded == null) return null;
        for (BluetoothDevice d : bonded) {
            if (mac == null || mac.isEmpty() || mac.equalsIgnoreCase(d.getAddress())) return d;
        }
        return null;
    }

    /* ─────────────────────────────── 帧处理 */

    private void handleFrame(byte[] b, int n) {
        lastFrameAtMs = System.currentTimeMillis();   // 断流看门狗：任意包刷新
        RawDump.packet(b, n);   // 全收集：原始包全字节转储
        framesTotal++;
        if (n < 6 || b[0] != 0x04 || b[1] != 0x00 || b[2] != 0x04 || b[3] != 0x00) {
            framesRaw++;
            return;
        }
        int op = b[4] & 0xFF;
        lastOpcode = op;
        lastLen = n;
        if (op == 0x17) {
            if (n >= 70) {
                framesHt++;
                expFrames++;
                parseHeadTracking(b, n);
            } else {
                framesOther++;
            }
        } else {
            framesOther++;
        }
        maybeTelemetry();
    }

    /* ═══ v26 四元数解码（2026-09-26 协议破译版）═══
     * 64B 块内的姿态 = 单位四元数 (x,y,z)：x@块+26、y@+28、z@+30（int16 LE，32768=1.0）
     * w = +√(1−x²−y²−z²) 隐式（固件按 w≥0 半球归一化）
     * 固件在 w 过零时把 x/y/z 集体反号（q≡−q 双覆盖）→ 必须做符号连续化
     * 块起点 = 固定标记 3a3e08101a3a01；多块包（145~593B）逐块解析
     * 输出：视线向量法（转头后低头不混轴；F0=校准时头部朝前方向）
     */
    /* 64B 块的起始标记。
     * 旧固件/旧配置：3a3e08101a3a01（精确匹配）
     * 2026-09-28 实测：耳机每次重配对/重连都可能换标记第2字节：
     *   10011a3c100001 → 10031a3c100001（第2字节 01→03，其余不变）。
     * 稳定后缀是 1a3c100001，前缀固定 10 __。所以新式标记只匹配
     * b[i]==0x10 && b[i+2..i+6]==1a3c100001，第2字节通配。
     * 精确匹配会让 findMarker 每帧返回 -1 → 不校准、不 onPose → 姿态与音场全冻结。 */
    private static final byte[] BLK_MARK_OLD = {0x3a, 0x3e, 0x08, 0x10, 0x1a, 0x3a, 0x01};
    // 新式标记后缀（第2字节通配）：10 ?? 1a 3c 10 00 01
    private static final byte[] BLK_MARK_NEW_SUFFIX = {0x1a, 0x3c, 0x10, 0x00, 0x01};

    private static boolean markAt(byte[] b, int i, byte[] m) {
        for (int k = 0; k < m.length; k++) if (b[i + k] != m[k]) return false;
        return true;
    }

    private static boolean markNewAt(byte[] b, int i) {
        if (b[i] != 0x10) return false;
        for (int k = 0; k < BLK_MARK_NEW_SUFFIX.length; k++) {
            if (b[i + 2 + k] != BLK_MARK_NEW_SUFFIX[k]) return false;
        }
        return true;
    }

    private int findMarker(byte[] b, int n, int from) {
        for (int i = from; i + 7 <= n; i++) {
            if (markNewAt(b, i)) return i;
            if (markAt(b, i, BLK_MARK_OLD)) return i;
        }
        return -1;
    }

    private void parseHeadTracking(byte[] b, int n) {
        int pos = findMarker(b, n, 0);
        if (pos < 0) return;   // 无块标记（异常帧）

        if (recalibRequested) {
            recalibRequested = false;
            quatCalN = 0;
            calibrated = false;
            manualCal = true;
            state("重新校准中…（保持头正 1 秒）");
        }

        while (pos >= 0 && pos + 64 <= n) {
            processBlock(b, pos);
            pos = findMarker(b, n, pos + 1);
        }
    }

    private void processBlock(byte[] b, int base) {
        int xi = le16(b, base + 26);
        int yi = le16(b, base + 28);
        int zi = le16(b, base + 30);
        lastO1 = xi; lastO2 = yi; lastO3 = zi;   // 遥测显示（现为四元数原始值）
        lastAH = 0; lastAV = 0;

        float x = xi / 32768f, y = yi / 32768f, z = zi / 32768f;
        float w2 = 1f - x * x - y * y - z * z;
        float w = (float) Math.sqrt(Math.max(0f, w2));
        float nrm = (float) Math.sqrt(w * w + x * x + y * y + z * z);
        if (nrm > 1e-6f) { w /= nrm; x /= nrm; y /= nrm; z /= nrm; }

        // 双覆盖符号连续化（与上一块点积 <0 则整体取反）
        if (quatHasPrev && (w * qPw + x * qPx + y * qPy + z * qPz) < 0) {
            w = -w; x = -x; y = -y; z = -z;
        }
        qPw = w; qPx = x; qPy = y; qPz = z; quatHasPrev = true;

        if (!calibrated) {
            if (quatCalN == 0) {
                qCalW = w; qCalX = x; qCalY = y; qCalZ = z;
            } else {
                float d = w * qCalW + x * qCalX + y * qCalY + z * qCalZ;
                float sg = d < 0 ? -1f : 1f;
                qCalW += sg * w; qCalX += sg * x; qCalY += sg * y; qCalZ += sg * z;
            }
            quatCalN++;
            int need = manualCal ? 5 : CAL_N;
            if (quatCalN >= need) {
                float nn = (float) Math.sqrt(qCalW * qCalW + qCalX * qCalX + qCalY * qCalY + qCalZ * qCalZ);
                if (nn > 1e-6f) { qCalW /= nn; qCalX /= nn; qCalY /= nn; qCalZ /= nn; }
                calibrated = true;
                manualCal = false;
                state("校准完成（四元数中性位）");
            }
            return;
        }

        // qRel = q ⊗ qCal⁻¹（相对校准姿态的世界系旋转）
        float cw = qCalW, cx = -qCalX, cy = -qCalY, cz = -qCalZ;
        float rw = w * cw - x * cx - y * cy - z * cz;
        float rx = w * cx + x * cw + y * cz - z * cy;
        float ry = w * cy - x * cz + y * cw + z * cx;
        float rz = w * cz + x * cy - y * cx + z * cw;

        // 视线向量法：f = qRel ⊗ F0（F0 为校准头部朝前方向，水平）
        float tx = 2f * (ry * F0Z - rz * F0Y);
        float ty = 2f * (rz * F0X - rx * F0Z);
        float tz = 2f * (rx * F0Y - ry * F0X);
        float fx = F0X + rw * tx + (ry * tz - rz * ty);
        float fy = F0Y + rw * ty + (rz * tx - rx * tz);
        float fz = F0Z + rw * tz + (rx * ty - ry * tx);

        double az = Math.toDegrees(Math.atan2(fy, fx) - Math.atan2(F0Y, F0X));
        az = (az + 540.0) % 360.0 - 180.0;
        double el = Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, fz))));

        // 世界系欧拉俯仰（ZYX）：点头/歪头/转头的仲裁者，见下注释
        float ecp = (float) Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, 2 * (rw * ry - rz * rx)))));
        float pitch = ecp;   // 抬头/低头：世界系欧拉俯仰（2026-09-28 实测：视线法 el 压缩点头±10°、
        // 歪头±54°会 1:1 漏进 el；而 eP 点头 [-59,+34] 真实、歪头基本平坦、左右转平坦，改用 eP）
        float yaw = (float) az;     // 左右转头（视线法方位角，左右转干净，无需换）

        // 诊断（v27）：世界系欧拉角 + 头顶向量——用于方向映射分析
        float ecy = (float) Math.toDegrees(Math.atan2(2 * (rw * rz + rx * ry), 1 - 2 * (ry * ry + rz * rz)));
        float ecr = (float) Math.toDegrees(Math.atan2(2 * (rw * rx + ry * rz), 1 - 2 * (rx * rx + ry * ry)));
        float uwx = 2f * (x * z + w * y);      // u = q⊗(0,0,1) 世界系头顶方向
        float uwy = 2f * (y * z - w * x);
        float uwz = 1f - 2f * (x * x + y * y);

        if (calibSampling) {
            synchronized (calibLock) {
                if (calibSampling) { calibSumP += ecp; calibSumY += az; calibCnt++; }
            }
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastDataLog >= 100) {
            lastDataLog = nowMs;
            HTLog.log("DATA", String.format(Locale.US,
                    "Q x=%+.3f y=%+.3f z=%+.3f w=%+.3f | el=%+.1f az=%+.1f | eP=%+.0f eR=%+.0f eY=%+.0f | u=%+.2f,%+.2f,%+.2f",
                    x, y, z, w, el, az, ecp, ecr, ecy, uwx, uwy, uwz));
        }

        listener.onPose(pitch, yaw, framesHt, framesTotal);
    }

    private void maybeTelemetry() {
        long now = System.currentTimeMillis();
        if (now - lastTelemetry < 500) return;
        lastTelemetry = now;
        listener.onTelemetry(String.format(Locale.US,
                "包 %d · 头追 %d · 其他 %d · 原始 %d | op=0x%02X len=%d | quat x=%d y=%d z=%d",
                framesTotal, framesHt, framesOther, framesRaw,
                lastOpcode, lastLen, lastO1, lastO2, lastO3));
    }

    private void startRetryThread(final OutputStream out) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                int attempt = 1;
                while (running && framesHt == 0 && attempt <= 6) {
                    sleep(2500);
                    if (!running || framesHt > 0) break;
                    attempt++;
                    boolean ori = (attempt % 2 == 0);
                    send(out, ori ? withPeriod(P_START_ORI) : withPeriod(P_START_ALT));
                    state("重试 Start 包 #" + attempt + "（" + (ori ? "原版" : "alternate") + "，" + (periodUs / 1000) + "ms）");
                }
            }
        }, "aacp-start-retry");
        t.setDaemon(true);
        t.start();
    }

    /* ─────────────────────────────── 小工具 */

    private static int le16(byte[] b, int i) {
        return (short) ((b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8));
    }

    private void send(OutputStream out, String hex) {
        try {
            out.write(hexToBytes(hex));
            out.flush();
        } catch (Throwable e) {
            Log.w(TAG, "send(" + hex.substring(0, Math.min(8, hex.length())) + ") 失败: " + e);
            HTLog.log(TAG, "send fail: " + e);
        }
    }

    static byte[] hexToBytes(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private void state(String s) {
        Log.i(TAG, "state: " + s);
        HTLog.log(TAG, "state: " + s);
        listener.onState(s);
    }

    private void closeQuiet() {
        try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        socket = null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static String shortErr(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }
}