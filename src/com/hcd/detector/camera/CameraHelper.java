package com.hcd.detector.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
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
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.WindowManager;

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

    private final Context context;
    private final CameraManager cameraManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 当前相机 SENSOR_ORIENTATION（度）与实际朝向（预览/标记矩阵计算用）。 */
    private int sensorOrientation;
    private boolean frontFacing;
    /** 已协商的预览缓冲尺寸；null 表示尚未建立会话。 */
    private Size previewBufferSize;
    /** 显示旋转角（度）。Activity 竖屏锁定时恒为 0，仍按实际值换算。 */
    private int displayRotation;

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
        this.context = context;
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
        try {
            CameraCharacteristics characteristics =
                    cameraManager.getCameraCharacteristics(cameraId);
            Integer orientation = characteristics.get(
                    CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = orientation != null ? orientation : 0;
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            frontFacing = facing != null
                    && facing == CameraCharacteristics.LENS_FACING_FRONT;
        } catch (CameraAccessException | IllegalArgumentException e) {
            reportError(e.getMessage() != null ? e.getMessage()
                    : e.getClass().getSimpleName());
            return;
        }
        displayRotation = readDisplayRotationDegrees();
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

    /** 当前相机 SENSOR_ORIENTATION（度）；未打开过相机时为 0。 */
    public int getSensorOrientation() {
        return sensorOrientation;
    }

    /** 当前镜头是否前摄。 */
    public boolean isFront() {
        return frontFacing;
    }

    /**
     * 预览变换矩阵（TextureView.setTransform 用）：与 computeFrameMatrix 同一
     * 变换，但 setTransform 的输入空间是“帧被拉伸铺满视图”后的视图坐标，
     * 故先逆拉伸回帧像素坐标再套用。
     */
    public Matrix computePreviewMatrix(int viewW, int viewH) {
        if (previewBufferSize == null || viewW <= 0 || viewH <= 0) {
            return new Matrix();
        }
        Matrix m = computeFrameMatrix(viewW, viewH,
                previewBufferSize.getWidth(), previewBufferSize.getHeight());
        m.postScale(previewBufferSize.getWidth() / (float) viewW,
                previewBufferSize.getHeight() / (float) viewH);
        return m;
    }

    /**
     * 帧坐标 → 视图坐标的映射矩阵（blob 标记用，与预览共用同一变换）：
     * 帧中心平移到原点 → 旋转至屏幕直立 → 前摄水平镜像 → center-crop 均匀
     * 放大填满视图（不留黑边）→ 平移到视图中心。
     */
    public Matrix computeFrameMatrix(int viewW, int viewH, int frameW, int frameH) {
        Matrix m = new Matrix();
        if (viewW <= 0 || viewH <= 0 || frameW <= 0 || frameH <= 0) {
            return m;
        }
        int rotation = rotationDegrees();
        m.setTranslate(-frameW / 2f, -frameH / 2f);
        m.postRotate(rotation);
        if (frontFacing) {
            m.postScale(-1f, 1f); // 前摄镜像（自拍预览习惯）
        }
        boolean swap = rotation % 180 != 0;
        float rotatedW = swap ? frameH : frameW;
        float rotatedH = swap ? frameW : frameH;
        float scale = Math.max(viewW / rotatedW, viewH / rotatedH);
        m.postScale(scale, scale);
        m.postTranslate(viewW / 2f, viewH / 2f);
        return m;
    }

    /** 重算并应用预览变换（内部 post 到主线程）。视图尺寸变化时由 Activity 调用。 */
    public void applyPreviewTransform() {
        final TextureView view = previewView;
        if (view == null) {
            return;
        }
        mainHandler.post(() -> {
            if (released || previewBufferSize == null) {
                return;
            }
            int vw = view.getWidth();
            int vh = view.getHeight();
            if (vw <= 0 || vh <= 0) {
                return;
            }
            view.setTransform(computePreviewMatrix(vw, vh));
        });
    }

    /**
     * 用映射矩阵把帧坐标 blob [x,y,radius]×count 映射为视图坐标：圆心走
     * mapPoints，半径乘矩阵均匀缩放因子。矩阵为 null 时返回 null（清空标记）。
     */
    public static float[] mapBlobs(Matrix matrix, float scale, int[] blobs, int count) {
        if (matrix == null || blobs == null || count <= 0) {
            return null;
        }
        float[] pts = new float[count * 2];
        for (int i = 0; i < count; i++) {
            pts[i * 2] = blobs[i * 3];
            pts[i * 2 + 1] = blobs[i * 3 + 1];
        }
        matrix.mapPoints(pts);
        float[] out = new float[count * 3];
        for (int i = 0; i < count; i++) {
            out[i * 3] = pts[i * 2];
            out[i * 3 + 1] = pts[i * 2 + 1];
            out[i * 3 + 2] = blobs[i * 3 + 2] * scale;
        }
        return out;
    }

    /** 矩阵的均匀缩放因子（blob 半径帧像素→视图像素）：映射 100px 水平段求长度。 */
    public static float matrixScale(Matrix matrix) {
        if (matrix == null) {
            return 1f;
        }
        float[] seg = {0f, 0f, 100f, 0f};
        matrix.mapPoints(seg);
        return (float) Math.hypot(seg[2] - seg[0], seg[3] - seg[1]) / 100f;
    }

    /**
     * 帧旋转到屏幕直立所需的顺时针角度：后摄 sensorOrientation−displayRotation；
     * 前摄 sensorOrientation+displayRotation（配合随后的水平镜像，等价于老
     * Camera API setDisplayOrientation 的前摄换算）。
     */
    private int rotationDegrees() {
        int r = frontFacing
                ? (sensorOrientation + displayRotation) % 360
                : (sensorOrientation - displayRotation + 360) % 360;
        return (r + 360) % 360;
    }

    /** 读取显示旋转角（度）。 */
    @SuppressWarnings("deprecation") // getDefaultDisplay 自 API 30 废弃，minSdk 26 无全版本替代
    private int readDisplayRotationDegrees() {
        try {
            WindowManager wm =
                    (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null && wm.getDefaultDisplay() != null) {
                switch (wm.getDefaultDisplay().getRotation()) {
                    case Surface.ROTATION_90:
                        return 90;
                    case Surface.ROTATION_180:
                        return 180;
                    case Surface.ROTATION_270:
                        return 270;
                    default:
                        return 0;
                }
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "readDisplayRotation failed", e);
        }
        return 0;
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

    /**
     * 从候选尺寸中选与 target 面积最接近且宽高比与 aspect 一致（交叉相乘 5% 容差）
     * 者；无同比例候选时退回纯面积最近（此时分析流与预览流可能存在 FOV 裁切差异）。
     */
    public static Size pickSize(List<Size> choices, int targetW, int targetH, Size aspect) {
        if (choices != null && !choices.isEmpty() && aspect != null) {
            Size best = null;
            long bestDiff = Long.MAX_VALUE;
            for (Size s : choices) {
                long a = (long) s.getWidth() * aspect.getHeight();
                long b = (long) s.getHeight() * aspect.getWidth();
                if (Math.abs(a - b) * 20 > Math.abs(a + b)) {
                    continue; // 宽高比不符
                }
                long diff = Math.abs((long) s.getWidth() * s.getHeight()
                        - (long) targetW * targetH);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    best = s;
                }
            }
            if (best != null) {
                return best;
            }
        }
        return pickSize(choices, targetW, targetH);
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
            previewBufferSize = previewSize;
            // 预览尺寸确定后立即应用变换矩阵（旋转/镜像/center-crop），画面才不拉伸
            applyPreviewTransform();

            List<Surface> targets = new ArrayList<>();
            targets.add(previewSurface);

            if (wantAnalysis) {
                // 分析流与预览流保持同宽高比（同 FOV 裁切），标记坐标映射才与预览严格一致
                Size analysisSize = pickSize(
                        Arrays.asList(map.getOutputSizes(ImageFormat.YUV_420_888)),
                        preferredSize.getWidth(), preferredSize.getHeight(), previewSize);
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
