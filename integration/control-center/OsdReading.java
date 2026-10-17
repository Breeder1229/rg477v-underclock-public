package com.anbernic.control.underclock;

import java.util.Locale;

/** Three lines from one update. Missing power/FPS never becomes a numeric efficiency. */
public final class OsdReading {
    public static double efficiency(BatteryPowerEstimate power,double fps) {
        if(power==null||!power.efficiencyEligible||!Double.isFinite(power.signedWatts)||power.signedWatts>=0
                ||!Double.isFinite(fps)||fps<0)return Double.NaN;
        double result=fps/(-power.signedWatts);return Double.isFinite(result)?result:Double.NaN;
    }
    public static String text(BatteryPowerEstimate power,double fps) {
        double efficiency=efficiency(power,fps);
        String first=power==null?"BAT · 센서 없음":power.text();
        return first+"\nFPS "+(Double.isFinite(fps)&&fps>=0?String.format(Locale.ROOT,"%.1f",fps):"—")
            +"\nFPS/W "+(Double.isFinite(efficiency)?String.format(Locale.ROOT,"%.1f",efficiency):"—");
    }
    private OsdReading(){}
}
