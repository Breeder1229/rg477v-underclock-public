package io.github.breeder1229.rg477vunderclock;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.security.MessageDigest;

/** Pinned platform identity; never silently falls back to another privilege backend. */
final class PlatformAccess {
    static final String CERTIFICATE="c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    static void require(Context context) {
        try {
            if(android.os.Process.myUid()!=1000||android.os.Build.VERSION.SDK_INT!=34)
                throw new IllegalStateException("지원하는 Android 14 시스템 권한 환경이 아닙니다.");
            PackageManager pm=context.getPackageManager();
            PackageInfo info=pm.getPackageInfo(context.getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
            if(!"android.uid.system".equals(info.sharedUserId)||info.signingInfo==null
                    ||info.signingInfo.hasMultipleSigners()||info.signingInfo.getApkContentsSigners().length!=1
                    ||pm.checkSignatures("android",context.getPackageName())!=PackageManager.SIGNATURE_MATCH)
                throw new IllegalStateException("플랫폼 서명과 시스템 UID를 확인할 수 없습니다.");
            StringBuilder hash=new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray()))
                hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
            if(!CERTIFICATE.contentEquals(hash))throw new IllegalStateException("지원하는 플랫폼 인증서가 아닙니다.");
            for(String permission:new String[]{"android.permission.INSTALL_PACKAGES","android.permission.WRITE_SETTINGS"})
                if(context.checkSelfPermission(permission)!=PackageManager.PERMISSION_GRANTED)
                    throw new IllegalStateException("설치·설정 권한을 확인할 수 없습니다.");
        }catch(IllegalStateException failure){throw failure;}
        catch(Exception failure){throw new IllegalStateException("시스템 권한 확인 실패",failure);}
    }
    static boolean available(Context context){try{require(context);return true;}catch(RuntimeException failure){return false;}}
    private PlatformAccess() { }
}
