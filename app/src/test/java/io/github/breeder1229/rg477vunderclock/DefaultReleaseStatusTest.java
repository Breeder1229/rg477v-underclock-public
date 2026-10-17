package io.github.breeder1229.rg477vunderclock;

import org.junit.Test;
import static org.junit.Assert.*;

public final class DefaultReleaseStatusTest {
    @Test public void newNativeDefaultMustConfirmBootRetryWasCancelled() {
        DefaultReleaseStatus.verifyNoBootRetry(true,false);
        reject(()->DefaultReleaseStatus.verifyNoBootRetry(false,false));
        reject(()->DefaultReleaseStatus.verifyNoBootRetry(true,true));
    }
    private static void check(int revision,int display,int handle,long[] cpu,long gpu,long target,boolean cleared,int stage,boolean fixed,boolean thermal){
        DefaultReleaseStatus.verify(true,revision,display,handle,cpu,gpu,target,cleared,stage,fixed,thermal);
    }
    @Test public void currentAndLegacyReleasedKeepersAreAccepted(){
        check(1,0,0,new long[]{0,0,0},0,0,true,0,false,false);
        check(2,0,0,new long[]{0,0,0},0,0,true,0,false,false);
        DefaultReleaseStatus.verifySource("7278cfd9ea3e450f35c9e1563ef467049282eb845ea18852cd7335e59c199aec",true,2,0,0,new long[]{0,0,0},0,0,true,0,false,false);
        DefaultReleaseStatus.verifySource("e88151df43437aa43b269dbf9c53a8008bbb9d62d73cbaa710aef4fadd4bbc0d",true,2,0,0,new long[]{0,0,0},0,0,true,0,false,false);
        DefaultReleaseStatus.verifySource("fcce06f869bdfa843ea3155d04823155385fc74bc8d0c8ea9f18848ae0eaaa41",
                true,2,0,0,new long[]{0,0,0},0,0,true,0,false,false);
    }
    @Test public void defaultLabelWithLiveHandleOrCapsIsRejected(){
        reject(()->check(2,0,57,new long[]{0,0,0},0,0,true,0,false,false));
        reject(()->check(2,0,0,new long[]{0,1200000,0},0,0,true,0,false,false));
        reject(()->check(2,0,0,new long[]{0,0,0},601000000,0,true,0,false,false));
        reject(()->check(2,0,0,new long[]{0,0,0},0,601000000,true,0,false,false));
        reject(()->check(2,0,0,new long[]{0,0,0},0,0,false,0,false,false));
    }
    @Test public void missingOrWrongShapeCannotBeInterpretedAsReleased(){
        reject(()->check(2,0,0,null,0,0,true,0,false,false));
        reject(()->check(2,0,0,new long[]{0,0},0,0,true,0,false,false));
        reject(()->check(2,0,0,new long[]{0,Long.MIN_VALUE,0},0,0,true,0,false,false));
        reject(()->check(2,0,-1,new long[]{0,0,0},0,0,true,0,false,false));
        reject(()->check(2,0,0,new long[]{0,0,0},-1,0,true,0,false,false));
        reject(()->check(3,0,0,new long[]{0,0,0},0,0,true,0,false,false));
    }
    @Test public void activeProfileOrThermalFlagsAreRejected(){
        reject(()->check(2,3,0,new long[]{0,0,0},0,0,true,0,false,false));
        reject(()->check(2,0,0,new long[]{0,0,0},0,0,true,1,false,false));
        reject(()->check(2,0,0,new long[]{0,0,0},0,0,true,0,true,false));
        reject(()->check(2,0,0,new long[]{0,0,0},0,0,true,0,false,true));
    }
    @Test public void exactTagV02SchemaDoesNotRequireFieldsAddedByLaterTrials(){
        // Tag v0.2 publish(): integrated,revision,display_index,handle,
        // requested_cap_cpu_khz,requested_gpu_hz,thermal_stage are present.
        // gpu_target_hz/requested_gpu_opp_index/fixed_ceiling_trial/thermal_control_active are absent.
        DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,0,
                new long[]{0,0,0},0,-1,false,0,true,true);
    }
    @Test public void legacyFieldOmissionIsRejectedForEveryModernPinnedPayload(){
        String[] hashes={"3964d7f8eb91a8ee4b46ae1add9aee4ea518edc978fa14e0924d36bcb6d1d1af",
            "22121559378d5cb8990c5f65ef9b7008a8e07b7a2fc3bc524b60139b17dad3e5",
            "45d50da8e0ad70fa0e45c8d54bacc3ac1ab5a8267937477b70c10a5f6a2d4a2a",
            "35aa2f861aacf8c0f16b3ebc69d9385c54852440beb5fd3eb880b5f409a0b25d",
            "fcce06f869bdfa843ea3155d04823155385fc74bc8d0c8ea9f18848ae0eaaa41",
            "e88151df43437aa43b269dbf9c53a8008bbb9d62d73cbaa710aef4fadd4bbc0d",
            "7278cfd9ea3e450f35c9e1563ef467049282eb845ea18852cd7335e59c199aec"};
        for(String hash:hashes)reject(()->DefaultReleaseStatus.verifySource(hash,true,
                DefaultReleaseStatus.revisionForSource(hash),0,0,new long[]{0,0,0},0,-1,false,0,true,true));
    }
    @Test public void alteredSourceAndMissingCommonLegacyFieldsStillFailClosed(){
        reject(()->DefaultReleaseStatus.revisionForSource("43b345149548be671b963ff017487a5e8975f2b373a3abb17a5cc6aaa2ce0960"));
        reject(()->DefaultReleaseStatus.revisionForSource(null));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,false,1,0,0,new long[]{0,0,0},0,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,2,0,0,new long[]{0,0,0},0,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,-1,0,new long[]{0,0,0},0,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,-1,new long[]{0,0,0},0,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,0,null,0,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,0,new long[]{0,Long.MIN_VALUE,0},0,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,0,new long[]{0,0,0},-1,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,0,new long[]{0,0,0},0,-1,false,-1,true,true));
    }
    @Test public void activeLegacyLeaseOrRequestedCapsAreRejected(){
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,57,new long[]{0,0,0},0,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,0,new long[]{0,1200000,0},0,-1,false,0,true,true));
        reject(()->DefaultReleaseStatus.verifySource(DefaultReleaseStatus.LEGACY_V02,true,1,0,0,new long[]{0,0,0},601000000,-1,false,0,true,true));
    }
    @Test public void onlyTheCurrentBootstrapPayloadRequiresTheNewBootField() {
        String current="7278cfd9ea3e450f35c9e1563ef467049282eb845ea18852cd7335e59c199aec";
        for(String prior:new String[]{DefaultReleaseStatus.LEGACY_V02,
                "35aa2f861aacf8c0f16b3ebc69d9385c54852440beb5fd3eb880b5f409a0b25d",
                "fcce06f869bdfa843ea3155d04823155385fc74bc8d0c8ea9f18848ae0eaaa41",
                "e88151df43437aa43b269dbf9c53a8008bbb9d62d73cbaa710aef4fadd4bbc0d"})
            DefaultReleaseStatus.verifyBootStateForSource(prior,current,false,true);
        DefaultReleaseStatus.verifyBootStateForSource(current,current,true,false);
        reject(()->DefaultReleaseStatus.verifyBootStateForSource(current,current,false,false));
        reject(()->DefaultReleaseStatus.verifyBootStateForSource(current,current,true,true));
        reject(()->DefaultReleaseStatus.verifyBootStateForSource(null,current,false,false));
        reject(()->DefaultReleaseStatus.verifyBootStateForSource("unknown",current,true,false));
    }
    private static void reject(Runnable call){try{call.run();fail("Incomplete Default accepted");}catch(IllegalStateException expected){}}
}
