package io.github.breeder1229.rg477vunderclock;

import com.anbernic.control.underclock.BatteryPowerEstimate;
import org.junit.Test;
import static org.junit.Assert.*;

public class BatteryPowerEstimateTest {
    private BatteryPowerEstimate sample(double current,double voltage){return BatteryPowerEstimate.sample(current,voltage,3,0);}
    @Test public void measuredDischargeUnitsAreMicroampsAndMillivolts() {
        BatteryPowerEstimate value=sample(-811700,4275);
        assertEquals(BatteryPowerEstimate.DISCHARGE,value.kind);assertTrue(value.efficiencyEligible);
        assertEquals(-3.4700175,value.signedWatts,0.0000001);assertEquals("BAT -3.5 W",value.text());
        assertEquals(-8.4,sample(-2000000,4200).signedWatts,0.000001);
    }
    @Test public void pluggedNegativeIsStillBatteryDischargeAndNotEfficiency() {
        for(int plugged:new int[]{1,2,4,8}) {
            BatteryPowerEstimate value=BatteryPowerEstimate.sample(-2000000,4200,3,plugged);
            assertEquals(BatteryPowerEstimate.DISCHARGE,value.kind);assertEquals(-8.4,value.signedWatts,0.000001);
            assertEquals("BAT -8.4 W",value.text());assertFalse(value.efficiencyEligible);
        }
    }
    @Test public void chargingPositiveDisplaysActualPositiveFlow() {
        for(int status:new int[]{2,3,4,5}) {
            BatteryPowerEstimate value=BatteryPowerEstimate.sample(2000000,4200,status,1);
            assertEquals(BatteryPowerEstimate.CHARGING,value.kind);assertEquals(8.4,value.signedWatts,0.000001);
            assertEquals("BAT +8.4 W",value.text());assertFalse(value.efficiencyEligible);
        }
    }
    @Test public void zeroIsValidButNeverAnEfficiencyDenominator() {
        for(int plugged:new int[]{0,1}) {
            BatteryPowerEstimate value=BatteryPowerEstimate.sample(0,4200,3,plugged);
            assertEquals(BatteryPowerEstimate.IDLE,value.kind);assertEquals(0,value.signedWatts,0);
            assertEquals("BAT 0.0 W",value.text());assertFalse(value.efficiencyEligible);
        }
    }
    @Test public void bothUnsupportedSentinelsAreRejectedEvenWhileCharging() {
        for(long sentinel:new long[]{Long.MIN_VALUE,Integer.MIN_VALUE})for(int plugged:new int[]{0,1}) {
            BatteryPowerEstimate value=BatteryPowerEstimate.sample(sentinel,4200,3,plugged);
            assertEquals(BatteryPowerEstimate.UNAVAILABLE,value.kind);assertTrue(Double.isNaN(value.signedWatts));assertFalse(value.efficiencyEligible);
        }
    }
    @Test public void invalidVoltageAndMicrovoltUnitMistakeAreRejected() {
        for(double voltage:new double[]{-1,0,2499,5001,4275000,Double.NaN,Double.POSITIVE_INFINITY})
            assertEquals(BatteryPowerEstimate.INVALID,sample(-811700,voltage).kind);
    }
    @Test public void inclusiveBatteryBoundsAndBothSignedLimitsAreValid() {
        assertEquals(-2.5,sample(-1000000,2500).signedWatts,0.000001);
        assertEquals(-50,sample(-10000000,5000).signedWatts,0.000001);
        assertEquals(50,sample(10000000,5000).signedWatts,0.000001);
        assertEquals(-0.42,sample(-100000,4200).signedWatts,0.000001);
    }
    @Test public void unknownBatteryStateCannotProduceAWattValue() {
        for(int status:new int[]{-1,0,1,6})assertEquals(BatteryPowerEstimate.UNKNOWN,BatteryPowerEstimate.sample(-811700,4275,status,0).kind);
        assertEquals(BatteryPowerEstimate.UNKNOWN,BatteryPowerEstimate.sample(-811700,4275,3,-1).kind);
    }
    @Test public void nonfiniteAndOutOfRangeCurrentsAreRejected() {
        for(double current:new double[]{10000001,-10000001,Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})
            assertEquals(BatteryPowerEstimate.INVALID,sample(current,4200).kind);
    }
    @Test public void statusAndPlugNeverInventOrReverseTheSensorSign() {
        for(int status:new int[]{2,3,4,5})for(int plugged:new int[]{0,1})for(int sign:new int[]{-1,0,1}) {
            BatteryPowerEstimate value=BatteryPowerEstimate.sample(sign*1000000,4000,status,plugged);
            assertEquals(sign*4,value.signedWatts,0.0);
            assertEquals(sign<0&&plugged==0&&status!=2&&status!=5,value.efficiencyEligible);
        }
    }
}
