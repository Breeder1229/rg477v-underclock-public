package io.github.breeder1229.rg477vunderclock;

/** A Default label alone is insufficient evidence that the native keeper stopped renewing. */
final class DefaultReleaseStatus {
    static void verifyNoBootRetry(boolean present,boolean pending) {
        if(!present||pending)throw new IllegalStateException("부팅 프로필 재시도가 중지되지 않아 복구를 중단합니다.");
    }
    static void verifyBootStateForSource(String verifiedSource,String currentSource,boolean present,boolean pending) {
        if(verifiedSource!=null)revisionForSource(verifiedSource);
        if(verifiedSource==null||currentSource.equals(verifiedSource))verifyNoBootRetry(present,pending);
    }
    static final String LEGACY_V02="43b345149548be671b963ff017487a5e8975f2b373a3abb17a5cc6aaa2ce0961";
    static int revisionForSource(String hash) {
        if(LEGACY_V02.equals(hash)
                ||"3964d7f8eb91a8ee4b46ae1add9aee4ea518edc978fa14e0924d36bcb6d1d1af".equals(hash)
                ||"22121559378d5cb8990c5f65ef9b7008a8e07b7a2fc3bc524b60139b17dad3e5".equals(hash)
                ||"45d50da8e0ad70fa0e45c8d54bacc3ac1ab5a8267937477b70c10a5f6a2d4a2a".equals(hash))return 1;
        if("434246eaec4f417d2ff1ed04c39063ec5ef0ffdf8a7fb0b1f28a9c72482a7ab0".equals(hash)
                ||"7132fe38bdc0fa56faa8442f9bb7fd07e52bc381a89ed419a608eecc44d9d81d".equals(hash)
                ||"f291059ccfc40031ae652af1e84043602c8190ed1f9da9c8b03b5d599d0ea1bd".equals(hash)
                ||"35aa2f861aacf8c0f16b3ebc69d9385c54852440beb5fd3eb880b5f409a0b25d".equals(hash)
                ||"fcce06f869bdfa843ea3155d04823155385fc74bc8d0c8ea9f18848ae0eaaa41".equals(hash)
                ||"e88151df43437aa43b269dbf9c53a8008bbb9d62d73cbaa710aef4fadd4bbc0d".equals(hash)
                ||"7278cfd9ea3e450f35c9e1563ef467049282eb845ea18852cd7335e59c199aec".equals(hash))return 2;
        throw new IllegalStateException("알 수 없는 native 응답 스키마이므로 복구를 중단합니다.");
    }
    static void verifySource(String hash,boolean integrated,int revision,int display,int handle,long[] cpu,
                             long gpu,long target,boolean oppCleared,int stage,boolean fixed,boolean thermal) {
        if(revision!=revisionForSource(hash))throw new IllegalStateException("native 원본과 응답 버전이 다릅니다.");
        if(LEGACY_V02.equals(hash)) {
            // Exact tag-v0.2 publish() has only these common request/release fields.
            // Its absence of the four newer fields is never generalized to other revision-one APKs.
            if(!integrated||display!=0||handle!=0||cpu==null||cpu.length!=3
                    ||cpu[0]!=0||cpu[1]!=0||cpu[2]!=0||gpu!=0||stage!=0)
                throw new IllegalStateException("v0.2 Default 해제 응답이 불완전합니다.");
        } else verify(integrated,revision,display,handle,cpu,gpu,target,oppCleared,stage,fixed,thermal);
    }
    static void verify(boolean integrated,int revision,int display,int handle,long[] cpu,
                       long gpu,long target,boolean oppCleared,int stage,boolean fixed,boolean thermal) {
        if(!integrated || (revision!=1 && revision!=2) || display!=0 || handle!=0
                || cpu==null || cpu.length!=3 || cpu[0]!=0 || cpu[1]!=0 || cpu[2]!=0
                || gpu!=0 || target!=0 || !oppCleared || stage!=0 || fixed || thermal)
            throw new IllegalStateException("Default 해제 응답이 불완전해 복구를 중단했습니다.");
    }
    private DefaultReleaseStatus() { }
}
