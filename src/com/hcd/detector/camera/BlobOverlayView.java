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
    private int[] blobs = new int[0];
    private int blobCount = 0;
    private int imageWidth = 1;
    private int imageHeight = 1;

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
     * 线程安全（任意线程）。blobs 为 [x,y,radius]×count（Y 平面坐标），
     * count=0 清空。
     */
    public void setBlobs(int[] blobs, int count, int imageWidth, int imageHeight) {
        synchronized (lock) {
            if (blobs == null || count <= 0) {
                this.blobs = new int[0];
                this.blobCount = 0;
            } else {
                this.blobs = Arrays.copyOf(blobs, count * 3);
                this.blobCount = count;
            }
            this.imageWidth = Math.max(1, imageWidth);
            this.imageHeight = Math.max(1, imageHeight);
        }
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        final int[] b;
        final int n;
        final float scaleX;
        final float scaleY;
        synchronized (lock) {
            b = blobs;
            n = blobCount;
            scaleX = getWidth() / (float) imageWidth;
            scaleY = getHeight() / (float) imageHeight;
        }
        if (n == 0) {
            return;
        }
        float radiusScale = (scaleX + scaleY) / 2f;
        for (int i = 0; i < n; i++) {
            float cx = b[i * 3] * scaleX;
            float cy = b[i * 3 + 1] * scaleY;
            float radius = b[i * 3 + 2] * radiusScale;
            if (radius < strokePx) {
                radius = strokePx; // 极小斑也保证可见
            }
            canvas.drawCircle(cx, cy, radius, paint);
            canvas.drawLine(cx - radius, cy, cx + radius, cy, paint);
            canvas.drawLine(cx, cy - radius, cx, cy + radius, paint);
        }
    }
}
