package io.github.breeder1229.rg477vunderclock;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.ZipFile;


/** Installs only the embedded, hash-pinned Control Center update through the verified platform identity. */
final class ControlCenterInstaller {
    private static final String PACKAGE = "com.anbernic.control";
    private static final String SERVICE = "com.anbernic.control.service.ControlCenterService";
    private static final String ASSET = "control-center.apk";
    private static final String STOCK_ASSET = "stock-control-center.apk";
    private static final String CERTIFICATE = "c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8";
    private static final String STOCK_APK = "37b7cb60e393176c2208d1b9cf1666d14b9df89e9b8367d28eb0f4bddf85877a";
    // Exact embedded native payload from the published v0.2 verification record.
    // Allows a separate trial frontend to upgrade that reviewed predecessor.
    private static final String V02_CONTROL_CENTER = "43b345149548be671b963ff017487a5e8975f2b373a3abb17a5cc6aaa2ce0961";
    private static final String FIRST_MEDIUM_TRIAL = "3964d7f8eb91a8ee4b46ae1add9aee4ea518edc978fa14e0924d36bcb6d1d1af";
    private static final String SECOND_MEDIUM_TRIAL = "22121559378d5cb8990c5f65ef9b7008a8e07b7a2fc3bc524b60139b17dad3e5";
    private static final String V023_CONTROL_CENTER = "45d50da8e0ad70fa0e45c8d54bacc3ac1ab5a8267937477b70c10a5f6a2d4a2a";
    private static final String V030_CONTROL_CENTER = "35aa2f861aacf8c0f16b3ebc69d9385c54852440beb5fd3eb880b5f409a0b25d";
    private static final String V041_CONTROL_CENTER = "fcce06f869bdfa843ea3155d04823155385fc74bc8d0c8ea9f18848ae0eaaa41";
    private static final String V050_PREBOOT_CONTROL_CENTER = "e88151df43437aa43b269dbf9c53a8008bbb9d62d73cbaa710aef4fadd4bbc0d";
    private static final String V050_CONTROL_CENTER = "7278cfd9ea3e450f35c9e1563ef467049282eb845ea18852cd7335e59c199aec";
    private static final String JOURNAL = "control_center_install";
    private static final String V051_CONTROL_CENTER = "434246eaec4f417d2ff1ed04c39063ec5ef0ffdf8a7fb0b1f28a9c72482a7ab0";
    private static final String V051_ALIGNMENT_CANDIDATE_CONTROL_CENTER = "7132fe38bdc0fa56faa8442f9bb7fd07e52bc381a89ed419a608eecc44d9d81d";
    private static final String V051_UI_CANDIDATE_CONTROL_CENTER = "f291059ccfc40031ae652af1e84043602c8190ed1f9da9c8b03b5d599d0ea1bd";

    private static final int INFO_FLAGS = PackageManager.GET_SIGNING_CERTIFICATES;
    private static final long MAX_PAYLOAD_BYTES = 16L * 1024L * 1024L;

    private ControlCenterInstaller() { }

    static final class Result {
        final boolean alreadyInstalled;
        final boolean serviceStarted;
        final String message;

        Result(boolean alreadyInstalled, boolean serviceStarted) {
            this.alreadyInstalled = alreadyInstalled;
            this.serviceStarted = serviceStarted;
            message = serviceStarted
                    ? (alreadyInstalled ? "Control Center 연결 준비 완료" : "Control Center에 Under-clock 설치 완료")
                    : "Under-clock 설치 확인 완료 · Control Center를 열어 연결해 주세요.";
        }
    }

    /** Blocking read-only check. Call from the application's serial worker. */
    static boolean isInstalled(Context context, String expectedSha) {
        try {
            checkHash(expectedSha);
            checkFirmware();
            PackageInfo info = installedInfo(context);
            checkIdentity(info, true);
            return expectedSha.equals(fileHash(new File(info.applicationInfo.sourceDir)));
        } catch (Exception unavailable) {
            return false;
        }
    }

    static ControlCenterMode.Store modeStore(Context context) {
        final android.content.SharedPreferences prefs=context.getApplicationContext()
                .getSharedPreferences(JOURNAL,Context.MODE_PRIVATE);
        return new ControlCenterMode.Store() {
            @Override public String read() { return prefs.getString("desired_mode","INITIAL"); }
            @Override public boolean write(String value) { return prefs.edit().putString("desired_mode",value).commit(); }
        };
    }

    static ControlCenterMode.Mode mode(Context context) { return ControlCenterMode.read(modeStore(context)); }
    static boolean profileRequestsAllowed(Context context) { return ControlCenterMode.profileRequestsAllowed(modeStore(context)); }
    static void requireUnderclockMode(Context context) {
        if(!profileRequestsAllowed(context)) throw new IllegalStateException("복구 상태에서는 프로필을 적용하지 않습니다.");
    }
    static synchronized void reserveRestore(Context context) {
        ControlCenterMode.save(modeStore(context),ControlCenterMode.Mode.RESTORE_PENDING);
    }

    /** This monitor is shared with restore; queued auto work rechecks durable intent here. */
    static synchronized Result install(Context activityContext, String expectedSha, boolean explicit) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("설치는 백그라운드에서 실행해야 합니다.");
        ControlCenterMode.requireExplicitInstall(explicit);
        PlatformAccess.require(activityContext);checkFirmware();
        PackageSessionInstaller.reconcileBeforeMutation(activityContext);
        ControlCenterMode.Store store=modeStore(activityContext);
        ControlCenterMode.save(store,ControlCenterMode.Mode.INSTALL_PENDING);
        try {
            Result result=installInternal(activityContext,expectedSha);
            ControlCenterMode.save(store,ControlCenterMode.Mode.UNDERCLOCK);
            return result;
        } catch(Exception failure) {
            ControlCenterMode.failed(store,ControlCenterMode.Mode.INSTALL_FAILED,failure);
            throw failure;
        }
    }

    /** Blocking installation; caller supplies an Activity context while it is visible. */
    private static Result installInternal(Context activityContext, String expectedSha) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("설치는 백그라운드 작업에서 실행해야 합니다.");
        checkHash(expectedSha);
        checkFirmware();
        Context context = activityContext.getApplicationContext();
        if (isInstalled(context, expectedSha)) {
            recordVerified(context, expectedSha);
            PowerOverlayStop.allowAfterExplicitInstall(context);
            return new Result(true, startService(activityContext));
        }
        PlatformAccess.require(activityContext);

        PackageInfo before = installedInfo(context);
        checkIdentity(before, true);
        File currentApk = new File(before.applicationInfo.sourceDir);
        String currentHash = fileHash(currentApk);
        String previousOwned = context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE)
                .getString("verified_payload_sha", "");
        if (!isKnownSource(currentHash, previousOwned))
            throw new IllegalStateException("현재 Control Center가 확인한 순정 또는 이 앱의 설치본과 다릅니다.");

        File folder = new File(context.getNoBackupFilesDir(), "control-center-installer");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IllegalStateException("설치 임시 폴더를 만들 수 없습니다.");
        File payload = File.createTempFile("payload-", ".apk", folder);
        File transcript = File.createTempFile("result-", ".txt", folder);
        try {
            try (InputStream input = context.getAssets().open(ASSET);
                 FileOutputStream output = new FileOutputStream(payload)) {
                byte[] buffer = new byte[65536];
                long length = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    length += count;
                    if (length > MAX_PAYLOAD_BYTES) throw new IllegalStateException("설치 파일 크기 확인 실패");
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }
            if (payload.length() < 1024 || !expectedSha.equals(fileHash(payload)))
                throw new IllegalStateException("내장 Control Center 설치 파일 검증 실패");
            PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(payload.getAbsolutePath(), INFO_FLAGS);
            checkIdentity(archive, false);
            if (!Arrays.equals(manifestBytes(currentApk), manifestBytes(payload)))
                throw new IllegalStateException("순정 Control Center 권한·구성 보존 확인 실패");
            if (before.applicationInfo.targetSdkVersion != archive.applicationInfo.targetSdkVersion
                    || before.applicationInfo.minSdkVersion != archive.applicationInfo.minSdkVersion
                    || ((before.applicationInfo.flags ^ archive.applicationInfo.flags) & ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                throw new IllegalStateException("Control Center 호환성 확인 실패");

            // Recheck the target immediately before mutation; never overwrite a different update.
            PackageInfo recheck = installedInfo(context);
            checkIdentity(recheck, true);
            if (!currentHash.equals(fileHash(new File(recheck.applicationInfo.sourceDir))))
                throw new IllegalStateException("Control Center가 변경되어 설치를 중단했습니다.");
            installFromFileDescriptor(context, payload, expectedSha);
            boolean verified = false;
            for (int attempt = 0; attempt < 20; attempt++) {
                if (isInstalled(context, expectedSha)) { verified = true; break; }
                SystemClock.sleep(250);
            }
            if (!verified) throw new IllegalStateException("설치 후 Control Center 파일 검증 실패");
            recordVerified(context, expectedSha);
            PowerOverlayStop.allowAfterExplicitInstall(context);
            return new Result(false, startService(activityContext));
        } finally {
            // These are exact app-private random temporary files created by this operation.
            if (!payload.delete()) payload.deleteOnExit();
            if (!transcript.delete()) transcript.deleteOnExit();
        }
    }

    private static PackageInfo installedInfo(Context context) throws PackageManager.NameNotFoundException {
        return context.getPackageManager().getPackageInfo(PACKAGE, INFO_FLAGS);
    }

    static boolean isKnownSource(String currentHash, String previousOwned) {
        return STOCK_APK.equals(currentHash) || V02_CONTROL_CENTER.equals(currentHash)
                || FIRST_MEDIUM_TRIAL.equals(currentHash)
                || SECOND_MEDIUM_TRIAL.equals(currentHash)
                || V023_CONTROL_CENTER.equals(currentHash)
                || V030_CONTROL_CENTER.equals(currentHash)
                || V041_CONTROL_CENTER.equals(currentHash)
                || V050_CONTROL_CENTER.equals(currentHash)
                || V051_CONTROL_CENTER.equals(currentHash)
                || V051_ALIGNMENT_CANDIDATE_CONTROL_CENTER.equals(currentHash)
                || V051_UI_CANDIDATE_CONTROL_CENTER.equals(currentHash)
                || V050_PREBOOT_CONTROL_CENTER.equals(currentHash);
    }

    static boolean isStockInstalled(Context context) {
        try {
            checkFirmware();
            PackageInfo info=installedInfo(context); checkIdentity(info,true);
            return STOCK_APK.equals(fileHash(new File(info.applicationInfo.sourceDir)));
        } catch(Exception unavailable) { return false; }
    }

    /** Same-version replacement preserves the CC's data, including inactive Custom slots. */
    static synchronized String restore(Context activityContext, String expectedModifiedSha) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) throw new IllegalStateException("복구는 백그라운드에서 실행해야 합니다.");
        final Context context=activityContext.getApplicationContext();
        PlatformAccess.require(context);checkFirmware();
        PackageSessionInstaller.reconcileBeforeMutation(context);
        final ControlCenterMode.Store store=modeStore(context);
        if(ControlCenterMode.read(store)==ControlCenterMode.Mode.STOCK && isStockInstalled(context))
            return "순정 Control Center가 확인됐습니다. 자동 재설치는 중지돼 있습니다.";
        final File[] payload={null}, transcript={null};
        final String[] originalHash={null};
        try {
            StockRestoreTransaction.run(store,new StockRestoreTransaction.Actions() {
                @Override public boolean preflight() throws Exception {
                    checkHash(expectedModifiedSha); checkFirmware();
                    PackageInfo before=installedInfo(context); checkIdentity(before,true);
                    File current=new File(before.applicationInfo.sourceDir);
                    originalHash[0]=fileHash(current);
                    String owned=context.getSharedPreferences(JOURNAL,Context.MODE_PRIVATE).getString("verified_payload_sha","");
                    if(!expectedModifiedSha.equals(originalHash[0]) && !isKnownSource(originalHash[0],owned))
                        throw new IllegalStateException("알 수 없는 Control Center는 복구하지 않습니다.");
                    File folder=new File(context.getNoBackupFilesDir(),"control-center-installer");
                    if(!folder.isDirectory()&&!folder.mkdirs()) throw new IllegalStateException("복구 임시 폴더 생성 실패");
                    payload[0]=File.createTempFile("stock-",".apk",folder);
                    transcript[0]=File.createTempFile("restore-",".txt",folder);
                    try(InputStream input=context.getAssets().open(STOCK_ASSET); FileOutputStream output=new FileOutputStream(payload[0])) {
                        byte[] buffer=new byte[65536]; long size=0;
                        for(int n;(n=input.read(buffer))!=-1;) {
                            size+=n; if(size>MAX_PAYLOAD_BYTES)throw new IllegalStateException("순정 APK 크기 확인 실패");
                            output.write(buffer,0,n);
                        }
                        output.getFD().sync();
                    }
                    if(!STOCK_APK.equals(fileHash(payload[0])))throw new IllegalStateException("내장 순정 APK 해시 확인 실패");
                    PackageInfo archive=context.getPackageManager().getPackageArchiveInfo(payload[0].getAbsolutePath(),INFO_FLAGS);
                    checkIdentity(archive,false);
                    if(!Arrays.equals(manifestBytes(current),manifestBytes(payload[0])))
                        throw new IllegalStateException("순정 APK 권한·구성 보존 확인 실패");
                    boolean alreadyStock=STOCK_APK.equals(originalHash[0]);
                    PlatformAccess.require(context);
                    return alreadyStock;
                }
                @Override public void verifiedDefault() throws Exception {
                    PowerOverlayStop.beforeStockReplacement(context,originalHash[0]);
                    NativeControlCenter.requestDefaultForRestore(context,originalHash[0]);
                }
                @Override public void replaceWithStock() throws Exception {
                    PackageInfo current=installedInfo(context); checkIdentity(current,true);
                    if(!originalHash[0].equals(fileHash(new File(current.applicationInfo.sourceDir))))
                        throw new IllegalStateException("Control Center가 변경되어 복구를 중단했습니다.");
                    installFromFileDescriptor(context,payload[0],STOCK_APK);
                    for(int i=0;i<20 && !isStockInstalled(context);i++)Thread.sleep(250);
                }
                @Override public boolean stockVerified() { return isStockInstalled(context); }
                @Override public void waitForLeaseExpiry() throws InterruptedException { Thread.sleep(31000); }
            });
            startService(activityContext);
            return "순정 Control Center 복구 완료 · 잔여 임대 만료 대기 완료 · 자동 재설치 중지";
        } finally {
            if(payload[0]!=null&&!payload[0].delete())payload[0].deleteOnExit();
            if(transcript[0]!=null&&!transcript[0].delete())transcript[0].deleteOnExit();
        }
    }

    private static void recordVerified(Context context, String expectedSha) {
        if (!context.getSharedPreferences(JOURNAL, Context.MODE_PRIVATE).edit()
                .putString("verified_payload_sha", expectedSha).commit())
            throw new IllegalStateException("설치 확인 기록 저장 실패");
    }

    private static void checkHash(String expectedSha) {
        if (expectedSha == null || !expectedSha.matches("[0-9a-f]{64}") || !expectedSha.equals(BuildConfig.CONTROL_CENTER_SHA256))
            throw new IllegalArgumentException("내장 설치 파일의 고정 해시가 필요합니다.");
    }

    private static void checkFirmware() {
        if (Build.VERSION.SDK_INT != 34 || !MeasuredProfiles.FINGERPRINT.equals(Build.FINGERPRINT))
            throw new IllegalStateException("검증한 RG477V 펌웨어에서만 Control Center를 설치할 수 있습니다.");
    }

    private static void checkIdentity(PackageInfo info, boolean installed) throws Exception {
        // Each public entry already pins SDK 34; keep the API boundary explicit
        // here as well because signingInfo and long version codes require API 28.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            throw new IllegalStateException("Control Center 서명 확인에는 Android 9 이상이 필요합니다.");
        if (info == null || !PACKAGE.equals(info.packageName) || info.getLongVersionCode() != 9
                || !"1.0.29".equals(info.versionName) || !"android.uid.system".equals(info.sharedUserId)
                || info.applicationInfo == null || info.applicationInfo.targetSdkVersion != 34
                || info.signingInfo == null || info.signingInfo.hasMultipleSigners())
            throw new IllegalStateException("순정 Control Center 패키지·버전 확인 실패");
        Signature[] signers = info.signingInfo.getApkContentsSigners();
        if (signers.length != 1 || !CERTIFICATE.equals(hash(signers[0].toByteArray())))
            throw new IllegalStateException("순정 Control Center 서명 확인 실패");
        if (installed && (info.applicationInfo.uid != 1000
                || (info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0))
            throw new IllegalStateException("순정 Control Center 시스템 앱 확인 실패");
    }

    private static byte[] manifestBytes(File apk) throws Exception {
        try (ZipFile zip = new ZipFile(apk); InputStream input = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))) {
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) {
                if (output.size() + count > 256 * 1024) throw new IllegalStateException("매니페스트 크기 확인 실패");
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static void installFromFileDescriptor(Context context,File payload,String expectedHash)throws Exception {
        PackageSessionInstaller.install(context,payload,expectedHash);
    }

    private static boolean startService(Context activityContext) {
        try {
            // Stock service does not call startForeground; retain its original
            // ordinary service lifecycle instead of imposing a five-second FGS deadline.
            Intent intent = new Intent().setComponent(new ComponentName(PACKAGE, SERVICE));
            return activityContext.startService(intent) != null;
        } catch (RuntimeException unavailable) {
            android.util.Log.w("RGClock", "Installed Control Center service start unavailable", unavailable);
            return false;
        }
    }

    private static String fileHash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            for (int count; (count = input.read(buffer)) != -1;) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hash(byte[] bytes) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray(), result = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            result[i * 2] = alphabet[(bytes[i] & 255) >>> 4];
            result[i * 2 + 1] = alphabet[bytes[i] & 15];
        }
        return new String(result);
    }
}
