package com.anbernic.control.underclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ToggleButton;
import com.anbernic.control.underclock.shared.PowerOverlayProtocol;
import org.json.JSONObject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Display-only singleton in the existing native CC process. No frontend, HAL or shell. */
public final class PowerOverlay implements PowerOverlayLifecycle.Host {
    private static final String TAG="RG477VPowerOverlay";
    private static PowerOverlay instance;
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final BatteryManager battery;
    private final PowerOverlayLifecycle lifecycle;
    private WindowManager windows;
    private TextView label;
    private Intent batteryIntent;
    private boolean listening,observing;
    private String error="",sampleText="";
    private BatteryPowerEstimate estimate;
    private double fps=Double.NaN;
    private ForegroundFrameSampler frames;
    private ExecutorService sampler;
    private Future<?> inFlight;
    private volatile long epoch;
    private long sequence,fpsUpdated;
    private final Runnable fallback=this::fallbackPower;
    private void fallbackPower(){if(lifecycle.active())render(readPower(batteryIntent),Double.NaN);}
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            ui.removeCallbacks(this);
            if(!lifecycle.active())return;
            if(consumeFence())return;
            final long generation=epoch,sample=++sequence,started=System.nanoTime();
            ui.removeCallbacks(fallback);
            if(inFlight!=null&&!inFlight.isDone())render(readPower(batteryIntent),Double.NaN);
            else if(sampler!=null&&frames!=null) {
                final ForegroundFrameSampler reader=frames;
                final Intent sensor=batteryIntent==null?null:new Intent(batteryIntent);
                inFlight=sampler.submit(()->{
                    double measured=reader.sample();
                    BatteryPowerEstimate power=readPower(sensor);
                    if(Thread.currentThread().isInterrupted()||epoch!=generation)return;
                    // The ratio uses this update's current sample, never the previous watt reading.
                    final double value=System.nanoTime()-started<=500000000L?measured:Double.NaN;
                    ui.postAtTime(()->{
                        if(epoch!=generation||sequence!=sample||!lifecycle.active())return;
                        ui.removeCallbacks(fallback);render(power,value);
                    },PowerOverlay.this,SystemClock.uptimeMillis());
                });
                ui.postDelayed(fallback,500);
            }
            if(lifecycle.active())ui.postDelayed(this,1000);
        }
    };
    private BatteryPowerEstimate readPower(Intent sensor) {
        try {
            long current=battery==null?Long.MIN_VALUE:battery.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            int voltage=sensor==null?-1:sensor.getIntExtra(BatteryManager.EXTRA_VOLTAGE,-1);
            int status=sensor==null?1:sensor.getIntExtra(BatteryManager.EXTRA_STATUS,1);
            int plugged=sensor==null?-1:sensor.getIntExtra(BatteryManager.EXTRA_PLUGGED,-1);
            return BatteryPowerEstimate.sample(current,voltage,status,plugged);
        }catch(RuntimeException unavailable){return null;}
    }
    private void render(BatteryPowerEstimate power,double measured) {
        estimate=power;fps=measured;fpsUpdated=Double.isFinite(measured)?System.nanoTime():0;
        sampleText=OsdReading.text(power,measured);
        if(label!=null)label.setText(sampleText);publish();
    }
    private final BroadcastReceiver receiver=new BroadcastReceiver() {
        @Override public void onReceive(Context ignored,Intent intent) {
            if(!lifecycle.enabled())return;
            String action=intent.getAction();
            if(Intent.ACTION_BATTERY_CHANGED.equals(action)) {
                batteryIntent=new Intent(intent);
                if(lifecycle.active()){ui.removeCallbacks(tick);ui.post(tick);}
            }else if(Intent.ACTION_SCREEN_OFF.equals(action))interactive(false);
            else if(Intent.ACTION_SCREEN_ON.equals(action)||Intent.ACTION_USER_PRESENT.equals(action))interactive(true);
        }
    };
    private final ContentObserver stopObserver=new ContentObserver(ui) {
        @Override public void onChange(boolean self){consumeFence();}
    };
    private PowerOverlay(Context supplied) {
        context=supplied.getApplicationContext();
        preferences=context.getSharedPreferences("power_overlay_v1",Context.MODE_PRIVATE);
        battery=context.getSystemService(BatteryManager.class);
        PowerManager power=context.getSystemService(PowerManager.class);
        lifecycle=new PowerOverlayLifecycle(this,power!=null&&power.isInteractive());
        if(!consumeFence()&&preferences.getBoolean("enabled",false))setEnabled(true);
        else publish();
    }
    private static PowerOverlay get(Context context) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Overlay main looper required");
        if(instance==null)instance=new PowerOverlay(context);
        return instance;
    }
    public static void boot(Context context) {
        Context app=context.getApplicationContext();
        new Handler(Looper.getMainLooper()).post(()->{
            try{get(app);}catch(RuntimeException failure){Log.w(TAG,"Power display startup unavailable",failure);}
        });
    }
    /** Title occupies the first three preset columns; toggle occupies the Large column. */
    public static View titleRow(TextView title,ViewGroup.LayoutParams original) {
        Context context=title.getContext();PowerOverlay owner=get(context);
        LinearLayout row=new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        original.width=ViewGroup.LayoutParams.MATCH_PARENT;
        row.setLayoutParams(original);
        row.addView(title,new LinearLayout.LayoutParams(0,ViewGroup.LayoutParams.WRAP_CONTENT,3f));
        ToggleButton toggle=new ToggleButton(context);
        toggle.setTextOn("FPS/W");toggle.setTextOff("FPS/W");
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP,11);toggle.setSingleLine(true);
        toggle.setMinHeight(0);toggle.setMinimumHeight(0);toggle.setMinWidth(0);toggle.setMinimumWidth(0);
        toggle.setPadding(dp(context,2),0,dp(context,2),0);
        toggle.setContentDescription("FPS/W 표시");toggle.setTag("rg477v_power_toggle");
        toggle.setChecked(owner.lifecycle.enabled());style(toggle);
        row.addView(toggle,new LinearLayout.LayoutParams(0,dp(context,32),1f));
        toggle.setOnCheckedChangeListener((button,checked)->{
            boolean accepted=checked==owner.lifecycle.enabled()?checked:owner.setEnabled(checked);
            if(toggle.isChecked()!=accepted)toggle.setChecked(accepted);
            style(toggle);
        });
        return row;
    }
    public static void refresh(View card) {
        ToggleButton toggle=card.findViewWithTag("rg477v_power_toggle");
        if(toggle==null)return;
        boolean enabled=get(card.getContext()).lifecycle.enabled();
        if(toggle.isChecked()!=enabled)toggle.setChecked(enabled);
        style(toggle);
    }
    private static void style(ToggleButton toggle) {
        toggle.setStateDescription(toggle.isChecked()?"ON":"OFF");
        GradientDrawable background=new GradientDrawable();
        background.setColor(toggle.isChecked()?0x5536d6ba:0x2236d6ba);
        background.setCornerRadius(dp(toggle.getContext(),8));
        background.setStroke(dp(toggle.getContext(),1),toggle.isChecked()?0xff36d6ba:0xff71847f);
        toggle.setBackground(background);toggle.setTextColor(toggle.isChecked()?0xff70f2d5:Color.LTGRAY);
    }
    private boolean setEnabled(boolean enabled) {
        if(enabled&&PowerOverlayProtocol.blocked(fence())){consumeFence();return false;}
        if(!enabled)lifecycle.setEnabled(false);
        if(!preferences.edit().putBoolean("enabled",enabled).commit()) {
            error="표시 설정 저장 실패";publish();return lifecycle.enabled();
        }
        error="";
        try{lifecycle.setEnabled(enabled);}
        catch(RuntimeException failure) {
            preferences.edit().putBoolean("enabled",false).commit();
            error="표시 권한 확인";Log.w(TAG,"Power display unavailable",failure);
        }
        publish();return lifecycle.enabled();
    }
    private String fence(){
        try{return Settings.System.getString(context.getContentResolver(),PowerOverlayProtocol.FENCE_KEY);}
        catch(RuntimeException unavailable){return "restore:unavailable";}
    }
    private boolean consumeFence() {
        String value=fence();
        if(!PowerOverlayProtocol.blocked(value))return false;
        setEnabled(false);publish();return true;
    }
    private void interactive(boolean value) {
        try{lifecycle.setInteractive(value);}
        catch(RuntimeException failure){setEnabled(false);}
        publish();
    }
    @Override public void listen() {
        context.getContentResolver().registerContentObserver(Settings.System.getUriFor(PowerOverlayProtocol.FENCE_KEY),false,stopObserver);
        observing=true;
        IntentFilter filter=new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        batteryIntent=context.registerReceiver(receiver,filter,Context.RECEIVER_NOT_EXPORTED);
        listening=true;
    }
    @Override public void unlisten() {
        if(observing) {
            try{context.getContentResolver().unregisterContentObserver(stopObserver);}
            catch(RuntimeException failure){Log.w(TAG,"Observer cleanup",failure);}
            observing=false;
        }
        if(listening) {
            try{context.unregisterReceiver(receiver);}catch(RuntimeException failure){Log.w(TAG,"Receiver cleanup",failure);}
            listening=false;
        }
        batteryIntent=null;estimate=null;fps=Double.NaN;sampleText="";
    }
    @Override public void resume() {
        if(!Settings.canDrawOverlays(context))throw new SecurityException("Overlay permission unavailable");
        DisplayManager displays=context.getSystemService(DisplayManager.class);
        Display display=displays==null?null:displays.getDisplay(Display.DEFAULT_DISPLAY);
        if(display==null)throw new IllegalStateException("Default display unavailable");
        Context visual=context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        windows=visual.getSystemService(WindowManager.class);
        label=new TextView(visual);
        label.setText("BAT · 대기\nFPS —\nFPS/W —");label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP,12);label.setTypeface(Typeface.MONOSPACE,Typeface.BOLD);
        label.setIncludeFontPadding(false);label.setPadding(dp(visual,5),dp(visual,3),dp(visual,5),dp(visual,3));
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        GradientDrawable background=new GradientDrawable();background.setColor(0x99000000);background.setCornerRadius(dp(visual,4));
        label.setBackground(background);
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.x=dp(visual,6);params.y=dp(visual,6);
        params.alpha=0.7f;params.setTitle("RG477VPowerOverlay");
        windows.addView(label,params);
        frames=new ForegroundFrameSampler(context);
        sampler=Executors.newSingleThreadExecutor(task->{Thread thread=new Thread(task,"RG477VPowerFps");thread.setDaemon(true);return thread;});
        epoch++;ui.removeCallbacks(tick);ui.post(tick);
    }
    @Override public void pause() {
        epoch++;sequence++;ui.removeCallbacks(tick);ui.removeCallbacks(fallback);ui.removeCallbacksAndMessages(this);
        if(inFlight!=null){inFlight.cancel(true);inFlight=null;}
        if(frames!=null){frames.close();frames=null;}
        if(sampler!=null){sampler.shutdownNow();sampler=null;}
        if(label!=null&&windows!=null) {
            try{windows.removeViewImmediate(label);}catch(RuntimeException failure){Log.w(TAG,"Window cleanup",failure);}
        }
        label=null;windows=null;
    }
    private void publish() {
        try {
            JSONObject state=new JSONObject().put("enabled",lifecycle.enabled()).put("active",lifecycle.active())
                .put("window_attached",label!=null&&label.isAttachedToWindow()).put("receiver_registered",listening)
                .put("observer_registered",observing).put("fence",fence()).put("text",sampleText).put("error",error)
                .put("updated_elapsed_ms",SystemClock.elapsedRealtime());
            state.put("sampler_running",sampler!=null&&!sampler.isShutdown());
            state.put("fps",Double.isFinite(fps)?fps:JSONObject.NULL);
            state.put("fps_sample_monotonic_ns",fpsUpdated);
            double ratio=OsdReading.efficiency(estimate,fps);state.put("fps_per_watt",Double.isFinite(ratio)?ratio:JSONObject.NULL);
            state.put("signed_watts",estimate!=null&&Double.isFinite(estimate.signedWatts)?estimate.signedWatts:JSONObject.NULL);
            state.put("efficiency_eligible",estimate!=null&&estimate.efficiencyEligible);
            state.put("watts",estimate!=null&&estimate.efficiencyEligible?-estimate.signedWatts:JSONObject.NULL);
            Settings.System.putString(context.getContentResolver(),PowerOverlayProtocol.STATE_KEY,state.toString());
        }catch(Exception failure){Log.w(TAG,"Power display status unavailable",failure);}
    }
    private static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
