package com.hcd.detector.camera;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.TextureView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.hcd.detector.MainActivity;
import com.hcd.detector.R;

/**
 * 强光反光检测（分区 B）：后摄预览 + 手电筒常亮（会话内 FLASH_MODE_TORCH，
 * 契约决策 #3）+ 原生亮斑检测。照射可疑区域，镜头反光呈高亮斑。
 */
public class TorchActivity extends Activity implements TextureView.SurfaceTextureListener {

    private CameraHelper cameraHelper;
    private TextureView previewView;
    private BlobOverlayView overlayView;
    private TextView statusView;
    private Button toggleButton;
    private HandlerThread analysisThread;
    private boolean torchOn = true;
    /** 帧坐标→视图坐标映射矩阵（与预览同一变换）及其均匀缩放因子；仅主线程访问。 */
    private Matrix frameMatrix;
    private float matrixScale = 1f;
    private int frameW;
    private int frameH;
    /** onPause 释放过相机且表面仍存活时，onResume 重启预览（C2：后台不耗电，回前台恢复可用）。 */
    private boolean restartOnResume;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 防御性权限复查（契约第 0 章：双保险）
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        setContentView(R.layout.activity_torch);
        previewView = findViewById(R.id.texture_preview);
        overlayView = findViewById(R.id.overlay_blobs);
        statusView = findViewById(R.id.tv_torch_status);
        toggleButton = findViewById(R.id.btn_torch_toggle);
        statusView.setText(R.string.torch_hint);
        toggleButton.setOnClickListener(v -> toggleTorch());
        cameraHelper = new CameraHelper(this);
        previewView.setSurfaceTextureListener(this);
        if (previewView.isAvailable()) {
            startCamera();
        }
        MainActivity.applyImmersive(this);
        // 预览层（TextureView/叠加层）保持全屏铺满，只有控件容器避让系统栏
        MainActivity.applyInsetsPadding(this, findViewById(R.id.controls_container));
        MainActivity.applyHighRefreshRate(this);
    }

    private void startCamera() {
        if (analysisThread != null) {
            analysisThread.quitSafely();
            analysisThread = null;
        }
        analysisThread = new HandlerThread("torch-analysis");
        analysisThread.start();
        final Handler analysisHandler = new Handler(analysisThread.getLooper());
        cameraHelper.start(previewView, false, true, new Size(640, 480),
                new CameraHelper.OpenListener() {
                    @Override
                    public void onOpened(Size analysisSize, ImageReader reader) {
                        // 按当前开关状态初始化闪光，避免与用户操作脱同步（m1）
                        cameraHelper.setTorch(torchOn);
                        reader.setOnImageAvailableListener(
                                TorchActivity.this::onImage, analysisHandler);
                        runOnUiThread(() -> {
                            if (analysisSize != null) {
                                frameW = analysisSize.getWidth();
                                frameH = analysisSize.getHeight();
                            }
                            rebuildMatrix();
                            toggleButton.setText(
                                    torchOn ? R.string.torch_on : R.string.torch_off);
                        });
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

    private void toggleTorch() {
        torchOn = !torchOn;
        cameraHelper.setTorch(torchOn);
        toggleButton.setText(torchOn ? R.string.torch_on : R.string.torch_off);
    }

    /** 相机后台线程回调：取最新帧 → 原生检测 → 主线程刷新叠加层与状态行。 */
    private void onImage(ImageReader reader) {
        Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (IllegalStateException e) {
            return; // reader 已被 close（退出竞态），丢弃本帧（M2）
        }
        if (image == null) {
            return;
        }
        int[] blobs;
        try {
            blobs = BlobDetector.detect(image, BlobDetector.TORCH_THRESHOLD);
        } finally {
            image.close();
        }
        final int[] result = blobs;
        final int count = blobs == null ? 0 : blobs.length / 3;
        runOnUiThread(() -> {
            overlayView.setBlobs(
                    CameraHelper.mapBlobs(frameMatrix, matrixScale, result, count), count);
            if (count > 0) {
                statusView.setText(getString(R.string.torch_found, count));
                statusView.setTextColor(
                        getResources().getColor(R.color.colorAlert, getTheme()));
            } else {
                statusView.setText(R.string.torch_none);
                statusView.setTextColor(
                        getResources().getColor(R.color.colorNormal, getTheme()));
            }
        });
    }

    /** 视图/帧尺寸或相机变化后重建映射矩阵，并清空旧矩阵下的残留标记。 */
    private void rebuildMatrix() {
        frameMatrix = null;
        matrixScale = 1f;
        if (previewView == null || cameraHelper == null) {
            return;
        }
        int vw = previewView.getWidth();
        int vh = previewView.getHeight();
        if (vw <= 0 || vh <= 0 || frameW <= 0 || frameH <= 0) {
            return;
        }
        frameMatrix = cameraHelper.computeFrameMatrix(vw, vh, frameW, frameH);
        matrixScale = CameraHelper.matrixScale(frameMatrix);
        overlayView.clearBlobs();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        startCamera();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        // 视图尺寸变化：预览矩阵与标记矩阵都重算，旧标记清空
        cameraHelper.applyPreviewTransform();
        rebuildMatrix();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        releaseCamera();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    /** 释放相机与分析线程（onPause / onDestroy / onTrimMemory 共用）。 */
    private void releaseCamera() {
        if (cameraHelper != null) {
            cameraHelper.close();
        }
        if (analysisThread != null) {
            analysisThread.quitSafely();
            analysisThread = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (restartOnResume && previewView != null && previewView.isAvailable()) {
            startCamera();
        }
        restartOnResume = false;
    }

    /** 功耗标准（C2）：不可见即停预览与手电筒，回前台不意外耗电。 */
    @Override
    protected void onPause() {
        super.onPause();
        restartOnResume = true;
        releaseCamera();
    }

    /** 内存管理（T/TAF 358，C1）：内存吃紧时释放相机。 */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_RUNNING_LOW) {
            restartOnResume = true;
            releaseCamera();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        releaseCamera();
    }
}
