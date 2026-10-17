package io.github.breeder1229.rg477voppdiag;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** One-shot, fixed-path read-only diagnostic. No Binder hardware calls or settings writes. */
public final class MainActivity extends Activity {
    private static final String TAG = "RG477VOppDiag";
    private static final String FINGERPRINT = "alps/vext_k6897v1_64/k6897v1_64:14/UP1A.231005.007/V654202605290319:user/test-keys";
    private static final int LIMIT = 131072;
    private static final String[] FILES = {
        "/proc/self/attr/current",
        "/proc/gpufreqv2/gpufreq_opp_dump",
        "/proc/gpufreqv2/stack_opp_dump",
        "/proc/gpufreqv2/gpufreq_status",
        "/proc/gpufreqv2/gpufreq_var_dump",
        "/proc/gpufreqv2/gpufreq_working_opp_table",
        "/proc/gpufreqv2/gpu_working_opp_table",
        "/sys/devices/platform/soc/13000000.mali/devfreq/13000000.mali/available_frequencies",
        "/sys/kernel/ged/hal/gpu_freqLevelCount",
        "/sys/kernel/ged/hal/total_gpu_freq_level_count",
        "/sys/devices/system/cpu/cpufreq/policy0/scaling_available_frequencies",
        "/sys/devices/system/cpu/cpufreq/policy4/scaling_available_frequencies",
        "/sys/devices/system/cpu/cpufreq/policy7/scaling_available_frequencies"
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setTextSize(14); text.setPadding(24, 24, 24, 24);
        text.setTextIsSelectable(true);
        text.setText("RG477V OPP 읽기 전용 진단 실행 중…");
        ScrollView scroll = new ScrollView(this); scroll.addView(text); setContentView(scroll);
        new Thread(() -> {
            String report = collect();
            String output = save(report);
            String displayed = report + "\n" + output;
            for (int offset = 0; offset < displayed.length(); offset += 1800)
                Log.i(TAG, displayed.substring(offset, Math.min(offset + 1800, displayed.length())));
            runOnUiThread(() -> { if (!isDestroyed()) text.setText(displayed); });
        }, "ReadOnlyOppDiagnostic").start();
    }

    private String collect() {
        StringBuilder out = new StringBuilder("READ ONLY OPP DIAGNOSTIC v1\n");
        out.append("model=").append(Build.MODEL).append("\nsdk=").append(Build.VERSION.SDK_INT)
            .append("\nuid=").append(android.os.Process.myUid())
            .append("\nfingerprint=").append(Build.FINGERPRINT).append("\n");
        if (android.os.Process.myUid() != 1000 || Build.VERSION.SDK_INT != 34
                || !FINGERPRINT.equals(Build.FINGERPRINT))
            return out.append("STOP: expected UID1000 / exact Android14 firmware required.\n").toString();
        for (String path : FILES) {
            out.append("\n=== ").append(path).append(" ===\n");
            try (FileInputStream input = new FileInputStream(path);
                 ByteArrayOutputStream data = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer, 0, Math.min(buffer.length, LIMIT - data.size()))) > 0)
                    data.write(buffer, 0, count);
                out.append(new String(data.toByteArray(), StandardCharsets.UTF_8));
                if (data.size() == LIMIT) out.append("\n[read limit reached]");
                out.append('\n');
            } catch (Exception failure) { out.append("READ ERROR: ").append(failure).append('\n'); }
        }
        return out.toString();
    }

    private String save(String report) {
        File folder = getExternalFilesDir(null);
        if (folder == null) return "OUTPUT ERROR: own external files directory unavailable";
        File output = new File(folder, "opp-diagnostic.txt");
        try (FileOutputStream stream = new FileOutputStream(output, false)) {
            stream.write(report.getBytes(StandardCharsets.UTF_8)); stream.getFD().sync();
            return "OUTPUT: " + output.getAbsolutePath();
        } catch (Exception failure) { return "OUTPUT ERROR: " + failure; }
    }
}
