package com.anbernic.control.underclock;

import java.util.TreeSet;

/** FPS of newly presented buffers on one exact foreground SurfaceView; no cached/global fallback. */
public final class PresentedFrameRate {
    private String previousLayer;
    private long previousSample,lastFrame;
    public static String selectLayer(String list,String component) {
        if(list==null||list.length()>262144||component==null||component.length()>500)return null;
        int slash=component.indexOf('/');if(slash<=0||slash==component.length()-1)return null;
        String pkg=component.substring(0,slash),activity=component.substring(slash+1);
        if(activity.startsWith("."))activity=pkg+activity;
        if(!pkg.matches("[a-zA-Z0-9_.]+")||!activity.matches("[a-zA-Z0-9_.$]+"))return null;
        String full="SurfaceView["+pkg+"/"+activity+"](BLAST)#";
        String abbreviated=activity.startsWith(pkg+".")?"SurfaceView["+pkg+"/"+activity.substring(pkg.length())+"](BLAST)#":full;
        String selected=null;
        for(String line:list.split("\\n")) {
            line=line.trim();String prefix=line.startsWith(full)?full:line.startsWith(abbreviated)?abbreviated:null;
            if(prefix==null||!line.substring(prefix.length()).matches("[0-9]+"))continue;
            if(selected!=null&&!selected.equals(line))return null;
            selected=line;
        }
        return selected;
    }
    public void reset(){previousLayer=null;previousSample=0;lastFrame=0;}
    public double sample(String layer,String latency,long now) {
        long[] frames=parse(latency,now);
        if(layer==null||frames==null||frames.length==0){reset();return Double.NaN;}
        long latest=frames[frames.length-1];
        if(!layer.equals(previousLayer)||previousSample<=0||now<=previousSample||latest<lastFrame) {
            baseline(layer,now,latest);return Double.NaN;
        }
        long elapsed=now-previousSample;
        if(elapsed<250000000L)return Double.NaN;
        if(elapsed>2000000000L){baseline(layer,now,latest);return Double.NaN;}
        int added=0;for(long frame:frames)if(frame>lastFrame)added++;
        // A completely overwritten 127-entry ring can hide frames: fail closed.
        if(lastFrame<frames[0]||added>=127||(added>0&&now-latest>2000000000L)){baseline(layer,now,latest);return Double.NaN;}
        double fps=added*1000000000d/elapsed;
        baseline(layer,now,latest);
        return Double.isFinite(fps)&&fps>=0&&fps<=240?fps:Double.NaN;
    }
    private void baseline(String layer,long now,long latest){previousLayer=layer;previousSample=now;lastFrame=latest;}
    static long[] parse(String raw,long now) {
        if(raw==null||raw.length()>32768||now<=0)return null;
        String[] lines=raw.trim().split("\\n");if(lines.length<2||lines.length>128)return null;
        try {
            long period=Long.parseLong(lines[0].trim());if(period<=0||period>1000000000L)return null;
            TreeSet<Long> frames=new TreeSet<>();
            for(int i=1;i<lines.length;i++) {
                String[] columns=lines[i].trim().split("\\s+");if(columns.length!=3)return null;
                Long.parseLong(columns[0]);long present=Long.parseLong(columns[1]);Long.parseLong(columns[2]);
                if(present<=0||present==Long.MAX_VALUE)continue;
                if(present>now&&present-now>50000000L)return null;
                if(present<=now)frames.add(present);
            }
            long[] result=new long[frames.size()];int i=0;for(long frame:frames)result[i++]=frame;
            return result;
        }catch(NumberFormatException invalid){return null;}
    }
}
