package io.github.breeder1229.rg477vunderclock;

import android.content.Context;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serialized management only; every clock lease remains in native Control Center. */
final class PowerBridge {
    private static PowerBridge instance;
    static synchronized PowerBridge get(Context context) {
        if(instance==null) instance=new PowerBridge(context.getApplicationContext());
        return instance;
    }
    private final Context context;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final AtomicBoolean managementBusy=new AtomicBoolean();
    private PowerBridge(Context context) {
        this.context=context;
    }
    boolean authorized() {
        return PlatformAccess.available(context);
    }
}
