package io.github.breeder1229.rg477vunderclock;

/** Durable intent takes precedence over stale native status and queued automatic work. */
final class ControlCenterMode {
    enum Mode { INITIAL, UNDERCLOCK, RESTORE_PENDING, STOCK, RESTORE_FAILED, INSTALL_PENDING, INSTALL_FAILED }
    interface Store {
        String read();
        boolean write(String value);
    }
    static Mode read(Store store) {
        try { return Mode.valueOf(store.read()); }
        catch (RuntimeException invalid) { return Mode.RESTORE_FAILED; }
    }
    static boolean profileRequestsAllowed(Store store) { return read(store)==Mode.UNDERCLOCK; }
    static void requireExplicitInstall(boolean explicit) {
        if(!explicit)throw new IllegalStateException("설치 버튼을 눌러 시작해 주세요. 자동 설치는 실행하지 않습니다.");
    }
    static void save(Store store, Mode next) {
        String previous=store.read();
        if(!store.write(next.name())) {
            // SharedPreferences can update memory even when the disk commit fails.
            store.write(previous);
            throw new IllegalStateException("Control Center 작업 상태 저장 실패 · 기기 변경을 중단합니다.");
        }
    }
    static void failed(Store store, Mode failure, Exception original) {
        try { save(store,failure); }
        catch (IllegalStateException persistenceFailure) { original.addSuppressed(persistenceFailure); }
    }
    private ControlCenterMode() { }
}
