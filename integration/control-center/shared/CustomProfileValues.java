package com.anbernic.control.underclock.shared;

import java.util.Arrays;

/** Immutable maximum-clock requests for the pinned RG477V firmware. */
public final class CustomProfileValues {
    private static final int[][] OPTIONS = {
        {700000,800000,900000,1000000,1100000,1200000,1300000,1400000,1500000,1600000,1700000,1800000,1900000,2000000,2100000,2200000},
        {1000000,1100000,1200000,1300000,1400000,1500000,1600000,1700000,1800000,1900000,2000000,2100000,2200000,2300000,2400000,2500000,2600000,2660000,2800000,2850000,2900000,3000000,3100000,3200000},
        {1100000,1200000,1300000,1400000,1500000,1600000,1700000,1800000,1900000,2000000,2100000,2200000,2300000,2400000,2500000,2600000,2700000,2800000,2900000,3000000,3050000,3100000,3200000,3350000},
        {403000000,423000000,443000000,463000000,483000000,502000000,522000000,542000000,562000000,582000000,601000000,621000000,641000000,661000000,681000000,701000000,720000000,740000000,760000000,780000000,800000000,820000000,836000000,852000000,868000000,884000000,900000000,916000000,932000000,948000000,965000000,981000000,997000000,1013000000,1029000000,1045000000,1061000000,1077000000,1093000000,1110000000,1126000000,1142000000,1158000000,1174000000,1190000000,1206000000,1222000000,1238000000,1255000000,1271000000,1287000000,1303000000,1319000000,1335000000,1351000000,1367000000,1383000000,1400000000}
    };
    private static final int[] GPU_DESCENDING = {1400000000,1383000000,1367000000,1351000000,1335000000,1319000000,1303000000,1287000000,1271000000,1255000000,1238000000,1222000000,1206000000,1190000000,1174000000,1158000000,1142000000,1126000000,1110000000,1093000000,1077000000,1061000000,1045000000,1029000000,1013000000,997000000,981000000,965000000,948000000,932000000,916000000,900000000,884000000,868000000,852000000,836000000,820000000,800000000,780000000,760000000,740000000,720000000,701000000,681000000,661000000,641000000,621000000,601000000,582000000,562000000,542000000,522000000,502000000,483000000,463000000,443000000,423000000,403000000,383000000,364000000,344000000,324000000,304000000,284000000,265000000};
    private final int[] values;

    private CustomProfileValues(int[] row) { values = row.clone(); }

    public static CustomProfileValues of(int... row) {
        if (row == null || row.length != 4) throw new IllegalArgumentException("CPU 3개와 GPU 값이 필요합니다.");
        for (int i=0; i<4; i++) if (Arrays.binarySearch(OPTIONS[i], row[i]) < 0)
            throw new IllegalArgumentException("지원하지 않거나 선택 범위를 벗어난 주파수입니다.");
        return new CustomProfileValues(row);
    }

    public static CustomProfileValues small() { return of(2000000,3000000,3100000,1190000000); }
    public static int[] options(int component) {
        if (component<0 || component>3) throw new IllegalArgumentException("Unknown component");
        return OPTIONS[component].clone();
    }
    public int get(int component) { return values[component]; }
    public int[] toArray() { return values.clone(); }
    public int gpuOppIndex() {
        for(int i=0;i<GPU_DESCENDING.length;i++) if(GPU_DESCENDING[i]==values[3]) return i;
        throw new IllegalStateException("GPU support table mismatch");
    }
    public int[] resources() {
        return new int[]{0x00404000,values[0],0x00404100,values[1],0x00404200,values[2],0x00c04000,gpuOppIndex()};
    }
    public String encode() { return values[0]+","+values[1]+","+values[2]+","+values[3]; }
    public static CustomProfileValues decode(String encoded) {
        if(encoded==null || !encoded.matches("[0-9]{1,10}(,[0-9]{1,10}){3}"))
            throw new IllegalArgumentException("저장된 Custom 값의 형식을 확인할 수 없습니다.");
        String[] fields=encoded.split(",",-1);int[] row=new int[4];
        try { for(int i=0;i<4;i++) row[i]=Integer.parseInt(fields[i]); }
        catch(NumberFormatException failure) { throw new IllegalArgumentException("Custom 숫자 범위를 벗어났습니다.",failure); }
        return of(row);
    }
    @Override public boolean equals(Object other) {
        return other instanceof CustomProfileValues && Arrays.equals(values,((CustomProfileValues)other).values);
    }
    @Override public int hashCode() { return Arrays.hashCode(values); }
}
