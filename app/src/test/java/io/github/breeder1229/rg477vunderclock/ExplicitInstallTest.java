package io.github.breeder1229.rg477vunderclock;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ExplicitInstallTest {
    @Test public void newInstallDoesNotEnableProfileRequestsOrMutateInitialMode() {
        ControlCenterMode.Store store=new ControlCenterMode.Store() {
            public String read(){return "INITIAL";}
            public boolean write(String value){fail("Observation must not change durable intent");return false;}
        };
        assertEquals(ControlCenterMode.Mode.INITIAL,ControlCenterMode.read(store));
        assertFalse(ControlCenterMode.profileRequestsAllowed(store));
        try{ControlCenterMode.requireExplicitInstall(false);fail("Automatic installation allowed");}
        catch(IllegalStateException expected){}
    }
    @Test public void onlyUserActionAllowsInstallationEntry() {
        ControlCenterMode.requireExplicitInstall(true);
    }
}
