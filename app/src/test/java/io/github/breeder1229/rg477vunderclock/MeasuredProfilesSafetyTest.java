package io.github.breeder1229.rg477vunderclock;

import org.junit.Test;
import static org.junit.Assert.*;
import java.util.Arrays;

public final class MeasuredProfilesSafetyTest {
    private static final int MEDIUM=3;
    private static final int[] MEDIUM_CAPS={1800000,2700000,2790000,23};
    private static final int[] MEDIUM_RESOURCES={0x00404000,1800000,0x00404100,2700000,0x00404200,2790000,0x00c04000,23};

    @Test public void everyProfileRequestsOnlyCeilingsWithinTheMeasuredHardware() {
        int[] max={2200000,3200000,3350000}, min={480000,400000,400000};
        int[] allowed={0x00404000,0x00404100,0x00404200,0x00c04000};
        for(int fan=1;fan<=2;fan++) for(int profile=1;profile<4;profile++) for(int stage=0;stage<4;stage++) {
            int[] request=MeasuredProfiles.resources(profile,stage,fan);
            assertEquals(8,request.length);
            for(int i=0;i<4;i++) assertEquals(allowed[i],request[i*2]);
            for(int i=0;i<3;i++) { assertTrue(request[i*2+1]>=min[i]); assertTrue(request[i*2+1]<=max[i]); }
            assertTrue(request[7]>=0); assertTrue(request[7]<65);
        }
    }

    @Test public void mediumRequestsTheSameCapsAcrossEveryStageAndFanSet() {
        assertEquals(MEDIUM,MeasuredProfiles.index("sustained"));
        assertEquals(MEDIUM,com.anbernic.control.underclock.MeasuredProfiles.index("sustained"));
        for(int fan=1;fan<=2;fan++) for(int stage=0;stage<4;stage++) {
            assertArrayEquals(MEDIUM_CAPS,MeasuredProfiles.caps(MEDIUM,stage,fan));
            assertArrayEquals(MEDIUM_RESOURCES,MeasuredProfiles.resources(MEDIUM,stage,fan));
            assertEquals(1029000000,MeasuredProfiles.gpuHz(MEDIUM,stage,fan));
            assertArrayEquals(MEDIUM_CAPS,com.anbernic.control.underclock.MeasuredProfiles.caps(MEDIUM,stage,fan));
            assertArrayEquals(MEDIUM_RESOURCES,com.anbernic.control.underclock.MeasuredProfiles.resources(MEDIUM,stage,fan));
        }
    }

    @Test public void verifiedGpuOppExactlyMatchesThe1029MHzTarget() {
        assertTrue(MeasuredProfiles.GPU_OPP_DEVICE_VERIFIED);
        assertTrue(com.anbernic.control.underclock.MeasuredProfiles.GPU_OPP_DEVICE_VERIFIED);
        for(int fan=1;fan<=2;fan++) for(int stage=0;stage<4;stage++) {
            assertEquals(1029000000,MeasuredProfiles.gpuTargetHz(MEDIUM,stage,fan));
            assertEquals(23,MeasuredProfiles.caps(MEDIUM,stage,fan)[3]);
            assertEquals(1029000000,MeasuredProfiles.gpuHz(MEDIUM,stage,fan));
            assertEquals(MeasuredProfiles.gpuHz(MEDIUM,stage,fan),MeasuredProfiles.gpuTargetHz(MEDIUM,stage,fan));
        }
        String description=MeasuredProfiles.values(MEDIUM);
        assertTrue(description.contains("확정 고정 상한"));
        assertTrue(description.contains("CPU 요청 1800 / 2700 / 2790"));
        assertTrue(description.contains("GPU 목표 1029 MHz"));
        assertTrue(description.contains("요청 OPP 23"));
        assertTrue(description.contains("1029 MHz, 실기 지원 표 확인"));
    }

    @Test public void temperatureAndSensorFailuresHaveNoEffectOnTheFixedMediumRequest() {
        float[] readings={Float.NaN,Float.NEGATIVE_INFINITY,-20,0,40,60,70,78,82,130,Float.POSITIVE_INFINITY};
        for(int previous=0;previous<4;previous++) for(float chip:readings) for(float skin:readings) {
            int stage=MeasuredProfiles.thermalStage(previous,chip,skin);
            int integratedStage=com.anbernic.control.underclock.MeasuredProfiles.thermalStage(previous,chip,skin);
            assertEquals(0,stage); assertEquals(0,integratedStage);
            for(int fan=1;fan<=2;fan++) {
                assertArrayEquals(MEDIUM_RESOURCES,MeasuredProfiles.resources(MEDIUM,stage,fan));
                assertArrayEquals(MEDIUM_RESOURCES,com.anbernic.control.underclock.MeasuredProfiles.resources(MEDIUM,integratedStage,fan));
            }
        }
    }

    @Test public void defaultAndSmallStayUnchangedWhileLargeUsesThe601MHzCandidate() {
        int[][] expected={{2200000,3200000,3350000,0},{2000000,3000000,3100000,13},{900000,1200000,1400000,47}};
        int[] gpu={1400000000,1190000000,601000000};
        assertArrayEquals(new String[]{"기본 · 순정 복원","Small · 성능 유지","Large · 고정 상한 시험"},Arrays.copyOf(MeasuredProfiles.NAMES,3));
        for(int index=0;index<3;index++) for(int fan=1;fan<=2;fan++) for(int stage=0;stage<4;stage++) {
            assertArrayEquals(expected[index],MeasuredProfiles.caps(index,stage,fan));
            assertArrayEquals(expected[index],com.anbernic.control.underclock.MeasuredProfiles.caps(index,stage,fan));
            assertEquals(gpu[index],MeasuredProfiles.gpuHz(index,stage,fan));
            assertEquals(gpu[index],MeasuredProfiles.gpuTargetHz(index,stage,fan));
            if(index>0) {
                int[] resources={0x00404000,expected[index][0],0x00404100,expected[index][1],0x00404200,expected[index][2],0x00c04000,expected[index][3]};
                assertArrayEquals(resources,MeasuredProfiles.resources(index,stage,fan));
                assertArrayEquals(resources,com.anbernic.control.underclock.MeasuredProfiles.resources(index,stage,fan));
            }
        }
    }

    @Test public void bothPackagesExposeIdenticalCatalogsAndRequests() {
        assertEquals(MeasuredProfiles.FINGERPRINT,com.anbernic.control.underclock.MeasuredProfiles.FINGERPRINT);
        assertArrayEquals(MeasuredProfiles.KEYS,com.anbernic.control.underclock.MeasuredProfiles.KEYS);
        assertArrayEquals(MeasuredProfiles.NAMES,com.anbernic.control.underclock.MeasuredProfiles.NAMES);
        for(int index=0;index<4;index++) for(int fan=1;fan<=2;fan++) {
            assertEquals(MeasuredProfiles.values(index,fan),com.anbernic.control.underclock.MeasuredProfiles.values(index,fan));
            assertEquals(index==2||index==3,MeasuredProfiles.fixedCeiling(index));
            assertEquals(MeasuredProfiles.fixedCeiling(index),com.anbernic.control.underclock.MeasuredProfiles.fixedCeiling(index));
            for(int stage=0;stage<4;stage++) {
                assertArrayEquals(MeasuredProfiles.caps(index,stage,fan),com.anbernic.control.underclock.MeasuredProfiles.caps(index,stage,fan));
                assertEquals(MeasuredProfiles.gpuHz(index,stage,fan),com.anbernic.control.underclock.MeasuredProfiles.gpuHz(index,stage,fan));
                assertEquals(MeasuredProfiles.gpuTargetHz(index,stage,fan),com.anbernic.control.underclock.MeasuredProfiles.gpuTargetHz(index,stage,fan));
                if(index>0) assertArrayEquals(MeasuredProfiles.resources(index,stage,fan),com.anbernic.control.underclock.MeasuredProfiles.resources(index,stage,fan));
            }
        }
    }

    @Test public void returnedArraysCannotMutateAnyLaterRequestInEitherPackage() {
        for(int index=0;index<4;index++) for(int fan=1;fan<=2;fan++) for(int stage=0;stage<4;stage++) {
            int[] expected=MeasuredProfiles.caps(index,stage,fan);
            int[] app=MeasuredProfiles.caps(index,stage,fan), integrated=com.anbernic.control.underclock.MeasuredProfiles.caps(index,stage,fan);
            Arrays.fill(app,Integer.MAX_VALUE); Arrays.fill(integrated,Integer.MIN_VALUE);
            assertArrayEquals(expected,MeasuredProfiles.caps(index,stage,fan));
            assertArrayEquals(expected,com.anbernic.control.underclock.MeasuredProfiles.caps(index,stage,fan));
            if(index>0) {
                expected=MeasuredProfiles.resources(index,stage,fan);
                app=MeasuredProfiles.resources(index,stage,fan); integrated=com.anbernic.control.underclock.MeasuredProfiles.resources(index,stage,fan);
                Arrays.fill(app,Integer.MAX_VALUE); Arrays.fill(integrated,Integer.MIN_VALUE);
                assertArrayEquals(expected,MeasuredProfiles.resources(index,stage,fan));
                assertArrayEquals(expected,com.anbernic.control.underclock.MeasuredProfiles.resources(index,stage,fan));
            }
        }
    }

    @Test public void largeRemainsBelowFixedMediumInBothFanSets() {
        for(int fan=1;fan<=2;fan++) for(int stage=0;stage<4;stage++) {
            int[] medium=MeasuredProfiles.caps(MEDIUM,stage,fan), large=MeasuredProfiles.caps(2,0,fan);
            for(int i=0;i<3;i++) assertTrue(large[i]<medium[i]);
            assertTrue(MeasuredProfiles.gpuHz(2,0,fan)<MeasuredProfiles.gpuHz(MEDIUM,stage,fan));
        }
    }

    @Test public void invalidInputsStillFailClosedInBothPackages() {
        rejects(() -> MeasuredProfiles.resources(0)); rejects(() -> com.anbernic.control.underclock.MeasuredProfiles.resources(0));
        rejects(() -> MeasuredProfiles.index("other; arbitrary resource")); rejects(() -> com.anbernic.control.underclock.MeasuredProfiles.index("other; arbitrary resource"));
        rejects(() -> MeasuredProfiles.resources(MEDIUM,0,3)); rejects(() -> com.anbernic.control.underclock.MeasuredProfiles.resources(MEDIUM,0,3));
        rejects(() -> MeasuredProfiles.caps(MEDIUM,4,1)); rejects(() -> com.anbernic.control.underclock.MeasuredProfiles.caps(MEDIUM,4,1));
        rejects(() -> MeasuredProfiles.gpuTargetHz(-1,0,1)); rejects(() -> com.anbernic.control.underclock.MeasuredProfiles.gpuTargetHz(-1,0,1));
        rejects(() -> MeasuredProfiles.thermalStage(4,40,40)); rejects(() -> com.anbernic.control.underclock.MeasuredProfiles.thermalStage(4,40,40));
    }

    private static void rejects(Runnable call) {
        try { call.run(); fail("Expected an invalid request to be rejected"); }
        catch(IllegalArgumentException expected) { }
    }
}
