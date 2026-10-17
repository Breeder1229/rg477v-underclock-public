package io.github.breeder1229.rg477vunderclock;

import com.anbernic.control.underclock.PresentedFrameRate;
import com.anbernic.control.underclock.BatteryPowerEstimate;
import com.anbernic.control.underclock.OsdReading;
import org.junit.Test;
import static org.junit.Assert.*;

public class PresentedFrameRateTest {
    private static final long SECOND=1000000000L,BASE=8*SECOND;
    private String history(int fps,int first,int last) {
        StringBuilder raw=new StringBuilder("8333333\n");
        for(int i=first;i<=last;i++){long present=BASE+i*SECOND/fps;raw.append(present-1000000).append('\t').append(present).append('\t').append(present-2000000).append('\n');}
        return raw.toString();
    }
    @Test public void thirtyAndSixtyUsePresentedFramesNotRefreshHeader() {
        for(int fps:new int[]{30,60}) {
            PresentedFrameRate r=new PresentedFrameRate();int n=2*fps;
            assertTrue(Double.isNaN(r.sample("game#1",history(fps,0,n-1),10*SECOND)));
            assertEquals(fps,r.sample("game#1",history(fps,fps==60?60:0,n+fps-1),11*SECOND),0.000001);
        }
    }
    @Test public void full120FpsRingIsValidWhenPreviousFrameStillExists() {
        PresentedFrameRate r=new PresentedFrameRate();r.sample("game#1",history(120,113,239),10*SECOND);
        assertEquals(120,r.sample("game#1",history(120,233,359),11*SECOND),0.000001);
    }
    @Test public void ringOverrunCannotUnderreportAsLowerFps() {
        PresentedFrameRate r=new PresentedFrameRate();r.sample("game#1",history(120,113,239),10*SECOND);
        assertTrue(Double.isNaN(r.sample("game#1",history(120,246,372),11100000000L)));
    }
    @Test public void ringOverrunWith126ValidAndOnePendingAlsoStaysUnavailable() {
        PresentedFrameRate r=new PresentedFrameRate();r.sample("game#1",history(120,113,239),10*SECOND);
        String raw=history(120,246,371)+"1\t9223372036854775807\t1\n";
        assertTrue(Double.isNaN(r.sample("game#1",raw,11100000000L)));
    }
    @Test public void stoppedProducerReportsZeroInsteadOfLastFps() {
        PresentedFrameRate r=new PresentedFrameRate();String raw=history(30,0,59);r.sample("game#1",raw,10*SECOND);
        for(int i=11;i<=18;i++)assertEquals(0,r.sample("game#1",raw,i*SECOND),0.0);
    }
    @Test public void foregroundLayerChangeAndLongGapRequireFreshBaseline() {
        PresentedFrameRate r=new PresentedFrameRate();r.sample("game#1",history(30,0,59),10*SECOND);
        assertTrue(Double.isNaN(r.sample("other#2",history(30,0,89),11*SECOND)));
        assertTrue(Double.isNaN(r.sample("other#2",history(30,90,149),16*SECOND)));
    }
    @Test public void missingMalformedPermissionDeniedAndEmptyDataNeverProduceFps() {
        for(String raw:new String[]{null,"Permission Denial","8333333","8333333\n0\t0\t0\n","8333333\n1\t9223372036854775807\t1\n","8333333\n1\twrong\t1\n"}) {
            PresentedFrameRate r=new PresentedFrameRate();assertTrue(Double.isNaN(r.sample("game#1",raw,10*SECOND)));
        }
    }
    @Test public void futureAndBackwardClockDataResetTheMeasurement() {
        PresentedFrameRate r=new PresentedFrameRate();String raw=history(30,0,59);r.sample("game#1",raw,10*SECOND);
        assertTrue(Double.isNaN(r.sample("game#1",history(30,0,89),9*SECOND)));
        assertTrue(Double.isNaN(r.sample("game#1","8333333\n1\t12000000000\t1\n",11*SECOND)));
    }
    @Test public void duplicatePresentTimestampsAreCountedOnce() {
        PresentedFrameRate r=new PresentedFrameRate();String old=history(30,0,59);r.sample("game#1",old,10*SECOND);
        String next=history(30,0,89);long duplicate=BASE+89*SECOND/30;
        next+=duplicate+"\t"+duplicate+"\t"+duplicate+"\n";
        assertEquals(30,r.sample("game#1",next,11*SECOND),0.000001);
    }
    @Test public void disappearingLayerAndExplicitResetCannotReuseAnOldBaseline() {
        PresentedFrameRate r=new PresentedFrameRate();r.sample("game#1",history(30,0,59),10*SECOND);
        assertTrue(Double.isNaN(r.sample(null,null,11*SECOND)));
        assertTrue(Double.isNaN(r.sample("game#1",history(30,0,89),12*SECOND)));
        r.reset();assertTrue(Double.isNaN(r.sample("game#1",history(30,0,89),13*SECOND)));
    }
    @Test public void exactForegroundBlastExcludesBackgroundDecorAndOtherApps() {
        String layer="SurfaceView[com.game/com.game.Play](BLAST)#606";
        String list="Background for "+layer+"\nSurfaceView[com.game/com.game.Play]#605\ncom.game/com.game.Play#600\nSurfaceView[com.other/com.other.Play](BLAST)#7\n"+layer;
        assertEquals(layer,PresentedFrameRate.selectLayer(list,"com.game/com.game.Play"));
        assertEquals(layer,PresentedFrameRate.selectLayer(list,"com.game/.Play"));
        assertNull(PresentedFrameRate.selectLayer(list,"com.gam/com.game.Play"));
    }
    @Test public void multipleGameSurfacesAndUnsupportedDecorStayUnavailable() {
        String layer="SurfaceView[com.game/com.game.Play](BLAST)#606";
        assertNull(PresentedFrameRate.selectLayer(layer+"\n"+layer.replace("606","607"),"com.game/com.game.Play"));
        assertNull(PresentedFrameRate.selectLayer("com.game/com.game.Play#600","com.game/com.game.Play"));
        assertNull(PresentedFrameRate.selectLayer(layer,"invalid"));
        assertNull(PresentedFrameRate.selectLayer(layer,"com.game/com.game.Play](BLAST)#606"));
    }
    @Test public void efficiencyUsesSameUpdatePowerAndHandlesChargingZeroAndMissing() {
        BatteryPowerEstimate power=BatteryPowerEstimate.sample(-2000000,4200,3,0);
        assertEquals(60/8.4,OsdReading.efficiency(power,60),0.000001);
        assertEquals("BAT -8.4 W\nFPS 60.0\nFPS/W 7.1",OsdReading.text(power,60));
        assertEquals(0,OsdReading.efficiency(power,0),0);
        assertTrue(Double.isNaN(OsdReading.efficiency(power,Double.NaN)));
        assertTrue(Double.isNaN(OsdReading.efficiency(power,Double.POSITIVE_INFINITY)));
        assertTrue(Double.isNaN(OsdReading.efficiency(power,-1)));
        assertTrue(Double.isNaN(OsdReading.efficiency(null,60)));
        assertTrue(Double.isNaN(OsdReading.efficiency(BatteryPowerEstimate.sample(0,4200,3,0),60)));
        assertEquals("BAT -8.4 W\nFPS 60.0\nFPS/W —",OsdReading.text(BatteryPowerEstimate.sample(-2000000,4200,3,1),60));
    }
    @Test public void signedBatteryFlowProducesOnlyPositiveUnpluggedEfficiency() {
        for(int plugged:new int[]{0,1})for(int sign:new int[]{-1,0,1}) {
            BatteryPowerEstimate power=BatteryPowerEstimate.sample(sign*2000000,4200,3,plugged);
            double ratio=OsdReading.efficiency(power,60);
            if(plugged==0&&sign<0)assertEquals(60/8.4,ratio,0.000001);
            else assertTrue(Double.isNaN(ratio));
        }
        assertEquals("BAT +8.4 W\nFPS 60.0\nFPS/W —",OsdReading.text(BatteryPowerEstimate.sample(2000000,4200,2,1),60));
        assertEquals("BAT 0.0 W\nFPS 60.0\nFPS/W —",OsdReading.text(BatteryPowerEstimate.sample(0,4200,3,0),60));
    }
}
