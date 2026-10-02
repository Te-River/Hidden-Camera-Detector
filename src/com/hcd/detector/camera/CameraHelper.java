package com.hcd.detector.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Camera2 生命周期封装（分区 B）。
 *
 * 内部自持 HandlerThread "camera-bg"；ImageReader 由本类创建并在
 * StreamConfigurationMap 上完成尺寸协商闭环（契约决策 #8），经 onOpened
 * 交给 Activity 注册监听。Torch 常亮走会话内 FLASH_MODE_TORCH（决策 #3），
 * 不使用 CameraManager.setTorchMode（与持有相机互斥）。
 */
public class CameraHelper {

    private static final String TAG = "CameraHelper";

    /** 打开结果回调（相机后台线程回调，UI 更新需自行切主线程）。 */
    public interface OpenListener {
        /**
         * 预览已启动。wantAnalysis=true 时 analysisReader 非空，
         * analysisSize 为实际协商出的尺寸。
         */
        void onOpened(Size analysisSize, ImageReader analysisReader);

        void onError(String message);
    }

    private final CameraManager cameraManager;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession session;
    private ImageReader analysisReader;
    private CaptureRequest.Builder requestBuilder;
    private Surface previewSurface;
    private TextureView previewView;
    private OpenListener listener;
    private boolean useFront;
    private boolean wantAnalysis;
    private Size preferredSize;
    private boolean torchOn;
    /** close() 后为 true：拦截迟到的 onOpened/onConfigured，防止相机被重新占用（M1）。 */
    private volatile boolean released;

    public CameraHelper(Context context) {
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    /**
     * 打开前/后相机并启动预览。previewView 的 SurfaceTexture 必须已 available。
     * wantAnalysis=true 时内部创建 ImageReader（YUV_420_888，maxImages=2，
     * 尺寸取 StreamConfigurationMap 中与 preferred 最接近的合法值）并加入会话。
     */
    public void start(TextureView previewView, boolean useFront, boolean wantAnalysis,
                      Size preferred, OpenListener listener) {
        this.previewView = previewView;
        this.useFront = useFront;
        this.wantAnalysis = wantAnalysis;
        this.preferredSize = preferred != null ? preferred : new Size(640, 480);
        this.listener = listener;
        this.torchOn = false;

        close(); // 幂等复位旧会话与线程
        released = false;

        if (cameraManager == null) {
            reportError("相机服务不可用");
            return;
        }
        String cameraId = pickCameraId(cameraManager, useFront);
        if (cameraId == null) {
            reportError(useFront ? "未找到前置相机" : "未找到后置相机");
            return;
        }
        cameraThread = new HandlerThread("camera-bg");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        try {
            cameraManager.openCamera(cameraId, deviceCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalStateException e) {
            reportError(e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName());
        }
    }

    /**
     * 手电筒开关：重建 repeating request，FLASH_MODE_TORCH / OFF。仅后摄有效。
     * 会话尚未建立时仅记录状态，会话建立时按该状态初始化闪光模式。
     */
    public void setTorch(boolean on) {
        torchOn = on;
        if (useFront) {
            return; // 仅后摄有效
        }
        final CaptureRequest.Builder builder = requestBuilder;
        final CameraCaptureSession currentSession = session;
        final Handler handler = cameraHandler;
        if (builder == null || currentSession == null || handler == null) {
            return;
        }
        final boolean enable = on;
        handler.post(() -> {
            builder.set(CaptureRequest.FLASH_MODE, enable
                    ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
            try {
                currentSession.setRepeatingRequest(builder.build(), null, handler);
            } catch (CameraAccessException | IllegalStateException e) {
                Log.e(TAG, "setTorch failed", e);
            }
        });
    }

    /** 释放相机、会话、ImageReader、线程。幂等。 */
    public void close() {
        released = true;
        if (session != null) {
            session.close();
            session = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (analysisReader != null) {
            // 先摘除监听再 close，缩小与分析线程 acquireLatestImage 的竞态窗口（M2）
            analysisReader.setOnImageAvailableListener(null, null);
            analysisReader.close();
            analysisReader = null;
        }
        if (previewSurface != null) {
            previewSurface.release();
            previewSurface = null;
        }
        requestBuilder = null;
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }

    /** 按朝向（LENS_FACING）找相机 ID；找不到返回 null。 */
    public static String pickCameraId(CameraManager cm, boolean front) {
        if (cm == null) {
            return null;
        }
        try {
            int want = front ? CameraCharacteristics.LENS_FACING_FRONT
                    : CameraCharacteristics.LENS_FACING_BACK;
            for (String id : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == want) {
                    return id;
                }
            }
        } catch (CameraAccessException e) {
            Log.e(TAG, "pickCameraId failed", e);
        }
        return null;
    }

    /** 从候选尺寸中选与 target 面积比最接近者（优先同宽高比）。 */
    public static Size pickSize(List<Size> choices, int targetW, int targetH) {
        if (choices == null || choices.isEmpty()) {
            return new Size(targetW, targetH);
        }
        Size bestAny = null;
        long bestAnyDiff = Long.MAX_VALUE;
        Size bestRatio = null;
        long bestRatioDiff = Long.MAX_VALUE;
        for (Size s : choices) {
            long diff = Math.abs((long) s.getWidth() * s.getHeight()
                    - (long) targetW * targetH);
            if (diff < bestAnyDiff) {
                bestAnyDiff = diff;
                bestAny = s;
            }
            // 宽高比一致判定（交叉相乘，5% 容差）：w1*h2 ≈ h1*w2
            long a = (long) s.getWidth() * targetH;
            long b = (long) s.getHeight() * targetW;
            if (Math.abs(a - b) * 20 <= Math.abs(a + b) && diff < bestRatioDiff) {
                bestRatioDiff = diff;
                bestRatio = s;
            }
        }
        return bestRatio != null ? bestRatio : bestAny;
    }

    private void reportError(String message) {
        if (released) {
            return; // 已释放：不再回调，避免触碰已退出的 Activity
        }
        if (listener != null) {
            listener.onError(message);
        } else {
            Log.e(TAG, message);
        }
    }

    private void startSession() {
        SurfaceTexture texture = previewView != null ? previewView.getSurfaceTexture() : null;
        if (texture == null) {
            reportError("预览表面未就绪");
            return;
        }
        try {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraDevice.getId());
            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            // 预览尺寸按 TextureView 实际大小就近协商（未布局时退回 1280x720）
            int viewW = previewView.getWidth();
            int viewH = previewView.getHeight();
            if (viewW <= 0 || viewH <= 0) {
                viewW = 1280;
                viewH = 720;
            }
            Size previewSize = pickSize(
                    Arrays.asList(map.getOutputSizes(SurfaceTexture.class)), viewW, viewH);
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(texture);

            List<Surface> targets = new ArrayList<>();
            targets.add(previewSurface);

            if (wantAnalysis) {
                Size analysisSize = pickSize(
                        Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                        preferredSize.getWidth(), preferredSize.getHeight());
                analysisReader = ImageReader.newInstance(analysisSize.getWidth(),
                        analysisSize.getHeight(), ImageFormat.YUV_420_888, 2);
                targets.add(analysisReader.getSurface());
            }

            cameraDevice.createCaptureSession(targets, sessionCallback, cameraHandler);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            reportError(e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName());
        }
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            if (released) {
                // close() 已在途：迟到的相机直接关掉，不建会话（M1）
                camera.close();
                return;
            }
            cameraDevice = camera;
            if (released) {
                // close() 恰好在赋值瞬间介入：补一次关闭，避免相机被永久占用
                cameraDevice = null;
                camera.close();
                return;
            }
            startSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            if (cameraDevice == camera) {
                cameraDevice = null;
            }
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            if (cameraDevice == camera) {
                cameraDevice = null;
            }
            if (released) {
                return;
            }
            reportError("相机错误码 " + error);
        }
    };

    private final CameraCaptureSession.StateCallback sessionCallback =
            new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (released || cameraDevice == null) {
                        s.close();
                        if (!released) {
                            reportError("相机已释放");
                        }
                        return;
                    }
                    session = s;
                    try {
                        requestBuilder = cameraDevice.createCaptureRequest(
                                CameraDevice.TEMPLATE_PREVIEW);
                        requestBuilder.addTarget(previewSurface);
                        if (analysisReader != null) {
                            requestBuilder.addTarget(analysisReader.getSurface());
                        }
                        // 固定无穷远对焦：IR/Torch 检测都要求焦距锁定（契约 4.4）
                        requestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                CaptureRequest.CONTROL_AF_MODE_OFF);
                        requestBuilder.set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f);
                        if (!useFront) {
                            requestBuilder.set(CaptureRequest.FLASH_MODE, torchOn
                                    ? CaptureRequest.FLASH_MODE_TORCH
                                    : CaptureRequest.FLASH_MODE_OFF);
                        }
                        s.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                        if (listener != null) {
                            listener.onOpened(analysisReader == null ? null
                                    : new Size(analysisReader.getWidth(),
                                            analysisReader.getHeight()), analysisReader);
                        }
                    } catch (CameraAccessException | IllegalStateException e) {
                        reportError(e.getMessage() != null ? e.getMessage()
                                : e.getClass().getSimpleName());
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    s.close();
                    reportError("相机会话配置失败");
                }
            };
}
