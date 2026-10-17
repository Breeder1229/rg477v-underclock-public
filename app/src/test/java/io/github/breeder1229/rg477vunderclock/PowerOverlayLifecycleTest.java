package io.github.breeder1229.rg477vunderclock;

import com.anbernic.control.underclock.PowerOverlayLifecycle;
import com.anbernic.control.underclock.shared.PowerOverlayProtocol;
import org.junit.Test;
import static org.junit.Assert.*;

public class PowerOverlayLifecycleTest {
    static final class Host implements PowerOverlayLifecycle.Host {
        int listen,unlisten,resume,pause;boolean receiver,window,callback,observer,failListen,failResume;
        public void listen(){listen++;receiver=true;observer=true;if(failListen)throw new SecurityException();}
        public void unlisten(){unlisten++;receiver=false;observer=false;}
        public void resume(){resume++;window=true;callback=true;if(failResume)throw new SecurityException();}
        public void pause(){pause++;window=false;callback=false;}
        void assertClean(){assertFalse(receiver);assertFalse(window);assertFalse(callback);assertFalse(observer);}
    }
    @Test public void initialOffHasNoResources(){Host h=new Host();PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,true);p.setEnabled(false);assertEquals(0,h.listen);assertEquals(0,h.unlisten);h.assertClean();}
    @Test public void repeatedOnDoesNotDuplicateWindowOrReceiver(){Host h=new Host();PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,true);for(int i=0;i<10;i++)p.setEnabled(true);assertTrue(p.active());assertEquals(1,h.listen);assertEquals(1,h.resume);}
    @Test public void offClosesEverythingExactlyOnce(){Host h=new Host();PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,true);p.setEnabled(true);for(int i=0;i<10;i++)p.setEnabled(false);assertEquals(1,h.pause);assertEquals(1,h.unlisten);h.assertClean();}
    @Test public void screenOffPausesPollingAndWindowButKeepsStopListener(){Host h=new Host();PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,true);p.setEnabled(true);p.setInteractive(false);assertTrue(p.enabled());assertFalse(p.active());assertFalse(h.window);assertFalse(h.callback);assertTrue(h.receiver);assertTrue(h.observer);p.setInteractive(true);assertEquals(2,h.resume);assertEquals(1,h.listen);}
    @Test public void offDuringScreenOffAlsoUnregistersListeners(){Host h=new Host();PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,true);p.setEnabled(true);p.setInteractive(false);p.setEnabled(false);p.setInteractive(true);h.assertClean();assertFalse(p.enabled());assertEquals(1,h.resume);}
    @Test public void enablingWhileScreenOffDefersWindowUntilWake(){Host h=new Host();PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,false);p.setEnabled(true);assertEquals(0,h.resume);p.setInteractive(true);assertEquals(1,h.resume);p.setEnabled(false);h.assertClean();}
    @Test public void partialReceiverFailureIsCleaned(){Host h=new Host();h.failListen=true;PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,true);try{p.setEnabled(true);fail();}catch(SecurityException expected){}assertFalse(p.enabled());h.assertClean();}
    @Test public void partialWindowFailureIsCleanedAndCanRetry(){Host h=new Host();h.failResume=true;PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,true);try{p.setEnabled(true);fail();}catch(SecurityException expected){}assertFalse(p.enabled());h.assertClean();h.failResume=false;p.setEnabled(true);assertTrue(p.active());p.setEnabled(false);h.assertClean();}
    @Test public void wakeWindowFailureAlsoClosesListeners(){Host h=new Host();PowerOverlayLifecycle p=new PowerOverlayLifecycle(h,false);p.setEnabled(true);h.failResume=true;try{p.setInteractive(true);fail();}catch(SecurityException expected){}assertFalse(p.enabled());h.assertClean();}
    @Test public void restoreFenceBlocksOnlyRestoreAndRequiresCompleteStopAck(){assertFalse(PowerOverlayProtocol.blocked(null));assertFalse(PowerOverlayProtocol.blocked("ready:1"));assertTrue(PowerOverlayProtocol.blocked("restore:2"));String n="restore:2";assertTrue(PowerOverlayProtocol.stopped(false,false,false,false,n,n));assertFalse(PowerOverlayProtocol.stopped(false,false,false,false,"restore:1",n));assertFalse(PowerOverlayProtocol.stopped(true,false,false,false,n,n));assertFalse(PowerOverlayProtocol.stopped(false,true,false,false,n,n));assertFalse(PowerOverlayProtocol.stopped(false,false,true,false,n,n));assertFalse(PowerOverlayProtocol.stopped(false,false,false,true,n,n));}
}
