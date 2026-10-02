package com.hcd.detector.network;

import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 局域网 TCP 端口扫描器（纯 connect 探测）。
 *
 * Android 10+ 对应用禁读 /proc/net（ARP 表不可用），因此只做 TCP connect：
 * 每个主机一个任务，顺序尝试 12 个端口，单个 connect 超时 250ms，
 * 连接成功即回调 onPortOpen 并关闭 socket。64 线程并发，254 主机一轮。
 */
public class PortScanner {

    /** 全端口表（顺序即扫描顺序） */
    public static final int[] PORTS = {554, 8000, 80, 81, 443, 8080, 8888, 34567, 34568, 8899, 8554, 5000};
    /** RTSP / 厂商私有端口，命中即视为可疑摄像头特征 */
    public static final int[] SUSPICIOUS_PORTS = {554, 8554, 8000, 34567, 34568, 8899, 5000};
    public static final int CONNECT_TIMEOUT_MS = 250;
    public static final int THREADS = 64;
    public static final int HOSTS = 254;   // .1 ~ .254

    /** 回调契约：onPortOpen 在工作线程（Activity 需自行合并 + 切主线程）；onProgress / onFinish 在主线程。 */
    public interface Callback {
        void onPortOpen(String ip, int port);
        void onProgress(int scannedHosts, int totalHosts);
        void onFinish(int totalHosts);
    }

    private final Callback cb;
    private final Handler main = new Handler(Looper.getMainLooper());

    /**
     * 代际号：scan() 与 cancel() 均自增。在途任务见到代际不符即静默退出，
     * 防止 cancel 后仍在途的 connect（最长 250ms）把结果灌进新一轮扫描。
     */
    private volatile int generation = 0;
    private volatile ExecutorService executor;

    public PortScanner(Callback cb) {
        this.cb = cb;
    }

    /** subnet 形如 "192.168.1"（无尾点）。重复调用先 cancel 旧任务。 */
    public void scan(String subnet) {
        if (subnet == null) return;
        cancel();
        final int gen = ++generation;
        final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        executor = pool;
        final AtomicInteger scanned = new AtomicInteger(0);
        for (int i = 1; i <= HOSTS; i++) {
            final String ip = subnet + "." + i;
            pool.execute(new Runnable() {
                @Override
                public void run() {
                    scanHost(ip, gen, scanned);
                }
            });
        }
        pool.shutdown(); // 只拒绝后续提交，已提交任务照常执行完毕
    }

    private void scanHost(String ip, int gen, AtomicInteger scanned) {
        for (int port : PORTS) {
            if (gen != generation) return; // 已被 cancel 或新一轮 scan 取代
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
                if (gen == generation) cb.onPortOpen(ip, port); // 工作线程直发，由 Activity 合并
            } catch (IOException ignored) {
                // 超时 / 拒绝 = 端口关闭，正常情况
            } finally {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
        final int n = scanned.incrementAndGet();
        if (gen != generation) return;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gen == generation) cb.onProgress(n, HOSTS);
            }
        });
        // 见到 scanned==total 的那个线程负责 post onFinish
        if (n == HOSTS) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (gen == generation) cb.onFinish(HOSTS);
                }
            });
        }
    }

    /** 置 volatile 取消标志 + executor.shutdownNow()；在途 connect 最长 250ms 自然结束。 */
    public void cancel() {
        generation++;
        ExecutorService pool = executor;
        executor = null;
        if (pool != null) pool.shutdownNow();
    }

    public boolean isScanning() {
        ExecutorService pool = executor;
        return pool != null && !pool.isTerminated();
    }
}
