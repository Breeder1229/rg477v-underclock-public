package io.github.breeder1229.rg477vunderclock;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.SystemClock;
import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Verified CC payloads only. No shell transaction, process or external payload path. */
final class PackageSessionInstaller {
    private static final String PACKAGE="com.anbernic.control",PREFS="cc_package_session",TOKEN="operation_token";
    private static final Object LOCK=new Object();
    private static SharedPreferences prefs(Context c){return c.getSharedPreferences(PREFS,Context.MODE_PRIVATE);}
    private static InstallSessionState read(SharedPreferences p){return new InstallSessionState(p.getInt("session",-1),p.getString(TOKEN,""),p.getInt("result",InstallSessionState.WAITING),p.getBoolean("timed_out",false));}
    private static void commit(SharedPreferences.Editor e){if(!e.commit())throw new IllegalStateException("설치 상태 저장 실패 · 작업을 중단합니다.");}
    static void receive(Context context,Intent intent) {
        if(intent==null)return;
        synchronized(LOCK) {
            SharedPreferences p=prefs(context);InstallSessionState before=read(p);
            int id=intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID,-1);
            String token=intent.getStringExtra(TOKEN);
            if(!p.getBoolean("submitted",false)||!before.accepts(id,token))return;
            int result=intent.getIntExtra(PackageInstaller.EXTRA_STATUS,PackageInstaller.STATUS_FAILURE);
            try {commit(p.edit().putInt("result",before.result(id,token,result).result)
                    .putString("message",intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)));}
            catch(RuntimeException failure) {
                // A failed disk commit can still update SharedPreferences memory.
                p.edit().putInt("result",InstallSessionState.WAITING).putBoolean("timed_out",true).commit();
                android.util.Log.e("RGClock","Install callback state could not be saved",failure);
            }
        }
    }
    private static boolean own(PackageInstaller.SessionInfo info,Context c) {
        return InstallSessionOwner.matches(info.getInstallerPackageName(),info.getAppPackageName(),c.getPackageName());
    }
    /** A previous process or timeout must not race another Default/install/restore. */
    static void reconcileBeforeMutation(Context context)throws Exception {
        PlatformAccess.require(context);
        int id;synchronized(LOCK){id=read(prefs(context)).session;}
        PackageInstaller installer=context.getPackageManager().getPackageInstaller();
        java.util.Set<Integer> previous=new java.util.LinkedHashSet<>();
        if(id>=0)previous.add(id);
        // Covers process death between createSession() and durable journal creation.
        for(PackageInstaller.SessionInfo candidate:installer.getMySessions())
            if(own(candidate,context))previous.add(candidate.getSessionId());
        for(int previousId:previous) {
            PackageInstaller.SessionInfo info=installer.getSessionInfo(previousId);
            if(info==null)continue;
            if(!own(info,context))throw new IllegalStateException("설치 세션 소유자를 확인할 수 없습니다.");
            try{installer.abandonSession(previousId);}catch(RuntimeException cannotAbandon){android.util.Log.w("RGClock","Previous session has not closed",cannotAbandon);}
            for(int i=0;i<10&&installer.getSessionInfo(previousId)!=null;i++)Thread.sleep(100);
            if(installer.getSessionInfo(previousId)!=null)throw new IllegalStateException("이전 설치의 종료를 확인 중입니다. 잠시 뒤 다시 시도해 주세요.");
        }
        synchronized(LOCK){if(id>=0&&read(prefs(context)).session==id)commit(prefs(context).edit().clear());}
    }
    static void install(Context supplied,File payload,String expectedHash)throws Exception {
        Context context=supplied.getApplicationContext();PlatformAccess.require(context);
        if(!BuildConfig.CONTROL_CENTER_SHA256.equals(expectedHash)
                &&!"37b7cb60e393176c2208d1b9cf1666d14b9df89e9b8367d28eb0f4bddf85877a".equals(expectedHash))
            throw new IllegalStateException("검증된 내장 APK만 설치할 수 있습니다.");
        long size=payload.length();
        if(size<1024||size>16L*1024L*1024L)throw new IllegalStateException("설치 파일 크기 확인 실패");
        reconcileBeforeMutation(context);
        PackageInstaller installer=context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params=new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(PACKAGE);params.setSize(size);
        if(android.os.Build.VERSION.SDK_INT>=31)params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id=installer.createSession(params);String token=UUID.randomUUID().toString();
        PendingIntent callback=null;boolean successful=false;
        try {
            synchronized(LOCK) {
                if(read(prefs(context)).session>=0)throw new IllegalStateException("다른 설치 작업이 남아 있습니다.");
                commit(prefs(context).edit().clear().putInt("session",id).putString(TOKEN,token)
                    .putString("expected_sha256",expectedHash).putInt("result",InstallSessionState.WAITING).putBoolean("submitted",false));
            }
            try(PackageInstaller.Session session=installer.openSession(id)) {
                try(FileInputStream input=new FileInputStream(payload);OutputStream output=session.openWrite("base.apk",0,size)) {
                    java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                    long copied=0;
                    byte[] buffer=new byte[65536];for(int n;(n=input.read(buffer))!=-1;) {
                        copied+=n;if(copied>size)throw new IllegalStateException("설치 중 APK 크기가 변경됐습니다.");
                        digest.update(buffer,0,n);output.write(buffer,0,n);
                    }
                    StringBuilder hash=new StringBuilder();
                    for(byte b:digest.digest())hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
                    if(copied!=size||!expectedHash.contentEquals(hash))throw new IllegalStateException("설치 중 APK 내용이 변경됐습니다.");
                    session.fsync(output);
                }
                Intent target=new Intent(context,InstallResultReceiver.class).setAction(context.getPackageName()+".INSTALL."+token).putExtra(TOKEN,token);
                int flags=PendingIntent.FLAG_UPDATE_CURRENT;
                if(android.os.Build.VERSION.SDK_INT>=31)flags|=PendingIntent.FLAG_MUTABLE;
                callback=PendingIntent.getBroadcast(context,id,target,flags);
                synchronized(LOCK){commit(prefs(context).edit().putBoolean("submitted",true));}
                session.commit(callback.getIntentSender());
            }
            long until=SystemClock.elapsedRealtime()+45000;
            while(SystemClock.elapsedRealtime()<until) {
                InstallSessionState state;synchronized(LOCK){state=read(prefs(context));}
                if(state.session!=id||!token.equals(state.token))throw new IllegalStateException("설치 세션 상태가 변경됐습니다.");
                if(state.timedOut)throw new IllegalStateException("설치 결과 저장을 확인할 수 없습니다.");
                if(state.result==PackageInstaller.STATUS_SUCCESS){successful=true;return;}
                if(state.result==PackageInstaller.STATUS_PENDING_USER_ACTION)
                    throw new IllegalStateException("기기에서 추가 설치 확인을 요구해 작업을 중단했습니다.");
                if(state.result!=InstallSessionState.WAITING)throw new IllegalStateException("Android 설치 실패 ("+state.result+")");
                Thread.sleep(100);
            }
            synchronized(LOCK){commit(prefs(context).edit().putBoolean("timed_out",true));}
            throw new IllegalStateException("설치 응답 시간이 초과했습니다. 종료 확인 후 다시 시도해 주세요.");
        }finally {
            if(!successful) {
                synchronized(LOCK){if(read(prefs(context)).session==id)try{commit(prefs(context).edit().putBoolean("timed_out",true));}catch(RuntimeException failure){android.util.Log.e("RGClock","Session stop state save failed",failure);}}
                try{installer.abandonSession(id);}catch(RuntimeException failure){android.util.Log.w("RGClock","Session termination pending",failure);}
            }
            // A live timed-out session stays recorded and blocks the next mutation.
            if(installer.getSessionInfo(id)==null)synchronized(LOCK){if(read(prefs(context)).session==id)commit(prefs(context).edit().clear());}
            if(callback!=null)callback.cancel();
        }
    }
    private PackageSessionInstaller() { }
}
