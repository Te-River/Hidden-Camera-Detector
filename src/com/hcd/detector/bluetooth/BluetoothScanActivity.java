package com.hcd.detector.bluetooth;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.hcd.detector.MainActivity;
import com.hcd.detector.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 蓝牙扫描：BLE（BluetoothLeScanner，LOW_LATENCY 无过滤）+ 经典发现（startDiscovery）
 * 双通道并行，按 MAC 合并设备；经典发现约 12s 自动停，靠 DISCOVERY_FINISHED 广播循环续扫。
 */
public class BluetoothScanActivity extends Activity {

    /** 可疑名称关键词（大写匹配） */
    private static final String[] KEYWORDS = {"CAM", "IPC", "DVR", "NVR", "HIDDEN", "MINI"};
    private static final long RESTART_DELAY_MS = 1000;
    private static final long REFRESH_MERGE_MS = 100;

    private final HashMap<String, BtDevice> byMac = new HashMap<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean scanning;

    private BluetoothAdapter btAdapter;
    private BluetoothLeScanner bleScanner;
    private BtDeviceAdapter adapter;
    private List<BtDevice> snapshot = Collections.emptyList();
    private BroadcastReceiver receiver;
    private boolean receiverRegistered;

    private TextView tvStatus;

    private static class BtDevice {
        String name; // 未广播名称的设备一直为 null
        final String mac;
        int rssi;
        boolean suspicious;

        BtDevice(String mac) {
            this.mac = mac;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_bluetooth_scan);

        // 防御性复查（MainActivity 已按 MODE_PERMISSIONS 预检，双保险）
        String[] required = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        for (String p : required) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_LONG).show();
                finish();
                return;
            }
        }

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        btAdapter = bm != null ? bm.getAdapter() : null;

        adapter = new BtDeviceAdapter();
        ((ListView) findViewById(R.id.list_bt_devices)).setAdapter(adapter);

        tvStatus = findViewById(R.id.tv_bt_status);
        findViewById(R.id.btn_bt_start).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startScan();
            }
        });
        findViewById(R.id.btn_bt_stop).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopScan();
            }
        });

        if (btAdapter == null) {
            findViewById(R.id.btn_bt_start).setEnabled(false); // 设备无蓝牙
        }

        receiver = new BroadcastReceiver() {
            @Override
            @SuppressWarnings("deprecation") // getParcelableExtra 在 API 33 标记 deprecated，纯 framework 沿用
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                    BluetoothDevice dev = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (dev == null) return;
                    int rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    mergeDevice(dev.getAddress(), dev.getName(), rssi);
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                    // 经典发现约 12s 自动停；scanning 仍为 true 则延迟 1s 重启续扫
                    if (scanning) main.postDelayed(restartDiscovery, RESTART_DELAY_MS);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(receiver, filter); // 两个均为系统保护广播，无需 EXPORTED 标志
        receiverRegistered = true;

        MainActivity.applyFullscreen(this);
        MainActivity.applyHighRefreshRate(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        if (receiverRegistered) {
            unregisterReceiver(receiver);
            receiverRegistered = false;
        }
        main.removeCallbacksAndMessages(null);
    }

    // ==================== 扫描主流程 ====================

    private void startScan() {
        if (btAdapter == null) return;
        if (!btAdapter.isEnabled()) {
            // 蓝牙未开启：拉起系统开启对话框（契约无对应文案资源，交由系统 UI）
            startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        scanning = true;
        byMac.clear();
        rebuildSnapshot();
        adapter.notifyDataSetChanged();

        bleScanner = btAdapter.getBluetoothLeScanner();
        if (bleScanner != null) {
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build();
            try {
                bleScanner.startScan(Collections.<android.bluetooth.le.ScanFilter>emptyList(),
                        settings, scanCallback); // 空过滤 = 扫全部
            } catch (Exception e) {
                // BLE 启动失败（常见：定位服务关闭）—— 经典发现仍继续
            }
        }
        btAdapter.startDiscovery();
        tvStatus.setText(R.string.bt_scanning);
    }

    private void stopScan() {
        scanning = false;
        main.removeCallbacks(restartDiscovery);
        if (bleScanner != null) {
            try {
                bleScanner.stopScan(scanCallback);
            } catch (Exception ignored) {
                // 蓝牙已关 / 回调未注册 —— 忽略
            }
            bleScanner = null;
        }
        if (btAdapter != null) btAdapter.cancelDiscovery();
        int count;
        synchronized (byMac) {
            count = byMac.size();
        }
        tvStatus.setText(getString(R.string.bt_finished, count));
    }

    /** 经典发现续扫任务：scanning 仍为 true 且蓝牙可用时重启 startDiscovery。 */
    private final Runnable restartDiscovery = new Runnable() {
        @Override
        public void run() {
            if (scanning && btAdapter != null && btAdapter.isEnabled()) {
                btAdapter.startDiscovery();
            }
        }
    };

    // ==================== 设备合并 ====================

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (!scanning) return;
            BluetoothDevice dev = result.getDevice();
            if (dev == null) return;
            String name = dev.getName();
            if (name == null && result.getScanRecord() != null) {
                name = result.getScanRecord().getDeviceName(); // 广播包里带名称的情况
            }
            mergeDevice(dev.getAddress(), name, result.getRssi());
        }

        @Override
        public void onScanFailed(int errorCode) {
            // BLE 起不来（如定位服务关闭）：不影响经典发现续扫循环，仅放弃 BLE 通道
        }
    };

    /** BLE / 经典发现共用合并入口；可能来自 binder 线程，byMac 全程持锁。 */
    private void mergeDevice(String mac, String name, int rssi) {
        if (mac == null) return;
        synchronized (byMac) {
            BtDevice d = byMac.get(mac);
            if (d == null) {
                d = new BtDevice(mac);
                byMac.put(mac, d);
            }
            if (name != null && !name.isEmpty()) d.name = name;
            d.rssi = rssi;
            d.suspicious = isSuspicious(d.name);
        }
        scheduleRefresh();
    }

    /** name 非空且大写化后包含任一关键词（Locale.ROOT 规避土耳其语系 i 点化问题）。 */
    private static boolean isSuspicious(String name) {
        if (name == null) return false;
        String upper = name.toUpperCase(Locale.ROOT);
        for (String k : KEYWORDS) {
            if (upper.contains(k)) return true;
        }
        return false;
    }

    /** 置脏标记 + postDelayed(100ms) 一次性刷新，避免 BLE 高频回调洪泛主线程。 */
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
        List<BtDevice> list;
        synchronized (byMac) {
            list = new ArrayList<>(byMac.values());
        }
        Collections.sort(list, new Comparator<BtDevice>() {
            @Override
            public int compare(BtDevice a, BtDevice b) {
                return Integer.compare(b.rssi, a.rssi); // RSSI 降序，近的设备在前
            }
        });
        snapshot = list;
    }

    // ==================== 列表适配器 ====================

    private class BtDeviceAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(BluetoothScanActivity.this);

        @Override
        public int getCount() {
            return snapshot.size();
        }

        @Override
        public BtDevice getItem(int position) {
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
            BtDevice d = getItem(position);

            String name;
            int rssi;
            boolean suspicious;
            synchronized (byMac) {
                name = d.name != null ? d.name : d.mac;
                rssi = d.rssi;
                suspicious = d.suspicious;
            }

            ((TextView) v.findViewById(R.id.tv_device_name)).setText(name);
            ((TextView) v.findViewById(R.id.tv_device_info)).setText("RSSI: " + rssi + "dBm");

            View dot = v.findViewById(R.id.v_device_dot);
            dot.getBackground().mutate().setTint(suspicious
                    ? getColor(R.color.colorDotAlert) : getColor(R.color.colorDotNormal));

            v.findViewById(R.id.tv_device_flag)
                    .setVisibility(suspicious ? View.VISIBLE : View.GONE);
            return v;
        }
    }
}
