package com.campusdash.it;

import com.campusdash.application.usecase.CreditCalibrationUseCase;
import com.campusdash.domain.credit.model.CreditEventType;
import com.campusdash.domain.credit.ports.CreditRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * P6：信用分每日校准。
 *
 * P5 的业务事务会增量更新 credit_score；P6 的每日 job 用 credit_event 的 30 天窗口重算快照，
 * 把过期事件的影响移除，避免分数永久带着窗口外历史。
 */
@SpringBootTest(properties = {"dash.mq.enabled=false"})
class CreditCalibrationIT {

    static final long USER = 7201L;

    @Autowired JdbcTemplate jdbc;
    @Autowired CreditRepository creditRepository;
    @Autowired CreditCalibrationUseCase calibrationUseCase;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM credit_event WHERE user_id = ?", USER);
        jdbc.update("DELETE FROM credit_score WHERE user_id = ?", USER);
    }

    @Test
    @DisplayName("每日校准按 30 天窗口重算，移除过期事件贡献")
    void calibration_rebuilds_score_from_recent_window() {
        jdbc.update("""
                INSERT INTO credit_event (id, biz_no, user_id, type, delta, ref_type, ref_id, created_at)
                VALUES
                  (7201001, 'old_timeout:7201', ?, 'GRAB_TIMEOUT_REVERT', -5, 'ERRAND', 1, DATE_SUB(NOW(3), INTERVAL 31 DAY)),
                  (7201002, 'recent_settle:7201', ?, 'SETTLE', 2, 'ERRAND', 2, DATE_SUB(NOW(3), INTERVAL 1 DAY))
                """, USER, USER);
        jdbc.update("INSERT INTO credit_score (user_id, score, version) VALUES (?, 57, 1)", USER);

        int changed = calibrationUseCase.calibrate(CreditEventType.WINDOW_DAYS, 100);

        assertEquals(1, changed, "应修正一条快照");
        assertEquals(62, creditRepository.scoreOf(USER), "校准后只保留 30 天窗口内的 +2");
    }
}
