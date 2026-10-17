package io.github.breeder1229.rg477vunderclock;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Export has a fixed command and fixed state path; importing reports never executes content. */
public final class ExportScriptBuilder {
    private ExportScriptBuilder() { }
    public static String build(String engine, String action, String profile) {
        if (!action.matches("diagnose|apply|restore|recover")) throw new IllegalArgumentException("Unsupported action");
        if (!profile.matches("small|medium|large")) throw new IllegalArgumentException("Unsupported profile");
        String encoded = Base64.getEncoder().encodeToString(engine.replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8));
        String command = "PATH=/system/bin:/system/xbin:/vendor/bin; export PATH; "
                + "rg_uid=$(id -u) || exit 2; "
                + "case \"$rg_uid\" in ''|*[!0-9]*) exit 2;; esac; "
                + "case \"$rg_uid\" in 1000) rg_state=/data/system/rg477v-runshell-1000 ;; "
                + "*) rg_state=\"/data/local/tmp/rg477v-runshell-$rg_uid\" ;; esac; "
                + "rg_report=/storage/emulated/0/Download/rg477v-" + action + "-" + profile + "-report.txt; "
                + "[ ! -L \"$rg_report\" ] || exit 2; "
                + "printf '%s' " + quote(encoded) + " | /system/bin/base64 -d | /system/bin/sh -s -- "
                + quote(action) + " \"$rg_state\" " + quote(profile) + " > \"$rg_report\" 2>&1; "
                + "rg_exit=$?; printf '\\nexecutor_exit=%s\\n' \"$rg_exit\" >> \"$rg_report\"; exit \"$rg_exit\"";
        return "#!/system/bin/sh\n"
                + "# RG477V clock v1: exported action " + action + " / " + profile + "\n"
                + "# Saving this file does not apply clocks. Run it explicitly in the firmware's Run Shell.\n"
                + "# Restore with the same execution identity; snapshots are kept off shared storage.\n"
                + "# A failure or blocked result never means clocks were applied.\n"
                + "# Firmware may launch each non-comment line in a fresh shell.\n"
                + "# The complete engine runs in one shell; its report is saved in local Download.\n"
                + "/system/bin/sh -c " + quote(command) + "\n";
    }
    public static String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
