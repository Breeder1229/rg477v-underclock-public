package io.github.breeder1229.rg477vunderclock;
import org.junit.Test;
import static org.junit.Assert.*;

public final class InstallSessionStateTest {
    private InstallSessionState waiting(){return new InstallSessionState(42,"unique-operation",InstallSessionState.WAITING,false);}
    @Test public void onlyMatchingSessionAndTokenCanComplete() {
        InstallSessionState s=waiting();
        assertSame(s,s.result(41,"unique-operation",0));
        assertSame(s,s.result(42,"previous-operation",0));
        assertSame(s,s.result(42,null,0));
        assertEquals(0,s.result(42,"unique-operation",0).result);
    }
    @Test public void aTerminalResultCannotBeReplacedByAnotherBroadcast() {
        InstallSessionState s=waiting().result(42,"unique-operation",7);
        assertEquals(7,s.result(42,"unique-operation",0).result);
    }
    @Test public void timeoutRejectsLateSuccessAndRetainsSessionForReconciliation() {
        InstallSessionState s=waiting().timeout();
        assertEquals(42,s.session);assertEquals("unique-operation",s.token);
        assertEquals(InstallSessionState.WAITING,s.result(42,"unique-operation",0).result);
        assertTrue(s.timedOut);
    }
    @Test public void pendingUserActionCannotTurnIntoUnapprovedSuccess() {
        InstallSessionState s=waiting().result(42,"unique-operation",-1);
        assertEquals(-1,s.result(42,"unique-operation",0).result);
    }
    @Test public void malformedOrAbsentSessionNeverAcceptsCallback() {
        assertFalse(new InstallSessionState(-1,"unique-operation",InstallSessionState.WAITING,false).accepts(-1,"unique-operation"));
        assertFalse(new InstallSessionState(42,"",InstallSessionState.WAITING,false).accepts(42,""));
        assertFalse(new InstallSessionState(42,null,InstallSessionState.WAITING,false).accepts(42,null));
    }
    @Test public void retryTokenCannotAcceptTheOldSessionsResult() {
        InstallSessionState s=new InstallSessionState(43,"new-operation",InstallSessionState.WAITING,false);
        assertSame(s,s.result(42,"unique-operation",0));
        assertSame(s,s.result(43,"unique-operation",0));
        assertEquals(0,s.result(43,"new-operation",0).result);
    }
}
