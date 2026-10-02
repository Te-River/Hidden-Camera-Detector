package com.hcd.detector.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import com.hcd.detector.MainActivity;
import com.hcd.detector.R;

/**
 * 红外检测（分区 B）：前摄预览 + 原生亮斑检测 + 红圈叠加。
 * 暗环境下红外补光灯在前摄（多无 IR 滤光片）中呈亮斑。
 */
public class IRActivity extends Activity implements TextureView.SurfaceTextureListener {

    private CameraHelper cameraHelper;
    private TextureView previewView;
    private BlobOverlayView overlayView;
    private TextView statusView;
    private HandlerThread analysisThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 防御性权限复查（契约第 0 章：双保险）
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_ir);
        previewView = findViewById(R.id.texture_preview);
        overlayView = findViewById(R.id.overlay_blobs);
        statusView = findViewById(R.id.tv_ir_status);
        statusView.setText(R.string.ir_hint);
        cameraHelper = new CameraHelper(this);
        previewView.setSurfaceTextureListener(this);
        if (previewView.isAvailable()) {
            startCamera();
        }
        MainActivity.applyFullscreen(this);
        MainActivity.applyHighRefreshRate(this);
    }

    private void startCamera() {
        if (analysisThread != null) {
            analysisThread.quitSafely();
            analysisThread = null;
        }
        analysisThread = new HandlerThread("ir-analysis");
        analysisThread.start();
        final Handler analysisHandler = new Handler(analysisThread.getLooper());
        cameraHelper.start(previewView, true, true, new Size(640, 480),
                new CameraHelper.OpenListener() {
                    @Override
                    public void onOpened(Size analysisSize, ImageReader reader) {
                        reader.setOnImageAvailableListener(
                                IRActivity.this::onImage, analysisHandler);
                        runOnUiThread(() -> statusView.setText(R.string.ir_searching));
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            statusView.setText(getString(R.string.camera_error, message));
                            statusView.setTextColor(getResources().getColor(
                                    R.color.colorAlert, getTheme()));
                        });
                    }
                });
    }

    /** 相机后台线程回调：取最新帧 → 原生检测 → 主线程刷新叠加层与状态行。 */
    private void onImage(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) {
            return;
        }
        int imageWidth = image.getWidth();
        int imageHeight = image.getHeight();
        int[] blobs;
        try {
            blobs = BlobDetector.detect(image, BlobDetector.IR_THRESHOLD);
        } finally {
            image.close();
        }
        final int[] result = blobs;
        final int count = blobs == null ? 0 : blobs.length / 3;
        runOnUiThread(() -> {
            overlayView.setBlobs(result, count, imageWidth, imageHeight);
            if (count > 0) {
                statusView.setText(getString(R.string.ir_found, count));
                statusView.setTextColor(
                        getResources().getColor(R.color.colorAlert, getTheme()));
            } else {
                statusView.setText(R.string.ir_none);
                statusView.setTextColor(
                        getResources().getColor(R.color.colorNormal, getTheme()));
            }
        });
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        startCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (cameraHelper != null) {
            cameraHelper.close();
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (cameraHelper != null) {
            cameraHelper.close();
        }
        if (analysisThread != null) {
            analysisThread.quitSafely();
            analysisThread = null;
        }
    }
}
