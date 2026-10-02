package com.hcd.detector.magnetic;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Button;
import android.widget.TextView;

import com.hcd.detector.MainActivity;
import com.hcd.detector.R;

import java.util.ArrayList;

public class MagneticActivity extends Activity implements SensorEventListener {

    private static final long CALIBRATION_MS = 10_000L;
    private static final float ALERT_MULTIPLIER = 2f;
    private static final float MIN_ALERT_UT = 100f;
    private static final long VIBRATE_INTERVAL_MS = 2_000L;

    private SensorManager sensorManager;
    private Sensor magneticSensor;
    private Vibrator vibrator;

    private GaugeView gauge;
    private TextView tvValue;
    private TextView tvStatus;
    private TextView tvBaseline;

    private final ArrayList<Float> samples = new ArrayList<>();
    private long calibrationStart;
    private boolean calibrating = false;
    private boolean calibrated = false;
    private float baseline;
    private float threshold;
    private boolean alerting = false;
    private long lastVibrate = -VIBRATE_INTERVAL_MS;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_magnetic);

        gauge = findViewById(R.id.gauge_view);
        tvValue = findViewById(R.id.tv_magnetic_value);
        tvStatus = findViewById(R.id.tv_magnetic_status);
        tvBaseline = findViewById(R.id.tv_magnetic_baseline);
        Button btnRecalibrate = findViewById(R.id.btn_recalibrate);
        btnRecalibrate.setOnClickListener(v -> startCalibration());

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (sensorManager != null) {
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        MainActivity.applyImmersive(this);
        MainActivity.applyInsetsPadding(this, findViewById(android.R.id.content));
        MainActivity.applyHighRefreshRate(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sensorManager != null && magneticSensor != null) {
            sensorManager.registerListener(this, magneticSensor, SensorManager.SENSOR_DELAY_GAME);
        }
        if (!calibrated) {
            startCalibration();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float v = (float) Math.sqrt(x * x + y * y + z * z);

        if (calibrating) {
            samples.add(v);
            long remainMs = CALIBRATION_MS - (SystemClock.elapsedRealtime() - calibrationStart);
            if (remainMs <= 0) {
                finishCalibration();
            } else {
                long remainSec = (remainMs + 999) / 1000; // 向上取整，避免开局显示 0 秒
                tvStatus.setTextColor(getColor(R.color.colorNormal));
                tvStatus.setText(getString(R.string.magnetic_calibrating, remainSec));
            }
            return;
        }

        gauge.setValue(v);
        tvValue.setText(getString(R.string.magnetic_value, v));
        checkAlert(v);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // 无需处理
    }

    /** 清空样本重新开始 10 秒本底校准。 */
    private void startCalibration() {
        samples.clear();
        calibrationStart = SystemClock.elapsedRealtime();
        calibrating = true;
        calibrated = false;
        alerting = false;
        gauge.setAlert(false);
        tvStatus.setTextColor(getColor(R.color.colorNormal));
        tvStatus.setText(getString(R.string.magnetic_calibrating, CALIBRATION_MS / 1000));
    }

    /** 样本均值即本底；告警阈值 = max(本底×2, 100µT)。 */
    private void finishCalibration() {
        calibrating = false;
        calibrated = true;
        alerting = false;

        float sum = 0f;
        for (float s : samples) {
            sum += s;
        }
        baseline = samples.isEmpty() ? 0f : sum / samples.size();
        threshold = Math.max(baseline * ALERT_MULTIPLIER, MIN_ALERT_UT);

        gauge.setAlert(false);
        gauge.setRange(Math.max(threshold * 1.5f, 200f));
        tvBaseline.setText(getString(R.string.magnetic_baseline, baseline));
        tvStatus.setTextColor(getColor(R.color.colorNormal));
        tvStatus.setText(R.string.magnetic_normal);
    }

    /** 超阈值进入告警态（变红 + 节流震动），回落则恢复。 */
    private void checkAlert(float v) {
        if (v > threshold) {
            if (!alerting) {
                alerting = true;
                gauge.setAlert(true);
                tvStatus.setText(R.string.magnetic_alert);
                tvStatus.setTextColor(getColor(R.color.colorAlert));
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastVibrate >= VIBRATE_INTERVAL_MS) {
                lastVibrate = now;
                vibrate();
            }
        } else if (alerting) {
            alerting = false;
            gauge.setAlert(false);
            tvStatus.setText(R.string.magnetic_normal);
            tvStatus.setTextColor(getColor(R.color.colorNormal));
        }
    }

    private void vibrate() {
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        vibrator.vibrate(VibrationEffect.createOneShot(300, VibrationEffect.DEFAULT_AMPLITUDE));
    }
}
