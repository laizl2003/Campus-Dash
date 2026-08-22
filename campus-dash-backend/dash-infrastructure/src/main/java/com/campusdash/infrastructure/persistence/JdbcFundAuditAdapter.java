package com.campusdash.infrastructure.persistence;

import com.campusdash.domain.wallet.ports.FundAuditPort;
import com.campusdash.shared.SnowflakeIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 资金审计的 JDBC 实现，事务传播用 REQUIRES_NEW。
 *
 * 为什么必须是 REQUIRES_NEW 而不是默认的 REQUIRED：
 *   - 资金动作失败回滚时，审计记录要留下来——那正是排查现场。用 REQUIRED 会被一起回滚。
 *   - 审计自己写失败（比如 JSON 字段超长）不该把资金操作带崩，所以内部还吞掉异常。
 *
 * 代价必须说清楚：REQUIRES_NEW 会占用第二个数据库连接，主事务持有的连接并不释放。
 * 连接池太小时，"主事务等新事务拿连接、新事务等主事务还连接"就会死锁。
 * 本项目连接池按 (1 + REQUIRES_NEW 嵌套层数) × 并发上限 配置，
 * TransactionPitfallIT 里有一个刻意把池设成 2 的用例复现这个死锁。
 */
@Repository
public class JdbcFundAuditAdapter implements FundAuditPort {

    private static final Logger log = LoggerFactory.getLogger(JdbcFundAuditAdapter.class);

    private final JdbcTemplate jdbc;
    private final SnowflakeIdGenerator idGenerator;

    public JdbcFundAuditAdapter(JdbcTemplate jdbc, SnowflakeIdGenerator idGenerator) {
        this.jdbc = jdbc;
        this.idGenerator = idGenerator;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String bizNo, String action, long errandId, long operatorId,
                       String detailJson, boolean success, String message) {
        try {
            jdbc.update("""
                    INSERT INTO fund_audit_log (id, biz_no, action, errand_id, operator_id,
                                                detail, result, message)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    idGenerator.nextId(), bizNo, action, errandId, operatorId,
                    detailJson, success ? "SUCCESS" : "FAILED",
                    message == null ? null : message.substring(0, Math.min(message.length(), 250)));
        } catch (RuntimeException e) {
            // 审计失败绝不能影响资金结果，但一定要留下日志线索
            log.error("审计写入失败 bizNo={} action={}", bizNo, action, e);
        }
    }
}
