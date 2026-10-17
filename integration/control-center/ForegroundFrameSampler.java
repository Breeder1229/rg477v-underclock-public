package com.anbernic.control.underclock;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.view.Display;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Read-only native Binder diagnostics. No shell, global counter, enabling or clearing. */
public final class ForegroundFrameSampler implements AutoCloseable {
    private final ActivityManager activities;
    private final PresentedFrameRate rate=new PresentedFrameRate();
    private final Object lock=new Object();
    private boolean closed;
    private ParcelFileDescriptor[] activePipe;
    private IBinder compositor;
    public ForegroundFrameSampler(Context context){activities=context.getSystemService(ActivityManager.class);}
    public double sample() {
        try {
            String component=foreground();
            if(component==null){rate.reset();return Double.NaN;}
            if(compositor==null)compositor=(IBinder)Class.forName("android.os.ServiceManager")
                .getMethod("checkService",String.class).invoke(null,"SurfaceFlinger");
            if(compositor==null)throw new IOException("Compositor unavailable");
            String layer=PresentedFrameRate.selectLayer(dump("--list"),component);
            if(layer==null){rate.reset();return Double.NaN;}
            String raw=dump("--latency",layer);long now=System.nanoTime();
            if(!component.equals(foreground())){rate.reset();return Double.NaN;}
            return rate.sample(layer,raw,now);
        }catch(Exception unavailable){rate.reset();compositor=null;return Double.NaN;}
    }
    private String foreground()throws Exception {
        if(activities==null)return null;
        List<ActivityManager.RunningTaskInfo> tasks=activities.getRunningTasks(1);
        if(tasks==null||tasks.isEmpty())return null;
        if(ActivityManager.RunningTaskInfo.class.getField("displayId").getInt(tasks.get(0))!=Display.DEFAULT_DISPLAY)return null;
        ComponentName top=tasks.get(0).topActivity;
        return top==null?null:top.flattenToString();
    }
    private String dump(String... arguments)throws Exception {
        ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
        synchronized(lock) {
            if(closed){close(pipe);throw new IOException("Sampler closed");}
            activePipe=pipe;
        }
        try {
            // Async Binder + polled pipe prevents synchronous dump stalls from retaining workers on OFF.
            compositor.dumpAsync(pipe[1].getFileDescriptor(),arguments);pipe[1].close();
            long until=System.nanoTime()+500000000L;
            ByteArrayOutputStream data=new ByteArrayOutputStream();byte[] block=new byte[4096];
            StructPollfd descriptor=new StructPollfd();descriptor.fd=pipe[0].getFileDescriptor();descriptor.events=(short)OsConstants.POLLIN;
            while(true) {
                if(Thread.currentThread().isInterrupted())throw new IOException("Sampler cancelled");
                long remaining=until-System.nanoTime();if(remaining<=0)throw new IOException("Read timed out");
                int ready=Os.poll(new StructPollfd[]{descriptor},(int)Math.min(100,Math.max(1,remaining/1000000L)));
                if(ready==0)continue;
                int flags=descriptor.revents;
                if((flags&OsConstants.POLLIN)!=0) {
                    int count=Os.read(descriptor.fd,block,0,block.length);
                    if(count==0)break;
                    if(data.size()+count>262144)throw new IOException("Diagnostic size limit");
                    data.write(block,0,count);continue;
                }
                if((flags&OsConstants.POLLHUP)!=0)break;
                throw new IOException("Diagnostic pipe closed");
            }
            return new String(data.toByteArray(),StandardCharsets.UTF_8);
        }finally {
            synchronized(lock){if(activePipe==pipe)activePipe=null;}
            close(pipe);
        }
    }
    @Override public void close() {
        synchronized(lock){closed=true;close(activePipe);activePipe=null;}
    }
    private static void close(ParcelFileDescriptor[] pipe) {
        if(pipe!=null)for(ParcelFileDescriptor descriptor:pipe)try{descriptor.close();}catch(IOException ignored){}
    }
}
