package com.campusdash.domain.admin.ports;

import java.time.Instant;
import java.util.List;

/** 仲裁记录查询端口（数据源：fund_audit_log.action='ARBITRATE'） */
public interface AdminArbitrationQueryPort {

    Page page(int offset, int limit);

    record Row(long id, long errandId, String errandTitle, String operatorName,
               String favor, long amountCents, String result, Instant createdAt) {
    }

    record Page(List<Row> items, long total) {
    }
}
