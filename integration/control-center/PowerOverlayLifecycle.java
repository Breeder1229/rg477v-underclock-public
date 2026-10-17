package com.anbernic.control.underclock;

/** Serialized on the native main looper; tested without Android window/receiver stubs. */
public final class PowerOverlayLifecycle {
    public interface Host { void listen(); void unlisten(); void resume(); void pause(); }
    private final Host host;
    private boolean enabled,active,interactive;
    public PowerOverlayLifecycle(Host host,boolean interactive){this.host=host;this.interactive=interactive;}
    public boolean enabled(){return enabled;}
    public boolean active(){return active;}
    public void setEnabled(boolean value) {
        if(enabled==value)return;
        enabled=value;
        if(!value){try{deactivate();}finally{host.unlisten();}return;}
        try{host.listen();if(interactive)activate();}
        catch(RuntimeException failure){setEnabled(false);throw failure;}
    }
    public void setInteractive(boolean value) {
        interactive=value;
        if(!enabled)return;
        try{if(value)activate();else deactivate();}
        catch(RuntimeException failure){setEnabled(false);throw failure;}
    }
    private void activate(){if(!active){active=true;host.resume();}}
    private void deactivate(){if(active){active=false;host.pause();}}
}
