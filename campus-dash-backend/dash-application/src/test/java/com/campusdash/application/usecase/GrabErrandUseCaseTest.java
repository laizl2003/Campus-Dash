package com.campusdash.application.usecase;

import com.campusdash.domain.credit.model.CreditEvent;
import com.campusdash.domain.credit.model.CreditScore;
import com.campusdash.domain.credit.ports.CreditRepository;
import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.domain.grab.model.SlotOutcome;
import com.campusdash.domain.grab.ports.CandidateQueuePort;
import com.campusdash.domain.grab.ports.GrabRateLimiterPort;
import com.campusdash.domain.grab.ports.GrabSlotPort;
import com.campusdash.domain.notify.ports.RealtimeNotifier;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class GrabErrandUseCaseTest {

    @Test
    @DisplayName("热点限流拒绝后直接返回限流码，不再访问信用分和名额裁决")
    void grab_returns_rate_limited_before_downstream_calls() {
        RejectingRateLimiter rateLimiter = new RejectingRateLimiter();
        CountingCreditRepository creditRepository = new CountingCreditRepository();
        CountingGrabSlotPort grabSlotPort = new CountingGrabSlotPort();
        GrabErrandUseCase useCase = new GrabErrandUseCase(
                grabSlotPort,
                new NoopCandidateQueue(),
                new EmptyErrandRepository(),
                null,
                new SnowflakeIdGenerator(1),
                null,
                null,
                creditRepository,
                new NoopErrandQueryPort(),
                40,
                5,
                new NoopRealtimeNotifier(),
                rateLimiter);

        GrabErrandUseCase.Result result = useCase.grab(
                new GrabErrandUseCase.Command(10001L, 2001L, "req-limited"));

        assertFalse(result.grabbed());
        assertEquals(ErrorCode.GRAB_RATE_LIMITED, result.code());
        assertEquals(1, rateLimiter.calls);
        assertEquals(0, creditRepository.scoreCalls, "限流后不应查询信用分");
        assertEquals(0, grabSlotPort.tryAcquireCalls, "限流后不应打 Redis Lua");
    }

    private static final class RejectingRateLimiter implements GrabRateLimiterPort {
        int calls;

        @Override
        public boolean tryPass(long errandId, long runnerId) {
            calls++;
            return false;
        }
    }

    private static final class CountingCreditRepository implements CreditRepository {
        int scoreCalls;

        @Override
        public int scoreOf(long userId) {
            scoreCalls++;
            return 60;
        }

        @Override public Optional<CreditScore> find(long userId) { return Optional.empty(); }
        @Override public boolean applyEvent(CreditEvent event) { return false; }
        @Override public List<CreditEvent> recentEvents(long userId, int days, int limit) { return List.of(); }
        @Override public int windowDelta(long userId, int windowDays) { return 0; }
        @Override public int calibrateScores(int windowDays, int limit) { return 0; }
    }

    private static final class CountingGrabSlotPort implements GrabSlotPort {
        int tryAcquireCalls;

        @Override
        public SlotOutcome tryAcquire(long errandId, long runnerId, String requestId) {
            tryAcquireCalls++;
            return SlotOutcome.NOT_GRABBABLE;
        }

        @Override public void rollback(long errandId, long runnerId, String requestId) {}
        @Override public void initSlot(long errandId, int slotTotal, long ttlSeconds) {}
        @Override public long remainingSlot(long errandId) { return 0; }
    }

    private static final class NoopCandidateQueue implements CandidateQueuePort {
        @Override public void offer(long errandId, long runnerId, double score) {}
        @Override public Optional<Long> pollBest(long errandId) { return Optional.empty(); }
        @Override public long size(long errandId) { return 0; }
    }

    private static final class EmptyErrandRepository implements ErrandRepository {
        @Override public void insert(Errand errand) {}
        @Override public Optional<Errand> findById(long errandId) { return Optional.empty(); }
        @Override public int casLockForRunner(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casPublish(long errandId, long expectedVersion) { return 0; }
        @Override public void appendStatusLog(long errandId, ErrandStatus from, ErrandStatus to, int round, long operatorId) {}
        @Override public int casAccept(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casTransferToNext(long errandId, long nextRunnerId, long expectedVersion, int expectedRound) { return 0; }
        @Override public int casRevertToPublished(long errandId, long expectedVersion, int expectedRound) { return 0; }
        @Override public List<Errand> findConfirmTimeout(long timeoutSeconds, int limit) { return List.of(); }
        @Override public int casPickUp(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casDeliver(long errandId, long runnerId, long expectedVersion) { return 0; }
        @Override public int casSettle(long errandId, long expectedVersion) { return 0; }
        @Override public int casRefundFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casCancel(long errandId, long expectedVersion) { return 0; }
        @Override public int casDispute(long errandId, long expectedVersion) { return 0; }
        @Override public int casSettleFromDispute(long errandId, long expectedVersion) { return 0; }
        @Override public List<Errand> findAutoSettleDue(long autoSettleSeconds, int limit) { return List.of(); }
    }

    private static final class NoopErrandQueryPort implements ErrandQueryPort {
        @Override public List<Errand> list(long campusId, String status, int page, int size) { return List.of(); }
        @Override public List<CursorItem> listByCursor(long campusId, String status, java.time.Instant beforeCreatedAt, Long beforeId, int size) { return List.of(); }
        @Override public List<Errand> listByPublisher(long publisherId, int page, int size) { return List.of(); }
        @Override public List<Errand> listByRunner(long runnerId, int page, int size) { return List.of(); }
        @Override public List<StatusChange> statusLog(long campusId, long errandId) { return List.of(); }
        @Override public List<Long> sampleIds(int limit) { return List.of(); }
        @Override public int countOngoingByRunner(long runnerId) { return 0; }
    }

    private static final class NoopRealtimeNotifier implements RealtimeNotifier {
        @Override public void errandStatusChanged(long errandId, long publisherId, Long grabberId, String status, int round) {}
        @Override public void notificationArrived(long userId, long errandId, String type, String content) {}
        @Override public void creditChanged(long userId, int newScore, int delta, String reason) {}
    }
}
