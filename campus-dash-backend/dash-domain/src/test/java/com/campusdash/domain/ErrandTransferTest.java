package com.campusdash.domain;

import com.campusdash.domain.errand.model.Errand;
import com.campusdash.domain.errand.model.ErrandStatus;
import com.campusdash.domain.errand.model.ErrandType;
import com.campusdash.shared.BizException;
import com.campusdash.shared.ErrorCode;
import com.campusdash.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 超时流转的领域规则测试（P2）。
 * 这些规则先在内存里验证清楚，数据库的 CAS 只是把同样的约束在并发下再兜一次。
 */
class ErrandTransferTest {

    private Errand lockedErrand(long runnerId) {
        Errand e = Errand.draft(1L, 1L, 1001L, ErrandType.DELIVERY, "取快递", Money.ofCents(800), 1);
        e.publish(0L);
        e.lockBy(runnerId, e.version(), Instant.now());
        return e;
    }

    @Test
    @DisplayName("流转给下一位：状态仍是 LOCKED，round+1，占用者换人，名额不变")
    void transferToNextRunner() {
        Errand e = lockedErrand(2001L);
        int roundBefore = e.round();
        int slotTakenBefore = e.slotTaken();

        e.transferToNextRunner(2002L, e.version(), Instant.now());

        assertEquals(ErrandStatus.LOCKED, e.status(), "流转后仍是 LOCKED（合法自环）");
        assertEquals(roundBefore + 1, e.round(), "round 必须自增，否则旧消息无法被识别");
        assertEquals(2002L, e.grabberId());
        assertEquals(slotTakenBefore, e.slotTaken(), "名额还是那一个，只是占用者换人");
    }

    @Test
    @DisplayName("候选队列空则回退 PUBLISHED：名额要还回去，否则任务显示可抢但实际满")
    void revertToPublishedReturnsSlot() {
        Errand e = lockedErrand(2001L);
        assertEquals(1, e.slotTaken());

        e.revertToPublished(e.version());

        assertEquals(ErrandStatus.PUBLISHED, e.status());
        assertEquals(0, e.slotTaken(), "名额必须归还");
        assertNull(e.grabberId());
        assertTrue(e.slotAvailable());
    }

    @Test
    @DisplayName("只有当前抢中者本人能确认接单")
    void onlyCurrentGrabberCanAccept() {
        Errand e = lockedErrand(2001L);

        BizException ex = assertThrows(BizException.class, () -> e.acceptByRunner(9999L, e.version()));
        assertEquals(ErrorCode.NOT_CURRENT_GRABBER, ex.code());

        e.acceptByRunner(2001L, e.version());
        assertEquals(ErrandStatus.ACCEPTED, e.status());
    }

    @Test
    @DisplayName("已确认的任务不能再被流转（超时消息到达时状态已变）")
    void acceptedErrandCannotBeTransferred() {
        Errand e = lockedErrand(2001L);
        e.acceptByRunner(2001L, e.version());

        BizException ex = assertThrows(BizException.class,
                () -> e.transferToNextRunner(2002L, e.version(), Instant.now()));
        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, ex.code());
    }

    @Test
    @DisplayName("版本号不匹配时拒绝流转：确认与流转的并发竞争必有一方失败")
    void staleVersionRejectsTransfer() {
        Errand e = lockedErrand(2001L);
        long staleVersion = e.version();
        // 模拟跑腿先确认了，version 已经推进
        e.acceptByRunner(2001L, staleVersion);

        BizException ex = assertThrows(BizException.class,
                () -> e.transferToNextRunner(2002L, staleVersion, Instant.now()));
        // 状态已不是 LOCKED，先被状态校验拦住
        assertEquals(ErrorCode.ILLEGAL_STATE_TRANSITION, ex.code());
    }

    @Test
    @DisplayName("多轮流转：round 持续累加，用于识别旧轮次消息")
    void multipleRoundsAccumulate() {
        Errand e = lockedErrand(2001L);
        e.transferToNextRunner(2002L, e.version(), Instant.now());
        e.transferToNextRunner(2003L, e.version(), Instant.now());
        // 又轮回到 2001：如果只看 version 无法区分第 1 轮消息，round 能
        e.transferToNextRunner(2001L, e.version(), Instant.now());

        assertEquals(3, e.round());
        assertEquals(2001L, e.grabberId());
        assertEquals(1, e.slotTaken(), "多轮流转不应改变名额占用数");
    }

    @Test
    @DisplayName("确认超时判定：未到期不算超时，到期才算")
    void confirmTimeoutDetection() {
        Instant lockedAt = Instant.parse("2026-08-18T10:00:00Z");
        Errand e = Errand.rehydrate(1L, 1L, 1001L, ErrandType.DELIVERY, "t",
                Money.ofCents(800), 1, 2001L, ErrandStatus.LOCKED, 1, 0, 2L, lockedAt);

        assertFalse(e.confirmTimeout(lockedAt.plusSeconds(299), 300), "未到期不算超时");
        assertTrue(e.confirmTimeout(lockedAt.plusSeconds(301), 300), "到期应判定超时");
    }

    @Test
    @DisplayName("已确认的任务不参与超时扫描")
    void acceptedErrandIsNotTimeoutCandidate() {
        Instant lockedAt = Instant.parse("2026-08-18T10:00:00Z");
        Errand e = Errand.rehydrate(1L, 1L, 1001L, ErrandType.DELIVERY, "t",
                Money.ofCents(800), 1, 2001L, ErrandStatus.ACCEPTED, 1, 0, 3L, lockedAt);

        assertFalse(e.confirmTimeout(lockedAt.plusSeconds(9999), 300));
    }
}
