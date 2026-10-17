package io.github.breeder1229.rg477vunderclock;

import com.anbernic.control.underclock.BootRestorePolicy;
import org.junit.Test;
import static org.junit.Assert.*;

public final class BootRestorePolicyTest {
    private static final class RuntimeActions implements BootRestorePolicy.Actions {
        int acquires,stops,waits;long delay;
        @Override public void attempt(){acquires++;}
        @Override public void expired(){stops++;}
        @Override public void waitFor(long delay){waits++;this.delay=delay;}
    }
    @Test public void actualDispatcherPublishesTerminalFailureWhenCallbackIsLate() {
        BootRestorePolicy p=new BootRestorePolicy(2,0);RuntimeActions actions=new RuntimeActions();long ticket=p.epoch();
        p.dispatch(ticket,0,actions);assertEquals(1,actions.acquires);
        p.dispatch(ticket,1,actions);assertEquals(1,actions.waits);assertEquals(4999,actions.delay);
        p.dispatch(ticket,60000,actions);assertEquals(1,actions.acquires);assertEquals(1,actions.stops);
        assertFalse(p.pending());assertEquals(2,p.selected());
        p.dispatch(ticket,61000,actions);assertEquals(1,actions.stops);
    }
    @Test public void actualDispatcherDoesNothingAfterNewDefaultOrManualRequest() {
        BootRestorePolicy p=new BootRestorePolicy(5,0);RuntimeActions actions=new RuntimeActions();long ticket=p.epoch();
        p.dispatch(ticket,0,actions);p.cancel();
        p.dispatch(ticket,5000,actions);p.dispatch(ticket,60000,actions);
        assertEquals(1,actions.acquires);assertEquals(0,actions.stops);assertEquals(0,actions.waits);
    }
    @Test public void retryCountIsBoundedAndEventsCannotConsumeEarlyAttempts() {
        BootRestorePolicy p=new BootRestorePolicy(2,1000);long ticket=p.epoch();
        for(int i=0;i<BootRestorePolicy.MAX_ATTEMPTS;i++) {
            long now=1000+i*BootRestorePolicy.INTERVAL_MS;
            assertTrue(p.begin(ticket,now));
            assertFalse(p.begin(ticket,now+1));
            assertEquals(i+1,p.attempts());
        }
        assertFalse(p.begin(ticket,1000+BootRestorePolicy.MAX_ATTEMPTS*BootRestorePolicy.INTERVAL_MS));
        assertEquals(-1,p.delay(ticket,999999));
    }
    @Test public void delayedCallbackBeyondDeadlineNeverStartsAnAcquire() {
        BootRestorePolicy p=new BootRestorePolicy(2,1000);long ticket=p.epoch();
        assertTrue(p.begin(ticket,1000));
        assertFalse(p.begin(ticket,1000+BootRestorePolicy.WINDOW_MS));
        assertFalse(p.begin(ticket,1000+BootRestorePolicy.WINDOW_MS+60000));
        assertEquals(1,p.attempts());assertEquals(2,p.selected());
    }
    @Test public void defaultAndFailedManualSelectionsCancelTheOldEpoch() {
        BootRestorePolicy p=new BootRestorePolicy(5,0);long savedTicket=p.epoch();
        assertTrue(p.begin(savedTicket,0));p.cancel();
        assertFalse(p.pending());assertFalse(p.begin(savedTicket,5000));
        assertEquals(-1,p.delay(savedTicket,5000));
        assertFalse(p.begin(p.epoch(),5000));assertEquals(5,p.selected());
    }
    @Test public void successEndsBootstrapAndNoScheduledCallbackCanRestartIt() {
        BootRestorePolicy p=new BootRestorePolicy(7,0);long ticket=p.epoch();
        assertTrue(p.begin(ticket,0));p.complete(ticket);
        assertFalse(p.pending());assertFalse(p.begin(ticket,5000));
        assertFalse(p.begin(p.epoch(),5000));
    }
    @Test public void staleCompletionCannotEndAValidEpoch() {
        BootRestorePolicy p=new BootRestorePolicy(1,0);long ticket=p.epoch();
        p.complete(ticket+1);assertTrue(p.pending());assertTrue(p.begin(ticket,0));
    }
    @Test public void onlyKnownTransientHalFailuresAreRetried() {
        assertTrue(BootRestorePolicy.retryable("java.lang.IllegalStateException","PowerHAL request failed"));
        assertTrue(BootRestorePolicy.retryable("java.lang.IllegalStateException","PowerHAL not ready"));
        assertTrue(BootRestorePolicy.retryable("android.os.DeadObjectException",null));
        assertTrue(BootRestorePolicy.retryable("android.os.RemoteException","unavailable"));
        for(String reason:new String[]{"Unverified firmware","Unverified CPU path","Unverified GPU path","Unverified PowerHAL interface","PowerHAL request rejected"})
            assertFalse(BootRestorePolicy.retryable("java.lang.IllegalStateException",reason));
        assertFalse(BootRestorePolicy.retryable("java.lang.SecurityException","PowerHAL request failed"));
        assertFalse(BootRestorePolicy.retryable("java.lang.IllegalArgumentException","Custom 설정을 먼저 저장해 주세요."));
        assertFalse(BootRestorePolicy.retryable(null,null));
    }
    @Test public void monotonicClockReversalAndInvalidSavedSelectionAreRejected() {
        BootRestorePolicy p=new BootRestorePolicy(0,1000);assertFalse(p.begin(p.epoch(),999));
        try{new BootRestorePolicy(8,0);fail();}catch(IllegalArgumentException expected){}
        try{new BootRestorePolicy(-1,0);fail();}catch(IllegalArgumentException expected){}
    }
}
