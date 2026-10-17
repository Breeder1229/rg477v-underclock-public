package com.anbernic.control.underclock;

import java.util.Locale;

/** Signed instantaneous battery flow estimate; no history or integration. */
public final class BatteryPowerEstimate {
    public static final int UNKNOWN=0, DISCHARGE=1, CHARGING=2, UNAVAILABLE=3, INVALID=4, IDLE=5;
    public final int kind;
    public final double signedWatts;
    public final boolean efficiencyEligible;
    private BatteryPowerEstimate(int kind,double signedWatts,boolean efficiencyEligible){this.kind=kind;this.signedWatts=signedWatts;this.efficiencyEligible=efficiencyEligible;}
    public static BatteryPowerEstimate sample(double currentMicroamps,double voltageMillivolts,
                                              int batteryStatus,int plugged) {
        if(plugged<0||batteryStatus<2||batteryStatus>5)return result(UNKNOWN);
        if(currentMicroamps==Long.MIN_VALUE||currentMicroamps==Integer.MIN_VALUE)
            return result(UNAVAILABLE);
        if(!Double.isFinite(currentMicroamps)||!Double.isFinite(voltageMillivolts)
                ||Math.abs(currentMicroamps)>10000000d
                ||voltageMillivolts<2500d||voltageMillivolts>5000d)return result(INVALID);
        double signedWatts=currentMicroamps*voltageMillivolts/1000000000d;
        if(!Double.isFinite(signedWatts)||Math.abs(signedWatts)>50d)return result(INVALID);
        int kind=signedWatts<0?DISCHARGE:signedWatts>0?CHARGING:IDLE;
        boolean eligible=plugged==0&&kind==DISCHARGE&&batteryStatus!=2&&batteryStatus!=5;
        return new BatteryPowerEstimate(kind,signedWatts,eligible);
    }
    private static BatteryPowerEstimate result(int kind){return new BatteryPowerEstimate(kind,Double.NaN,false);}
    public String text() {
        switch(kind) {
            case DISCHARGE:return String.format(Locale.ROOT,"BAT %.1f W",signedWatts);
            case CHARGING:return String.format(Locale.ROOT,"BAT +%.1f W",signedWatts);
            case IDLE:return "BAT 0.0 W";
            case UNAVAILABLE:return "BAT · 센서 없음";
            case INVALID:return "BAT · 센서 확인";
            default:return "BAT · 대기";
        }
    }
}
