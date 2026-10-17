package io.github.breeder1229.rg477vunderclock;

/** Runs only while the installer's shared operation monitor is held. */
final class StockRestoreTransaction {
    interface Actions {
        boolean preflight() throws Exception;
        void verifiedDefault() throws Exception;
        void replaceWithStock() throws Exception;
        boolean stockVerified() throws Exception;
        void waitForLeaseExpiry() throws Exception;
    }
    static void run(ControlCenterMode.Store store, Actions actions) throws Exception {
        // A failed commit must prevent every device-changing action.
        ControlCenterMode.save(store,ControlCenterMode.Mode.RESTORE_PENDING);
        try {
            boolean alreadyStock=actions.preflight();
            if(!alreadyStock) {
                actions.verifiedDefault();
                actions.replaceWithStock();
            }
            if(!actions.stockVerified()) throw new IllegalStateException("복원 후 순정 APK 확인 실패");
            // Release is one-way. Replacement stops renewal; allow its finite lease to expire.
            actions.waitForLeaseExpiry();
            if(!actions.stockVerified()) throw new IllegalStateException("대기 중 Control Center가 변경됐습니다.");
            ControlCenterMode.save(store,ControlCenterMode.Mode.STOCK);
        } catch(Exception failure) {
            ControlCenterMode.failed(store,ControlCenterMode.Mode.RESTORE_FAILED,failure);
            throw failure;
        }
    }
    private StockRestoreTransaction() { }
}
