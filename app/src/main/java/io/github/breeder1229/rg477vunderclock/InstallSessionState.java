package io.github.breeder1229.rg477vunderclock;

/** Timeout never becomes success from an unrelated or late result. */
final class InstallSessionState {
    static final int WAITING=Integer.MIN_VALUE;
    final int session,result;
    final String token;
    final boolean timedOut;
    InstallSessionState(int session,String token,int result,boolean timedOut) {
        this.session=session;this.token=token;this.result=result;this.timedOut=timedOut;
    }
    boolean accepts(int id,String incomingToken) {
        return !timedOut&&result==WAITING&&session>=0&&token!=null&&!token.isEmpty()
                &&session==id&&token.equals(incomingToken);
    }
    InstallSessionState result(int id,String incomingToken,int value) {
        return accepts(id,incomingToken)?new InstallSessionState(session,token,value,false):this;
    }
    InstallSessionState timeout(){return new InstallSessionState(session,token,result,true);}
}
