package io.github.breeder1229.rg477vunderclock;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
/** Explicit PendingIntent target; no exported command endpoint. */
public final class InstallResultReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){PackageSessionInstaller.receive(context,intent);}
}
