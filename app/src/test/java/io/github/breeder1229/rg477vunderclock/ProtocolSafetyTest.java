package io.github.breeder1229.rg477vunderclock;

import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolSafetyTest {
    @Test public void failureExitCannotMasqueradeAsSuccess() {
        ClockReport report = new ClockReport("action=diagnose\noutcome=ok\ncompatible=true\ncan_apply=true\nprofile=small\n", 1);
        assertFalse(report.success()); assertFalse(report.canApply("small"));
    }
    @Test public void staleProfileCannotEnableDifferentApply() {
        ClockReport report = new ClockReport("action=diagnose\noutcome=ok\ncompatible=true\ncan_apply=true\nprofile=medium\n", 0);
        assertTrue(report.canApply("medium")); assertFalse(report.canApply("small"));
    }
    @Test public void unitsAndFractionalCpuGoalRemainExact() {
        assertEquals("2062.5 MHz", ClockReport.mhz("2062500", false));
        assertEquals("1718.75 MHz", ClockReport.mhz("1718750", false));
        assertEquals("1190 MHz", ClockReport.mhz("1190000000", true));
        assertEquals("확인 불가", ClockReport.mhz("unknown", true));
        assertEquals("드라이버 기본값 (0)", ClockReport.capMhz("0", true));
        assertEquals("확인 불가", ClockReport.mhz("0", true));
    }
    @Test public void reportValuesDoNotExecuteOrDropEquals() {
        ClockReport report = new ClockReport("message=a=b\ninvalid key=malicious\nuid=10123\n", 1);
        assertEquals("a=b", report.get("message")); assertEquals("", report.get("invalid key"));
    }
    @Test public void exportBindsChosenActionAndPreservesSharedPrivateState() {
        String script = ExportScriptBuilder.build("echo engine\n", "restore", "small");
        assertTrue(script.contains("/system/bin/sh -s --"));
        assertTrue(script.contains("/data/local/tmp/rg477v-runshell-$rg_uid"));
        assertTrue(script.contains("1000) rg_state=/data/system/rg477v-runshell-1000"));
        assertFalse(script.contains("/sdcard/"));
        assertTrue(script.contains(java.util.Base64.getEncoder().encodeToString("echo engine\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        assertTrue(script.contains("rg477v-restore-small-report.txt"));
        assertEquals(1, java.util.Arrays.stream(script.split("\n")).filter(line -> !line.isEmpty() && !line.startsWith("#")).count());
    }
    @Test(expected = IllegalArgumentException.class) public void exportRejectsCommandInjection() {
        ExportScriptBuilder.build("", "apply; id", "small");
    }
    @Test(expected = IllegalArgumentException.class) public void exportRejectsProfileInjection() {
        ExportScriptBuilder.build("", "apply", "small' ; id");
    }
    @Test public void rootCommandQuotesInternalPathSafely() {
        assertEquals("'a'\\''b'", ExportScriptBuilder.quote("a'b"));
    }
    @Test public void invalidSnapshotIsNeverRestorable() {
        assertFalse(new ClockReport("snapshot=present\nsnapshot_valid=0\n", 2).validSnapshot());
        assertFalse(new ClockReport("snapshot=absent\nsnapshot_valid=1\n", 0).validSnapshot());
        assertTrue(new ClockReport("snapshot=present\nsnapshot_valid=1\n", 0).validSnapshot());
    }
    @Test public void restoreReadbackCannotMasqueradeAsProfilePreview() {
        ClockReport report = new ClockReport("action=restore\noutcome=ok\ncompatible=true\ncan_apply=true\nprofile=medium\n", 0);
        assertTrue(report.success()); assertFalse(report.canApply("medium"));
    }
}
