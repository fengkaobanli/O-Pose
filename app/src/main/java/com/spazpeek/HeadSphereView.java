package com.spazpeek;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * 头部姿态可视化：3D 线框球 + 蓝点。
 *
 * 语义（v29，观察者位于用户"背后"）：
 *   - 中心灰点 = "我"的头/位置（固定参考点）。
 *   - 蓝点 = 面部朝向的投影，恒为实心：
 *       正方向（校准中性位）= 蓝点实心压住灰点（最大最亮）
 *       转头/抬头 = 蓝点向对应方向偏移；转到背面（|角|>90°）= 变暗缩小沉入深处
 *   - yaw 为正（右转）→ 蓝点向右移动；pitch 为正（抬头）→ 蓝点向上移动。
 *   - |角| = 90° 到达外圈；超过 90° 越过边缘、明暗切换表示绕到另一侧。
 *
 * 「正方向重置」：把当前姿态存为参考基准（蓝点回到"正方向"位置）。
 */
public class HeadSphereView extends View {

    private volatile float pitchDeg = 0f;
    private volatile float yawDeg = 0f;

    private float basePitch = 0f;
    private float baseYaw = 0f;
    // 可视化自身方向符号（背后视角默认：上下需反向才与抬头一致）
    private volatile float vizPitchSign = -1f;
    private volatile float vizYawSign = 1f;
    private volatile int sendRx = 0;
    private volatile int sendRz = 0;
    private volatile float sendP = 0f;
    private volatile float sendY = 0f;
    public void setSendInfo(int rx, int rz, float p, float y) {
        sendRx = rx; sendRz = rz; sendP = p; sendY = y;
    }

    private float density = 1f;

    private final Paint pFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHalo = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCenter = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HeadSphereView(Context c) {
        super(c);
        init();
    }

    public HeadSphereView(Context c, AttributeSet a) {
        super(c, a);
        init();
    }

    private void init() {
        density = getResources().getDisplayMetrics().density;

        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeWidth(dp(1.5f));
        pLine.setColor(0xFF53657A);

        pGrid.setStyle(Paint.Style.STROKE);
        pGrid.setStrokeWidth(dp(1f));
        pGrid.setColor(0xFF2E3E50);

        pLink.setStyle(Paint.Style.STROKE);
        pLink.setStrokeWidth(dp(2f));
        pLink.setColor(0x882196F3);

        pDot.setStyle(Paint.Style.FILL);
        pDot.setColor(0xFF2196F3);

        pHalo.setStyle(Paint.Style.FILL);
        pHalo.setColor(0x332196F3);

        pCenter.setStyle(Paint.Style.FILL);
        pCenter.setColor(0xFF5A6B7C);
    }

    /** 更新当前姿态（度）。可由任意线程调用。 */
    public void setPose(float pitch, float yaw) {
        this.pitchDeg = pitch;
        this.yawDeg = yaw;
        postInvalidateOnAnimation();
    }

    /** 把当前姿态设为"正方向"参考（蓝点回到"正方向"位置）。
     * 仅可视化自身归零：解码器零点不动。适用于"只想重定显示"的场景。 */
    public void resetReference() {
        basePitch = pitchDeg;
        baseYaw = yawDeg;
        postInvalidateOnAnimation();
    }

    /** 与解码器 recalibrate() 配对使用：解码器重校准后输出即归零 (0,0)，
     * 可视化基准必须同步归零；若仍快照当前值，会把校准前的残差永久吃成偏移
     *（2026-09-28 实测：残差 pitch +22°/yaw -13° 重置后变永久偏移，蓝点永不回中）。 */
    public void flipPitchDisplay() { vizPitchSign = -vizPitchSign; postInvalidateOnAnimation(); }
    public void flipYawDisplay() { vizYawSign = -vizYawSign; postInvalidateOnAnimation(); }
    public void resetToZero() {
        basePitch = 0f;
        baseYaw = 0f;
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas cv) {
        super.onDraw(cv);

        float w = getWidth();
        float h = getHeight();
        float cx = w / 2f;
        float cy = h / 2f;
        float R = Math.min(w, h) * 0.42f;

        // ── 球体填充（径向渐变营造球面光照） ──
        pFill.setShader(new RadialGradient(
                cx - R * 0.35f, cy - R * 0.35f, R * 1.7f,
                new int[] { 0xFF2A3948, 0xFF18232E, 0xFF0D141B },
                new float[] { 0f, 0.62f, 1f },
                Shader.TileMode.CLAMP));
        cv.drawCircle(cx, cy, R, pFill);
        pFill.setShader(null);

        // ── 网格线（纬线：横向扁椭圆） ──
        cv.drawOval(new RectF(cx - R, cy - R * 0.36f, cx + R, cy + R * 0.36f), pGrid);          // 赤道
        cv.drawOval(new RectF(cx - R * 0.86f, cy - R * 0.78f, cx + R * 0.86f, cy - R * 0.30f), pGrid);
        cv.drawOval(new RectF(cx - R * 0.86f, cy + R * 0.30f, cx + R * 0.86f, cy + R * 0.78f), pGrid);

        // ── 网格线（经线：竖椭圆，不同扁度） ──
        float[] mws = { 0.35f, 0.70f, 0.95f };
        for (float mw : mws) {
            cv.drawOval(new RectF(cx - R * mw, cy - R, cx + R * mw, cy + R), pGrid);
        }

        // ── 外圈 ──
        cv.drawCircle(cx, cy, R, pLine);

        // ── 圆心参考点（"我"的头/位置） ──
        cv.drawCircle(cx, cy, dp(2.5f), pCenter);

        // ── 蓝点位置（相对基准的偏移） ──
        // 观察者在用户"背后"：中性位蓝点压住灰点（实心最大最亮）；
        // 转开即偏移；转到背面（|角|>90°）沉入深处变暗缩小（仍实心，不用空心）
        float yawD = wrap180(yawDeg - baseYaw) * vizYawSign;
        float pitchD = wrap180(pitchDeg - basePitch) * vizPitchSign;
        double rYaw = Math.toRadians(yawD);
        double rPitch = Math.toRadians(pitchD);
        float vx = (float) (Math.sin(rYaw) * Math.cos(rPitch));
        float vy = (float) Math.sin(rPitch);
        float vz = (float) (-Math.cos(rYaw) * Math.cos(rPitch));   // 中性位 -1（压住灰点）；背面 +1（深处）
        float px = cx - vx * R; // 背后视角：与面对面镜像，右转仍在右侧
        float py = cy - vy * R;

        // 深度明暗（背后视角）：中性位压住灰点，最亮最大；转开渐暗渐小，背面最深
        float aMul = 0.28f + 0.36f * (1f - vz);   // 中性位 vz=-1→1.0；背面 vz=+1→0.28

        // ── 中心→蓝点连线（随深度变暗） ──
        pLink.setColor(Color.argb((int) (0x88 * aMul), 33, 150, 243));
        cv.drawLine(cx, cy, px, py, pLink);

        // ── 蓝点：恒实心；中性位最大最亮压住灰点，背面缩小变暗 ──
        int dotA = (int) (255 * aMul);
        float persp = 0.70f + 0.30f * (1f - vz) * 0.5f;   // vz=-1→1.0（近/大压灰点）；+1→0.70（远/小）
        pDot.setColor(Color.argb(dotA, 33, 150, 243));
        pHalo.setColor(Color.argb((int) (0x33 * aMul), 33, 150, 243));
        cv.drawCircle(px, py, dp(11f) * persp, pHalo);
        pDot.setStyle(Paint.Style.FILL);
        cv.drawCircle(px, py, dp(5.5f) * persp, pDot);
        // send info已移入卡片文本区，球体保持纯净，不覆盖绘制

    }

    private float dp(float v) {
        return v * density;
    }

    private static float wrap180(float d) {
        d = d % 360f;
        if (d > 180f) d -= 360f;
        if (d < -180f) d += 360f;
        return d;
    }
}
