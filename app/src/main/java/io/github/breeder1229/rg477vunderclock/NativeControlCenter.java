package io.github.breeder1229.rg477vunderclock;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONObject;
import org.json.JSONArray;
import com.anbernic.control.underclock.shared.CustomProfileCommand;
import com.anbernic.control.underclock.shared.CustomProfileValues;

/** Frontend for the verified native keeper; never owns a PowerHAL lease. */
final class NativeControlCenter {
    static final String STATE_KEY = "rg477v_underclock_state";
    static final String REQUEST_KEY = "rg477v_underclock_request";
    static final String[] LABELS = {"Default", "Small", "Medium", "Large","Custom 1","Custom 2","Custom 3","Custom 4"};
    private static final AtomicLong LAST_NONCE = new AtomicLong();

    static JSONObject read(Context context) throws Exception {
        String raw = Settings.System.getString(context.getContentResolver(), STATE_KEY);
        if (raw == null || raw.isEmpty()) return new JSONObject();
        JSONObject state = new JSONObject(raw);
        if (!state.optBoolean("integrated") || state.optInt("revision") != 2)
            throw new IllegalStateException("순정 Control Center 응답 형식을 확인할 수 없습니다.");
        JSONArray clocks = new JSONArray();
        for (int policy : new int[]{0, 4, 7}) {
            try {
                String rawClock = new String(Files.readAllBytes(new File(
                        "/sys/devices/system/cpu/cpufreq/policy" + policy + "/scaling_cur_freq").toPath()),
                        StandardCharsets.UTF_8).trim();
                clocks.put(Long.parseLong(rawClock));
            } catch (Exception unavailable) { clocks.put(JSONObject.NULL); }
        }
        state.put("cpu_current_khz", clocks).put("cpu_sample_elapsed_ms", SystemClock.elapsedRealtime());
        return state;
    }

    /** Blocking bounded acknowledgement; call the bridge worker only. */
    static JSONObject request(Context context, int displayIndex) throws Exception {
        return request(context,displayIndex,null);
    }

    static JSONObject requestCustom(Context context,int slot,CustomProfileValues values) throws Exception {
        if(slot<0||slot>3||values==null)throw new IllegalArgumentException("Custom 슬롯 확인 실패");
        return request(context,slot+4,values);
    }

    /** Restore also supports the pinned pre-v0.3 native keeper's revision-one Default. */
    static JSONObject requestDefaultForRestore(Context context,String verifiedSourceHash) throws Exception {
        DefaultReleaseStatus.revisionForSource(verifiedSourceHash);
        PlatformAccess.require(context);
        String request=CustomProfileCommand.selection(nextNonce(),0);
        putRequest(context,request);
        long until=SystemClock.elapsedRealtime()+12000;
        do {
            String raw=Settings.System.getString(context.getContentResolver(),STATE_KEY);
            JSONObject state=raw==null?new JSONObject():new JSONObject(raw);
            if(request.equals(state.optString("ack_request"))) {
                if(!state.optString("error").isEmpty() || !state.optString("command_error").isEmpty())
                    throw new IllegalStateException("Default 적용 오류로 복구를 중단했습니다.");
                verifyReleasedState(state,verifiedSourceHash);
                return state;
            }
            Thread.sleep(150);
        }while(SystemClock.elapsedRealtime()<until);
        throw new IllegalStateException("Default 해제 응답 시간이 초과해 복구를 중단했습니다.");
    }

    private static String nextNonce() {
        long previous,nonce;
        do {
            previous=LAST_NONCE.get(); nonce=Math.max(System.currentTimeMillis(),previous+1);
        }while(!LAST_NONCE.compareAndSet(previous,nonce));
        return Long.toString(nonce);
    }

    private static void verifyReleasedState(JSONObject state) {
        verifyReleasedState(state,null);
    }

    private static void verifyReleasedState(JSONObject state,String verifiedSourceHash) {
        DefaultReleaseStatus.verifyBootStateForSource(verifiedSourceHash,BuildConfig.CONTROL_CENTER_SHA256,
                state.has("boot_restore_pending"),state.optBoolean("boot_restore_pending",true));
        JSONArray row=state.optJSONArray("requested_cap_cpu_khz");
        long[] cpu=row==null||row.length()!=3?null:new long[]{row.optLong(0,Long.MIN_VALUE),row.optLong(1,Long.MIN_VALUE),row.optLong(2,Long.MIN_VALUE)};
        if(verifiedSourceHash!=null) {
            DefaultReleaseStatus.verifySource(verifiedSourceHash,state.optBoolean("integrated"),state.optInt("revision",-1),
                    state.optInt("display_index",-1),state.optInt("handle",-1),cpu,
                    state.optLong("requested_gpu_hz",-1),state.optLong("gpu_target_hz",-1),
                    state.has("requested_gpu_opp_index")&&state.isNull("requested_gpu_opp_index"),
                    state.optInt("thermal_stage",-1),state.optBoolean("fixed_ceiling_trial",true),
                    state.optBoolean("thermal_control_active",true));
            return;
        }
        DefaultReleaseStatus.verify(state.optBoolean("integrated"),state.optInt("revision",-1),
                state.optInt("display_index",-1),state.optInt("handle",-1),cpu,
                state.optLong("requested_gpu_hz",-1),state.optLong("gpu_target_hz",-1),
                state.has("requested_gpu_opp_index")&&state.isNull("requested_gpu_opp_index"),
                state.optInt("thermal_stage",-1),state.optBoolean("fixed_ceiling_trial",true),
                state.optBoolean("thermal_control_active",true));
    }

    private static JSONObject request(Context context,int displayIndex,CustomProfileValues custom) throws Exception {
        if (displayIndex < 0 || displayIndex > 7)
            throw new IllegalArgumentException("프로필 인덱스 확인 실패");
        PlatformAccess.require(context);
        String nonce=nextNonce();
        String request = custom==null?CustomProfileCommand.selection(nonce,displayIndex)
                :CustomProfileCommand.custom(nonce,displayIndex-4,custom);
        putRequest(context, request);
        long until = SystemClock.elapsedRealtime() + 12000;
        do {
            JSONObject state = read(context);
            if (request.equals(state.optString("ack_request"))) {
                String commandError=state.optString("command_error");
                if(!commandError.isEmpty())throw new IllegalStateException(commandError);
                String error = state.optString("error");
                if (!error.isEmpty()) throw new IllegalStateException(error);
                if (state.optInt("display_index", -1) != displayIndex)
                    throw new IllegalStateException("순정 Control Center 적용 상태가 요청과 다릅니다.");
                if (displayIndex != 0 && (!state.optBoolean("compatible") || state.optInt("handle") <= 0))
                    throw new IllegalStateException("기기 확인 또는 클럭 상한 적용에 실패했습니다.");
                if(displayIndex==0)verifyReleasedState(state);
                if (displayIndex == 2 || displayIndex == 3) verifyFixedCeiling(state, displayIndex);
                if(displayIndex>=4) {
                    verifyCustom(state,displayIndex);
                    if(custom!=null&&!custom.equals(customValues(state,displayIndex-4)))
                        throw new IllegalStateException("저장된 Custom 값이 요청과 다릅니다.");
                }
                return state;
            }
            Thread.sleep(150);
        } while (SystemClock.elapsedRealtime() < until);
        throw new IllegalStateException("순정 Control Center의 적용 응답 시간이 초과했습니다.");
    }

    static CustomProfileValues customValues(JSONObject state,int slot) {
        if(slot<0||slot>3)throw new IllegalArgumentException("Custom 슬롯 확인 실패");
        try {
            JSONArray slots=state.getJSONArray("custom_slots");
            if(slots.length()!=4)throw new IllegalStateException("Custom 슬롯 수 확인 실패");
            JSONObject row=slots.getJSONObject(slot);JSONArray cpu=row.getJSONArray("cpu_khz");
            if(row.getInt("slot")!=slot||cpu.length()!=3)throw new IllegalStateException("Custom 슬롯 형식 확인 실패");
            return CustomProfileValues.of(cpu.getInt(0),cpu.getInt(1),cpu.getInt(2),row.getInt("gpu_hz"));
        }catch(Exception failure){throw new IllegalStateException("Custom 설정 응답을 확인할 수 없습니다.",failure);}
    }

    static boolean expectedState(JSONObject state) {
        int selected=state.optInt("display_index",-1);
        if(!state.optBoolean("integrated")||state.optInt("revision")!=2||selected<0||selected>7)return false;
        if(selected==2||selected==3)return expectedFixedCeilingState(state);
        if(selected>=4)try {verifyCustom(state,selected);}catch(IllegalStateException failure){return false;}
        return true;
    }

    private static void verifyCustom(JSONObject state,int index) {
        CustomProfileValues row=customValues(state,index-4);
        JSONArray cpu=state.optJSONArray("requested_cap_cpu_khz"),slots=state.optJSONArray("custom_slots");
        if(slots==null||!slots.optJSONObject(index-4).optBoolean("configured")||cpu==null||cpu.length()!=3
                ||cpu.optInt(0)!=row.get(0)||cpu.optInt(1)!=row.get(1)||cpu.optInt(2)!=row.get(2)
                ||state.optInt("requested_gpu_hz")!=row.get(3)||state.optInt("gpu_target_hz")!=row.get(3)
                ||state.optInt("requested_gpu_opp_index",-1)!=row.gpuOppIndex()
                ||state.optInt("thermal_stage",-1)!=0||state.optBoolean("thermal_control_active",true)
                ||state.optBoolean("gpu_opp_provisional",true)||!state.optBoolean("fixed_ceiling_trial"))
            throw new IllegalStateException("Custom 상한 요청과 저장값이 일치하지 않습니다.");
    }

    static boolean expectedFixedCeilingState(JSONObject state) {
        int displayIndex = state.optInt("display_index", -1);
        if (displayIndex != 2 && displayIndex != 3) return false;
        try { verifyFixedCeiling(state, displayIndex); return true; }
        catch (IllegalStateException invalid) { return false; }
    }

    private static void verifyFixedCeiling(JSONObject state, int displayIndex) {
        JSONArray requested = state.optJSONArray("requested_cap_cpu_khz");
        long[] cpu = requested == null || requested.length() != 3 ? null
                : new long[]{requested.optLong(0), requested.optLong(1), requested.optLong(2)};
        FixedCeilingStatus.verify(displayIndex == 2 ? 3 : 2, cpu, state.optLong("requested_gpu_hz"),
                state.optInt("requested_gpu_opp_index", -1), state.optLong("gpu_target_hz"),
                state.optInt("thermal_stage", -1), state.optBoolean("fixed_ceiling_trial"),
                state.optBoolean("thermal_control_active", true), state.optBoolean("gpu_opp_provisional", true));
    }

    private static void putRequest(Context context,String request)throws Exception {
        PlatformAccess.require(context);
        PackageSessionInstaller.reconcileBeforeMutation(context);
        if(!Settings.System.putString(context.getContentResolver(),REQUEST_KEY,request)
                ||!request.equals(Settings.System.getString(context.getContentResolver(),REQUEST_KEY)))
            throw new IllegalStateException("프로필 선택 요청 쓰기 확인 실패");
    }

    private NativeControlCenter() {}
}
