package com.campusdash.application.usecase;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandCachePort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 任务过期：状态机校验 + CAS 兜底 + 幂等跳过。
 */
class ExpireErrandUseCaseTest {

    private static Errand errand(ErrandStatus status, Instant expiredAt) {
        return Errand.rehydrate(1L, 1L, "pub", ErrandType.DELIVERY, "qukuaidi",
                null, null, expiredAt, null,
                Money.ofCents(500), 1, null, status, 0, 0, 3L, null, null);
    }

    private static Stub stub(Errand e, int casResult) {
        return new Stub(e, casResult);
    }

    private static ExpireErrandUseCase useCase(Stub repo) {
        return new ExpireErrandUseCase(repo, null, new NoopCache());
    }

    @Test
    void expiresWhenPastDeadlineAndStillPublished() {
        Stub repo = stub(errand(ErrandStatus.PUBLISHED, Instant.now().minusSeconds(1)), 1);
        assertEquals(ExpireErrandUseCase.Outcome.EXPIRED, useCase(repo).expire(1L));
        assertEquals(3L, repo.casVersion, "CAS 必须用读到的版本号，不是自增后的");
        assertEquals(List.of(ErrandStatus.EXPIRED), repo.loggedTo);
    }

    @Test
    void skipsWhenAlreadyLocked() {
        Stub repo = stub(errand(ErrandStatus.LOCKED, Instant.now().minusSeconds(1)), 1);
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo).expire(1L));
        assertNull(repo.casVersion);
    }

    @Test
    void skipsWhenNoDeadline() {
        Stub repo = stub(errand(ErrandStatus.PUBLISHED, null), 1);
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo).expire(1L));
        assertNull(repo.casVersion);
    }

    @Test
    void skipsWhenDeadlineInFuture() {
        Stub repo = stub(errand(ErrandStatus.PUBLISHED, Instant.now().plusSeconds(3600)), 1);
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo).expire(1L));
    }

    @Test
    void skipsWhenCasLoses() {
        Stub repo = stub(errand(ErrandStatus.PUBLISHED, Instant.now().minusSeconds(1)), 0);
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(repo).expire(1L));
    }

    @Test
    void skipsWhenErrandMissing() {
        assertEquals(ExpireErrandUseCase.Outcome.SKIPPED, useCase(stub(null, 1)).expire(1L));
    }

    @Test
    void statusMachineAllowsPublishedToExpired() {
        assertTrue(ErrandStatus.PUBLISHED.canTransitTo(ErrandStatus.EXPIRED));
        assertFalse(ErrandStatus.LOCKED.canTransitTo(ErrandStatus.EXPIRED));
        assertFalse(ErrandStatus.EXPIRED.canTransitTo(ErrandStatus.PUBLISHED));
    }

    static class NoopCache implements ErrandCachePort {
        @Override public java.util.Optional<CachedErrand> get(long errandId) { return java.util.Optional.empty(); }
        @Override public void put(long errandId, String payloadJson) { }
        @Override public void putEmpty(long errandId) { }
        @Override public void evict(long errandId) { }
        @Override public boolean tryAcquireRebuild(long errandId) { return true; }
        @Override public void releaseRebuild(long errandId) { }
        @Override public boolean mightExist(long errandId) { return true; }
        @Override public void registerExisting(long errandId) { }
    }

    static class Stub implements ErrandRepository {
        private final Errand errand;
        private final int casResult;
        Long casVersion;
        final List<ErrandStatus> loggedTo = new ArrayList<>();

        Stub(Errand errand, int casResult) {
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
}