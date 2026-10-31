package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.voucher.model.Voucher;
import com.campusdash.domain.voucher.model.VoucherStock;
import com.campusdash.domain.voucher.model.VoucherUse;
import com.campusdash.domain.voucher.ports.VoucherRepository;
import com.campusdash.domain.wallet.model.AccountType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.domain.wallet.model.LedgerEntry;
import com.campusdash.domain.wallet.model.WalletAccount;
import com.campusdash.domain.wallet.ports.WalletRepository;
import com.campusdash.shared.Money;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务过期：状态机校验 + CAS 兜底 + 幂等跳过 + 退款退券。
 */
class ExpireErrandUseCaseTest {

    private static Errand errand(ErrandStatus status, Instant expiredAt) {
        return Errand.rehydrate(1L, 1L, "pub", ErrandType.DELIVERY, "qukuaidi",
                null, null, expiredAt, null,
                Money.ofCents(500), 1, null, status, 0, 0, 3L, null, null);
    }

    private static ExpireErrandUseCase useCase(StubErrand repo, StubVoucher voucher) {
        return new ExpireErrandUseCase(repo, new StubWallet(), voucher,
                (a, b, c, d, e, f, g) -> { },
                new SnowflakeIdGenerator(1L), new NoopCache());
    }

    private static ExpireErrandUseCase useCase(StubErrand repo) {
        return useCase(repo, new StubVoucher());
    }

    @Test
    void expiresWhenPastDeadlineAndStillPublished() {
        StubErrand repo = new StubErrand(errand(ErrandStatus.PUBLISHED, Instant.now().minusSeconds(1)), 1);
        assertEquals(ExpireErrandUseCase.Outcome.EXPIRED, useCase(repo).expire(1L));
        assertEquals(3L, repo.casVersion, "CAS 必须用读到的版本号");
        assertEquals(List.of(ErrandStatus.EXPIRED), repo.loggedTo);
    }

    @Test
    void restoresVoucherWhenExpired() {
        StubErrand repo = new StubErrand(errand(ErrandStatus.PUBLISHED, Instant.now().minusSeconds(1)), 1);
        StubVoucher voucher = new StubVoucher();
        assertEquals(ExpireErrandUseCase.Outcome.EXPIRED, useCase(repo, voucher).expire(1L));
        assertEquals(1, voucher.restored, "过期成功后必须退券");
    }

    @Test
    void doesNotRestoreVoucherWhenSkipped() {
        StubErrand repo = new StubErrand(errand(ErrandStatus.LOCKED, Instant.now().minusSeconds(1)), 1);
        StubVoucher voucher = new StubVoucher();
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo, voucher).expire(1L));
        assertEquals(0, voucher.restored, "跳过时不能碰券");
    }

    @Test
    void skipsWhenAlreadyLocked() {
        StubErrand repo = new StubErrand(errand(ErrandStatus.LOCKED, Instant.now().minusSeconds(1)), 1);
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo).expire(1L));
        assertNull(repo.casVersion);
    }

    @Test
    void skipsWhenNoDeadline() {
        StubErrand repo = new StubErrand(errand(ErrandStatus.PUBLISHED, null), 1);
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo).expire(1L));
        assertNull(repo.casVersion);
    }

    @Test
    void skipsWhenDeadlineInFuture() {
        StubErrand repo = new StubErrand(errand(ErrandStatus.PUBLISHED, Instant.now().plusSeconds(3600)), 1);
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo).expire(1L));
    }

    @Test
    void skipsWhenCasLoses() {
        StubErrand repo = new StubErrand(errand(ErrandStatus.PUBLISHED, Instant.now().minusSeconds(1)), 0);
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo).expire(1L));
    }

    @Test
    void skipsWhenErrandMissing() {
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(new StubErrand(null, 1)).expire(1L));
    }

    @Test
    void statusMachineAllowsPublishedToExpired() {
        assertTrue(ErrandStatus.PUBLISHED.canTransitTo(ErrandStatus.EXPIRED));
        assertFalse(ErrandStatus.LOCKED.canTransitTo(ErrandStatus.EXPIRED));
        assertFalse(ErrandStatus.EXPIRED.canTransitTo(ErrandStatus.PUBLISHED));
    }

    static class NoopCache implements ErrandCachePort {
        @Override public Optional<CachedErrand> get(long errandId) { return Optional.empty(); }
        @Override public void put(long errandId, String payloadJson) { }
        @Override public void putEmpty(long errandId) { }
        @Override public void evict(long errandId) { }
        @Override public boolean tryAcquireRebuild(long errandId) { return true; }
        @Override public void releaseRebuild(long errandId) { }
        @Override public boolean mightExist(long errandId) { return true; }
        @Override public void registerExisting(long errandId) { }
    }

    static class StubErrand implements ErrandRepository {
        private final Errand errand;
        private final int casResult;
        Long casVersion;
        final List<ErrandStatus> loggedTo = new ArrayList<>();

        StubErrand(Errand errand, int casResult) {
            this.errand = errand;
            this.casResult = casResult;
        }

        @Override public Optional<Errand> findById(long errandId) { return Optional.ofNullable(errand); }
        @Override public int casExpire(long errandId, long expectedVersion) {
            this.casVersion = expectedVersion;
            return casResult;
        }
        @Override public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to,
                                              int round, String operatorId) { loggedTo.add(to); }

        @Override public void insert(Errand e) { }
        @Override public boolean hasOngoing(String userId) { return false; }
        @Override public int casLockForRunner(long errandId, String runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override public int casAccept(long errandId, String runnerId, long expectedVersion) { return 0; }
        @Override public int casTransferToNext(long errandId, String nextRunnerId, long expectedVersion, int expectedRound) { return 0; }
        @Override public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) { return 0; }
        @Override public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) { return List.of(); }
        @Override public int casPickUp(long errandId, String runnerId, long expectedVersion) { return 0; }
        @Override public int casDeliver(long errandId, String runnerId, long expectedVersion) { return 0; }
        @Override public int casSettle(long errandId, long expectedVersion) { return 0; }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
    }

    static class StubWallet implements WalletRepository {
        @Override public void insertAccount(WalletAccount a) { }
        @Override public void deleteAccount(long id) { }
        @Override public Optional<WalletAccount> findByOwner(String ownerId, AccountType type) { return Optional.empty(); }
        @Override public Optional<WalletAccount> findById(long id) { return Optional.empty(); }
        @Override public int casDebit(long id, Money amount) { return 1; }
        @Override public int casCredit(long id, Money amount) { return 1; }
        @Override public void insertLedger(LedgerEntry e) { }
        @Override public void insertEscrow(EscrowOrder o) { }
        @Override public boolean escrowExists(long campusId, long errandId) { return false; }
        @Override public Optional<EscrowOrder> findEscrowByErrandId(long campusId, long errandId) { return Optional.empty(); }
        @Override public int casEscrowStatus(long campusId, long errandId, EscrowOrder.EscrowStatus from, EscrowOrder.EscrowStatus to) { return 1; }
        @Override public boolean ledgerExists(String bizNo) { return false; }
        @Override public long netPlatformIncomeCents() { return 0; }
        @Override public PlatformLedgerPage queryPlatformLedger(AccountType type, Instant from, int offset, int limit) { return null; }
    }

    static class StubVoucher implements VoucherRepository {
        int restored = 0;
        @Override public int restoreByErrand(long errandId) { restored++; return 1; }

        @Override public int updateDescription(int type, String d) { return 0; }
        @Override public void insertStock(VoucherStock s) { }
        @Override public int updateStock(int type, int stock) { return 0; }
        @Override public int updateStatus(int type, String status) { return 0; }
        @Override public int deleteStock(int type) { return 0; }
        @Override public int deleteRecordsByType(int type) { return 0; }
        @Override public int nextType() { return 0; }
        @Override public int decrementStock(int type) { return 0; }
        @Override public void insert(Voucher v) { }
        @Override public int insertIfAbsent(Voucher v) { return 1; }
        @Override public int deleteById(long voucherId) { return 0; }
        @Override public List<VoucherStock> findAllStocks() { return List.of(); }
        @Override public Optional<VoucherStock> findStock(int type) { return Optional.empty(); }
        @Override public Optional<Voucher> findById(long voucherId) { return Optional.empty(); }
        @Override public List<Voucher> listByUser(String userId, Instant now) { return List.of(); }
        @Override public int countAvailable(String userId, int type, Instant now) { return 0; }
        @Override public int markExpired(long voucherId) { return 0; }
        @Override public int markUsed(long voucherId) { return 0; }
        @Override public List<Voucher> findExpiredAvailable(int limit) { return List.of(); }
        @Override public int expireOverdue(int limit) { return 0; }
        @Override public int lockForErrand(long voucherId, String userId, long errandId, long runnerBearCents) { return 1; }
        @Override public int markUsedByErrand(long errandId) { return 0; }
        @Override public Optional<VoucherUse> findUseByErrand(long errandId) { return Optional.empty(); }
    }
}
