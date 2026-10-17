package com.anbernic.control.underclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.util.AtomicFile;
import android.util.Log;
import android.view.View;
import com.anbernic.control.underclock.shared.CustomProfileCommand;
import com.anbernic.control.underclock.shared.CustomProfileEditor;
import com.anbernic.control.underclock.shared.CustomProfileValues;
import com.anbernic.control.underclock.shared.CustomProfileStore;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;

/** Fan-independent finite leases, owned by the stock Control Center process. */
public final class UnderclockRuntime implements UnderclockCard.Controller,BootRestorePolicy.Actions {
    private static final String TAG="UnderclockRuntime";
    private static final String STATE="rg477v_underclock_state";
    private static final String COMMAND="rg477v_underclock_request";
    private static final String POWER_DESCRIPTOR="com.mediatek.powerhalmgr.IPowerHalMgr";
    private static final String CPU="/sys/devices/system/cpu/cpufreq/policy";
    private static final String GPU="/sys/devices/platform/soc/13000000.mali/devfreq/13000000.mali";
    private static final int[] POLICY={0,4,7};
    private static final int[] INDEX={0,1,3,2};
    private static volatile UnderclockRuntime instance;
    private static boolean waitingForUnlock;
    private final Context context;
    private final SharedPreferences prefs;
    private final CustomProfileStore.Persistence profileStore;
    private final Handler worker, ui=new Handler(Looper.getMainLooper());
    private volatile int display;
    private volatile String status="default · stock";
    private IBinder power;
    private int handle,stage;
    private boolean compatible;
    private String error="",lastRequest="",sensorError="";
    private String selectionError="";
    private volatile CustomProfileValues[] customValues=new CustomProfileValues[4];
    private volatile boolean[] configured=new boolean[4];
    private float chip=Float.NaN,skin=Float.NaN;
    private int[] requestedCpu={0,0,0};
    private int requestedGpu,gpuTarget,requestedGpuOpp=-1;
    private long updated;
    private final BootRestorePolicy bootRestore;
    private final long bootEpoch;
    private final Runnable renew=new Runnable() {
        @Override public void run() {
            worker.removeCallbacks(this);
            if(bootRestore.pending()){restoreSavedProfile();return;}
            if(display==0) return;
            try {
                verify(); use(acquire(display,null,handle)); error=""; describe(); publish();
                worker.postDelayed(this,8000);
            } catch(Exception failure) { fail(failure); }
        }
    };

    private UnderclockRuntime(Context supplied) {
        context=supplied.getApplicationContext();
        prefs=context.getSharedPreferences("underclock_profiles_v1",Context.MODE_PRIVATE);
        profileStore=new CustomProfileStore.Persistence() {
            @Override public CustomProfileStore.Snapshot read() {
                String[] slots=new String[4];for(int i=0;i<4;i++)slots[i]=prefs.getString("custom_"+i,null);
                return new CustomProfileStore.Snapshot(prefs.getInt("display",0),prefs.getString("last_request",""),slots);
            }
            @Override public boolean write(CustomProfileStore.Snapshot value) {
                SharedPreferences.Editor edit=prefs.edit().putInt("display",value.displayIndex).putString("last_request",value.lastRequest);
                for(int i=0;i<4;i++)edit.putString("custom_"+i,value.slot(i));
                return edit.commit();
            }
        };
        display=prefs.getInt("display",0); if(display<0||display>7) display=0;
        bootRestore=new BootRestorePolicy(display,SystemClock.elapsedRealtime());bootEpoch=bootRestore.epoch();
        // The saved choice is an intention until a valid lease is acquired.
        display=0;
        for(int i=0;i<4;i++) {
            customValues[i]=CustomProfileValues.small();
            String saved=prefs.getString("custom_"+i,null);
            if(saved!=null)try { customValues[i]=CustomProfileValues.decode(saved);configured[i]=true; }
            catch(IllegalArgumentException failure) { Log.w(TAG,"Invalid saved Custom slot "+i); }
        }
        prefs.edit().remove("original_fan").apply();
        lastRequest=prefs.getString("last_request","");
        HandlerThread thread=new HandlerThread("RG477VUnderclock"); thread.start();
        worker=new Handler(thread.getLooper());
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(COMMAND),false,
            new ContentObserver(worker) { @Override public void onChange(boolean self) { command(); } });
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor("fan_mode"),false,
            new ContentObserver(worker) { @Override public void onChange(boolean self) {
                if(display>0||bootRestore.pending()) { worker.removeCallbacks(renew); worker.post(renew); }
            } });
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor("cpu_mode"),false,
            new ContentObserver(worker) { @Override public void onChange(boolean self) {
                if(display>0||bootRestore.pending()) { worker.removeCallbacks(renew); worker.postDelayed(renew,500); }
            } });
        BroadcastReceiver wake=new BroadcastReceiver() {
            @Override public void onReceive(Context c,Intent intent) {
                worker.post(() -> { if(display>0||bootRestore.pending()) { worker.removeCallbacks(renew); worker.post(renew); } });
            }
        };
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
        context.registerReceiver(wake,filter,Context.RECEIVER_NOT_EXPORTED);
        worker.post(() -> restoreSavedProfile());
    }

    private void restoreSavedProfile() {
        bootRestore.dispatch(bootEpoch,SystemClock.elapsedRealtime(),this);
    }
    @Override public void waitFor(long delay){worker.postDelayed(renew,delay);}
    @Override public void expired(){bootFailure("PowerHAL 준비 대기 시간이 초과했습니다.");command();}
    private void bootFailure(String detail) {
        bootRestore.cancel();clearActiveRequest();error=detail;
        status="저장한 프로필 자동 적용 실패 · 저장값 유지 · 다시 선택해 주세요.";publish();
    }
    @Override public void attempt() {
        try {
            verify();
            int saved=bootRestore.selected();
            if(saved>0)use(acquire(saved,null,0));
            display=saved;bootRestore.complete(bootEpoch);error="";selectionError="";
            describe();publish();if(display>0)worker.postDelayed(renew,8000);
        }catch(Exception failure) {
            clearActiveRequest();
            String detail=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
            long next=bootRestore.delay(bootEpoch,SystemClock.elapsedRealtime());
            if(BootRestorePolicy.retryable(failure.getClass().getName(),failure.getMessage())&&next>=0) {
                error="";status="저장한 프로필 적용 대기 · PowerHAL 준비 중 ("+bootRestore.attempts()+"/"+BootRestorePolicy.MAX_ATTEMPTS+")";
                publish();worker.postDelayed(renew,next);
            }else {
                bootFailure(detail);Log.w(TAG,"Saved profile bootstrap stopped",failure);
            }
        }
        // A newer Default/restore command wins over bootstrap retry and saved state.
        command();
    }

    private static synchronized UnderclockRuntime get(Context context) {
        if(instance==null) instance=new UnderclockRuntime(context);
        return instance;
    }
    public static void boot(Context context) {
        try {
            Context app=context.getApplicationContext();
            if(!app.getSystemService(UserManager.class).isUserUnlocked()) {
                synchronized(UnderclockRuntime.class) {
                    if(waitingForUnlock) return;
                    waitingForUnlock=true;
                }
                app.registerReceiver(new BroadcastReceiver() {
                    @Override public void onReceive(Context c,Intent intent) {
                        c.unregisterReceiver(this);
                        synchronized(UnderclockRuntime.class) { waitingForUnlock=false; }
                        boot(c);
                    }
                },new IntentFilter(Intent.ACTION_USER_UNLOCKED),Context.RECEIVER_NOT_EXPORTED);
                return;
            }
            PowerOverlay.boot(app);
            get(app);
        } catch(Exception | LinkageError failure) { Log.e(TAG,"Startup failed",failure); }
    }
    public static void install(View root) {
        try { if(root!=null) UnderclockCard.install(root,get(root.getContext())); }
        catch(Exception | LinkageError failure) { Log.e(TAG,"UI attachment failed",failure); }
    }
    @Override public int getDisplayIndex() { return display; }
    @Override public String statusText() { return status; }
    @Override public void requestProfile(int next,UnderclockCard.Result callback) {
        worker.post(() -> { select(next,null,null); if(callback!=null) ui.post(callback::complete); });
    }
    @Override public CustomProfileValues getCustomProfile(int slot) {
        if(slot<0||slot>3)throw new IllegalArgumentException("Custom 슬롯 확인 실패");
        return customValues[slot];
    }
    @Override public void requestCustomProfile(int slot,CustomProfileValues values,CustomProfileEditor.Completion callback) {
        worker.post(() -> {
            String problem=slot<0||slot>3||values==null?"Custom 슬롯 확인 실패":select(slot+4,values,null);
            if(callback!=null)ui.post(()->callback.complete(problem));
        });
    }
    private void command() {
        String request=Settings.System.getString(context.getContentResolver(),COMMAND);
        if(request==null || request.equals(lastRequest)) return;
        if(request.length()>70)return;
        boolean committed=false;
        try {
            CustomProfileCommand command=CustomProfileCommand.parse(request);
            committed=select(command.displayIndex,command.values,request).isEmpty();
        } catch(IllegalArgumentException failure) { selectionError=failure.getMessage(); }
        // A failed request is acknowledged without overwriting the active slot.
        lastRequest=request;
        if(!committed)try {CustomProfileStore.acknowledge(profileStore,request);}
        catch(RuntimeException failure){selectionError="요청 기록을 저장하지 못했습니다.";}
        publish();
    }
    private String select(int next,CustomProfileValues candidate,String request) {
        Lease pending=null;
        try {
            if(next<0||next>7||(next<4&&candidate!=null))throw new IllegalArgumentException("프로필 인덱스 확인 실패");
            if(bootRestore.pending()){bootRestore.cancel();worker.removeCallbacks(renew);}
            if(next==0) {
                CustomProfileStore.select(profileStore,next,null,request);
                if(request!=null)lastRequest=request;selectionError="";stop("default · stock");return "";
            }
            verify();
            // Keep the old request until both the new lease and durable save succeed.
            pending=acquire(next,candidate,0);
            CustomProfileStore.select(profileStore,next,candidate,request);
            worker.removeCallbacks(renew);release(handle);use(pending);pending=null;
            if(next>=4&&candidate!=null) {
                CustomProfileValues[] rows=customValues.clone();rows[next-4]=candidate;customValues=rows;
                boolean[] saved=configured.clone();saved[next-4]=true;configured=saved;
            }
            display=next; error="";
            if(request!=null)lastRequest=request;selectionError="";
            describe(); publish(); worker.postDelayed(renew,8000);
            return "";
        } catch(Exception failure) {
            if(pending!=null)release(pending.handle);
            selectionError=failure.getMessage()==null?"프로필을 적용하지 못했습니다.":failure.getMessage();
            describe();publish();return selectionError;
        }
    }
    private static IBinder service(String name) throws Exception {
        return (IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
    }
    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()),StandardCharsets.UTF_8).trim();
    }
    private void verify() throws Exception {
        compatible=false;
        String model=Build.MODEL.toLowerCase(Locale.ROOT).replaceAll("\\s+","");
        if(!(model.contains("477v")||model.contains("477m")||model.contains("557")))
            throw new IllegalStateException("Unverified model");
        String platform=(String)Class.forName("android.os.SystemProperties").getMethod("get",String.class).invoke(null,"ro.board.platform");
        if(!"mt6897".equalsIgnoreCase(platform)||!MeasuredProfiles.FINGERPRINT.equals(Build.FINGERPRINT))
            throw new IllegalStateException("Unverified firmware");
        String[] topology={"0 1 2 3","4 5 6","7"};
        for(int i=0;i<3;i++) {
            String path=CPU+POLICY[i];
            if(!new File(path).getCanonicalPath().equals(path)||!read(path+"/related_cpus").replaceAll("\\s+"," ").equals(topology[i]))
                throw new IllegalStateException("Unverified CPU path");
        }
        if(!new File(GPU).getCanonicalPath().equals(GPU)||!new File(GPU).isDirectory())
            throw new IllegalStateException("Unverified GPU path");
        power=service("power_hal_mgr_service");
        if(power==null)throw new IllegalStateException("PowerHAL not ready");
        if(!POWER_DESCRIPTOR.equals(power.getInterfaceDescriptor()))
            throw new IllegalStateException("Unverified PowerHAL interface");
        compatible=true;
    }
    private int fan() throws Exception {
        int value=Settings.System.getInt(context.getContentResolver(),"fan_mode");
        if(value<0||value>3) throw new IllegalStateException("Unknown fan mode");
        return value;
    }
    private static final class Lease {
        final int handle;final int[] cpu;final int gpu,opp;
        Lease(int handle,int[] cpu,int gpu,int opp){this.handle=handle;this.cpu=cpu.clone();this.gpu=gpu;this.opp=opp;}
    }
    private void use(Lease lease) {
        handle=lease.handle;requestedCpu=lease.cpu.clone();requestedGpu=lease.gpu;
        gpuTarget=lease.gpu;requestedGpuOpp=lease.opp;
    }
    private Lease acquire(int selected,CustomProfileValues candidate,int previousHandle) throws Exception {
        stage=0;
        int[] resources,cpu;int gpu,opp;
        if(selected>=4) {
            int slot=selected-4;
            if(candidate==null&&!configured[slot])throw new IllegalArgumentException("Custom 설정을 먼저 저장해 주세요.");
            CustomProfileValues values=candidate!=null?candidate:customValues[slot];
            resources=values.resources();cpu=new int[]{values.get(0),values.get(1),values.get(2)};
            gpu=values.get(3);opp=values.gpuOppIndex();temperature();
        } else {
            int index=INDEX[selected];int[] row=MeasuredProfiles.caps(index,0,1);
            resources=MeasuredProfiles.resources(index,0,1);cpu=new int[]{row[0],row[1],row[2]};
            gpu=MeasuredProfiles.gpuHz(index,0,1);opp=row[3];
            if(MeasuredProfiles.fixedCeiling(index))temperature();else{chip=Float.NaN;skin=Float.NaN;sensorError="";}
        }
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(POWER_DESCRIPTOR); data.writeInt(previousHandle); data.writeInt(30000);
            data.writeIntArray(resources);
            if(!power.transact(22,data,reply,0)) throw new IllegalStateException("PowerHAL request rejected");
            reply.readException(); int next=reply.readInt();
            if(next<=0) throw new IllegalStateException("PowerHAL request failed");
            return new Lease(next,cpu,gpu,opp);
        } finally { data.recycle(); reply.recycle(); }
    }
    private void temperature() {
        stage=0; chip=Float.NaN; skin=Float.NaN;
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try {
            IBinder thermal=service("thermalservice");
            if(thermal==null||!"android.os.IThermalService".equals(thermal.getInterfaceDescriptor()))
                throw new IllegalStateException("Thermal interface unavailable");
            data.writeInterfaceToken("android.os.IThermalService");
            if(!thermal.transact(4,data,reply,0)) throw new IllegalStateException("Thermal request rejected");
            reply.readException(); int count=reply.readInt();
            if(count<0||count>128) throw new IllegalStateException("Thermal count invalid");
            for(int i=0;i<count;i++) {
                int present=reply.readInt(); if(present==0) continue;
                if(present!=1) throw new IllegalStateException("Thermal record invalid");
                float value=reply.readFloat(); int type=reply.readInt(); reply.readString(); reply.readInt();
                if(!Float.isFinite(value)||value<0||value>130) continue;
                if(type==0||type==1||type==9||type==10||type==13) chip=Float.isNaN(chip)?value:Math.max(chip,value);
                if(type==3) skin=Float.isNaN(skin)?value:Math.max(skin,value);
            }
            if(reply.dataAvail()!=0||!Float.isFinite(chip)||!Float.isFinite(skin))
                throw new IllegalStateException("Thermal sensors unavailable");
            sensorError="";
        } catch(Exception failure) { chip=Float.NaN; skin=Float.NaN; sensorError=failure.toString(); }
        finally { data.recycle(); reply.recycle(); }
        // Observation only: a missing sensor cannot alter or suspend the cap request.
        stage=0;
    }
    private void release(int ownedHandle) {
        if(ownedHandle>0 && power!=null) {
            Parcel data=Parcel.obtain();
            try { data.writeInterfaceToken(POWER_DESCRIPTOR); data.writeInt(ownedHandle); power.transact(23,data,null,IBinder.FLAG_ONEWAY); }
            catch(Exception failure) { Log.w(TAG,"Lease expires within 30 seconds",failure); }
            finally { data.recycle(); }
        }
    }
    private void clearActiveRequest() {
        release(handle);handle=0; display=0; stage=0;
        requestedCpu=new int[]{0,0,0}; requestedGpu=0; gpuTarget=0; requestedGpuOpp=-1;
        chip=Float.NaN; skin=Float.NaN; sensorError="";
    }
    private void stop(String message) {
        worker.removeCallbacks(renew);bootRestore.cancel();clearActiveRequest();
        prefs.edit().putInt("display",0).remove("original_fan").commit();
        error=""; status=message; publish();
    }
    private void fail(Exception failure) {
        String detail=failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();
        stop("Stopped · "+detail); error=detail; publish();
        Log.w(TAG,"Under-clock stopped",failure);
    }
    private void describe() {
        if(display==0) { status="default · stock"; return; }
        if(display<4&&MeasuredProfiles.fixedCeiling(INDEX[display])) { status=MeasuredProfiles.values(INDEX[display],1); return; }
        status=(display>=4?"Custom "+(display-3)+" · ":"")+"CPU "+requestedCpu[0]/1000+"/"+requestedCpu[1]/1000+"/"+requestedCpu[2]/1000
            +" MHz · GPU "+requestedGpu/1000000+" MHz";
    }
    private void publish() {
        updated=SystemClock.elapsedRealtime();
        try {
            boolean fixed=display>=4||(display>0&&MeasuredProfiles.fixedCeiling(INDEX[display]));
            JSONObject state=new JSONObject().put("integrated",true).put("revision",2).put("display_index",display)
                .put("compatible",compatible).put("error",error).put("status",status).put("handle",handle)
                .put("lease_ms",30000).put("updated_elapsed_ms",updated).put("ack_request",lastRequest)
                .put("command_error",selectionError)
                .put("boot_restore_pending",bootRestore.pending()).put("boot_restore_attempt",bootRestore.attempts())
                .put("saved_display_index",prefs.getInt("display",0))
                .put("requested_cap_cpu_khz",new JSONArray(requestedCpu)).put("requested_gpu_hz",requestedGpu)
                .put("gpu_target_hz",gpuTarget).put("requested_gpu_opp_index",requestedGpuOpp<0?JSONObject.NULL:requestedGpuOpp)
                .put("fixed_ceiling_trial",fixed)
                .put("gpu_opp_provisional",fixed&&!MeasuredProfiles.GPU_OPP_DEVICE_VERIFIED)
                .put("thermal_control_active",false).put("thermal_sensor_error",sensorError)
                .put("thermal_stage",stage).put("executor_uid",android.os.Process.myUid())
                .put("chip_temperature_c",Float.isFinite(chip)?chip:JSONObject.NULL)
                .put("skin_temperature_c",Float.isFinite(skin)?skin:JSONObject.NULL).put("gpu_current_readable",false);
            JSONArray clocks=new JSONArray();
            for(int policy:POLICY) {
                try { clocks.put(Long.parseLong(read(CPU+policy+"/scaling_cur_freq"))); }
                catch(Exception failure) { clocks.put(JSONObject.NULL); }
            }
            state.put("cpu_current_khz",clocks);
            JSONArray slots=new JSONArray();
            for(int i=0;i<4;i++) {
                CustomProfileValues row=customValues[i];
                slots.put(new JSONObject().put("slot",i).put("configured",configured[i])
                    .put("cpu_khz",new JSONArray(new int[]{row.get(0),row.get(1),row.get(2)}))
                    .put("gpu_hz",row.get(3)).put("gpu_opp_index",row.gpuOppIndex()));
            }state.put("custom_slots",slots);
            try { state.put("fan_mode",fan()); } catch(Exception ignored) { }
            String raw=state.toString();
            if(!Settings.System.putString(context.getContentResolver(),STATE,raw)) Log.w(TAG,"Status setting unavailable");
            AtomicFile file=new AtomicFile(new File(context.getFilesDir(),"underclock-status.json"));
            FileOutputStream output=null;
            try { output=file.startWrite(); output.write(raw.getBytes(StandardCharsets.UTF_8)); file.finishWrite(output); }
            catch(Exception failure) { if(output!=null) file.failWrite(output); Log.w(TAG,"Status journal unavailable",failure); }
        } catch(Exception failure) { Log.w(TAG,"Status publication failed",failure); }
    }
}
