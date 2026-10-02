package com.hcd.detector.camera;

import android.media.Image;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * 亮斑检测入口（分区 B）：JNI 原生游程连通域检测的 Java 声明与便捷封装。
 * final 且私有构造，不可实例化。
 */
public final class BlobDetector {

    /** 输出斑数上限（nativeScan 的 maxBlobs 缺省值）。 */
    public static final int MAX_BLOBS = 16;
    /** IRActivity 检测阈值（0-255，Y 分量亮度）。 */
    public static final int IR_THRESHOLD = 200;
    /** TorchActivity 检测阈值。 */
    public static final int TORCH_THRESHOLD = 220;

    static {
        System.loadLibrary("irscan");
    }

    private BlobDetector() {
    }

    /**
     * JNI 原生检测。yPlane 为整幅 Y 平面（含 stride 行距）。
     * outPositions 长度 ≥ maxBlobs*3。
     * 返回斑数 n>0：outPositions[0..3n-1]=[x,y,radius]×n（Y 平面像素坐标）；
     * 无斑返回 0；参数非法返回 -1。
     */
    public static native int nativeScan(byte[] yPlane, int width, int height, int stride,
                                        int threshold, int[] outPositions, int maxBlobs);

    /**
     * 便捷封装：从 YUV_420_888 Image 提取 Y 平面并检测（仅相机后台线程调用）。
     * planes[0].getPixelStride()!=1 → 返回 null；buffer.rewind() 后按行复制
     * （每行 position(row*stride) 取 width 字节）；返回 [x,y,radius]×n 数组，
     * 无斑/失败返回 null。
     */
    public static int[] detect(Image image, int threshold) {
        if (image == null) {
            return null;
        }
        Image.Plane[] planes = image.getPlanes();
        if (planes == null || planes.length == 0) {
            return null;
        }
        Image.Plane yPlane = planes[0];
        if (yPlane.getPixelStride() != 1) {
            return null;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        int stride = yPlane.getRowStride();
        ByteBuffer buffer = yPlane.getBuffer();
        if (width <= 0 || height <= 0 || stride < width || buffer == null) {
            return null;
        }
        buffer.rewind();
        if (buffer.remaining() < stride * (height - 1) + width) {
            return null;
        }
        // 按行复制 Y 平面（跳过行尾 padding），打包为 stride=width 的紧凑数组
        byte[] y = new byte[width * height];
        for (int row = 0; row < height; row++) {
            buffer.position(row * stride);
            buffer.get(y, row * width, width);
        }
        int[] out = new int[MAX_BLOBS * 3];
        int n = nativeScan(y, width, height, width, threshold, out, MAX_BLOBS);
        if (n <= 0) {
            return null;
        }
        return Arrays.copyOf(out, n * 3);
    }
}
