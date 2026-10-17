package com.anbernic.control.underclock;

/** Maximum-only resources; Medium is confirmed and Large is a fixed-ceiling trial. */
public final class MeasuredProfiles {
    public static final String FINGERPRINT = "alps/vext_k6897v1_64/k6897v1_64:14/UP1A.231005.007/V654202605290319:user/test-keys";
    public static final String[] KEYS = {"default", "efficient", "battery", "sustained"};
    public static final String[] NAMES = {"기본 · 순정 복원", "Small · 성능 유지", "Large · 고정 상한 시험", "Medium · 확정 상한"};
    private static final int[][] CAPS = {{2200000,3200000,3350000,0}, {2000000,3000000,3100000,13},
            {900000,1200000,1400000,47}, {1800000,2700000,2790000,23}};
    // The pinned device confirms OPP 23 / 1029 MHz and OPP 47 / 601 MHz.
    public static final boolean GPU_OPP_DEVICE_VERIFIED = true;
    private static final int[] GPU_HZ = {1400000000,1190000000,601000000,1029000000};
    private static final int MEDIUM_GPU_TARGET_HZ = 1029000000;
    private MeasuredProfiles() { }
    public static void checkFan(int fan) {
        if(fan!=1 && fan!=2) throw new IllegalArgumentException("Unknown fan set");
    }
    public static String fanName(int fan) { checkFan(fan); return fan==1 ? "Bal" : "Turbo"; }
    public static boolean fixedCeiling(int index) {
        caps(index,0);
        return index==2 || index==3;
    }
    public static int index(String key) {
        for (int i=0;i<KEYS.length;i++) if (KEYS[i].equals(key)) return i;
        throw new IllegalArgumentException("Unknown profile");
    }
    public static int[] resources(int index) {
        return resources(index,0);
    }
    public static int[] caps(int index,int stage) {
        return caps(index,stage,1);
    }
    public static int[] caps(int index,int stage,int fan) {
        checkFan(fan);
        if(index<0 || index>=CAPS.length || stage<0 || stage>3) throw new IllegalArgumentException("Invalid profile stage");
        return CAPS[index].clone();
    }
    public static int gpuHz(int index,int stage) {
        return gpuHz(index,stage,1);
    }
    public static int gpuHz(int index,int stage,int fan) {
        caps(index,stage,fan);
        return GPU_HZ[index];
    }
    /** User target, distinct from the nominal frequency associated with the requested OPP. */
    public static int gpuTargetHz(int index,int stage) {
        return gpuTargetHz(index,stage,1);
    }
    public static int gpuTargetHz(int index,int stage,int fan) {
        caps(index,stage,fan);
        return index==3 ? MEDIUM_GPU_TARGET_HZ : GPU_HZ[index];
    }
    public static int[] resources(int index,int stage) {
        return resources(index,stage,1);
    }
    public static int[] resources(int index,int stage,int fan) {
        if (index < 1 || index >= CAPS.length) throw new IllegalArgumentException("A custom profile is required");
        int[] row = caps(index,stage,fan);
        return new int[]{0x00404000,row[0],0x00404100,row[1],0x00404200,row[2],0x00c04000,row[3]};
    }
    public static int thermalStage(int previous,float chip,float skin) {
        if(previous<0 || previous>3) throw new IllegalArgumentException("Invalid previous stage");
        // Temperatures are observation-only in this trial. OEM protection remains active.
        return 0;
    }
    public static String values(int index) {
        return values(index,1);
    }
    public static String values(int index,int fan) {
        checkFan(fan);
        if(index==0) return "앱의 임시 제한을 해제하고 순정 성능 모드에 맡깁니다.";
        int[] row=caps(index,0,fan);
        if(fixedCeiling(index)) return (index==2 ? "Large 고정 상한 시험" : "Medium 확정 고정 상한") + " · CPU 요청 " + row[0]/1000 + " / " + row[1]/1000 + " / " + row[2]/1000
                + " MHz · GPU 목표 " + gpuTargetHz(index,0,fan)/1000000 + " MHz → 요청 OPP " + row[3]
                + " (" + gpuHz(index,0,fan)/1000000 + " MHz, 실기 지원 표 확인)";
        return "CPU " + row[0]/1000 + " / " + row[1]/1000 + " / " + row[2]/1000
                + " MHz · GPU " + gpuHz(index,0,fan)/1000000 + " MHz";
    }
}
