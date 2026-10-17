package io.github.breeder1229.rg477vunderclock;

/** Rejects stale or inconsistent acknowledgements for the fixed Medium and Large caps. */
final class FixedCeilingStatus {
    static void verify(int index, long[] cpu, long gpuHz, int gpuOpp, long gpuTargetHz,
                       int stage, boolean fixedTrial, boolean thermalControl, boolean provisional) {
        if (index != 2 && index != 3) throw new IllegalStateException("고정 상한 프로필 확인 실패");
        int[] expected = MeasuredProfiles.caps(index, 0, 1);
        if (!fixedTrial || thermalControl || provisional || stage != 0 || cpu == null || cpu.length != 3
                || cpu[0] != expected[0] || cpu[1] != expected[1] || cpu[2] != expected[2]
                || gpuOpp != expected[3] || gpuHz != MeasuredProfiles.gpuHz(index, 0, 1)
                || gpuTargetHz != MeasuredProfiles.gpuTargetHz(index, 0, 1)) {
            throw new IllegalStateException("실제 요청 상한 응답이 현재 고정 상한 프로필과 일치하지 않습니다.");
        }
    }

    private FixedCeilingStatus() {}
}
