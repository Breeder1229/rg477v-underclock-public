package com.anbernic.control.underclock.shared;

/** Dedicated display-only fence; never shares the profile command/ACK channel. */
public final class PowerOverlayProtocol {
    public static final String FENCE_KEY="rg477v_power_overlay_fence";
    public static final String STATE_KEY="rg477v_power_overlay_state";
    public static boolean blocked(String fence){return fence!=null&&fence.startsWith("restore:");}
    public static boolean stopped(boolean enabled,boolean attached,boolean listening,
                                  boolean observing,String ack,String requested) {
        return requested!=null&&requested.equals(ack)&&!enabled&&!attached&&!listening&&!observing;
    }
    private PowerOverlayProtocol(){}
}
