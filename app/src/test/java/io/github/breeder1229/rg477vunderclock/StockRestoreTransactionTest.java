package io.github.breeder1229.rg477vunderclock;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public final class StockRestoreTransactionTest {
    private static final class Disk implements ControlCenterMode.Store {
        String value="UNDERCLOCK",disk="UNDERCLOCK";
        int calls,failAt=-1;
        @Override public String read(){return value;}
        @Override public boolean write(String next){value=next;calls++;if(calls==failAt)return false;disk=next;return true;}
        Disk restart(){Disk next=new Disk();next.disk=next.value=disk;return next;}
    }
    private static final class Actions implements StockRestoreTransaction.Actions {
        final Disk store;final List<String> events=new ArrayList<>();String failure="";
        boolean alreadyStock,stock=true;int checks;
        Actions(Disk store){this.store=store;}
        void event(String name)throws Exception{
            assertEquals("RESTORE_PENDING",store.disk);events.add(name);
            if(name.equals(failure))throw new Exception(name+" failed");
        }
        @Override public boolean preflight()throws Exception{event("validate");return alreadyStock;}
        @Override public void verifiedDefault()throws Exception{event("default");}
        @Override public void replaceWithStock()throws Exception{event("replace");}
        @Override public boolean stockVerified()throws Exception{event("verify");checks++;return stock;}
        @Override public void waitForLeaseExpiry()throws Exception{event("lease");}
    }
    private static void fails(Disk store,Actions actions)throws Exception{
        try{StockRestoreTransaction.run(store,actions);fail("Expected safe stop");}
        catch(Exception expected){assertFalse(ControlCenterMode.profileRequestsAllowed(store.restart()));}
    }
    @Test public void successValidatesReleasesReplacesAndWaitsBeforeDurableStock()throws Exception{
        Disk disk=new Disk();Actions actions=new Actions(disk);
        StockRestoreTransaction.run(disk,actions);
        assertEquals(Arrays.asList("validate","default","replace","verify","lease","verify"),actions.events);
        assertEquals(ControlCenterMode.Mode.STOCK,ControlCenterMode.read(disk.restart()));
        assertFalse(ControlCenterMode.profileRequestsAllowed(disk.restart()));
    }
    @Test public void pendingCommitFailurePreventsEvenPreflightAndAllDeviceWork()throws Exception{
        Disk disk=new Disk();disk.failAt=1;Actions actions=new Actions(disk);
        try{StockRestoreTransaction.run(disk,actions);fail("Uncommitted intent must stop");}catch(IllegalStateException expected){}
        assertTrue(actions.events.isEmpty());assertEquals("UNDERCLOCK",disk.disk);assertEquals("UNDERCLOCK",disk.value);
    }
    @Test public void wrongApkOrUnknownInstalledPackageStopsBeforeDefault()throws Exception{
        Disk disk=new Disk();Actions actions=new Actions(disk);actions.failure="validate";
        fails(disk,actions);assertEquals(Arrays.asList("validate"),actions.events);
    }
    @Test public void staleOrTimedOutDefaultNeverReplacesThePackage()throws Exception{
        Disk disk=new Disk();Actions actions=new Actions(disk);actions.failure="default";
        fails(disk,actions);assertEquals(Arrays.asList("validate","default"),actions.events);
    }
    @Test public void installTimeoutKeepsAutoDisabledAcrossProcessRestart()throws Exception{
        Disk disk=new Disk();Actions actions=new Actions(disk);actions.failure="replace";
        fails(disk,actions);assertEquals("RESTORE_FAILED",disk.restart().value);
    }
    @Test public void changedInstalledBytesPreventSuccessAndLeaseCompletion()throws Exception{
        Disk disk=new Disk();Actions actions=new Actions(disk);actions.stock=false;
        fails(disk,actions);assertFalse(actions.events.contains("lease"));
    }
    @Test public void interruptionDuringGracePeriodKeepsManualRecoveryMode()throws Exception{
        Disk disk=new Disk();Actions actions=new Actions(disk);actions.failure="lease";
        fails(disk,actions);assertEquals("RESTORE_FAILED",disk.disk);
    }
    @Test public void alreadyStockCanRecoverPendingWorkWithoutAnotherDefaultCommand()throws Exception{
        Disk disk=new Disk();disk.value=disk.disk="RESTORE_PENDING";Actions actions=new Actions(disk);actions.alreadyStock=true;
        StockRestoreTransaction.run(disk,actions);
        assertEquals(Arrays.asList("validate","verify","lease","verify"),actions.events);
        assertEquals("STOCK",disk.disk);
    }
    @Test public void completionCommitFailureNeverEnablesAutomaticInstall()throws Exception{
        Disk disk=new Disk();disk.failAt=2;Actions actions=new Actions(disk);
        fails(disk,actions);assertEquals("RESTORE_FAILED",disk.restart().value);
    }
    @Test public void queuedAutomaticWorkObservesTheCurrentDurableIntent() {
        Disk disk=new Disk();Runnable queued=()->{if(ControlCenterMode.profileRequestsAllowed(disk))fail("Stale queued auto install ran");};
        ControlCenterMode.save(disk,ControlCenterMode.Mode.RESTORE_PENDING);queued.run();
        ControlCenterMode.save(disk,ControlCenterMode.Mode.STOCK);queued.run();
        ControlCenterMode.save(disk,ControlCenterMode.Mode.RESTORE_FAILED);queued.run();
    }
    @Test public void explicitInstallMustCompleteBeforeAutoModeReturns() {
        Disk disk=new Disk();ControlCenterMode.save(disk,ControlCenterMode.Mode.STOCK);
        ControlCenterMode.save(disk,ControlCenterMode.Mode.INSTALL_PENDING);
        assertFalse(ControlCenterMode.profileRequestsAllowed(disk.restart()));
        ControlCenterMode.save(disk,ControlCenterMode.Mode.INSTALL_FAILED);
        assertFalse(ControlCenterMode.profileRequestsAllowed(disk.restart()));
        ControlCenterMode.save(disk,ControlCenterMode.Mode.UNDERCLOCK);
        assertTrue(ControlCenterMode.profileRequestsAllowed(disk.restart()));
    }
    @Test public void malformedModeFailsClosed() {
        Disk disk=new Disk();disk.value="unexpected";
        assertFalse(ControlCenterMode.profileRequestsAllowed(disk));
    }
}
