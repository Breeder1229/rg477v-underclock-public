package com.anbernic.control.underclock;

/** One bounded bootstrap epoch; manual requests permanently cancel its queued work. */
public final class BootRestorePolicy {
    public interface Actions {void attempt();void waitFor(long delay);void expired();}
    public static final int MAX_ATTEMPTS=12;
    public static final long WINDOW_MS=60000,INTERVAL_MS=5000;
    private final int selected;
    private final long started;
    private long epoch=1,nextAllowed;
    private int attempts;
    private boolean pending=true;
    public BootRestorePolicy(int selected,long now) {
        if(selected<0||selected>7||now<0)throw new IllegalArgumentException("Invalid bootstrap state");
        this.selected=selected;started=nextAllowed=now;
    }
    public int selected(){return selected;}
    public int attempts(){return attempts;}
    public long epoch(){return epoch;}
    public boolean pending(){return pending;}
    /** Negative means exhausted/cancelled; events may not bypass the interval. */
    public long delay(long ticket,long now) {
        if(!pending||ticket!=epoch||now<started||now-started>=WINDOW_MS||attempts>=MAX_ATTEMPTS)return -1;
        long delay=Math.max(0,nextAllowed-now);
        return now-started+delay>=WINDOW_MS?-1:delay;
    }
    public boolean begin(long ticket,long now) {
        if(delay(ticket,now)!=0)return false;
        attempts++;nextAllowed=now+INTERVAL_MS;return true;
    }
    public void cancel(){pending=false;epoch++;}
    public void complete(long ticket){if(pending&&ticket==epoch)cancel();}
    /** Shared runtime dispatcher: a delayed callback terminates instead of staying pending. */
    public void dispatch(long ticket,long now,Actions actions) {
        if(!pending||ticket!=epoch)return;
        long delay=delay(ticket,now);
        if(delay<0){cancel();actions.expired();}
        else if(delay>0)actions.waitFor(delay);
        else if(begin(ticket,now))actions.attempt();
    }
    public static boolean retryable(String type,String message) {
        if("android.os.DeadObjectException".equals(type)||"android.os.RemoteException".equals(type))return true;
        return "java.lang.IllegalStateException".equals(type)
            &&("PowerHAL not ready".equals(message)||"PowerHAL request failed".equals(message));
    }
}
