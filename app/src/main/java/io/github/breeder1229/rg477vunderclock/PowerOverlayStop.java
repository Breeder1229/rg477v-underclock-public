package io.github.breeder1229.rg477vunderclock;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import com.anbernic.control.underclock.shared.PowerOverlayProtocol;
import org.json.JSONObject;

/** Display-only restore fence. Replacement also destroys any dead/stale CC window. */
final class PowerOverlayStop {
    static void allowAfterExplicitInstall(Context context)throws Exception {
        write(context,"ready:"+System.currentTimeMillis());
    }
    static void beforeStockReplacement(Context context,String sourceHash)throws Exception {
        // Previous payloads never create this display; preserve their restoration behavior.
        if(!BuildConfig.CONTROL_CENTER_SHA256.equals(sourceHash))return;
        String request="restore:"+System.currentTimeMillis();
        write(context,request);
        long until=SystemClock.elapsedRealtime()+2000;
        do {
            try {
                String raw=Settings.System.getString(context.getContentResolver(),PowerOverlayProtocol.STATE_KEY);
                JSONObject state=raw==null?new JSONObject():new JSONObject(raw);
                if(PowerOverlayProtocol.stopped(state.optBoolean("enabled",true),state.optBoolean("window_attached",true),
                        state.optBoolean("receiver_registered",true),state.optBoolean("observer_registered",true),
                        state.optString("fence"),request))return;
            }catch(Exception unavailable){/* A dead keeper cannot acknowledge; APK replacement removes it. */}
            Thread.sleep(100);
        }while(SystemClock.elapsedRealtime()<until);
        Log.i("RGClock","Power display stop not acknowledged; verified stock replacement removes the native process");
    }
    private static void write(Context context,String value)throws Exception {
        if(!Settings.System.putString(context.getContentResolver(),PowerOverlayProtocol.FENCE_KEY,value)
                ||!value.equals(Settings.System.getString(context.getContentResolver(),PowerOverlayProtocol.FENCE_KEY)))
            throw new IllegalStateException("소비전력 표시 중지 설정 확인 실패");
    }
    private PowerOverlayStop(){}
}
