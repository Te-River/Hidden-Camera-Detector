package com.hcd.detector;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.hcd.detector.bluetooth.BluetoothScanActivity;
import com.hcd.detector.camera.IRActivity;
import com.hcd.detector.camera.TorchActivity;
import com.hcd.detector.magnetic.MagneticActivity;
import com.hcd.detector.network.NetworkScanActivity;

public class MainActivity extends Activity implements AdapterView.OnItemClickListener {

    /** 5 个模式入口，顺序：磁场 / 红外 / 强光反光 / 网络 / 蓝牙（与 MODE_ICONS 等平行表一致）。 */
    private static final Class<?>[] MODE_ACTIVITIES = {
            MagneticActivity.class,
            IRActivity.class,
            TorchActivity.class,
            NetworkScanActivity.class,
            BluetoothScanActivity.class,
    };

    private static final String[] MODE_ICONS = {"🧲", "👁", "🔦", "🌐", "📶"};

    private static final int[] MODE_NAMES = {
            R.string.mode_magnetic, R.string.mode_ir, R.string.mode_torch,
            R.string.mode_network, R.string.mode_bluetooth,
    };

    private static final int[] MODE_DESCS = {
            R.string.mode_magnetic_desc, R.string.mode_ir_desc, R.string.mode_torch_desc,
            R.string.mode_network_desc, R.string.mode_bluetooth_desc,
    };

    /** 与 MODE_ACTIVITIES 平行：各模式进入前需持有的运行时权限。 */
    private static final String[][] MODE_PERMISSIONS = new String[MODE_ACTIVITIES.length][];

    static {
        MODE_PERMISSIONS[0] = new String[0];
        MODE_PERMISSIONS[1] = new String[]{Manifest.permission.CAMERA};
        MODE_PERMISSIONS[2] = new String[]{Manifest.permission.CAMERA};
        MODE_PERMISSIONS[3] = new String[0];
        if (Build.VERSION.SDK_INT >= 31) {
            MODE_PERMISSIONS[4] = new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
            };
        } else {
            MODE_PERMISSIONS[4] = new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        }
    }

    /** 等待授权结果的模式下标，-1 表示无待处理请求。 */
    private int pendingMode = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ListView list = findViewById(R.id.list_modes);
        list.setAdapter(new ModeAdapter());
        list.setOnItemClickListener(this);

        applyFullscreen(this);
        applyHighRefreshRate(this);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (ensurePermissions(position)) {
            startActivity(new Intent(this, MODE_ACTIVITIES[position]));
        }
    }

    /** 全部已授权返回 true；否则发起请求、记录 pending 并返回 false。 */
    private boolean ensurePermissions(int modeIndex) {
        String[] perms = MODE_PERMISSIONS[modeIndex];
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                pendingMode = modeIndex;
                requestPermissions(perms, 100 + modeIndex);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        int modeIndex = requestCode - 100;
        if (modeIndex < 0 || modeIndex >= MODE_ACTIVITIES.length || modeIndex != pendingMode) {
            return;
        }
        pendingMode = -1;
        // 授权对话框被取消时 grantResults 为空数组，视为未授权
        if (grantResults.length == 0) {
            Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_SHORT).show();
            return;
        }
        for (int r : grantResults) {
            if (r != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.perm_denied, Toast.LENGTH_SHORT).show();
                return;
            }
        }
        startActivity(new Intent(this, MODE_ACTIVITIES[modeIndex]));
    }

    /** 沉浸式全屏：API 30+ 走 InsetsController，26-29 回退 systemUiVisibility。 */
    public static void applyFullscreen(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = a.getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            a.getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    /** 取与当前模式同分辨率且刷新率最高的显示模式，写入 preferredDisplayModeId。 */
    public static void applyHighRefreshRate(Activity a) {
        Display display = a.getWindowManager().getDefaultDisplay();
        if (display == null) {
            return;
        }
        Display.Mode current = display.getMode();
        Display.Mode best = current;
        for (Display.Mode m : display.getSupportedModes()) {
            if (m.getPhysicalWidth() == current.getPhysicalWidth()
                    && m.getPhysicalHeight() == current.getPhysicalHeight()
                    && m.getRefreshRate() > best.getRefreshRate()) {
                best = m;
            }
        }
        WindowManager.LayoutParams lp = a.getWindow().getAttributes();
        lp.preferredDisplayModeId = best.getModeId();
        a.getWindow().setAttributes(lp);
    }

    /** 主列表适配器：item_mode.xml 一行一模式。 */
    private final class ModeAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return MODE_ACTIVITIES.length;
        }

        @Override
        public Object getItem(int position) {
            return MODE_ACTIVITIES[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_mode, parent, false);
            }
            ((TextView) row.findViewById(R.id.tv_mode_icon)).setText(MODE_ICONS[position]);
            ((TextView) row.findViewById(R.id.tv_mode_name)).setText(MODE_NAMES[position]);
            ((TextView) row.findViewById(R.id.tv_mode_desc)).setText(MODE_DESCS[position]);
            return row;
        }
    }
}
