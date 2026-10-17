package io.github.breeder1229.rg477vunderclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import com.anbernic.control.underclock.shared.CustomProfileEditor;
import com.anbernic.control.underclock.shared.CustomProfileValues;

/** Installs the verified native keeper and edits its shared persistent profile slots. */
public final class PowerProfilesActivity extends Activity {
    private final Handler timer = new Handler();
    private PowerBridge bridge;
    private TextView status, detail;
    private Button installButton,restoreButton;
    private final Button[] buttons = new Button[8];
    private AlertDialog customEditor;
    private volatile JSONObject nativeState = new JSONObject();
    private volatile boolean nativeVerified, stockVerified, installing, requesting, reading;
    private volatile String operation = "순정 Control Center 연결을 확인합니다.";
    private volatile String actionError = "", readError = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            ensureIntegration(false);
            if (!reading) {
                reading = true;
                bridge.worker.execute(() -> {
                    try {
                        nativeVerified=ControlCenterInstaller.isInstalled(PowerProfilesActivity.this,BuildConfig.CONTROL_CENTER_SHA256);
                        stockVerified=!nativeVerified&&ControlCenterInstaller.isStockInstalled(PowerProfilesActivity.this);
                        if(nativeVerified&&ControlCenterInstaller.profileRequestsAllowed(PowerProfilesActivity.this))
                            nativeState=NativeControlCenter.read(PowerProfilesActivity.this);
                        else nativeState=new JSONObject();
                        readError = "";
                    } catch (Exception failure) { readError = failure.getMessage(); }
                    finally { reading = false; }
                    runOnUiThread(PowerProfilesActivity.this::render);
                });
            }
            render(); timer.postDelayed(this, 2000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        bridge = PowerBridge.get(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.rgb(16, 24, 32));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL); page.setPadding(32, 28, 32, 28);
        scroll.addView(page); setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            }
            return insets;
        });
        scroll.requestApplyInsets();
        page.addView(text("Under-clock V" + BuildConfig.VERSION_NAME + " · Custom", 24));
        page.addView(text("기본 프로필과 네 Custom 슬롯을 순정 Control Center와 함께 사용합니다.", 15));
        status = text(operation, 17); page.addView(status);
        installButton = new Button(this);
        installButton.setAllCaps(false); installButton.setText("Under-clock 설치");
        installButton.setOnClickListener(view -> authorize()); page.addView(installButton);
        restoreButton=new Button(this);restoreButton.setAllCaps(false);restoreButton.setText("순정 Control Center로 복구");
        restoreButton.setOnClickListener(view -> confirmRestore());page.addView(restoreButton);

        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL); page.addView(tabs);
        for (int index = 0; index < buttons.length; index++) {
            if(index==4){tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);page.addView(tabs);}
            final int selected = index;
            Button button = new Button(this); buttons[index] = button;
            button.setAllCaps(false); button.setText(NativeControlCenter.LABELS[index]);
            button.setSingleLine(true);button.setAutoSizeTextTypeUniformWithConfiguration(10,16,1,android.util.TypedValue.COMPLEX_UNIT_SP);
            button.setMinWidth(0); button.setMinimumWidth(0);
            button.setPadding(6, 12, 6, 12);
            button.setOnClickListener(view -> select(selected));
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            layout.setMargins(3, 8, 3, 8); tabs.addView(button, layout);
        }
        detail = text("", 16); page.addView(detail);
        page.addView(text("Custom 1~4를 누르면 CPU 세 그룹과 GPU의 최대 클럭을 조절할 수 있습니다. 저장값은 Control Center와 공유됩니다. 실제 클럭은 부하와 순정 제어에 따라 달라집니다. 팬과 프로필은 독립적이며 순정 성능 모드와 열 보호는 계속 적용됩니다.", 15));
        page.addView(text("설치 후에는 순정 Control Center가 프로필을 유지합니다. 이 앱을 닫거나 강제 종료해도 유지되며, 오른쪽 가장자리를 왼쪽으로 쓸어 Under-clock에서 변경할 수 있습니다. 별도 권한 앱 없이 설치와 프로필 조절, 순정 복구를 할 수 있습니다.", 15));
        Button copy = new Button(this); copy.setAllCaps(false); copy.setText("진단 복사");
        copy.setOnClickListener(view -> {
            getSystemService(android.content.ClipboardManager.class).setPrimaryClip(
                android.content.ClipData.newPlainText("Under-clock 진단", "verified=" + nativeVerified + "\n" + operation + "\n" + actionError + "\n" + readError + "\n" + nativeState));
            android.widget.Toast.makeText(this, "진단 결과를 복사했습니다.", android.widget.Toast.LENGTH_SHORT).show();
        }); page.addView(copy);
        render();
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(229, 238, 243)); view.setPadding(0, 12, 0, 12); return view;
    }

    private void render() {
        if (isDestroyed() || status == null || detail == null) return;
        JSONObject state = nativeState;
        ControlCenterMode.Mode mode=ControlCenterInstaller.mode(this);
        boolean underclock=mode==ControlCenterMode.Mode.UNDERCLOCK;
        boolean busy=bridge.managementBusy.get();
        int selected = state.optInt("display_index", 0);
        boolean ready = underclock && nativeVerified && NativeControlCenter.expectedState(state);
        String error = state.optString("error");
        if(error.isEmpty())error=state.optString("command_error");
        if (installing || requesting || busy) status.setText(operation);
        else if(!underclock) {
            if(!actionError.isEmpty())status.setText(actionError);
            else if(mode==ControlCenterMode.Mode.INITIAL)status.setText(stockVerified?"순정 Control Center · 설치 버튼을 눌러 시작하세요.":"설치 버튼을 눌러 시작하세요.");
            else if(mode==ControlCenterMode.Mode.STOCK && stockVerified)status.setText("순정 Control Center · 자동 재설치 중지");
            else if(mode==ControlCenterMode.Mode.RESTORE_PENDING)status.setText(stockVerified?"순정 APK 확인됨 · 복구 마무리를 다시 시도해 주세요.":"복구 대기 · 자동 재설치 중지");
            else if(mode==ControlCenterMode.Mode.RESTORE_FAILED)status.setText("복구 확인 필요 · 자동 재설치 중지");
            else status.setText("설치 확인 필요 · 버튼을 눌러 다시 시도해 주세요.");
        }
        else if (!actionError.isEmpty()) status.setText(actionError);
        else if (!readError.isEmpty()) status.setText(readError);
        else if(state.optBoolean("boot_restore_pending"))status.setText(state.optString("status","저장한 프로필 적용 대기"));
        else if (!ready) status.setText(nativeVerified ? "프로필 저장값과 Control Center 응답을 확인합니다." : operation);
        else if (!error.isEmpty()) status.setText("적용 확인: " + error);
        else if (selected == 0) status.setText("Default · 클럭 제한 없음");
        else if (selected < 0 || selected > 7) status.setText("프로필 응답을 확인할 수 없습니다.");
        else if (state.optInt("handle") <= 0 || SystemClock.elapsedRealtime() < state.optLong("updated_elapsed_ms")
                || SystemClock.elapsedRealtime() - state.optLong("updated_elapsed_ms") > 35000)
            status.setText("갱신 확인 대기 · " + NativeControlCenter.LABELS[selected]);
        else status.setText(NativeControlCenter.LABELS[selected] + " · 순정 Control Center에서 유지 중");

        StringBuilder values = new StringBuilder(Build.MODEL);
        if(mode==ControlCenterMode.Mode.INITIAL) {
            values.append("\n설치 버튼을 누르기 전에는 Control Center를 변경하지 않습니다.");
            values.append("\n설치 후 기본 프로필과 Custom 1~4를 사용할 수 있습니다.");
        } else if(!underclock) {
            values.append("\n이 앱을 다시 열거나 기기를 재부팅해도 수정본을 자동 설치하지 않습니다.");
            values.append("\nCustom 저장값은 Control Center에 남지만 순정에서는 사용되지 않습니다. 팬과 Perf Mode 선택은 변경하지 않습니다.");
            values.append("\nUnder-clock을 다시 사용하려면 위 설치 버튼을 누르세요.");
        } else if (ready) {
            JSONArray caps = state.optJSONArray("requested_cap_cpu_khz");
            if (selected == 0) values.append("\nCPU·GPU 상한: 순정 성능 모드 사용");
            else if (caps != null) values.append("\nCPU 요청 상한: ").append(caps.optLong(0) / 1000).append(" / ")
                .append(caps.optLong(1) / 1000).append(" / ").append(caps.optLong(2) / 1000).append(" MHz");
            long gpu = state.optLong("requested_gpu_hz");
            if (gpu > 0) values.append("\nGPU 요청 상한: ").append(gpu / 1000000).append(" MHz");
            if (selected >= 2) {
                long target = state.optLong("gpu_target_hz");
                if (target > 0) values.append("\nGPU 목표: ").append(target / 1000000).append(" MHz");
                if (state.has("requested_gpu_opp_index")) values.append(" · 요청 OPP ")
                    .append(state.optInt("requested_gpu_opp_index"));
                if (state.optBoolean("gpu_opp_provisional")) values.append("\nGPU OPP 후보 · 실기 표 확인 전");
                else values.append("\nGPU 지원 단계 확인됨 · 실제 동작 클럭과 구분합니다.");
                values.append("\n고정 상한 · 앱 추가 열제어 없음 · GPU 현재 클럭은 읽을 수 없습니다.");
            }
            JSONArray cpu = state.optJSONArray("cpu_current_khz");
            if (cpu != null) values.append("\n현재 CPU: ").append(cpu.optLong(0) / 1000).append(" / ")
                .append(cpu.optLong(1) / 1000).append(" / ").append(cpu.optLong(2) / 1000).append(" MHz");
            if (!state.optBoolean("compatible")) values.append("\n현재 기기·펌웨어에서는 상한을 적용할 수 없습니다.");
        } else values.append("\n설치 버튼을 누르면 포함된 Control Center 수정본을 확인하고 설치합니다.");
        detail.setText(values.toString());
        for (int index = 0; index < buttons.length; index++) {
            boolean active = ready && selected == index;
            GradientDrawable background = new GradientDrawable(); background.setCornerRadius(14);
            background.setColor(active ? Color.rgb(31, 85, 76) : Color.rgb(38, 48, 60));
            if (active) background.setStroke(3, Color.rgb(86, 220, 171));
            buttons[index].setBackground(background); buttons[index].setTextColor(Color.rgb(237, 246, 243));
            buttons[index].setEnabled(underclock && !busy && !installing && !requesting
                    && (index == 0 ? nativeVerified : ready && state.optBoolean("compatible")));
        }
        installButton.setText(underclock?"Under-clock 연결 확인":mode==ControlCenterMode.Mode.INITIAL?"Under-clock 설치":"Under-clock 다시 설치");
        installButton.setEnabled(!busy);restoreButton.setEnabled(!busy);
    }

    private void authorize() {
        if(bridge.managementBusy.get())return;
        try {PlatformAccess.require(this);ensureIntegration(true);}
        catch(RuntimeException failure){actionError=failure.getMessage();render();}
    }

    private void ensureIntegration(boolean explicit) {
        // Resume/recreation/refresh only observes. Package replacement always needs a button.
        if(!explicit)return;
        if(installing||requesting||!bridge.managementBusy.compareAndSet(false,true))return;
        installing=true;actionError="";operation="Control Center 설치를 확인합니다.";render();
        bridge.worker.execute(()->{
            try {
                PlatformAccess.require(this);
                ControlCenterInstaller.Result result=ControlCenterInstaller.install(this,BuildConfig.CONTROL_CENTER_SHA256,true);
                nativeVerified=ControlCenterInstaller.isInstalled(this,BuildConfig.CONTROL_CENTER_SHA256);
                if(!nativeVerified)throw new IllegalStateException("설치 후 Control Center 확인 실패");
                stockVerified=false;operation=result.message;
                try{nativeState=NativeControlCenter.read(this);readError="";}
                catch(Exception failure){readError=failure.getMessage();}
            }catch(Exception failure){nativeVerified=false;actionError="설치 실패: "+failure.getMessage();}
            finally{installing=false;bridge.managementBusy.set(false);}
            runOnUiThread(this::render);
        });
    }

    private void confirmRestore() {
        if(bridge.managementBusy.get())return;
        new AlertDialog.Builder(this).setTitle("순정 Control Center로 복구")
                .setMessage("Under-clock 메뉴와 클럭 제한을 해제하고 검증된 순정 1.0.29로 되돌립니다. Custom 저장값은 남지만 순정에서는 사용되지 않습니다. 팬과 Perf Mode 선택은 유지합니다. 잔여 상한 만료를 위해 교체 후 31초를 기다리며, 이 앱은 수정본을 자동 재설치하지 않습니다.")
                .setNegativeButton("취소",null).setPositiveButton("복구",(dialog,which)->runRestore()).show();
    }

    private void runRestore() {
        try{PlatformAccess.require(this);}catch(RuntimeException failure){actionError=failure.getMessage();render();return;}
        if(!bridge.managementBusy.compareAndSet(false,true))return;
        requesting=true;nativeVerified=false;actionError="";
        operation="Default 해제와 순정 교체를 확인합니다. 교체 후 31초간 기다립니다.";render();
        bridge.worker.execute(()->{
            try {
                operation=ControlCenterInstaller.restore(this,BuildConfig.CONTROL_CENTER_SHA256);
                stockVerified=ControlCenterInstaller.isStockInstalled(this);nativeState=new JSONObject();
            }catch(Exception failure){actionError="복구 실패: "+failure.getMessage();}
            finally{requesting=false;bridge.managementBusy.set(false);}
            runOnUiThread(this::render);
        });
    }

    private void select(int displayIndex) {
        if (installing || requesting || !nativeVerified) return;
        if (!bridge.authorized()) { authorize(); return; }
        if(displayIndex>=4) {
            if(customEditor!=null&&customEditor.isShowing())return;
            try {
                int slot=displayIndex-4;
                customEditor=CustomProfileEditor.show(this,slot,NativeControlCenter.customValues(nativeState,slot),
                    (values,result)->send(displayIndex,values,result),()->{customEditor=null;render();});
            }catch(IllegalStateException failure){actionError=failure.getMessage();render();}
            return;
        }
        send(displayIndex,null,null);
    }

    private void send(int displayIndex,CustomProfileValues custom,CustomProfileEditor.Completion completion) {
        if(installing||requesting||!nativeVerified||!bridge.authorized()||!ControlCenterInstaller.profileRequestsAllowed(this)
                ||!bridge.managementBusy.compareAndSet(false,true)) {
            if(completion!=null)completion.complete("시스템 권한과 Control Center 상태를 확인해 주세요.");return;
        }
        requesting = true; actionError = "";
        operation = NativeControlCenter.LABELS[displayIndex] + " 적용 응답을 확인합니다."; render();
        bridge.worker.execute(() -> {
            String failureText="";
            try {
                ControlCenterInstaller.requireUnderclockMode(this);
                PackageSessionInstaller.reconcileBeforeMutation(this);
                if (!ControlCenterInstaller.isInstalled(this, BuildConfig.CONTROL_CENTER_SHA256)) {
                    nativeVerified = false;
                    throw new IllegalStateException("순정 Control Center 수정본을 다시 확인해 주세요.");
                }
                nativeState = custom==null?NativeControlCenter.request(this, displayIndex)
                        :NativeControlCenter.requestCustom(this,displayIndex-4,custom);
                operation = NativeControlCenter.LABELS[displayIndex] + " 적용됨";
            } catch (Exception failure) { actionError = "선택 실패: " + failure.getMessage();failureText=actionError; }
            finally { requesting = false;bridge.managementBusy.set(false); }
            final String result=failureText;
            runOnUiThread(()->{render();if(completion!=null)completion.complete(result);});
        });
    }

    @Override protected void onResume() { super.onResume(); timer.post(refresh); }
    @Override protected void onPause() { timer.removeCallbacks(refresh); super.onPause(); }
    @Override protected void onDestroy() {
        if(customEditor!=null){customEditor.dismiss();customEditor=null;}
        super.onDestroy();
    }
}
