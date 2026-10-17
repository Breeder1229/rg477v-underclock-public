package com.anbernic.control.underclock.shared;

/** One worker owns this store. A failed disk commit must also undo its memory update. */
public final class CustomProfileStore {
    public interface Persistence {
        Snapshot read();
        boolean write(Snapshot value);
    }
    public static final class Snapshot {
        public final int displayIndex;
        public final String lastRequest;
        private final String[] slots;
        public Snapshot(int displayIndex,String lastRequest,String[] slots) {
            if(slots==null||slots.length!=4)throw new IllegalArgumentException("Custom slot count");
            this.displayIndex=displayIndex;this.lastRequest=lastRequest;this.slots=slots.clone();
        }
        public String slot(int index){return slots[index];}
    }
    public static void select(Persistence persistence,int index,CustomProfileValues candidate,String request) {
        if(index<0||index>7||(index<4&&candidate!=null))throw new IllegalArgumentException("프로필 인덱스 확인 실패");
        Snapshot before=persistence.read();String[] slots=before.slots.clone();
        if(candidate!=null)slots[index-4]=candidate.encode();
        commit(persistence,before,new Snapshot(index,request==null?before.lastRequest:request,slots));
    }
    public static void acknowledge(Persistence persistence,String request) {
        Snapshot before=persistence.read();
        commit(persistence,before,new Snapshot(before.displayIndex,request,before.slots));
    }
    private static void commit(Persistence persistence,Snapshot before,Snapshot after) {
        RuntimeException failure;
        try {
            if(persistence.write(after))return;
            failure=new IllegalStateException("설정값을 저장하지 못했습니다. 이전 선택을 유지합니다.");
        }catch(RuntimeException error){failure=error;}
        // SharedPreferences.commit(false) may already have replaced its memory map.
        // Writing the old snapshot restores that map even when disk I/O still fails.
        try {persistence.write(before);}catch(RuntimeException rollback){failure.addSuppressed(rollback);}
        throw failure;
    }
    private CustomProfileStore(){}
}
