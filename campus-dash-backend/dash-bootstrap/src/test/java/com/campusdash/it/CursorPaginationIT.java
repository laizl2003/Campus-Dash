package com.campusdash.it;

import com.campusdash.application.usecase.query.CursorPage;
import com.campusdash.application.usecase.query.QueryErrandListUseCase;
import com.campusdash.domain.errand.model.Errand;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P6：任务广场游标分页。
 *
 * 深分页不能继续靠 OFFSET。游标用 (created_at, id) 形成稳定排序，保证连续翻页无重复、无遗漏。
 */
@SpringBootTest(properties = {"dash.mq.enabled=false"})
class CursorPaginationIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired QueryErrandListUseCase queryUseCase;

    @BeforeAll
    static void requireMiddleware() {
        Assumptions.assumeTrue(MiddlewareAvailable.check(), "中间件未启动，跳过");
    }

    @BeforeEach
    void reset() {
        jdbc.update("DELETE FROM errand WHERE id BETWEEN 930000000001 AND 930000000003");
        for (int i = 1; i <= 3; i++) {
            jdbc.update("""
                    INSERT INTO errand (id, campus_id, publisher_id, type, title, reward_amount,
                                        slot_total, slot_taken, status, round, version, created_at)
                    VALUES (?, 930, 1001, 'DELIVERY', ?, 1000, 1, 0, 'PUBLISHED', 0, 0,
                            DATE_SUB(NOW(3), INTERVAL ? SECOND))
                    """, 930_000_000_000L + i, "cursor_" + i, i);
        }
    }

    @Test
    @DisplayName("游标分页连续翻页不重复不遗漏")
    void cursor_pagination_has_no_duplicate_or_gap() {
        CursorPage<Errand> first = queryUseCase.listByCursor(930L, "PUBLISHED", null, 2);
        assertEquals(List.of(930_000_000_001L, 930_000_000_002L),
                first.items().stream().map(Errand::id).toList());
        assertNotNull(first.nextCursor(), "第一页未取完时必须返回 nextCursor");

        CursorPage<Errand> second = queryUseCase.listByCursor(930L, "PUBLISHED", first.nextCursor(), 2);
        assertEquals(List.of(930_000_000_003L), second.items().stream().map(Errand::id).toList());
        assertNull(second.nextCursor(), "最后一页不应再返回 nextCursor");
    }
}
