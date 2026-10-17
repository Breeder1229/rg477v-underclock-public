package io.github.breeder1229.rg477vunderclock;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Line protocol; never interprets shell code or a snapshot as executable content. */
public final class ClockReport {
    public final Map<String, String> values;
    public final String raw;
    public final int exitCode;
    public final long observedAt;

    public ClockReport(String raw, int exitCode) {
        this.raw = raw;
        this.exitCode = exitCode;
        this.observedAt = System.currentTimeMillis();
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String line : raw.split("\\r?\\n")) {
            int split = line.indexOf('=');
            if (split <= 0) continue;
            String key = line.substring(0, split);
            if (key.matches("[a-zA-Z0-9_.]+")) parsed.put(key, line.substring(split + 1));
        }
        values = Collections.unmodifiableMap(parsed);
    }

    public String get(String key) { return values.getOrDefault(key, ""); }
    public boolean success() { return exitCode == 0 && "ok".equals(get("outcome")); }
    public boolean canApply(String profile) {
        return success() && "true".equals(get("compatible"))
                && "true".equals(get("can_apply")) && profile.equals(get("profile"))
                && ("diagnose".equals(get("action")) || "apply".equals(get("action")));
    }
    public boolean hasSnapshot() { return "present".equals(get("snapshot")); }
    public boolean validSnapshot() { return hasSnapshot() && "1".equals(get("snapshot_valid")); }
    public static String mhz(String raw, boolean gpu) {
        try {
            long number = Long.parseLong(raw);
            if (number <= 0) return "확인 불가";
            return BigDecimal.valueOf(number).divide(BigDecimal.valueOf(gpu ? 1000000L : 1000L))
                    .stripTrailingZeros().toPlainString() + " MHz";
        } catch (NumberFormatException e) { return "확인 불가"; }
    }
    public static String capMhz(String raw, boolean gpu) {
        return gpu && "0".equals(raw) ? "드라이버 기본값 (0)" : mhz(raw, gpu);
    }
}
