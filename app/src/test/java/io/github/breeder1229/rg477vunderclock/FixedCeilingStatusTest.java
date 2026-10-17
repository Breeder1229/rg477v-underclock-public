package io.github.breeder1229.rg477vunderclock;

import org.junit.Test;
import static org.junit.Assert.*;

public final class FixedCeilingStatusTest {
    private static final long[] MEDIUM = {1800000, 2700000, 2790000};
    private static final long[] LARGE = {900000, 1200000, 1400000};

    @Test public void confirmedMediumAndCandidateLargeAreAccepted() {
        FixedCeilingStatus.verify(3, MEDIUM, 1029000000, 23, 1029000000, 0, true, false, false);
        FixedCeilingStatus.verify(2, LARGE, 601000000, 47, 601000000, 0, true, false, false);
    }

    @Test public void oldMediumRuntimeAndInconsistentReportsAreRejected() {
        reject(3, new long[]{1200000,1600000,1800000}, 1013000000, 24, 1013000000, 0, false, false, false);
        reject(3, MEDIUM, 965000000, 27, 980000000, 0, true, false, false);
        reject(3, MEDIUM, 1061000000, 21, 1029000000, 0, true, false, false);
        reject(3, MEDIUM, 1029000000, 24, 1029000000, 0, true, false, false);
        reject(3, MEDIUM, 1029000000, 23, 980000000, 0, true, false, false);
        reject(3, new long[]{1800000,2700000,3100000}, 1029000000, 23, 1029000000, 0, true, false, false);
    }

    @Test public void oldLargeAndCrossedProfilesAreRejected() {
        reject(2, LARGE, 443000000, 55, 443000000, 0, true, false, false);
        reject(2, LARGE, 601000000, 48, 601000000, 0, true, false, false);
        reject(2, LARGE, 601000000, 47, 600000000, 0, true, false, false);
        reject(2, MEDIUM, 1029000000, 23, 1029000000, 0, true, false, false);
        reject(3, LARGE, 601000000, 47, 601000000, 0, true, false, false);
    }

    @Test public void incompleteOrThermallyControlledReportsAreRejectedForBothProfiles() {
        for (int index : new int[]{2,3}) {
            long[] cpu = index == 2 ? LARGE : MEDIUM;
            long gpu = index == 2 ? 601000000 : 1029000000;
            int opp = index == 2 ? 47 : 23;
            reject(index, cpu, gpu, opp, gpu, 0, false, false, false);
            reject(index, cpu, gpu, opp, gpu, 3, true, false, false);
            reject(index, cpu, gpu, opp, gpu, 0, true, true, false);
            reject(index, cpu, gpu, opp, gpu, 0, true, false, true);
            reject(index, null, gpu, opp, gpu, 0, true, false, false);
            reject(index, new long[]{cpu[0],cpu[1]}, gpu, opp, gpu, 0, true, false, false);
        }
        reject(1, LARGE, 601000000, 47, 601000000, 0, true, false, false);
    }

    private static void reject(int index, long[] cpu, long gpu, int opp, long target, int stage,
                               boolean fixed, boolean thermal, boolean provisional) {
        try {
            FixedCeilingStatus.verify(index, cpu, gpu, opp, target, stage, fixed, thermal, provisional);
            fail("Stale or inconsistent fixed-ceiling response must not be acknowledged");
        } catch (IllegalStateException expected) { }
    }
}
