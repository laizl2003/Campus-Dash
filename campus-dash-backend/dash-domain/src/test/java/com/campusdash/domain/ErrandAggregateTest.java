package com.campusdash.domain;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.domain.wallet.model.EscrowOrder;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ErrandAggregateTest {

    private Errand publishedErrand(int slotTotal) {
        Errand e = Errand.draft(1L, 1L, 1001L, ErrandType.DELIVERY, "取快递", Money.ofCents(800), slotTotal);
        e.publish(0L);
        return e;
    }

    @Test
    @DisplayName("版本号不匹配时拒绝流转（乐观锁的内存侧校验）")
    void staleVersionIsRejected() {
        Errand e = publishedErrand(1);
        BizException ex = assertThrows(BizException.class,
                () -> e.lockBy(2001L, 999L, Instant.now()));
        assertEquals(ErrorCode.STALE_VERSION, ex.code());
    }

    @Test
    @DisplayName("名额用尽后不能再抢（INV-1 的聚合内保证）")
    void cannotGrabWhenSlotFull() {
        Errand e = publishedErrand(1);
        e.lockBy(2001L, e.version(), Instant.now());
        assertEquals(ErrandStatus.LOCKED, e.status());
        assertEquals(1, e.slotTaken());
        assertFalse(e.slotAvailable());

        // 已是 LOCKED，状态机不允许再从 LOCKED 抢一次
        BizException ex = assertThrows(BizException.class,
                () -> e.lockBy(2002L, e.version(), Instant.now()));
        assertEquals(ErrorCode.ERRAND_NOT_GRABBABLE, ex.code());
    }

    @Test
    @DisplayName("抢中后 version 递增、grabberId 落定、状态日志留痕")
    void lockUpdatesAggregateState() {
        Errand e = publishedErrand(1);
        long before = e.version();
        e.lockBy(2001L, before, Instant.now());

        assertEquals(before + 1, e.version());
        assertEquals(2001L, e.grabberId());
        assertNotNull(e.lockedAt());
        assertTrue(e.changes().stream()
                .anyMatch(c -> c.from() == ErrandStatus.PUBLISHED && c.to() == ErrandStatus.LOCKED));
    }

    @Test
    @DisplayName("悬赏金额必须大于 0，名额至少 1 个")
    void draftValidatesInvariants() {
        assertThrows(IllegalArgumentException.class,
                () -> Errand.draft(1L, 1L, 1001L, ErrandType.BUY, "t", Money.ZERO, 1));
        assertThrows(IllegalArgumentException.class,
                () -> Errand.draft(1L, 1L, 1001L, ErrandType.BUY, "t", Money.ofCents(100), 0));
    }

    @Test
    @DisplayName("托管单携带 campusId，便于后续与任务共片")
    void escrowOrderCarriesCampusIdForSharding() {
        EscrowOrder order = EscrowOrder.held(1L, 9L, 1001L, 1L, Money.ofCents(800));

        assertEquals(9L, order.campusId());
    }
}
