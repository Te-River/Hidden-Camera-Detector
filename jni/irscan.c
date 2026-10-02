/*
 * irscan.c — 亮斑原生检测（隐藏摄像头探测器 · 分区 B）
 *
 * 算法：阈值二值化 → 逐行游程(run)编码 → union-find 连通域合并
 *       → 面积过滤（去噪声 / 去全屏过曝）→ 按面积降序输出前 maxBlobs 个
 *       斑点的质心坐标与等效半径（Y 平面像素坐标）。
 *
 * 契约要点（p1-design/01-architect-design-contract.md 第 5 章）：
 *  - JNI 符号 Java_com_hcd_detector_camera_BlobDetector_nativeScan 锁定；
 *  - GetPrimitiveArrayCritical 区间内禁止任何其他 JNI 调用（纯 C 计算）；
 *  - yPlane 以 JNI_ABORT 释放（只读），outPositions 以 0 释放（提交结果）；
 *  - 无静态状态、每次调用 malloc，可重入（单一相机后台线程调用）；
 *  - 一切错误统一返回 -1，不抛 Java 异常。
 */

#include <jni.h>
#include <limits.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>

/* 小于此像素面积的斑视为噪声丢弃 */
#define IR_MIN_AREA 6
/* 面积超过 width*height/4 的斑视为全屏过曝丢弃 */
#define IR_MAX_AREA_FRAC 4

typedef struct {
    int y;
    int x0;     /* 游程起点（含） */
    int x1;     /* 游程终点（不含） */
    int parent; /* union-find 父指针；指向自身即为根 */
} Run;

/* 面积聚合项：自包含结构供 qsort 比较器使用（避免全局状态，保持可重入） */
typedef struct {
    int root;
    long area;
} BlobAgg;

static int find_root(Run *runs, int i) {
    while (runs[i].parent != i) {
        /* 路径折半压缩 */
        runs[i].parent = runs[runs[i].parent].parent;
        i = runs[i].parent;
    }
    return i;
}

static void unite(Run *runs, int a, int b) {
    int ra = find_root(runs, a);
    int rb = find_root(runs, b);
    if (ra != rb) {
        runs[ra].parent = rb;
    }
}

/* qsort 比较器：面积降序 */
static int cmp_blob_area_desc(const void *pa, const void *pb) {
    const BlobAgg *a = (const BlobAgg *) pa;
    const BlobAgg *b = (const BlobAgg *) pb;
    if (a->area > b->area) return -1;
    if (a->area < b->area) return 1;
    return 0;
}

/*
 * 返回值：>0 输出斑数；0 无斑；-1 参数/内存错误。
 * outPositions[0..3n-1] = [x, y, radius] × n（Y 平面像素坐标）。
 */
JNIEXPORT jint JNICALL
Java_com_hcd_detector_camera_BlobDetector_nativeScan(
        JNIEnv *env, jclass clazz, jbyteArray yPlane, jint width, jint height,
        jint stride, jint threshold, jintArray outPositions, jint maxBlobs)
{
    Run *runs = NULL;
    long *area = NULL, *sumX = NULL, *sumY = NULL;
    int *minX = NULL, *maxX = NULL, *minY = NULL, *maxY = NULL;
    BlobAgg *aggs = NULL;
    jbyte *yP = NULL;
    jint *outP = NULL;
    long maxRuns, maxArea;
    int runCount, rowStart, nOut;
    int y, x, i, r, nValid;

    (void) clazz;

    /* ---- 参数校验（契约第 5 章第 1 条） ---- */
    if (env == NULL || yPlane == NULL || outPositions == NULL) return -1;
    if (width <= 0 || height <= 0 || stride < width || maxBlobs <= 0) return -1;

    /* 数组长度防御（必须在 critical 区间外做，区间内禁 JNI 调用） */
    if ((jlong) (*env)->GetArrayLength(env, yPlane) <
        (jlong) stride * (height - 1) + width) {
        return -1;
    }
    if ((jlong) (*env)->GetArrayLength(env, outPositions) < (jlong) maxBlobs * 3) {
        return -1;
    }

    yP = (jbyte *) (*env)->GetPrimitiveArrayCritical(env, yPlane, NULL);
    if (yP == NULL) return -1;
    outP = (jint *) (*env)->GetPrimitiveArrayCritical(env, outPositions, NULL);
    if (outP == NULL) {
        (*env)->ReleasePrimitiveArrayCritical(env, yPlane, yP, JNI_ABORT);
        return -1;
    }

    /* ===== critical 区间：以下直到 Release 只做纯 C 计算，不做任何 JNI 调用 ===== */

    nOut = -1;
    runCount = 0;
    rowStart = 0;

    /* 每行游程数上限 ceil(width/2)，全局上限 (width/2+1)*height */
    maxRuns = ((long) width / 2 + 1) * (long) height;
    runs = (Run *) malloc((size_t) maxRuns * sizeof(Run));
    if (runs == NULL) goto done;

    /* ---- Pass 1：逐行提游程，与上一行 x 区间重叠者 union ---- */
    for (y = 0; y < height; y++) {
        int rowPrevStart = rowStart;
        const jbyte *row = yP + (size_t) y * (size_t) stride;
        x = 0;
        while (x < width) {
            /* jbyte 是有符号 char，Y 分量取值 0..255 必须转无符号再比较，
             * 否则阈值 >127 时高亮度像素（>=128）永不命中 */
            if ((int) (uint8_t) row[x] >= threshold) {
                int x0 = x;
                int label;
                int p;
                while (x < width && (int) (uint8_t) row[x] >= threshold) x++;
                label = runCount++;
                runs[label].y = y;
                runs[label].x0 = x0;
                runs[label].x1 = x;
                runs[label].parent = label;
                for (p = rowPrevStart; p < rowStart; p++) {
                    if (x0 < runs[p].x1 && runs[p].x0 < x) {
                        unite(runs, label, p);
                    }
                }
            } else {
                x++;
            }
        }
        rowStart = runCount;
    }

    if (runCount == 0) {
        nOut = 0;
        goto done;
    }

    area = (long *) malloc((size_t) runCount * sizeof(long));
    sumX = (long *) malloc((size_t) runCount * sizeof(long));
    sumY = (long *) malloc((size_t) runCount * sizeof(long));
    minX = (int *) malloc((size_t) runCount * sizeof(int));
    maxX = (int *) malloc((size_t) runCount * sizeof(int));
    minY = (int *) malloc((size_t) runCount * sizeof(int));
    maxY = (int *) malloc((size_t) runCount * sizeof(int));
    aggs = (BlobAgg *) malloc((size_t) runCount * sizeof(BlobAgg));
    if (area == NULL || sumX == NULL || sumY == NULL || minX == NULL ||
        maxX == NULL || minY == NULL || maxY == NULL || aggs == NULL) {
        goto done;
    }

    /* ---- Pass 2：按 find(root) 聚合 area/sumX/sumY/bbox（area<0 表未初始化） ---- */
    for (i = 0; i < runCount; i++) area[i] = -1;
    for (i = 0; i < runCount; i++) {
        int w = runs[i].x1 - runs[i].x0;
        r = find_root(runs, i);
        if (area[r] < 0) {
            area[r] = 0;
            sumX[r] = 0;
            sumY[r] = 0;
            minX[r] = INT_MAX;
            maxX[r] = INT_MIN;
            minY[r] = INT_MAX;
            maxY[r] = INT_MIN;
        }
        area[r] += w;
        /* Σx（x0..x1-1 等差数列求和，(首项+末项)*项数 恒为偶数，整除无损） */
        sumX[r] += (long) (runs[i].x0 + runs[i].x1 - 1) * w / 2;
        sumY[r] += (long) runs[i].y * w;
        if (runs[i].x0 < minX[r]) minX[r] = runs[i].x0;
        if (runs[i].x1 > maxX[r]) maxX[r] = runs[i].x1;
        if (runs[i].y < minY[r]) minY[r] = runs[i].y;
        if (runs[i].y > maxY[r]) maxY[r] = runs[i].y;
    }

    /* ---- Pass 3：面积过滤 + 降序取前 maxBlobs 个输出 ---- */
    maxArea = (long) width * (long) height / IR_MAX_AREA_FRAC;
    nValid = 0;
    for (r = 0; r < runCount; r++) {
        if (area[r] < IR_MIN_AREA || area[r] > maxArea) continue;
        aggs[nValid].root = r;
        aggs[nValid].area = area[r];
        nValid++;
    }
    qsort(aggs, (size_t) nValid, sizeof(BlobAgg), cmp_blob_area_desc);
    nOut = nValid < maxBlobs ? nValid : maxBlobs;
    for (i = 0; i < nOut; i++) {
        long a = aggs[i].area;
        r = aggs[i].root;
        outP[3 * i] = (jint) (sumX[r] / a);
        outP[3 * i + 1] = (jint) (sumY[r] / a);
        outP[3 * i + 2] = (jint) (sqrt((double) a / 3.14159) + 0.5);
    }

done:
    free(runs);
    free(area);
    free(sumX);
    free(sumY);
    free(minX);
    free(maxX);
    free(minY);
    free(maxY);
    free(aggs);

    /* ===== critical 区间结束 ===== */
    (*env)->ReleasePrimitiveArrayCritical(env, yPlane, yP, JNI_ABORT);
    (*env)->ReleasePrimitiveArrayCritical(env, outPositions, outP, 0);

    return nOut;
}
