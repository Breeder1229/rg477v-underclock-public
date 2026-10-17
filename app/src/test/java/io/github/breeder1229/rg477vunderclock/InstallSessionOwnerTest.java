package io.github.breeder1229.rg477vunderclock;
import org.junit.Test;
import static org.junit.Assert.*;
public final class InstallSessionOwnerTest {
    private static final String OWN="io.github.breeder1229.rg477vunderclock";
    @Test public void onlyTheExactInstallerAndFixedCcTargetAreOwned() {
        assertTrue(InstallSessionOwner.matches(OWN,"com.anbernic.control",OWN));
        assertFalse(InstallSessionOwner.matches("com.vendor.installer","com.anbernic.control",OWN));
        assertFalse(InstallSessionOwner.matches(OWN,"com.other.target",OWN));
        assertFalse(InstallSessionOwner.matches(OWN,null,OWN));
        assertFalse(InstallSessionOwner.matches(null,"com.anbernic.control",OWN));
        assertFalse(InstallSessionOwner.matches(OWN,"com.anbernic.control",null));
    }
}
