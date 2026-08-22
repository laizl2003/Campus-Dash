package com.campusdash.application.usecase.query;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.errand.ports.ErrandQueryPort;
import com.campusdash.domain.errand.ports.ErrandRepository;
import com.campusdash.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QueryErrandListUseCaseTest {

    @Test
    @DisplayName("游标分页取 size+1 判断下一页，并用最后一条生成 nextCursor")
    void cursor_page_uses_extra_row_to_detect_next_page() {
        FakeErrandQueryPort port = new FakeErrandQueryPort();
        QueryErrandListUseCase useCase = new QueryErrandListUseCase(port, new FakeErrandRepository());

        CursorPage<Errand> first = useCase.listByCursor(1L, "PUBLISHED", null, 2);

        assertEquals(List.of(1L, 2L), first.items().stream().map(Errand::id).toList());
        assertNotNull(first.nextCursor());
        assertEquals(3, port.lastRequestedSize, "应多取一条用于判断是否存在下一页");

        CursorPage<Errand> second = useCase.listByCursor(1L, "PUBLISHED", first.nextCursor(), 2);
        assertEquals(List.of(3L), second.items().stream().map(Errand::id).toList());
        assertNull(second.nextCursor());
        assertEquals(Instant.parse("2026-08-21T00:00:02Z"), port.lastBeforeCreatedAt);
        assertEquals(2L, port.lastBeforeId);
    }

    private static final class FakeErrandQueryPort implements ErrandQueryPort {
        Instant lastBeforeCreatedAt;
        Long lastBeforeId;
        int lastRequestedSize;

        @Override
        public List<CursorItem> listByCursor(long campusId, String status, Instant beforeCreatedAt, Long beforeId, int size) {
            this.lastBeforeCreatedAt = beforeCreatedAt;
            this.lastBeforeId = beforeId;
            this.lastRequestedSize = size;
            List<CursorItem> all = new ArrayList<>(List.of(
                    item(1L, "2026-08-21T00:00:03Z"),
                    item(2L, "2026-08-21T00:00:02Z"),
                    item(3L, "2026-08-21T00:00:01Z")
            ));
            if (beforeCreatedAt == null) {
                return all.subList(0, Math.min(size, all.size()));
            }
            return all.stream()
                    .filter(row -> row.createdAt().isBefore(beforeCreatedAt)
                            || (row.createdAt().equals(beforeCreatedAt) && row.errand().id() < beforeId))
                    .limit(size)
                    .toList();
        }

        private CursorItem item(long id, String createdAt) {
            return new CursorItem(Errand.rehydrate(
                    id, 1L, 1001L, ErrandType.DELIVERY, "cursor_" + id, Money.ofCents(1000),
                    1, null, ErrandStatus.PUBLISHED, 0, 0, 0L, null, null), Instant.parse(createdAt));
        }

        @Override public List<Errand> list(long campusId, String status, int page, int size) { return List.of(); }
        @Override public List<Errand> listByPublisher(long publisherId, int page, int size) { return List.of(); }
        @Override public List<Errand> listByRunner(long runnerId, int page, int size) { return List.of(); }
        @Override public List<StatusChange> statusLog(long campusId, long errandId) { return List.of(); }
        @Override public List<Long> sampleIds(int limit) { return List.of(); }
        @Override public int countOngoingByRunner(long runnerId) { return 0; }
    }

    private static final class FakeErrandRepository implements ErrandRepository {
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
}
