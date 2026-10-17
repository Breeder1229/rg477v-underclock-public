package io.github.breeder1229.rg477vunderclock;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ControlCenterSourceTest {
    @Test public void separateTrialAllowsOnlyExactStockOrPublishedV02Predecessor() {
        assertTrue(ControlCenterInstaller.isKnownSource("37b7cb60e393176c2208d1b9cf1666d14b9df89e9b8367d28eb0f4bddf85877a", ""));
        assertTrue(ControlCenterInstaller.isKnownSource("43b345149548be671b963ff017487a5e8975f2b373a3abb17a5cc6aaa2ce0961", ""));
        assertTrue(ControlCenterInstaller.isKnownSource("3964d7f8eb91a8ee4b46ae1add9aee4ea518edc978fa14e0924d36bcb6d1d1af", ""));
        assertTrue(ControlCenterInstaller.isKnownSource("22121559378d5cb8990c5f65ef9b7008a8e07b7a2fc3bc524b60139b17dad3e5", ""));
        assertTrue(ControlCenterInstaller.isKnownSource("45d50da8e0ad70fa0e45c8d54bacc3ac1ab5a8267937477b70c10a5f6a2d4a2a", ""));
        assertTrue(ControlCenterInstaller.isKnownSource("35aa2f861aacf8c0f16b3ebc69d9385c54852440beb5fd3eb880b5f409a0b25d", ""));
        assertTrue(ControlCenterInstaller.isKnownSource("fcce06f869bdfa843ea3155d04823155385fc74bc8d0c8ea9f18848ae0eaaa41", ""));
        assertTrue(ControlCenterInstaller.isKnownSource("e88151df43437aa43b269dbf9c53a8008bbb9d62d73cbaa710aef4fadd4bbc0d", ""));
        assertTrue(ControlCenterInstaller.isKnownSource("7278cfd9ea3e450f35c9e1563ef467049282eb845ea18852cd7335e59c199aec", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("7278cfd9ea3e450f35c9e1563ef467049282eb845ea18852cd7335e59c199aed", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("e88151df43437aa43b269dbf9c53a8008bbb9d62d73cbaa710aef4fadd4bbc0e", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("fcce06f869bdfa843ea3155d04823155385fc74bc8d0c8ea9f18848ae0eaaa42", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("35aa2f861aacf8c0f16b3ebc69d9385c54852440beb5fd3eb880b5f409a0b25e", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("45d50da8e0ad70fa0e45c8d54bacc3ac1ab5a8267937477b70c10a5f6a2d4a2b", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("22121559378d5cb8990c5f65ef9b7008a8e07b7a2fc3bc524b60139b17dad3e4", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("3964d7f8eb91a8ee4b46ae1add9aee4ea518edc978fa14e0924d36bcb6d1d1ae", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("43b345149548be671b963ff017487a5e8975f2b373a3abb17a5cc6aaa2ce0960", ""));
        assertFalse(ControlCenterInstaller.isKnownSource("c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8", ""));
    }

    @Test public void anOwnershipRecordDoesNotWhitelistAnUnknownPayload() {
        String owned = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertFalse(ControlCenterInstaller.isKnownSource(owned, owned));
        assertFalse(ControlCenterInstaller.isKnownSource(owned, owned.replace('0', '1')));
        assertFalse(ControlCenterInstaller.isKnownSource("", ""));
        assertFalse(ControlCenterInstaller.isKnownSource(null, null));
    }
}
