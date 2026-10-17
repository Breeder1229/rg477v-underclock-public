#!/system/bin/sh
# RG477V/D8300 cap-only transaction engine. Never source state or change permissions
# on a hardware node. State is private to each execution route and each boot.
umask 077
set -f

ACTION=${1:-}
STATE=${2:-}
PROFILE=${3:-medium}
ROOT=
TEST=0
if [ "${RG477V_ENGINE_TEST:-}" = 1 ]; then
    TEST=1
    ROOT=${RG477V_TEST_ROOT:-}
    case "$ROOT" in */rg477v-test-*) ;; *) printf 'outcome=blocked\nmessage_code=invalid_test_root\nmessage=Unsafe test fixture path\n'; exit 2 ;; esac
    [ "$(readlink -f "$ROOT" 2>/dev/null)" = "$ROOT" ] || exit 2
    [ -f "$ROOT/.rg477v-test-fixture" ] && [ "$(cat "$ROOT/.rg477v-test-fixture" 2>/dev/null | tr -d '\r\n')" = RG477V_TEST_ONLY ] || exit 2
fi
UID_NOW=$(id -u 2>/dev/null)
[ "$TEST" = 0 ] || UID_NOW=10000
CONTEXT=$(id -Z 2>/dev/null)
[ -n "$CONTEXT" ] || CONTEXT=unavailable
BOOT=$(cat "$ROOT/proc/sys/kernel/random/boot_id" 2>/dev/null | tr -d '\r\n')
BOOT_VALID=0
case "$BOOT" in
    ????????-????-????-????-????????????)
        case "$BOOT" in *[!a-fA-F0-9-]*) ;; *) BOOT_VALID=1 ;; esac ;;
esac
STATE_ROOT=$STATE
[ "$BOOT_VALID" != 1 ] || STATE="$STATE_ROOT/$BOOT"
PROP_FILE=${RG477V_TEST_PROPERTIES:-}
prop() {
    if [ "$TEST" = 1 ]; then
        awk -v key="$1" 'index($0,key"=")==1 {print substr($0,length(key)+2); exit}' "$PROP_FILE" 2>/dev/null | tr -d '\r'
    else
        getprop "$1" 2>/dev/null
    fi
}
DEVICE=$(prop ro.product.device)
MODEL=$(prop ro.product.model)
PLATFORM=$(prop ro.board.platform)
FINGERPRINT=$(prop ro.build.fingerprint)
CPU_ROOT="$ROOT/sys/devices/system/cpu/cpufreq"
GPU="$ROOT/sys/devices/platform/soc/13000000.mali/devfreq/13000000.mali"
FIX="$ROOT/proc/gpufreqv2/fix_target_opp_index"
OUTCOME=ok
CODE=diagnosed
MESSAGE='Read-only diagnosis completed'
COMPATIBLE=false
IDENTITY_CHECK=not_checked
CAN_APPLY=false
SNAPSHOT=absent
LOCKED=0
GLOBAL_LOCKED=0
GLOBAL_PARENT="$ROOT/data/system"
GLOBAL_LOCK="$GLOBAL_PARENT/rg477v-underclock-hardware.lock-$BOOT"
IN_TRANSACTION=0
PENDING_VALID=0
BASE_VALID=0
FAIL_REASON=
C0_SEL= C4_SEL= C7_SEL= G_SEL=
C0_TARGET= C4_TARGET= C7_TARGET= G_TARGET=
C0_BASE= C4_BASE= C7_BASE= G_BASE=
TAB=$(printf '\t')
CR=$(printf '\r')
NL='
'

# Android mksh reserves the alias "integer" for typeset -i.
valid_integer() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ "${#1}" -le 10 ] && [ "$1" -le 2000000000 ] 2>/dev/null; }
positive() { valid_integer "$1" && [ "$1" -gt 0 ]; }
read_num() {
    NODE_VALUE=$(cat "$1" 2>/dev/null)
    while case "$NODE_VALUE" in *"$CR"*) true ;; *) false ;; esac; do NODE_VALUE=${NODE_VALUE%%"$CR"*}${NODE_VALUE#*"$CR"}; done
    printf '%s' "$NODE_VALUE"
}
kv() {
    KV_VALUE=$2
    while case "$KV_VALUE" in *"$NL"*) true ;; *) false ;; esac; do KV_VALUE=${KV_VALUE%%"$NL"*}" "${KV_VALUE#*"$NL"}; done
    while case "$KV_VALUE" in *"$CR"*) true ;; *) false ;; esac; do KV_VALUE=${KV_VALUE%%"$CR"*}" "${KV_VALUE#*"$CR"}; done
    printf '%s=%s\n' "$1" "$KV_VALUE"
}
fail() { OUTCOME=$1; CODE=$2; MESSAGE=$3; CAN_APPLY=false; }

slot() {
    SLOT=$1
    case "$SLOT" in
        cpu0) PATH_NOW="$CPU_ROOT/policy0"; MAX_NODE=scaling_max_freq; MIN_NODE=scaling_min_freq; CUR_NODE=scaling_cur_freq; AVAIL_NODE=scaling_available_frequencies; BASE=$C0_BASE; TARGET=$C0_TARGET; SELECTED=$C0_SEL ;;
        cpu4) PATH_NOW="$CPU_ROOT/policy4"; MAX_NODE=scaling_max_freq; MIN_NODE=scaling_min_freq; CUR_NODE=scaling_cur_freq; AVAIL_NODE=scaling_available_frequencies; BASE=$C4_BASE; TARGET=$C4_TARGET; SELECTED=$C4_SEL ;;
        cpu7) PATH_NOW="$CPU_ROOT/policy7"; MAX_NODE=scaling_max_freq; MIN_NODE=scaling_min_freq; CUR_NODE=scaling_cur_freq; AVAIL_NODE=scaling_available_frequencies; BASE=$C7_BASE; TARGET=$C7_TARGET; SELECTED=$C7_SEL ;;
        gpu) PATH_NOW=$GPU; MAX_NODE=max_freq; MIN_NODE=min_freq; CUR_NODE=cur_freq; AVAIL_NODE=available_frequencies; BASE=$G_BASE; TARGET=$G_TARGET; SELECTED=$G_SEL ;;
    esac
    MIN=$(read_num "$PATH_NOW/$MIN_NODE")
    MAX=$(read_num "$PATH_NOW/$MAX_NODE")
    CURRENT=$(read_num "$PATH_NOW/$CUR_NODE")
    AVAILABLE=$(cat "$PATH_NOW/$AVAIL_NODE" 2>/dev/null | tr '\r\n\t' '   ')
    HIGHEST=0
    AVAIL_VALID=1
    [ -n "$AVAILABLE" ] || AVAIL_VALID=0
    for hz in $AVAILABLE; do
        positive "$hz" || { AVAIL_VALID=0; break; }
        [ "$hz" -le "$HIGHEST" ] || HIGHEST=$hz
    done
}
supported() { for hz in $AVAILABLE; do [ "$hz" != "$1" ] || return 0; done; return 1; }
valid_cap() {
    valid_integer "$1" && [ "$AVAIL_VALID" = 1 ] || return 1
    if [ "$SLOT" = gpu ] && [ "$1" = 0 ]; then return 0; fi
    # The original kernel-valid ceiling need not equal an OPP. New caps are mapped
    # to OPPs; restore preserves the exact observed ceiling, including GPU zero.
    positive "$1" && [ "$1" -le "$HIGHEST" ]
}

# Exact-order data parser: no eval, sourcing, computed write paths, or duplicate keys.
field() {
    IFS= read -r LINE <&3 || return 1
    case "$LINE" in "$1="*) VALUE=${LINE#*=} ;; *) return 1 ;; esac
}
parse_state() {
    FILE=$1
    [ -f "$FILE" ] && [ ! -L "$FILE" ] || return 1
    [ "$TEST" = 1 ] || {
        [ "$(stat -c %u "$FILE" 2>/dev/null)" = "$UID_NOW" ] && [ "$(stat -c %a "$FILE" 2>/dev/null)" = 600 ] || return 1
    }
    exec 3< "$FILE" || return 1
    field version && [ "$VALUE" = 1 ] &&
    field boot_id && [ "$VALUE" = "$BOOT" ] &&
    field device && [ "$VALUE" = "$DEVICE" ] &&
    field model && [ "$VALUE" = "$MODEL" ] &&
    field platform && [ "$VALUE" = "$PLATFORM" ] &&
    field fingerprint && [ "$VALUE" = "$FINGERPRINT" ] &&
    field uid && [ "$VALUE" = "$UID_NOW" ] || { exec 3<&-; return 1; }
    for s in cpu0 cpu4 cpu7 gpu; do
        slot "$s"
        field "$s.path" && [ "$VALUE" = "$PATH_NOW" ] || { exec 3<&-; return 1; }
        field "$s.min" && valid_integer "$VALUE" || { exec 3<&-; return 1; }
        SAVED_MIN=$VALUE
        field "$s.max" && valid_cap "$VALUE" || { exec 3<&-; return 1; }
        if [ "$s" != gpu ]; then
            SNAP_HW_MAX=$(read_num "$PATH_NOW/cpuinfo_max_freq")
            positive "$SNAP_HW_MAX" && [ "$VALUE" -le "$SNAP_HW_MAX" ] || { exec 3<&-; return 1; }
        fi
        if [ "$VALUE" != 0 ]; then [ "$SAVED_MIN" -le "$VALUE" ] || { exec 3<&-; return 1; }; fi
        case "$s" in
            cpu0) PARSE_C0=$VALUE; PARSE_M0=$SAVED_MIN ;;
            cpu4) PARSE_C4=$VALUE; PARSE_M4=$SAVED_MIN ;;
            cpu7) PARSE_C7=$VALUE; PARSE_M7=$SAVED_MIN ;;
            gpu) PARSE_G=$VALUE; PARSE_MG=$SAVED_MIN ;;
        esac
    done
    LINE=
    if IFS= read -r LINE <&3 || [ -n "$LINE" ]; then exec 3<&-; return 1; fi
    exec 3<&-
    return 0
}
write_state() {
    DEST=$1
    TEMP="$STATE/.state-$$.tmp"
    [ ! -e "$TEMP" ] && [ ! -L "$TEMP" ] || return 1
    {
        kv version 1; kv boot_id "$BOOT"; kv device "$DEVICE"; kv model "$MODEL"; kv platform "$PLATFORM"; kv fingerprint "$FINGERPRINT"; kv uid "$UID_NOW"
        for s in cpu0 cpu4 cpu7 gpu; do slot "$s"; kv "$s.path" "$PATH_NOW"; kv "$s.min" "$MIN"; kv "$s.max" "$MAX"; done
    } > "$TEMP" || return 1
    parse_state "$TEMP" || { rm -f "$TEMP"; return 1; }
    mv "$TEMP" "$DEST" || { rm -f "$TEMP"; return 1; }
    # Android toybox sync flushes the snapshot/journal before any sysfs mutation.
    [ "$TEST" = 1 ] || sync || return 1
}
private_state() {
    case "$STATE" in /*) ;; *) return 1 ;; esac
    case "$STATE" in *'/../'*|*'/./'*|*/..|*/.|*"$TAB"*|*'
'*) return 1 ;; esac
    [ ! -L "$STATE" ] || return 1
    if [ ! -d "$STATE" ]; then
        [ "$ACTION" != diagnose ] || return 0
        mkdir "$STATE" 2>/dev/null || return 1
    fi
    [ "$(readlink -f "$STATE" 2>/dev/null)" = "$STATE" ] || return 1
    [ "$TEST" = 1 ] || {
        [ "$(stat -c %u "$STATE" 2>/dev/null)" = "$UID_NOW" ] && [ "$(stat -c %a "$STATE" 2>/dev/null)" = 700 ] || return 1
    }
    [ -r "$STATE" ] && [ -w "$STATE" ] && [ -x "$STATE" ]
}
identity() {
    IDENTITY_CHECK=boot_or_firmware
    [ "$BOOT_VALID" = 1 ] && [ -n "$FINGERPRINT" ] && [ -n "$UID_NOW" ] || return 1
    PLATFORM_LOWER=$(printf '%s' "$PLATFORM" | tr '[:upper:]' '[:lower:]')
    # Stock firmware can report "RG 477V" and generic device "k6897v1_64".
    # The RG prefix is optional for 477V, 477M, and 557; retain all hardware checks.
    NAME_LOWER=$(printf '%s %s' "$MODEL" "$DEVICE" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')
    IDENTITY_CHECK=platform
    [ "$PLATFORM_LOWER" = mt6897 ] || return 1
    IDENTITY_CHECK=model
    case "$NAME_LOWER" in *477v*|*477m*|*557*) ;; *) return 1 ;; esac
    COUNT=0
    set +f
    for p in "$CPU_ROOT"/policy*; do [ -d "$p" ] && COUNT=$((COUNT + 1)); done
    set -f
    IDENTITY_CHECK=cpu_policy_count
    [ "$COUNT" = 3 ] || return 1
    for n in 0 4 7; do
        IDENTITY_CHECK="cpu${n}_topology"
        CPUS=$(cat "$CPU_ROOT/policy$n/related_cpus" 2>/dev/null | tr -d '\r' | awk '{$1=$1;print}')
        case "$n:$CPUS" in '0:0 1 2 3'|'4:4 5 6'|'7:7') ;; *) return 1 ;; esac
    done
    IDENTITY_CHECK=gpu_path
    [ -d "$GPU" ] || return 1
    [ "$(readlink -f "$GPU" 2>/dev/null)" = "$GPU" ] || return 1
    for n in 0 4 7; do
        IDENTITY_CHECK="cpu${n}_path"
        [ "$(readlink -f "$CPU_ROOT/policy$n" 2>/dev/null)" = "$CPU_ROOT/policy$n" ] || return 1
    done
    COMPATIBLE=true
    IDENTITY_CHECK=verified
}
fixed_check() {
    FIXED=$(read_num "$FIX")
    case "$FIXED" in
        -1|'[GPUFREQ-DEBUG] fix GPU OPP index is disabled'|'[GPUFREQ-DEBUG] fix GPU/STACK OPP index is disabled') return 0 ;;
    esac
    case "$FIXED" in
        '[GPUFREQ-DEBUG] fix GPU OPP index: '*|'[GPUFREQ-DEBUG] fix GPU/STACK OPP index: '*) FAIL_REASON=fixed_opp; return 1 ;;
    esac
    if valid_integer "$FIXED"; then FAIL_REASON=fixed_opp; else FAIL_REASON=fixed_unknown; fi
    return 1
}
targets() {
    case "$PROFILE" in
        small) C0_TARGET=2062500; C4_TARGET=3000000; C7_TARGET=3100000; G_TARGET=1400000000 ;;
        medium) C0_TARGET=1925000; C4_TARGET=2800000; C7_TARGET=2900000; G_TARGET=1200000000 ;;
        large) C0_TARGET=1718750; C4_TARGET=2500000; C7_TARGET=2700000; G_TARGET=1000000000 ;;
        *) FAIL_REASON=invalid_profile; return 1 ;;
    esac
}
preflight() {
    FAIL_REASON=
    identity || { FAIL_REASON=incompatible; return 1; }
    fixed_check || return 1
    [ -w "$GLOBAL_PARENT" ] && [ -x "$GLOBAL_PARENT" ] || { FAIL_REASON=hardware_lock_unavailable; return 1; }
    for s in cpu0 cpu4 cpu7 gpu; do
        slot "$s"
        [ "$AVAIL_VALID" = 1 ] || { FAIL_REASON=opp_unavailable; return 1; }
        valid_integer "$MIN" && valid_cap "$MAX" || { FAIL_REASON=invalid_frequency; return 1; }
        if [ "$s" != gpu ]; then
            positive "$MIN" || { FAIL_REASON=invalid_frequency; return 1; }
            HW_MAX=$(read_num "$PATH_NOW/cpuinfo_max_freq")
            positive "$HW_MAX" && [ "$HIGHEST" -le "$HW_MAX" ] || { FAIL_REASON=invalid_frequency; return 1; }
        fi
        [ "$MAX" = 0 ] || [ "$MIN" -le "$MAX" ] || { FAIL_REASON=min_conflict; return 1; }
        [ -n "$BASE" ] || BASE=$MAX
        LIMIT=$BASE
        [ "$LIMIT" != 0 ] || LIMIT=$HIGHEST
        if [ "$ACTION" = restore ]; then
            SELECTED=$BASE
        else
            [ "$TARGET" -le "$LIMIT" ] || TARGET=$LIMIT
            SELECTED=0
            for hz in $AVAILABLE; do [ "$hz" -gt "$TARGET" ] || [ "$hz" -le "$SELECTED" ] || SELECTED=$hz; done
            [ "$SELECTED" -gt 0 ] || { FAIL_REASON=opp_no_floor; return 1; }
        fi
        case "$s" in cpu0) C0_SEL=$SELECTED ;; cpu4) C4_SEL=$SELECTED ;; cpu7) C7_SEL=$SELECTED ;; gpu) G_SEL=$SELECTED ;; esac
        [ "$SELECTED" = 0 ] || [ "$MIN" -le "$SELECTED" ] || { FAIL_REASON=min_conflict; return 1; }
        [ -w "$PATH_NOW/$MAX_NODE" ] || { FAIL_REASON=permission_denied; return 1; }
    done
    CAN_APPLY=true
}
reason_message() {
    case "$1" in
        incompatible) MESSAGE="Device hardware is not verified: $IDENTITY_CHECK (supported names: 477V, 477M, 557; platform: MT6897)" ;;
        fixed_opp) MESSAGE='GPU fixed OPP is active; changing a maximum alone cannot enforce this profile' ;;
        fixed_unknown) MESSAGE='GPU fixed OPP status is unavailable or unrecognized' ;;
        opp_unavailable) MESSAGE='A complete supported frequency list is required; no OPP values are guessed' ;;
        opp_no_floor) MESSAGE='No supported OPP is at or below the requested cap' ;;
        min_conflict) MESSAGE='Current minimum exceeds a selected maximum; no clocks were changed' ;;
        permission_denied) MESSAGE='Maximum clock nodes are not writable in this execution context' ;;
        hardware_lock_unavailable) MESSAGE='This executor cannot create the private device-wide mutex in /data/system' ;;
        invalid_frequency) MESSAGE='A clock value, OPP table, or hardware frequency bound is invalid' ;;
        *) MESSAGE='The requested operation failed validation' ;;
    esac
}
baseline() {
    if [ -e "$STATE/baseline.tsv" ] || [ -L "$STATE/baseline.tsv" ]; then
        SNAPSHOT=present
        parse_state "$STATE/baseline.tsv" || return 1
        C0_BASE=$PARSE_C0; C4_BASE=$PARSE_C4; C7_BASE=$PARSE_C7; G_BASE=$PARSE_G
        BASE_VALID=1
    fi
}
natural() { case "$1" in ''|*[!0-9]*) return 1 ;; esac; [ "${#1}" -le 18 ] && [ "$1" -gt 0 ] 2>/dev/null; }
process_info() {
    PROC_START= PROC_STATE=
    if [ "$TEST" = 1 ] && [ "$1" = "$$" ]; then PROC_START=123456; PROC_STATE=S; return 0; fi
    [ -r "$ROOT/proc/$1/stat" ] || return 1
    PROC_DATA=$(sed 's/^.*) //' "$ROOT/proc/$1/stat" 2>/dev/null | tr -d '\r')
    PROC_START=$(printf '%s\n' "$PROC_DATA" | awk '{print $20}')
    PROC_STATE=$(printf '%s\n' "$PROC_DATA" | awk '{print $1}')
    natural "$PROC_START"
}
put_owner() {
    process_info "$$" || return 1
    {
        printf 'pid=%s\nboot_id=%s\nstart=%s\nuid=%s\nstate=%s\n' "$$" "$BOOT" "$PROC_START" "$UID_NOW" "$STATE"
    } > "$1/owner"
}
take_lock() {
    TAKE_DIR=$1
    TAKE_CODE=$2
    if mkdir "$TAKE_DIR" 2>/dev/null; then
        put_owner "$TAKE_DIR" || { rm -f "$TAKE_DIR/owner"; rmdir "$TAKE_DIR"; fail error lock_io_failed 'Cannot persist process identity for the operation lock'; return 1; }
        return 0
    fi
    if [ ! -e "$TAKE_DIR" ] && [ ! -L "$TAKE_DIR" ]; then
        if [ "$TAKE_CODE" = busy_or_stale_hardware_lock ]; then
            fail blocked hardware_lock_unavailable 'Device-wide lock creation failed in this execution context; no clocks were changed'
        else
            fail blocked state_not_private 'Private state lock creation failed in this execution context; no clocks were changed'
        fi
        return 1
    fi
    fail blocked "$TAKE_CODE" 'Existing lock was preserved. If no operation is running, reboot the device and diagnose again'
    return 1
}
acquire() {
    take_lock "$STATE/.lock" busy_or_stale_lock || return 1
    LOCKED=1
    if [ ! -d "$GLOBAL_PARENT" ] || [ ! -w "$GLOBAL_PARENT" ] || [ ! -x "$GLOBAL_PARENT" ]; then
        fail blocked hardware_lock_unavailable 'This executor cannot create the private device-wide lock in /data/system'
        release
        return 1
    fi
    take_lock "$GLOBAL_LOCK" busy_or_stale_hardware_lock || { release; return 1; }
    GLOBAL_LOCKED=1
}
release() {
    if [ "$GLOBAL_LOCKED" = 1 ]; then
        rm -f "$GLOBAL_LOCK/owner"
        rmdir "$GLOBAL_LOCK" 2>/dev/null
        GLOBAL_LOCKED=0
    fi
    [ "$LOCKED" = 1 ] || return 0
    rm -f "$STATE/.lock/owner"
    rmdir "$STATE/.lock" 2>/dev/null
    LOCKED=0
}
write_cap() {
    WRITE_SLOT=$1; WRITE_VALUE=$2; WRITE_MODE=$3
    WRITE_EXPECTED=${4:-}
    if [ "$TEST" = 1 ] && [ "$WRITE_MODE:$WRITE_SLOT" = "apply:${RG477V_TEST_CHANGE_MIN_BEFORE_WRITE:-}" ]; then
        slot "$WRITE_SLOT"
        printf '%s\n' "${RG477V_TEST_CHANGE_MIN_VALUE:-2000000000}" > "$PATH_NOW/$MIN_NODE"
    fi
    slot "$WRITE_SLOT"
    valid_integer "$MIN" && { [ "$WRITE_VALUE" = 0 ] || [ "$MIN" -le "$WRITE_VALUE" ]; } || return 1
    [ -z "$WRITE_EXPECTED" ] || [ "$MAX" = "$WRITE_EXPECTED" ] || return 1
    if [ "$TEST" = 1 ]; then
        [ "$WRITE_MODE:$WRITE_SLOT" != "apply:${RG477V_TEST_FAIL_WRITE:-}" ] || return 1
        [ "$WRITE_MODE:$WRITE_SLOT" != "rollback:${RG477V_TEST_FAIL_ROLLBACK:-}" ] || return 1
    fi
    (printf '%s\n' "$WRITE_VALUE" > "$PATH_NOW/$MAX_NODE") 2>/dev/null || return 1
    if [ "$TEST" = 1 ] && [ "$WRITE_MODE:$WRITE_SLOT" = "apply:${RG477V_TEST_FAIL_READBACK:-}" ]; then
        printf '%s\n' 1 > "$PATH_NOW/$MAX_NODE"
    fi
    [ "$(read_num "$PATH_NOW/$MAX_NODE")" = "$WRITE_VALUE" ]
}
rollback() {
    parse_state "$STATE/pending.tsv" || { fail rollback_failed journal_invalid 'Rollback journal cannot be validated; no recovery values were guessed'; return 1; }
    R0=$PARSE_C0; R4=$PARSE_C4; R7=$PARSE_C7; RG=$PARSE_G
    RM0=$PARSE_M0; RM4=$PARSE_M4; RM7=$PARSE_M7; RMG=$PARSE_MG
    FAILED=0
    RECOVERY_SAFE=1
    GPU_RECOVERY_SAFE=1
    identity || { FAILED=1; RECOVERY_SAFE=0; }
    fixed_check || GPU_RECOVERY_SAFE=0
    for s in cpu0 cpu4 cpu7 gpu; do
        slot "$s"
        case "$s" in cpu0) ORIGINAL=$R0; ORIGINAL_MIN=$RM0 ;; cpu4) ORIGINAL=$R4; ORIGINAL_MIN=$RM4 ;; cpu7) ORIGINAL=$R7; ORIGINAL_MIN=$RM7 ;; gpu) ORIGINAL=$RG; ORIGINAL_MIN=$RMG ;; esac
        if [ "$MAX" != "$ORIGINAL" ]; then
            if [ "$s" = gpu ] && [ "$GPU_RECOVERY_SAFE" != 1 ]; then FAILED=1; continue; fi
            valid_integer "$MIN" && { [ "$ORIGINAL" = 0 ] || [ "$MIN" -le "$ORIGINAL" ]; } || { FAILED=1; continue; }
            [ "$RECOVERY_SAFE" = 1 ] || continue
            write_cap "$s" "$ORIGINAL" rollback || FAILED=1
        fi
    done
    for s in cpu0 cpu4 cpu7 gpu; do
        slot "$s"
        case "$s" in cpu0) ORIGINAL=$R0 ;; cpu4) ORIGINAL=$R4 ;; cpu7) ORIGINAL=$R7 ;; gpu) ORIGINAL=$RG ;; esac
        [ "$MAX" = "$ORIGINAL" ] || FAILED=1
    done
    IN_TRANSACTION=0
    if [ "$FAILED" = 0 ]; then
        rm -f "$STATE/pending.tsv" || return 1
        return 0
    fi
    fail rollback_failed rollback_failed 'One or more previous maxima could not be restored; pending.tsv was preserved'
    return 1
}
interrupted() {
    trap '' HUP INT TERM
    if [ "$IN_TRANSACTION" = 1 ]; then
        fail error interrupted 'Operation interrupted; previous maxima restored'
        rollback || :
    else
        fail error interrupted 'Operation interrupted before hardware changes'
    fi
    release
    report
    [ "$OUTCOME" != rollback_failed ] && exit 1 || exit 3
}
transaction() {
    write_state "$STATE/pending.tsv" || { fail error journal_failed 'Cannot persist a validated rollback journal; no clocks were changed'; return 1; }
    P0=$PARSE_C0; P4=$PARSE_C4; P7=$PARSE_C7; PG=$PARSE_G
    PM0=$PARSE_M0; PM4=$PARSE_M4; PM7=$PARSE_M7; PMG=$PARSE_MG
    IN_TRANSACTION=1
    for s in cpu0 cpu4 cpu7 gpu; do
        slot "$s"
        case "$s" in cpu0) BEFORE=$P0; BEFORE_MIN=$PM0 ;; cpu4) BEFORE=$P4; BEFORE_MIN=$PM4 ;; cpu7) BEFORE=$P7; BEFORE_MIN=$PM7 ;; gpu) BEFORE=$PG; BEFORE_MIN=$PMG ;; esac
        if ! identity || ! fixed_check || [ "$MIN" != "$BEFORE_MIN" ] || [ "$MAX" != "$BEFORE" ]; then
            fail error concurrent_change 'Hardware identity, minimum, maximum, or fixed OPP changed during the operation'
            rollback || :
            return 1
        fi
        # Re-read the intended node before writing its planned maximum.
        slot "$s"
        if [ "$MIN" != "$BEFORE_MIN" ] || [ "$MAX" != "$BEFORE" ] || { [ "$SELECTED" != 0 ] && [ "$MIN" -gt "$SELECTED" ]; }; then
            fail error concurrent_change 'Minimum or maximum changed immediately before the clock write'
            rollback || :
            return 1
        fi
        if ! valid_cap "$SELECTED" || { [ "$ACTION" = apply ] && ! supported "$SELECTED"; }; then
            fail error concurrent_change 'Supported OPPs changed before a clock write'
            rollback || :
            return 1
        fi
        if [ "$MAX" != "$SELECTED" ] && ! write_cap "$s" "$SELECTED" apply "$BEFORE"; then
            fail error write_verify_failed 'A clock write or readback failed; previous maxima restored'
            rollback || :
            return 1
        fi
        if [ "$TEST" = 1 ] && [ "$s" = "${RG477V_TEST_INTERRUPT_AFTER:-}" ]; then kill -TERM "$$"; fi
        if [ "$TEST" = 1 ] && [ "$s" = "${RG477V_TEST_KILL_AFTER:-}" ]; then kill -KILL "$$"; fi
        if [ "$TEST" = 1 ] && [ "$s" = "${RG477V_TEST_FIXED_AFTER:-}" ]; then printf '0\n' > "$FIX"; fi
    done
    identity && fixed_check || {
        fail error concurrent_change 'Hardware identity or fixed OPP changed after writes'
        rollback || :
        return 1
    }
    for s in cpu0 cpu4 cpu7 gpu; do
        slot "$s"
        case "$s" in cpu0) BEFORE_MIN=$PM0 ;; cpu4) BEFORE_MIN=$PM4 ;; cpu7) BEFORE_MIN=$PM7 ;; gpu) BEFORE_MIN=$PMG ;; esac
        if [ "$MIN" != "$BEFORE_MIN" ] || [ "$MAX" != "$SELECTED" ]; then
            fail error write_verify_failed 'Final maxima/minima verification failed; previous maxima restored'
            rollback || :
            return 1
        fi
    done
    IN_TRANSACTION=0
    rm -f "$STATE/pending.tsv" || { fail error journal_cleanup_failed 'Clocks verified, but rollback journal cleanup failed'; return 1; }
    [ "$TEST" = 1 ] || sync || :
    if [ "$ACTION" = restore ]; then
        CODE=restored; MESSAGE='Initial observed maximum clocks were restored and read back'
    else
        CODE=applied; MESSAGE='Supported maximum clocks were applied and read back'
    fi
}
report() {
    kv outcome "$OUTCOME"; kv message_code "$CODE"; kv message "$MESSAGE"
    kv uid "$UID_NOW"; kv context "$CONTEXT"; kv boot_id "$BOOT"; kv device "$DEVICE"; kv model "$MODEL"; kv platform "$PLATFORM"
    kv identity_check "$IDENTITY_CHECK"
    kv compatible "$COMPATIBLE"; kv can_apply "$CAN_APPLY"; kv snapshot "$SNAPSHOT"; kv snapshot_valid "$BASE_VALID"; kv action "$ACTION"; kv profile "$PROFILE"; kv state_dir "$STATE"; kv state_root "$STATE_ROOT"
    kv hardware_lock_path "$GLOBAL_LOCK"
    kv gpu.fixed "$(read_num "$FIX")"
    for s in cpu0 cpu4 cpu7 gpu; do
        slot "$s"
        kv "$s.path" "$PATH_NOW"; kv "$s.current" "$CURRENT"; kv "$s.min" "$MIN"; kv "$s.max" "$MAX"; kv "$s.available" "$AVAILABLE"
        kv "$s.target" "$TARGET"; kv "$s.selected" "$SELECTED"; kv "$s.baseline_max" "$BASE"
        if [ -w "$PATH_NOW/$MAX_NODE" ]; then kv "$s.writable" true; else kv "$s.writable" false; fi
    done
    [ ! -e "$STATE/pending.tsv" ] || kv pending true
    if [ -e "$STATE/.lock" ] || [ -L "$STATE/.lock" ]; then kv lock present; fi
    if [ -e "$GLOBAL_LOCK" ] || [ -L "$GLOBAL_LOCK" ]; then kv hardware_lock present; fi
}

case "$ACTION" in diagnose|apply|restore|recover) ;; *) fail blocked invalid_action 'Expected diagnose, apply, restore, or recover'; report; exit 2 ;; esac
targets || { reason_message "$FAIL_REASON"; fail blocked "$FAIL_REASON" "$MESSAGE"; report; exit 2; }
if [ "$BOOT_VALID" != 1 ]; then fail blocked incompatible 'A valid kernel boot UUID is required before state or hardware operations'; report; exit 2; fi
# Validate/create the route root first, then its separate private boot directory.
BOOT_STATE=$STATE
STATE=$STATE_ROOT
if ! private_state; then fail blocked state_not_private 'Route state root must be private (0700), owned by this execution UID, and writable'; report; exit 2; fi
STATE=$BOOT_STATE
if ! private_state; then fail blocked state_not_private 'State directory must be private (0700), owned by this execution UID, and writable'; report; exit 2; fi
if ! baseline; then fail blocked snapshot_invalid 'Snapshot is corrupt, from another boot/firmware/UID, or outside the verified frequency bounds'; report; exit 2; fi
if [ "$ACTION" = diagnose ]; then
    if [ -e "$STATE/.lock" ] || [ -L "$STATE/.lock" ] || [ -e "$STATE/pending.tsv" ] || [ -L "$STATE/pending.tsv" ] || [ -e "$GLOBAL_LOCK" ] || [ -L "$GLOBAL_LOCK" ]; then
        fail blocked pending_or_busy 'An operation, stale lock, or interrupted transaction requires inspection'
    elif ! preflight; then reason_message "$FAIL_REASON"; fail blocked "$FAIL_REASON" "$MESSAGE"; fi
    report
    [ "$OUTCOME" = ok ] && exit 0 || exit 2
fi
acquire || { report; [ "$OUTCOME" = error ] && exit 1 || exit 2; }
trap interrupted HUP INT TERM
trap release EXIT
# Another process may have captured a baseline while this invocation waited for
# the mutex. Re-read it only after both locks are held, preserving the first one.
BASE_VALID=0
C0_BASE= C4_BASE= C7_BASE= G_BASE=
if ! baseline; then fail blocked snapshot_invalid 'Snapshot changed or failed validation under the operation lock'; release; report; exit 2; fi
if [ -e "$STATE/pending.tsv" ] || [ -L "$STATE/pending.tsv" ]; then
    fail error recovered_pending 'An interrupted transaction was recovered; retry the requested operation'
    rollback || :
    if [ "$ACTION" = recover ] && [ "$OUTCOME" != rollback_failed ]; then OUTCOME=ok; fi
    release
    report
    case "$OUTCOME" in ok) exit 0 ;; rollback_failed) exit 3 ;; *) exit 1 ;; esac
fi
if [ "$ACTION" = recover ]; then
    OUTCOME=ok; CODE=recovery_not_needed; MESSAGE='No pending hardware journal exists; no clocks were changed'
    release; report; exit 0
fi
if [ "$ACTION" = restore ] && [ "$BASE_VALID" != 1 ]; then fail blocked snapshot_absent 'No initial snapshot exists in this execution route'; release; report; exit 2; fi
if ! preflight; then reason_message "$FAIL_REASON"; fail blocked "$FAIL_REASON" "$MESSAGE"; release; report; exit 2; fi
if [ "$ACTION" = apply ] && [ "$BASE_VALID" != 1 ]; then
    if ! write_state "$STATE/baseline.tsv"; then fail error snapshot_failed 'Cannot persist the initial snapshot; no clocks were changed'; release; report; exit 1; fi
    SNAPSHOT=present
    baseline || { fail blocked snapshot_invalid 'The initial snapshot failed validation'; release; report; exit 2; }
    preflight || { reason_message "$FAIL_REASON"; fail blocked "$FAIL_REASON" "$MESSAGE"; release; report; exit 2; }
fi
transaction
release
report
case "$OUTCOME" in ok) exit 0 ;; blocked) exit 2 ;; rollback_failed) exit 3 ;; *) exit 1 ;; esac
