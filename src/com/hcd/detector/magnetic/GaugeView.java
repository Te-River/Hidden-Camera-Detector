package com.hcd.detector.magnetic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.View;

import com.hcd.detector.R;

/**
 * 磁场仪表盘：270° 弧形表盘 + 平滑指针。
 * 指针经 Choreographer 帧循环向目标值趋近（每帧 display += (target-display)*0.25）。
 */
public class GaugeView extends View {

    /** 弧起止角：135° → 405°（即 45°），共 270°。 */
    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;
    /** 主刻度分段数（0..8 共 9 条刻度线）。 */
    private static final int TICK_DIVISIONS = 8;
    private static final float SMOOTH_FACTOR = 0.25f;
    private static final float SETTLE_EPSILON = 0.01f;

    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint alertArcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcRect = new RectF();

    private final int arcColor;
    private final int needleColor;
    private final int alertColor;
    private final float strokePx;
    private final float tickLengthPx;
    private final float pivotRadiusPx;

    private float range = 200f;
    private float target = 0f;
    private float display = 0f;
    private boolean alert = false;
    /** 告警触发时的读数，作为「超阈值弧段」的起点（≈ 告警阈值）。 */
    private float alertLevel = 0f;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (Math.abs(target - display) <= SETTLE_EPSILON) {
                display = target; // 收敛后不再 invalidate，避免空转重绘
            } else {
                display += (target - display) * SMOOTH_FACTOR;
                invalidate();
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    public GaugeView(Context context) {
        this(context, null);
    }

    public GaugeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        arcColor = context.getColor(R.color.colorGaugeArc);
        needleColor = context.getColor(R.color.colorGaugeNeedle);
        alertColor = context.getColor(R.color.colorAlert);

        float density = context.getResources().getDisplayMetrics().density;
        strokePx = 10f * density;
        tickLengthPx = 8f * density;
        pivotRadiusPx = 5f * density;

        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(strokePx);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(arcColor);

        alertArcPaint.setStyle(Paint.Style.STROKE);
        alertArcPaint.setStrokeWidth(strokePx);
        alertArcPaint.setStrokeCap(Paint.Cap.ROUND);
        alertArcPaint.setColor(alertColor);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeWidth(2f * density);
        tickPaint.setColor(needleColor);

        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(4f * density);
        needlePaint.setStrokeCap(Paint.Cap.ROUND);
        needlePaint.setColor(needleColor);
    }

    /** 设定目标读数（µT，仅主线程调用），指针平滑趋近。 */
    public void setValue(float microTesla) {
        target = Math.max(0f, microTesla);
    }

    /** 告警态：指针与超阈值弧段变 colorAlert。 */
    public void setAlert(boolean alert) {
        if (alert && !this.alert) {
            // 首次越限时的读数即阈值近似，红弧起点固定不随读数漂移
            alertLevel = target;
        }
        this.alert = alert;
        invalidate();
    }

    /** 满量程（µT）。 */
    public void setRange(float maxMicroTesla) {
        range = Math.max(1f, maxMicroTesla);
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        if (w <= 0f || h <= 0f) {
            return;
        }

        float cx = w / 2f;
        float cy = h / 2f;
        float radius = Math.min(w, h) / 2f - strokePx / 2f - 4f;
        if (radius <= strokePx) {
            return;
        }
        arcRect.set(cx - radius, cy - radius, cx + radius, cy + radius);

        // 表盘底弧（270°）
        canvas.drawArc(arcRect, START_ANGLE, SWEEP_ANGLE, false, arcPaint);

        // 超阈值弧段（告警态，从越限读数到满量程）
        if (alert && alertLevel > 0f) {
            float frac = clamp01(alertLevel / range);
            if (frac < 1f) {
                canvas.drawArc(arcRect, START_ANGLE + SWEEP_ANGLE * frac,
                        SWEEP_ANGLE * (1f - frac), false, alertArcPaint);
            }
        }

        // 主刻度（8 分段 = 9 条线，画在弧内侧）
        float tickOuter = radius - strokePx / 2f - 2f;
        for (int i = 0; i <= TICK_DIVISIONS; i++) {
            double rad = Math.toRadians(START_ANGLE + SWEEP_ANGLE * i / TICK_DIVISIONS);
            float cos = (float) Math.cos(rad);
            float sin = (float) Math.sin(rad);
            canvas.drawLine(cx + tickOuter * cos, cy + tickOuter * sin,
                    cx + (tickOuter - tickLengthPx) * cos, cy + (tickOuter - tickLengthPx) * sin,
                    tickPaint);
        }

        // 指针 + 中心轴点（告警时变红）
        needlePaint.setColor(alert ? alertColor : needleColor);
        double needleRad = Math.toRadians(START_ANGLE + SWEEP_ANGLE * clamp01(display / range));
        float needleLen = radius - strokePx / 2f - tickLengthPx - 4f;
        canvas.drawLine(cx, cy,
                cx + needleLen * (float) Math.cos(needleRad),
                cy + needleLen * (float) Math.sin(needleRad),
                needlePaint);
        canvas.drawCircle(cx, cy, pivotRadiusPx, needlePaint);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
