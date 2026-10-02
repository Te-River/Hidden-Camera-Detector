package com.hcd.detector.network;

import android.app.Activity;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.hcd.detector.MainActivity;
import com.hcd.detector.R;

import java.net.Inet4Address;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络扫描：TCP 端口扫描（PortScanner）+ NSD 服务发现（_rtsp/_onvif/_axis-video）。
 *
 * 端口结果来自 64 个工作线程，Activity 侧用 ConcurrentHashMap 按 IP 合并，
 * UI 刷新做 100ms 一次性合并（置脏标记 + postDelayed），避免洪泛主线程。
 */
public class NetworkScanActivity extends Activity implements PortScanner.Callback {

    /** NSD 服务类型：RTSP 流 / ONVIF 网络摄像头 / Axis 视频设备 */
    private static final String[] NSD_TYPES = {"_rtsp._tcp.", "_onvif._tcp.", "_axis-video._tcp."};
    private static final long REFRESH_MERGE_MS = 100;

    private final ConcurrentHashMap<String, Device> devices = new ConcurrentHashMap<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    /** NSD 串行 resolve 队列的锁（NsdManager 一次只允许一个 resolve 在途） */
    private final Object nsdLock = new Object();
    private final ArrayDeque<NsdServiceInfo> resolveQueue = new ArrayDeque<>();

    private PortScanner portScanner;
    private DeviceAdapter adapter;
    private List<Device> snapshot = Collections.emptyList();
    private String subnet;

    private NsdManager nsdManager;
    private final NsdManager.DiscoveryListener[] nsdListeners =
            new NsdManager.DiscoveryListener[NSD_TYPES.length];
    private boolean resolving;
    private volatile boolean nsdActive;

    private Button btnStart;
    private Button btnStop;
    private TextView tvProgress;

    private static class Device {
        final String ip;
        String name; // NSD 服务名（端口扫描阶段为 null）
        final TreeSet<Integer> ports = new TreeSet<>();
        boolean suspicious;

        Device(String ip) {
            this.ip = ip;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_scan);

        // 网络模式无运行时权限（MainActivity.MODE_PERMISSIONS[3] 为空表），无需防御性复查。

        adapter = new DeviceAdapter();
        ListView list = findViewById(R.id.list_devices);
        list.setAdapter(adapter);

        btnStart = findViewById(R.id.btn_scan_start);
        btnStop = findViewById(R.id.btn_scan_stop);
        tvProgress = findViewById(R.id.tv_scan_progress);

        portScanner = new PortScanner(this);
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);

        subnet = getSubnet();
        if (subnet == null) {
            Toast.makeText(this, R.string.scan_no_wifi, Toast.LENGTH_LONG).show();
            btnStart.setEnabled(false);
        }

        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScan();
            }
        });

        MainActivity.applyFullscreen(this);
        MainActivity.applyHighRefreshRate(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        portScanner.cancel();
        stopNsd();
        main.removeCallbacksAndMessages(null);
    }

    // ==================== 扫描主流程 ====================

    private void startScan() {
        if (subnet == null) {
            Toast.makeText(this, R.string.scan_no_wifi, Toast.LENGTH_LONG).show();
            return;
        }
        devices.clear();
        rebuildSnapshot();
        adapter.notifyDataSetChanged();

        tvProgress.setText(getString(R.string.scan_scanning, subnet));
        portScanner.scan(subnet);
        startNsd();
    }

    private void stopScan() {
        portScanner.cancel();
        stopNsd();
        tvProgress.setText(getString(R.string.scan_finished, devices.size()));
    }

    // ==================== PortScanner.Callback ====================

    /** 工作线程回调：按 IP 合并端口，命中可疑端口置位；UI 走 100ms 合并刷新。 */
    @Override
    public void onPortOpen(String ip, int port) {
        Device d = obtainDevice(ip);
        synchronized (d) {
            d.ports.add(port);
            if (!d.suspicious && isSuspiciousPort(port)) d.suspicious = true;
        }
        scheduleRefresh();
    }

    @Override
    public void onProgress(int scannedHosts, int totalHosts) {
        tvProgress.setText(getString(R.string.scan_scanning, subnet) + "  "
                + getString(R.string.scan_progress, scannedHosts, totalHosts));
    }

    @Override
    public void onFinish(int totalHosts) {
        // NSD 不在此停：发现回调可能晚于端口扫描到达，继续合并进列表，由 stopScan/onDestroy 统一停
        tvProgress.setText(getString(R.string.scan_finished, devices.size()));
    }

    // ==================== 设备合并与 UI 刷新 ====================

    private Device obtainDevice(String ip) {
        Device d = devices.get(ip);
        if (d != null) return d;
        Device fresh = new Device(ip);
        Device prev = devices.putIfAbsent(ip, fresh);
        return prev != null ? prev : fresh;
    }

    private static boolean isSuspiciousPort(int port) {
        for (int p : PortScanner.SUSPICIOUS_PORTS) {
            if (p == port) return true;
        }
        return false;
    }

    /** 置脏标记 + postDelayed(100ms) 一次性 notifyDataSetChanged（多线程只触发一次刷新）。 */
    private void scheduleRefresh() {
        if (refreshPending.compareAndSet(false, true)) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshPending.set(false);
                    if (isDestroyed()) return;
                    rebuildSnapshot();
                    adapter.notifyDataSetChanged();
                }
            }, REFRESH_MERGE_MS);
        }
    }

    private void rebuildSnapshot() {
        List<Device> list = new ArrayList<>(devices.values());
        Collections.sort(list, new Comparator<Device>() {
            @Override
            public int compare(Device a, Device b) {
                return Integer.compare(lastOctet(a.ip), lastOctet(b.ip));
            }
        });
        snapshot = list;
    }

    private static int lastOctet(String ip) {
        int dot = ip.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= ip.length()) return 0;
        try {
            return Integer.parseInt(ip.substring(dot + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // ==================== 子网推导 ====================

    /**
     * 遍历网络接口：优先 wlan0，回退任意 up 且含 site-local IPv4 的接口，
     * 取 IPv4 地址去掉最后一段返回 "192.168.1"；无 → null。
     */
    private String getSubnet() {
        try {
            NetworkInterface fallback = null;
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String prefix = ipv4Prefix(ni);
                if (prefix == null) continue;
                if ("wlan0".equals(ni.getName())) return prefix;
                if (fallback == null) fallback = ni;
            }
            return fallback == null ? null : ipv4Prefix(fallback);
        } catch (Exception e) {
            return null;
        }
    }

    private static String ipv4Prefix(NetworkInterface ni) {
        for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
            if (ia.getAddress() instanceof Inet4Address && ia.getAddress().isSiteLocalAddress()) {
                String host = ia.getAddress().getHostAddress(); // 形如 "192.168.1.12"
                int dot = host.lastIndexOf('.');
                if (dot > 0) return host.substring(0, dot);
            }
        }
        return null;
    }

    // ==================== NSD 服务发现 ====================

    private void startNsd() {
        if (nsdManager == null) return;
        stopNsd();
        synchronized (nsdLock) {
            resolveQueue.clear();
            resolving = false;
            nsdActive = true;
        }
        for (int i = 0; i < NSD_TYPES.length; i++) {
            final int idx = i;
            nsdListeners[i] = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {
                }

                @Override
                public void onDiscoveryStopped(String serviceType) {
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    enqueueResolve(serviceInfo);
                }
            };
            try {
                nsdManager.discoverServices(NSD_TYPES[i], NsdManager.PROTOCOL_DNS_SD, nsdListeners[i]);
            } catch (IllegalArgumentException e) {
                // 该类型已在发现中 / 服务异常 —— 忽略，其余类型继续
                nsdListeners[idx] = null;
            }
        }
    }

    private void stopNsd() {
        synchronized (nsdLock) {
            nsdActive = false;
            resolveQueue.clear();
            resolving = false;
        }
        for (int i = 0; i < nsdListeners.length; i++) {
            NsdManager.DiscoveryListener l = nsdListeners[i];
            if (l == null) continue;
            try {
                nsdManager.stopServiceDiscovery(l);
            } catch (IllegalArgumentException ignored) {
                // 该监听未在发现中（启动失败 / 已停）—— 正常
            }
            nsdListeners[i] = null;
        }
    }

    private void enqueueResolve(NsdServiceInfo info) {
        synchronized (nsdLock) {
            if (!nsdActive) return;
            resolveQueue.addLast(info);
            pumpResolveLocked();
        }
    }

    /**
     * NsdManager 同一时刻只允许一个 resolve 在途（决策 #10），串行队列逐个处理。
     *
     * API 分支：resolveService 是 API 34 新增；老方法 resolve 在 API 34 被标记
     * deprecated、且已从 compileSdk 36 的 android.jar 中移除，只能反射调用
     * （运行时 API 26-33 的 NsdManager 上该方法真实存在）。
     */
    @SuppressWarnings("deprecation") // resolveService 在 API 36 亦被标记 deprecated，但方法存在且可用，纯 framework 无更优替代
    private void pumpResolveLocked() {
        if (resolving || resolveQueue.isEmpty() || !nsdActive) return;
        NsdServiceInfo info = resolveQueue.pollFirst();
        resolving = true;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                nsdManager.resolveService(info, resolveListener);
            } else {
                Method resolve = NsdManager.class.getMethod("resolve",
                        NsdServiceInfo.class, NsdManager.ResolveListener.class);
                resolve.invoke(nsdManager, info, resolveListener);
            }
        } catch (Exception e) {
            // 内部仍有在途 resolve（如 stopNsd 后残留）或反射失败：丢弃本条，
            // 等在途回调或下一次 onServiceFound 再驱动队列
            resolving = false;
        }
    }

    private final NsdManager.ResolveListener resolveListener = new NsdManager.ResolveListener() {
        @Override
        public void onResolveFailed(NsdServiceInfo info, int errorCode) {
            synchronized (nsdLock) {
                resolving = false;
                pumpResolveLocked();
            }
        }

        @Override
        public void onServiceResolved(NsdServiceInfo info) {
            mergeNsdDevice(info);
            synchronized (nsdLock) {
                resolving = false;
                pumpResolveLocked();
            }
        }
    };

    /** 按 host IP 合并进 devices：name=服务名，suspicious=true（RTSP/ONVIF/Axis 服务即摄像头特征）。 */
    @SuppressWarnings("deprecation") // getHost() 在 API 36 标记 deprecated（替代品 getHostAddresses 为 API 34+），沿用
    private void mergeNsdDevice(NsdServiceInfo info) {
        if (!nsdActive) return;
        String ip = info.getHost() != null ? info.getHost().getHostAddress() : null;
        if (ip == null) return;
        Device d = obtainDevice(ip);
        synchronized (d) {
            if (d.name == null) d.name = info.getServiceName();
            d.suspicious = true;
        }
        scheduleRefresh();
    }

    // ==================== 列表适配器 ====================

    private class DeviceAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(NetworkScanActivity.this);

        @Override
        public int getCount() {
            return snapshot.size();
        }

        @Override
        public Device getItem(int position) {
            return snapshot.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : inflater.inflate(R.layout.item_device, parent, false);
            Device d = getItem(position);

            String name;
            String ports;
            boolean suspicious;
            synchronized (d) {
                name = d.name != null ? d.name : d.ip;
                StringBuilder sb = new StringBuilder();
                for (int p : d.ports) {
                    if (sb.length() > 0) sb.append(',');
                    sb.append(p);
                }
                ports = sb.toString();
                suspicious = d.suspicious;
            }

            ((TextView) v.findViewById(R.id.tv_device_name)).setText(name);
            ((TextView) v.findViewById(R.id.tv_device_info))
                    .setText(getString(R.string.device_ports, ports));

            View dot = v.findViewById(R.id.v_device_dot);
            dot.getBackground().mutate().setTint(suspicious
                    ? getColor(R.color.colorDotAlert) : getColor(R.color.colorDotNormal));

            v.findViewById(R.id.tv_device_flag)
                    .setVisibility(suspicious ? View.VISIBLE : View.GONE);
            return v;
        }
    }
}
