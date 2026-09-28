package com.spazpeek;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import java.util.Locale;

/**
 * 手机 IMU 姿态标尺（标定采集专用）—— 世界坐标系相对旋转版。
 * ====================
 * 修复历史（2026-09-25）：
 *   旧版用 getOrientation 的设备系欧拉角，手机"竖直举握"时 pitch≈±90° 进入
 *   万向节锁区——"左右转头"被耦合分解为 yaw+roll 联动（数值 yaw≈roll），
 *   用户看到"左右转却显示在别的轴上"。
 *   新版改用【世界坐标系相对旋转】：
 *     Rw = R_now · R_baseᵀ   （从基线姿态到当前姿态的旋转，表达于世界系）
 *     Rw 按 ZYX 分解：yawW(绕世界竖直轴) / pitchW(绕世界Y') / rollW(绕世界X'')
 *   映射到用户语义：
 *     左右（转头） = yawW   —— 绕世界竖直轴 ✓ 与手机握持姿态无关
 *     上下（抬头低头）= rollW —— 绕世界横向轴
 *     倾斜（歪头）   = pitchW —— 绕世界前后轴
 *   这样"左右转头"永远走第一栏，不再有奇异区混乱。
 *
 * 传感器：TYPE_GAME_ROTATION_VECTOR（无磁力计，Z 轴对齐重力，抗磁干扰）。
 * 日志：[PHONE] yawW=.. pitchW=.. rollW=..（50ms 节流；高采样模式每帧）。
 */
class PhoneImu implements SensorEventListener {

    interface Listener {
        void onPhonePose(float yawDeg, float pitchDeg, float rollDeg);
    }

    /** 高采样率模式：手机 IMU 全速采样 + 每帧写日志（由界面开关控制，持久化）。 */
    static volatile boolean highRate = false;

    private final SensorManager sm;
    private final Sensor sensor;
    private final Listener listener;

    private final float[] qNow = new float[4];
    private final float[] qBase = new float[4];
    private final float[] mNow = new float[9];
    private final float[] mBase = new float[9];
    private final float[] mRel = new float[9];

    private boolean baseline = false;
    private long lastLog = 0;
    private boolean registered = false;

    PhoneImu(Context ctx, Listener l) {
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        sensor = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        listener = l;
    }

    boolean available() { return sensor != null; }

    void start() {
        baseline = false;
        if (sensor != null && !registered) {
            sm.registerListener(this, sensor, highRate
                    ? SensorManager.SENSOR_DELAY_FASTEST
                    : SensorManager.SENSOR_DELAY_GAME);
            registered = true;
        }
        HTLog.log("PHONE", "sensor start avail=" + (sensor != null) + " highRate=" + highRate);
    }

    void stop() {
        if (sm != null && registered) {
            sm.unregisterListener(this);
            registered = false;
            HTLog.log("PHONE", "sensor stop");
        }
    }

    /** 以当前手机姿态为 0 点（"重置正方向"时与耳机校准同步调用）。 */
    void resetBaseline() { baseline = false; }

    @Override
    public void onSensorChanged(SensorEvent e) {
        if (e.sensor.getType() != Sensor.TYPE_GAME_ROTATION_VECTOR) return;
        SensorManager.getQuaternionFromVector(qNow, e.values);
        if (!baseline) {
            System.arraycopy(qNow, 0, qBase, 0, 4);
            baseline = true;
            HTLog.log("PHONE", String.format(Locale.US,
                    "baseline set (quat) w=%.3f x=%.3f y=%.3f z=%.3f",
                    qBase[0], qBase[1], qBase[2], qBase[3]));
            return;
        }

        // Rw = RNow · RBaseᵀ（世界系相对旋转）
        SensorManager.getRotationMatrixFromVector(mNow, qNow);
        SensorManager.getRotationMatrixFromVector(mBase, qBase);
        multABt(mRel, mNow, mBase);

        // ZYX 分解（Rz(yaw)·Ry(pitch)·Rx(roll)）
        float yawW = (float) Math.toDegrees(Math.atan2(mRel[3], mRel[0]));   // R[1][0], R[0][0]
        float pitchW = (float) Math.toDegrees(Math.asin(clamp(-mRel[6])));   // -R[2][0]
        float rollW = (float) Math.toDegrees(Math.atan2(mRel[7], mRel[8]));  // R[2][1], R[2][2]

        long now = System.currentTimeMillis();
        if (highRate || now - lastLog >= 50) {
            lastLog = now;
            HTLog.log("PHONE", String.format(Locale.US,
                    "yawW=%+.1f pitchW=%+.1f rollW=%+.1f", yawW, pitchW, rollW));
        }

        // 映射（2026-09-25 实测校准，GAME-ROTATION-VECTOR 初始设备系）：
        //   左右转    → pitchW（绕世界Y₀；左转负、右转正）
        //   抬头低头  → yawW  （绕世界Z₀；抬头正、低头负）
        //   歪头      → rollW （绕世界X₀；左歪负、右歪正）
        // 注意：左右转接近 ±90° 时，因分解奇异，另两轴数值可能出现耦合摆动
        //（数学特性）；主显示轴（左右）依然准确。
        if (listener != null) listener.onPhonePose(pitchW, yawW, rollW);
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}

    private static float clamp(float v) {
        return v > 1f ? 1f : (v < -1f ? -1f : v);
    }

    /** out = a · bᵀ （3x3 行主序）。 */
    private static void multABt(float[] out, float[] a, float[] b) {
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                out[r * 3 + c] = a[r * 3] * b[c * 3]
                        + a[r * 3 + 1] * b[c * 3 + 1]
                        + a[r * 3 + 2] * b[c * 3 + 2];
            }
        }
    }
}