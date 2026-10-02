package com.hcd.detector.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import com.hcd.detector.R;

import java.util.Arrays;

/**
 * 亮斑叠加层（分区 B，IR/Torch 共用）：在相机预览之上绘制检测到的
 * 亮斑红圈 + 十字准星。setBlobs 任意线程可调（内部同步拷贝 + postInvalidate）。
 */
public class BlobOverlayView extends View {

    private final Object lock = new Object();
    private float[] blobs = new float[0];
    private int blobCount = 0;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float strokePx;

    public BlobOverlayView(Context context) {
        this(context, null);
    }

    public BlobOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        strokePx = 3f * getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokePx);
        paint.setColor(getResources().getColor(R.color.colorAlert, context.getTheme()));
    }

    /**
     * 线程安全（任意线程）。blobs 为【视图坐标】下的 [x,y,radius]×count
     * （调用方已用预览矩阵 mapPoints 映射），count=0 清空。
     */
    public void setBlobs(float[] blobs, int count) {
        synchronized (lock) {
            if (blobs == null || count <= 0) {
                this.blobs = new float[0];
                this.blobCount = 0;
            } else {
                this.blobs = Arrays.copyOf(blobs, count * 3);
                this.blobCount = count;
            }
        }
        postInvalidate();
    }

    /** 清空标记（矩阵/视图尺寸变化时避免旧矩阵残留点）。 */
    public void clearBlobs() {
        setBlobs(null, 0);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final float[] b;
        final int n;
        synchronized (lock) {
            b = blobs;
            n = blobCount;
        }
        if (n == 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            float cx = b[i * 3];
            float cy = b[i * 3 + 1];
            float radius = b[i * 3 + 2];
            if (radius < strokePx) {
                radius = strokePx; // 极小斑也保证可见
            }
            canvas.drawCircle(cx, cy, radius, paint);
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint);
            canvas.drawLine(cx, cy - radius, cx, cy + radius, paint);
        }
    }
}
